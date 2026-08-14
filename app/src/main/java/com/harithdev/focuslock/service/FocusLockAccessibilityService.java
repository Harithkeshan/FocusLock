package com.harithdev.focuslock.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;
import com.harithdev.focuslock.ui.block.BlockActivity;
import com.harithdev.focuslock.util.AppUtils;
import com.harithdev.focuslock.util.TimeUtils;

import java.util.List;

/**
 * FocusLockAccessibilityService — real-time app switching monitor.
 *
 * ── Critical Fixes Applied ──────────────────────────────────────────────
 *
 * FIX 2 · Screen-off inflates session timer
 *   BroadcastReceiver listens to ACTION_SCREEN_OFF / ACTION_SCREEN_ON.
 *   When the screen goes off, elapsed time is saved and the timer pauses
 *   (sessionStartMs = 0). When screen comes back, the timer resumes from now.
 *   This prevents "phantom" usage being counted while the phone is locked.
 *
 * FIX 3 · Stale session after phone restart / service crash
 *   onServiceConnected() scans the DB for any rows where inActiveSession=1.
 *   These are orphaned sessions from a previous life of the process. They
 *   are closed (inActiveSession=0, sessionStartTimeMs=0) WITHOUT adding
 *   any extra time, so the user doesn't lose or gain session time unfairly.
 *
 * FIX 4 · Cooldown erased at midnight
 *   When creating a brand-new DailyUsage row for today, getOrCreateUsage()
 *   checks yesterday's row. If that cooldown is still in the future, it is
 *   carried over to today's row — so a 40-min cooldown that started at
 *   11:58 PM still blocks the app until 12:38 AM the next day.
 *
 * ── Other fixes ─────────────────────────────────────────────────────────
 *   • 2-second debounce on session close (MIUI overlay false-exit fix)
 *   • Expanded isSafeApp() list for MIUI system packages
 *   • onDestroy() unregisters screen receiver and cancels pending timers
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/service/FocusLockAccessibilityService.java
 */
public class FocusLockAccessibilityService extends AccessibilityService {

    private static final String TAG = "FocusLock";
    private static volatile FocusLockAccessibilityService instance = null;

    public static FocusLockAccessibilityService getInstance() {
        return instance;
    }

    // ── Debounce Timeouts ──────────────────────────────────────────────────
    // For launchers (Home screens), we assume the user left the app intentionally.
    // We give a small 2-second grace period for accidental presses or quick checks.
    private static final long HOME_DEBOUNCE_MS = 2_000;

    // For transient system UI (like the notification shade or volume panel),
    // the user is usually just checking a notification. We give a much larger
    // grace period. If they dismiss the shade within this time, the session resumes.
    private static final long TRANSIENT_UI_DEBOUNCE_MS = 10_000;

    // For general app transitions (e.g., opening an in-app browser or comment section),
    // we give a tiny grace period to prevent false early-exits before closing the session.
    private static final long APP_TRANSITION_DEBOUNCE_MS = 1_500;

    // ── FIX 8: Clock manipulation guard ───────────────────────────────
    private static final String PREFS_NAME       = "focuslock_prefs";
    private static final String KEY_LAST_KNOWN_MS = "last_known_timestamp_ms";
    private static final long   CLOCK_TOLERANCE_MS = 5 * 60_000L; // 5 min

    // ── Shared state (read by UsageTrackingService) ────────────────────
    public static volatile boolean isServiceRunning     = false;
    public static volatile String currentForegroundApp = null;
    public static volatile long   lastEventTime        = 0;

    // ── Session state ──────────────────────────────────────────────────
    private volatile String activeRestrictedPkg  = null;
    /** Wall-clock ms when the CURRENT screen-on interval started for this
     *  session. Zero means the session is paused (screen is off). */
    private volatile long   sessionStartMs       = 0;
    /** Accumulated screen time for the current session BEFORE the last
     *  screen-on interval. Used to correctly pause/resume across lock events. */
    private volatile long   sessionAccumulatedMs = 0;

    // ── Debounced session-pause ────────────────────────────────────────
    private final Handler  sessionCloseHandler = new Handler(Looper.getMainLooper());
    private       Runnable pendingSessionPause = null;
    private       String   pendingPausePkg     = null;

    // ── FIX 2: Screen-off receiver ────────────────────────────────────
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handleScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                handleScreenOn();
            }
        }
    };

    // ══════════════════════════════════════════════════════════════════════
    //  Accessibility event entry point
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        String newPkg = null;
        if (event.getPackageName() != null) {
            newPkg = event.getPackageName().toString();
        }

        // For TYPE_WINDOWS_CHANGED (e.g., when notification shade closes),
        // the event package might be SystemUI, but the ACTIVE window is now Facebook.
        // getRootInActiveWindow() gives us the true foreground package.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                newPkg = root.getPackageName().toString();
            }
        }

        if (newPkg == null) return;

        currentForegroundApp = newPkg;
        lastEventTime        = System.currentTimeMillis();

        String finalPkg = newPkg;
        new Thread(() -> handleWindowChange(finalPkg)).start();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FIX 2 — Screen on / off handlers
    // ══════════════════════════════════════════════════════════════════════

    /** Screen turned off — pause the session timer so we don't count
     *  locked-screen time as foreground app usage. */
    private void handleScreenOff() {
        if (activeRestrictedPkg != null && sessionStartMs > 0) {
            long now     = System.currentTimeMillis();
            long elapsed = now - sessionStartMs;
            sessionAccumulatedMs += elapsed;
            sessionStartMs = 0;  // 0 = paused
            Log.d(TAG, "📴 Screen OFF — pausing session for " + activeRestrictedPkg
                    + " (+" + elapsed / 1000 + "s, total=" + sessionAccumulatedMs / 1000 + "s)");
        }
    }

    /** Screen turned back on — resume the session timer from now. */
    private void handleScreenOn() {
        if (activeRestrictedPkg != null && sessionStartMs == 0) {
            sessionStartMs = System.currentTimeMillis();
            Log.d(TAG, "📱 Screen ON — resuming session for " + activeRestrictedPkg);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Core enforcement logic
    // ══════════════════════════════════════════════════════════════════════

    private void handleWindowChange(String newPkg) {
        long now = System.currentTimeMillis();

        // ── FIX 8: Reject clock-manipulation attempts ─────────────────────
        // If the system clock jumped backwards by more than CLOCK_TOLERANCE_MS,
        // the user likely set their date backwards to bypass the daily limit.
        // We reject this event cycle entirely — no session changes, no new usage.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastKnownMs = prefs.getLong(KEY_LAST_KNOWN_MS, 0);
        if (lastKnownMs > 0 && now < lastKnownMs - CLOCK_TOLERANCE_MS) {
            Log.w(TAG, "⚠️ Clock went backwards by " + (lastKnownMs - now) / 1000
                    + "s — ignoring accessibility event (likely date manipulation)");
            return;
        }
        if (now > lastKnownMs) {
            prefs.edit().putLong(KEY_LAST_KNOWN_MS, now).apply();
        }

        // ── Pre-check: is the new package restricted? ──
        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(newPkg);
        boolean isRestricted = (restriction != null && restriction.isRestricted);

        if (activeRestrictedPkg != null && !activeRestrictedPkg.equals(newPkg)) {
            // If the restricted app is still visible on screen (e.g., keyboard is open,
            // system dialog overlay, custom overlay drawer), ignore the exit transition.
            if (isAppWindowVisible(activeRestrictedPkg)) {
                Log.d(TAG, "🔍 Active restricted app " + activeRestrictedPkg + " is still visible on screen. Ignoring exit.");
                return;
            }

            if (AppUtils.isSafeApp(this, newPkg)) {
                // Transient system overlay (notification shade) OR Home screen.
                // Give the restricted app a grace period to come back.
                long debounceTime = AppUtils.isHomeApp(newPkg) ? HOME_DEBOUNCE_MS : TRANSIENT_UI_DEBOUNCE_MS;
                schedulePendingPause(activeRestrictedPkg, debounceTime);
                return;
            } else if (!isRestricted) {
                // User moved to a non-restricted app (which could just be an internal 
                // webview/component for the current app). Apply a short transition 
                // debounce to prevent false early-exits.
                schedulePendingPause(activeRestrictedPkg, APP_TRANSITION_DEBOUNCE_MS);
                return;
            } else {
                // User moved directly to ANOTHER restricted app.
                // Close the old session immediately and let the new one process.
                cancelPendingPause();
                AppRestriction oldRestriction = db.appRestrictionDao().getByPackageName(activeRestrictedPkg);
                if (oldRestriction != null && oldRestriction.splitSessions) {
                    pauseActiveSession(activeRestrictedPkg, now);
                } else {
                    closeActiveSession(activeRestrictedPkg, now);
                }
            }
        }

        // ── Step 2: If the restricted app returned, cancel pending close ───
        if (activeRestrictedPkg != null && activeRestrictedPkg.equals(newPkg)) {
            cancelPendingPause();
        }

        // ── Step 3: Safe apps — nothing to enforce ─────────────────────────
        if (AppUtils.isSafeApp(this, newPkg)) {
            return;
        }

        // ── Step 4: Look up restriction (already queried above) ────────────
        if (!isRestricted) {
            Log.d(TAG, "✅ Not restricted: " + newPkg);
            return;
        }

        // ── Feature B: Sync enforced values on new day ────────────────────
        String today = TimeUtils.todayString();
        if (restriction.lastEnforcedSyncDate == null 
                || !restriction.lastEnforcedSyncDate.equals(today)) {
            restriction.enforcedDailyLimitMinutes = restriction.dailyLimitMinutes;
            restriction.enforcedSessionCount      = restriction.sessionCount;
            restriction.enforcedCooldownMinutes   = restriction.cooldownMinutes;
            restriction.lastEnforcedSyncDate      = today;
            db.appRestrictionDao().update(restriction);
            Log.d(TAG, "🔄 Synced enforced values for " + newPkg + " (new day)");
        }

        // ── Step 5: Enforce block conditions ───────────────────────────────
        DailyUsage usage = getOrCreateUsage(db, newPkg, today);

        // 5a. Sleep mode
        if (restriction.sleepModeEnabled &&
                TimeUtils.isInSleepWindow(restriction.sleepStartTime, restriction.sleepEndTime)) {
            Log.d(TAG, "🌙 BLOCK — sleep");
            showBlock(newPkg, BlockActivity.REASON_SLEEP, restriction.sleepEndTime, 0, 0);
            return;
        }

        // 5b. Cooldown still active?
        if (usage.inCooldown) {
            if (now < usage.cooldownEndsAtMs) {
                if (isDailyLimitReached(restriction, usage)) {
                    showLimitBlock(newPkg, restriction, usage);
                } else {
                    String cooldownReason = usage.isEarlyExitCooldown
                            ? BlockActivity.REASON_EARLY_EXIT
                            : BlockActivity.REASON_SESSION_TIMEOUT;
                    Log.d(TAG, "⏳ BLOCK — cooldown [" + cooldownReason + "]");
                    showBlock(newPkg, cooldownReason, null, usage.cooldownEndsAtMs, 0);
                }
                return;
            } else {
                // Cooldown expired — clear it
                Log.d(TAG, "✅ Cooldown expired — clearing");
                usage.inCooldown          = false;
                usage.cooldownEndsAtMs    = 0;
                usage.isEarlyExitCooldown = false;
                db.dailyUsageDao().update(usage);
            }
        }

        // 5c. Daily / session limit already reached?
        if (isDailyLimitReached(restriction, usage)) {
            showLimitBlock(newPkg, restriction, usage);
            return;
        }

        // ── Step 6: Allow — start/resume session ───────────────────────────
        if (!newPkg.equals(activeRestrictedPkg)) {
            // Check for a PAUSED session to resume
            if (restriction.splitSessions
                    && usage.inActiveSession
                    && usage.sessionStartTimeMs == 0) {
                // RESUME paused session
                Log.d(TAG, "▶️ Resuming paused session for " + newPkg
                        + " (accumulated: " + usage.currentSessionUsedMs / 1000 + "s)");
                usage.sessionStartTimeMs = now;
                db.dailyUsageDao().update(usage);
        
                activeRestrictedPkg  = newPkg;
                sessionStartMs       = now;
                sessionAccumulatedMs = 0; // accumulated is already in currentSessionUsedMs
            } else {
                // If split sessions was turned off, clear stale pause state
                if (!restriction.splitSessions && usage.inActiveSession && usage.sessionStartTimeMs == 0) {
                    usage.inActiveSession = false;
                    db.dailyUsageDao().update(usage);
                    Log.d(TAG, "🧹 Cleared stale paused session (split sessions disabled)");
                }

                // START new session
                Log.d(TAG, "▶️ Session started for " + newPkg);
                if (restriction.splitSessions) {
                    usage.sessionsUsedToday += 1;
                }
                usage.inActiveSession      = true;
                usage.sessionStartTimeMs   = now;
                usage.currentSessionUsedMs = 0;
                db.dailyUsageDao().update(usage);
        
                activeRestrictedPkg  = newPkg;
                sessionStartMs       = now;
                sessionAccumulatedMs = 0;
            }
        }

        Log.d(TAG, "✅ " + newPkg + " allowed — session active");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Session close — Behaviour 2 (early exit cooldown)
    // ══════════════════════════════════════════════════════════════════════

    private void pauseActiveSession(String pkg, long now) {
        Log.d(TAG, "⏸️ Pausing session for " + pkg);

        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        String today = TimeUtils.todayString();
        DailyUsage usage = db.dailyUsageDao().getUsage(pkg, today);

        if (usage == null || !usage.inActiveSession) {
            // Session already closed (e.g., by timeout) — just clear in-memory state
            activeRestrictedPkg  = null;
            sessionStartMs       = 0;
            sessionAccumulatedMs = 0;
            return;
        }

        // Calculate pause-aware elapsed time
        long liveMs    = (sessionStartMs > 0) ? (now - sessionStartMs) : 0;
        long sessionMs = sessionAccumulatedMs + liveMs;
        if (sessionMs < 0) sessionMs = 0;

        // Save elapsed time to DB
        usage.totalUsedMs          += sessionMs;
        usage.currentSessionUsedMs += sessionMs;
        usage.sessionStartTimeMs    = 0;  // 0 = PAUSED (inActiveSession stays true)

        db.dailyUsageDao().update(usage);

        // Clear in-memory state
        activeRestrictedPkg  = null;
        sessionStartMs       = 0;
        sessionAccumulatedMs = 0;
    }

    private void closeActiveSession(String pkg, long now) {
        Log.d(TAG, "💾 Closing session for " + pkg);

        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        String today = TimeUtils.todayString();
        DailyUsage usage = db.dailyUsageDao().getUsage(pkg, today);

        if (usage == null || !usage.inActiveSession) {
            activeRestrictedPkg  = null;
            sessionStartMs       = 0;
            sessionAccumulatedMs = 0;
            return;
        }

        // FIX 2: Use pause-aware elapsed time instead of raw wall-clock.
        // If the screen was OFF when this close fires, sessionStartMs == 0,
        // so we don't add any live interval (it was already accumulated on screen-off).
        long liveMs    = (sessionStartMs > 0) ? (now - sessionStartMs) : 0;
        long sessionMs = sessionAccumulatedMs + liveMs;
        if (sessionMs < 0) sessionMs = 0;

        Log.d(TAG, "⏱️ Actual screen time this session: " + (sessionMs / 1000) + "s"
                + " (live=" + liveMs / 1000 + "s, accumulated=" + sessionAccumulatedMs / 1000 + "s)");

        usage.totalUsedMs          += sessionMs;
        usage.currentSessionUsedMs += sessionMs;
        usage.inActiveSession       = false;
        usage.sessionStartTimeMs    = 0;

        // With Pause-Resume, early exit in split mode is handled by pauseActiveSession().
        // closeActiveSession() is only reached for non-split mode exits.

        db.dailyUsageDao().update(usage);
        activeRestrictedPkg  = null;
        sessionStartMs       = 0;
        sessionAccumulatedMs = 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Debounced session-pause helpers (MIUI overlay false-exit fix)
    // ══════════════════════════════════════════════════════════════════════

    private synchronized void schedulePendingPause(final String pkg, long delayMs) {
        cancelPendingPause();
        pendingPausePkg = pkg;
        pendingSessionPause = () -> {
            if (pkg.equals(pendingPausePkg)) {
                // Double-check: is the app still visible on screen?
                if (isAppWindowVisible(pkg)) {
                    Log.d(TAG, "⏰ Debounce timer fired, but " + pkg + " is still visible. Cancelling session pause.");
                    pendingSessionPause = null;
                    pendingPausePkg     = null;
                    return;
                }
                Log.d(TAG, "⏰ Debounce fired — pausing/closing session for " + pkg);
                new Thread(() -> {
                    FocusLockDatabase db = FocusLockDatabase.getInstance(this);
                    AppRestriction r = db.appRestrictionDao().getByPackageName(pkg);
                    if (r != null && r.splitSessions) {
                        pauseActiveSession(pkg, System.currentTimeMillis());
                    } else {
                        closeActiveSession(pkg, System.currentTimeMillis());
                    }
                }).start();
            }
            pendingSessionPause = null;
            pendingPausePkg     = null;
        };
        sessionCloseHandler.postDelayed(pendingSessionPause, delayMs);
        Log.d(TAG, "⏱️ Debounce started for " + pkg + " (" + delayMs + "ms)");
    }

    private synchronized void cancelPendingPause() {
        if (pendingSessionPause != null) {
            sessionCloseHandler.removeCallbacks(pendingSessionPause);
            pendingSessionPause = null;
            pendingPausePkg     = null;
            Log.d(TAG, "❌ Pending pause cancelled (app returned within grace period)");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * FIX 4 — Cooldown carryover past midnight.
     *
     * When creating a brand-new today row, check yesterday's record. If the
     * yesterday row still has an active cooldown (cooldownEndsAtMs > now), carry
     * it over to today's row. This prevents a 40-min cooldown that started at
     * 11:58 PM from being silently cleared at midnight.
     */
    private DailyUsage getOrCreateUsage(FocusLockDatabase db, String pkg, String today) {
        DailyUsage usage = db.dailyUsageDao().getUsage(pkg, today);
        if (usage == null) {
            usage = new DailyUsage(pkg, today);

            // Check if yesterday had an active cooldown that still applies now
            String yesterday = TimeUtils.yesterdayString();
            DailyUsage prev  = db.dailyUsageDao().getUsage(pkg, yesterday);
            if (prev != null && prev.inCooldown
                    && prev.cooldownEndsAtMs > System.currentTimeMillis()) {
                usage.inCooldown          = true;
                usage.cooldownEndsAtMs    = prev.cooldownEndsAtMs;
                usage.isEarlyExitCooldown = prev.isEarlyExitCooldown;
                Log.d(TAG, "🌛 Carried over midnight cooldown for " + pkg
                        + " (ends at " + prev.cooldownEndsAtMs + ")");
            }

            db.dailyUsageDao().insert(usage);
        }
        return usage;
    }

    private void showLimitBlock(String pkg, AppRestriction restriction, DailyUsage usage) {
        boolean dailyLimitHit = usage.totalUsedMs >= (restriction.enforcedDailyLimitMinutes * 60_000L);
        if (restriction.splitSessions && usage.sessionsUsedToday >= restriction.enforcedSessionCount && !usage.inActiveSession && !dailyLimitHit) {
            Log.d(TAG, "🏁 BLOCK — all sessions done (" + restriction.enforcedSessionCount + ")");
            showBlock(pkg, BlockActivity.REASON_ALL_SESSIONS, null, 0, restriction.enforcedSessionCount);
        } else {
            Log.d(TAG, "🔒 BLOCK — daily limit reached");
            showBlock(pkg, BlockActivity.REASON_LIMIT, null, 0, 0);
        }
    }

    private boolean isDailyLimitReached(AppRestriction restriction, DailyUsage usage) {
        boolean dailyLimitHit = usage.totalUsedMs >= (restriction.enforcedDailyLimitMinutes * 60_000L);
        if (restriction.splitSessions) {
            boolean allSessionsHit = usage.sessionsUsedToday >= restriction.enforcedSessionCount && !usage.inActiveSession;
            return dailyLimitHit || allSessionsHit;
        } else {
            return dailyLimitHit;
        }
    }

    /**
     * Checks if a window belonging to the specified package name is currently visible on screen.
     */
    private boolean isAppWindowVisible(String pkgName) {
        if (pkgName == null) return false;
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) {
            return false;
        }
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                try {
                    CharSequence windowPkg = root.getPackageName();
                    if (windowPkg != null && pkgName.equals(windowPkg.toString())) {
                        return true;
                    }
                } finally {
                    root.recycle();
                }
            }
        }
        return false;
    }

    public void showBlock(String pkg, String reason,
                           String sleepEnd, long cooldownEndsMs, int sessionCount) {
        Intent intent = new Intent(this, BlockActivity.class);
        intent.putExtra(BlockActivity.EXTRA_PACKAGE,         pkg);
        intent.putExtra(BlockActivity.EXTRA_REASON,          reason);
        intent.putExtra(BlockActivity.EXTRA_SLEEP_END,       sleepEnd);
        intent.putExtra(BlockActivity.EXTRA_COOLDOWN_END_MS, cooldownEndsMs);
        intent.putExtra(BlockActivity.EXTRA_SESSION_COUNT,   sessionCount);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Service lifecycle
    // ══════════════════════════════════════════════════════════════════════

    @Override
    protected void onServiceConnected() {
        instance = this;
        isServiceRunning = true;
        // Configure which events we want
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes          = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        // Retrieve interactive windows is required to use getRootInActiveWindow()
        info.flags               = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        Log.d(TAG, "✅ Accessibility Service connected");

        // FIX 2: Register screen on/off receiver
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenFilter);
        Log.d(TAG, "📺 Screen on/off receiver registered");

        // FIX 3: Clean up stale sessions left by a crash or phone restart.
        // Any session still marked inActiveSession=1 at service start is orphaned.
        // We close them (set flag to false) WITHOUT adding any extra screen time,
        // so the user's session counts are not unfairly inflated.
        new Thread(this::cleanupStaleSessions).start();
    }

    /**
     * FIX 3 — Clears orphaned inActiveSession=1 rows left from a previous
     * process lifecycle (crash, reboot, forced stop by MIUI).
     *
     * Why no extra time is added: we don't know how long ago the process died,
     * so adding (now - old_sessionStartMs) would be wildly inaccurate.
     * The session that was in progress is simply treated as "already counted"
     * and the session slot is preserved for the user.
     */
    private void cleanupStaleSessions() {
        try {
            FocusLockDatabase db   = FocusLockDatabase.getInstance(this);
            String today           = TimeUtils.todayString();
            List<DailyUsage> stale = db.dailyUsageDao().getAllActiveSessions(today);

            if (!stale.isEmpty()) {
                Log.d(TAG, "🧹 Cleaning up " + stale.size() + " stale session(s) from previous process");
                for (DailyUsage s : stale) {
                    if (s.sessionStartTimeMs > 0) {
                        // Was ACTIVE when service died → move to PAUSED
                        s.sessionStartTimeMs = 0;  // PAUSED (inActiveSession stays true)
                        Log.d(TAG, "   ↳ Moved active→paused for " + s.packageName);
                    } else {
                        // Was already PAUSED → leave it alone
                        Log.d(TAG, "   ↳ Already paused, skipping " + s.packageName);
                    }
                    db.dailyUsageDao().update(s);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning stale sessions: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        isServiceRunning = false;
        instance = null;
        currentForegroundApp = null;
        cancelPendingPause();
        if (activeRestrictedPkg != null) {
            final String pkgToClose = activeRestrictedPkg;
            new Thread(() -> {
                FocusLockDatabase db = FocusLockDatabase.getInstance(this);
                AppRestriction oldRestriction = db.appRestrictionDao().getByPackageName(pkgToClose);
                if (oldRestriction != null && oldRestriction.splitSessions) {
                    pauseActiveSession(pkgToClose, System.currentTimeMillis());
                } else {
                    closeActiveSession(pkgToClose, System.currentTimeMillis());
                }
            }).start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        instance = null;
        currentForegroundApp = null;
        cancelPendingPause();
        // FIX 2: Unregister screen receiver to avoid leaks
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        Log.d(TAG, "🛑 Accessibility Service destroyed");
    }
}
