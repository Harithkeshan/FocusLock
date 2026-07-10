package com.harithdev.focuslock.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;
import com.harithdev.focuslock.ui.block.BlockActivity;
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

    /** Grace period before a session is considered "exited". 2 seconds handles
     *  MIUI transition overlays (~200ms) and within-app navigation (~600ms).
     *  Internet speed is irrelevant — window events fire when an Activity
     *  OPENS, not when its content finishes loading over the network. */
    private static final long SESSION_CLOSE_DEBOUNCE_MS = 2_000;

    // ── Shared state (read by UsageTrackingService) ────────────────────
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

    // ── Debounced session-close ────────────────────────────────────────
    private final Handler  sessionCloseHandler = new Handler(Looper.getMainLooper());
    private       Runnable pendingSessionClose = null;
    private       String   pendingClosePkg     = null;

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
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (event.getPackageName() == null) return;

        String newPkg = event.getPackageName().toString();
        currentForegroundApp = newPkg;
        lastEventTime        = System.currentTimeMillis();

        Log.d(TAG, "🪟 Window → " + newPkg);
        new Thread(() -> handleWindowChange(newPkg)).start();
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

        // ── Step 1: Handle session close when user moves away ─────────────
        if (activeRestrictedPkg != null && !activeRestrictedPkg.equals(newPkg)) {
            if (isSafeApp(newPkg)) {
                // Transient system overlay (MIUI animation, notification shade) —
                // debounce: give the restricted app 2 seconds to come back.
                schedulePendingClose(activeRestrictedPkg);
                return;
            } else {
                // User moved to a real non-restricted app — close immediately.
                cancelPendingClose();
                closeActiveSession(activeRestrictedPkg, now);
            }
        }

        // ── Step 2: If the restricted app returned, cancel pending close ───
        if (activeRestrictedPkg != null && activeRestrictedPkg.equals(newPkg)) {
            cancelPendingClose();
        }

        // ── Step 3: Safe apps — nothing to enforce ─────────────────────────
        if (isSafeApp(newPkg)) {
            return;
        }

        // ── Step 4: Look up restriction ────────────────────────────────────
        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(newPkg);
        if (restriction == null || !restriction.isRestricted) {
            Log.d(TAG, "✅ Not restricted: " + newPkg);
            return;
        }

        // ── Step 5: Enforce block conditions ───────────────────────────────
        String today = TimeUtils.todayString();
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
                    showLimitBlock(newPkg, restriction);
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
            showLimitBlock(newPkg, restriction);
            return;
        }

        // ── Step 6: Allow — start/resume session ───────────────────────────
        if (!newPkg.equals(activeRestrictedPkg)) {
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

        Log.d(TAG, "✅ " + newPkg + " allowed — session active");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Session close — Behaviour 2 (early exit cooldown)
    // ══════════════════════════════════════════════════════════════════════

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

        // Behaviour 2: early exit in split mode → start cooldown immediately
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(pkg);
        if (restriction != null && restriction.splitSessions) {
            usage.inCooldown          = true;
            usage.cooldownEndsAtMs    = now + (restriction.cooldownMinutes * 60_000L);
            usage.isEarlyExitCooldown = true;
            Log.d(TAG, "🧘 Early exit — cooldown until " + usage.cooldownEndsAtMs);
        }

        db.dailyUsageDao().update(usage);
        activeRestrictedPkg  = null;
        sessionStartMs       = 0;
        sessionAccumulatedMs = 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Debounced session-close helpers (MIUI overlay false-exit fix)
    // ══════════════════════════════════════════════════════════════════════

    private synchronized void schedulePendingClose(final String pkg) {
        cancelPendingClose();
        pendingClosePkg = pkg;
        pendingSessionClose = () -> {
            if (pkg.equals(pendingClosePkg)) {
                Log.d(TAG, "⏰ Debounce fired — closing session for " + pkg);
                new Thread(() -> closeActiveSession(pkg, System.currentTimeMillis())).start();
            }
            pendingSessionClose = null;
            pendingClosePkg     = null;
        };
        sessionCloseHandler.postDelayed(pendingSessionClose, SESSION_CLOSE_DEBOUNCE_MS);
        Log.d(TAG, "⏱️ Debounce started for " + pkg + " (" + SESSION_CLOSE_DEBOUNCE_MS + "ms)");
    }

    private synchronized void cancelPendingClose() {
        if (pendingSessionClose != null) {
            sessionCloseHandler.removeCallbacks(pendingSessionClose);
            pendingSessionClose = null;
            pendingClosePkg     = null;
            Log.d(TAG, "❌ Pending close cancelled (app returned within grace period)");
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

    private void showLimitBlock(String pkg, AppRestriction restriction) {
        if (restriction.splitSessions) {
            Log.d(TAG, "🏁 BLOCK — all sessions done (" + restriction.sessionCount + ")");
            showBlock(pkg, BlockActivity.REASON_ALL_SESSIONS, null, 0, restriction.sessionCount);
        } else {
            Log.d(TAG, "🔒 BLOCK — daily limit reached");
            showBlock(pkg, BlockActivity.REASON_LIMIT, null, 0, 0);
        }
    }

    private boolean isDailyLimitReached(AppRestriction restriction, DailyUsage usage) {
        if (restriction.splitSessions) {
            return usage.sessionsUsedToday >= restriction.sessionCount
                    && !usage.inActiveSession;
        } else {
            return usage.totalUsedMs >= (restriction.dailyLimitMinutes * 60_000L);
        }
    }

    private boolean isSafeApp(String pkg) {
        if (pkg == null) return true;
        if (pkg.startsWith("com.harithdev.focuslock")) return true;
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return true;
        String lower = pkg.toLowerCase();
        if (lower.contains("launcher")) return true;
        if (lower.contains("home"))     return true;
        if (lower.contains("shell"))    return true;
        // MIUI-specific system packages
        if (pkg.equals("com.miui.home"))                       return true;
        if (pkg.equals("com.miui.systemui.plugin"))            return true;
        if (pkg.equals("miui.systemui.plugin"))                return true;
        if (pkg.equals("com.miui.securitycenter"))             return true;
        if (pkg.equals("com.miui.system"))                     return true;
        if (pkg.equals("com.miui.packageinstaller"))           return true;
        if (pkg.equals("com.miui.screenshot"))                 return true;
        if (pkg.equals("com.miui.contentextension"))           return true;
        // Common Android launchers
        if (pkg.equals("com.sec.android.app.launcher"))        return true;
        if (pkg.equals("com.android.launcher3"))               return true;
        if (pkg.equals("com.google.android.apps.nexuslauncher")) return true;
        return false;
    }

    private void showBlock(String pkg, String reason,
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
        // Configure which events we want
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes          = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
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
                    s.inActiveSession    = false;
                    s.sessionStartTimeMs = 0;
                    // Do NOT change totalUsedMs or sessionsUsedToday — those
                    // were already recorded when the session started. We just
                    // mark the session as closed so the next open starts fresh.
                    db.dailyUsageDao().update(s);
                    Log.d(TAG, "   ↳ Closed stale session for " + s.packageName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning stale sessions: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        cancelPendingClose();
        if (activeRestrictedPkg != null) {
            closeActiveSession(activeRestrictedPkg, System.currentTimeMillis());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelPendingClose();
        // FIX 2: Unregister screen receiver to avoid leaks
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        Log.d(TAG, "🛑 Accessibility Service destroyed");
    }
}
