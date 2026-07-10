package com.harithdev.focuslock.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;
import com.harithdev.focuslock.ui.block.BlockActivity;
import com.harithdev.focuslock.util.TimeUtils;

/**
 * FocusLockAccessibilityService — real-time app switching monitor.
 *
 * Triggered on every TYPE_WINDOW_STATE_CHANGED event.
 * This is the primary enforcement engine.
 *
 * ── Bug fix: debounced session close ──────────────────────────────────
 *
 * Problem (v1): On MIUI/Xiaomi, navigating WITHIN an app (e.g. opening
 * Facebook Stories, tapping Comments) fires a brief system transition
 * event (com.android.systemui, com.miui.home, etc.) BEFORE the in-app
 * Activity arrives. The old code closed the session immediately on that
 * brief system event → false "early exit" block was shown.
 *
 * Fix (v2): Session closes are now DEBOUNCED by 2 seconds when the new
 * window belongs to a safe/system app. If the restricted app (or any other
 * non-system app) comes to the foreground within 2 seconds, the pending
 * close is cancelled and the session continues uninterrupted.
 *
 * Internet speed is NOT a factor — TYPE_WINDOW_STATE_CHANGED fires when
 * an Android Activity WINDOW opens (immediate), not when its content loads.
 *
 * ── Block reasons ──────────────────────────────────────────────────────
 *   REASON_SLEEP           → user opened app during sleep window
 *   REASON_EARLY_EXIT      → user re-opened during B2 cooldown
 *   REASON_SESSION_TIMEOUT → user re-opened during B1 cooldown
 *   REASON_LIMIT           → daily screen time exceeded (no-split mode)
 *   REASON_ALL_SESSIONS    → all N session slots consumed (split mode)
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/service/FocusLockAccessibilityService.java
 */
public class FocusLockAccessibilityService extends AccessibilityService {

    private static final String TAG = "FocusLock";

    /** Grace period before a session is considered "exited". 2 seconds handles:
     *  - MIUI transition overlays (< 400ms)
     *  - Facebook Stories / Comments in-app navigation (< 600ms)
     *  - Any other brief system-level window events during animations
     *  Internet speed has NO effect on this — window events fire when the
     *  Activity OPENS, not when its content finishes loading. */
    private static final long SESSION_CLOSE_DEBOUNCE_MS = 2_000;

    // ── Shared state (read by UsageTrackingService) ────────────────────
    public static volatile String currentForegroundApp = null;
    public static volatile long   lastEventTime        = 0;

    // ── Internal session state ─────────────────────────────────────────
    private volatile String activeRestrictedPkg = null;
    private volatile long   sessionStartMs      = 0;

    // ── Debounced session-close ────────────────────────────────────────
    // Runs on the main thread; holds a pending close that is cancelled if the
    // restricted app (or any real app) returns within SESSION_CLOSE_DEBOUNCE_MS.
    private final Handler  sessionCloseHandler = new Handler(Looper.getMainLooper());
    private       Runnable pendingSessionClose = null;
    private       String   pendingClosePkg     = null;

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
    //  Core logic
    // ══════════════════════════════════════════════════════════════════════

    private void handleWindowChange(String newPkg) {
        long now = System.currentTimeMillis();

        // ── Step 1: Handle session close when the user moves away ─────────
        //
        // We differentiate two cases:
        //
        //   A) newPkg is a SAFE / SYSTEM app (launcher, systemui, etc.)
        //      → Could be a transient MIUI overlay during within-app navigation.
        //      → DEBOUNCE: schedule close in 2s. If the restricted app (or any
        //        real app) returns within 2s, the close is cancelled.
        //
        //   B) newPkg is a REAL (non-safe) app other than the current session pkg
        //      → User definitely moved to another non-system app.
        //      → CLOSE IMMEDIATELY (cancel any pending debounce first).
        //
        if (activeRestrictedPkg != null && !activeRestrictedPkg.equals(newPkg)) {
            if (isSafeApp(newPkg)) {
                // Case A — might be a transient overlay, debounce the close
                schedulePendingClose(activeRestrictedPkg);
                // We still return here (safe app, nothing to enforce)
                return;
            } else {
                // Case B — user went to a different real app, close immediately
                cancelPendingClose();
                closeActiveSession(activeRestrictedPkg, now);
            }
        }

        // ── Step 2: If the restricted app came back, cancel any pending close ──
        // Handles: Stories back-press, Comments loading, any within-app return
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

            activeRestrictedPkg = newPkg;
            sessionStartMs      = now;
        }

        Log.d(TAG, "✅ " + newPkg + " allowed — session active");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Debounced session close helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Schedules closeActiveSession() for [pkg] to fire after
     * SESSION_CLOSE_DEBOUNCE_MS milliseconds.
     *
     * If the restricted app returns before the timer fires, call
     * cancelPendingClose() to abort it — the session continues as if
     * the user never left.
     *
     * Must be called from any thread; Handler is main-thread-safe.
     */
    private synchronized void schedulePendingClose(final String pkg) {
        cancelPendingClose();            // cancel any previous pending close
        pendingClosePkg = pkg;
        pendingSessionClose = () -> {
            // Double-check the package hasn't changed since we scheduled
            if (pkg.equals(pendingClosePkg)) {
                Log.d(TAG, "⏰ Debounce fired — closing session for " + pkg);
                new Thread(() -> closeActiveSession(pkg, System.currentTimeMillis())).start();
            }
            pendingSessionClose = null;
            pendingClosePkg     = null;
        };
        sessionCloseHandler.postDelayed(pendingSessionClose, SESSION_CLOSE_DEBOUNCE_MS);
        Log.d(TAG, "⏱️ Session close debounce started for " + pkg + " (" + SESSION_CLOSE_DEBOUNCE_MS + "ms)");
    }

    /**
     * Cancels a pending debounced session close (if any).
     * Call this when the restricted app returns to the foreground,
     * or when a real (non-safe) app takes over and we close immediately.
     */
    private synchronized void cancelPendingClose() {
        if (pendingSessionClose != null) {
            sessionCloseHandler.removeCallbacks(pendingSessionClose);
            pendingSessionClose = null;
            pendingClosePkg     = null;
            Log.d(TAG, "❌ Pending session close cancelled (app returned)");
        }
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
            activeRestrictedPkg = null;
            sessionStartMs      = 0;
            return;
        }

        long sessionMs = (sessionStartMs > 0) ? (now - sessionStartMs) : 0;
        if (sessionMs < 0) sessionMs = 0;

        Log.d(TAG, "⏱️ Screen time this session: " + (sessionMs / 1000) + "s");

        usage.totalUsedMs          += sessionMs;
        usage.currentSessionUsedMs += sessionMs;
        usage.inActiveSession       = false;
        usage.sessionStartTimeMs    = 0;

        // Behaviour 2: early exit in split mode → start cooldown immediately
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(pkg);
        if (restriction != null && restriction.splitSessions) {
            usage.inCooldown          = true;
            usage.cooldownEndsAtMs    = now + (restriction.cooldownMinutes * 60_000L);
            usage.isEarlyExitCooldown = true;    // marks this as a B2 cooldown
            Log.d(TAG, "🧘 Early exit — cooldown until " + usage.cooldownEndsAtMs);
        }

        db.dailyUsageDao().update(usage);
        activeRestrictedPkg = null;
        sessionStartMs      = 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

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
            long dailyLimitMs = restriction.dailyLimitMinutes * 60_000L;
            return usage.totalUsedMs >= dailyLimitMs;
        }
    }

    private DailyUsage getOrCreateUsage(FocusLockDatabase db, String pkg, String today) {
        DailyUsage usage = db.dailyUsageDao().getUsage(pkg, today);
        if (usage == null) {
            usage = new DailyUsage(pkg, today);
            db.dailyUsageDao().insert(usage);
            Log.d(TAG, "📝 New DailyUsage for " + pkg);
        }
        return usage;
    }

    /**
     * Returns true for packages that should NEVER trigger a session close or
     * block enforcement. These are launchers, system UI, and FocusLock itself.
     *
     * IMPORTANT: Keep this list in sync. If a system package triggers false
     * session closes, add it here rather than reducing the debounce timeout.
     */
    private boolean isSafeApp(String pkg) {
        if (pkg == null) return true;
        // FocusLock itself
        if (pkg.startsWith("com.harithdev.focuslock")) return true;
        // Android system
        if (pkg.equals("android")) return true;
        if (pkg.equals("com.android.systemui")) return true;
        // Launchers / home
        String lower = pkg.toLowerCase();
        if (lower.contains("launcher")) return true;
        if (lower.contains("home"))     return true;
        if (lower.contains("shell"))    return true;
        // MIUI-specific
        if (pkg.equals("com.miui.home"))                 return true;
        if (pkg.equals("com.miui.systemui.plugin"))      return true;
        if (pkg.equals("miui.systemui.plugin"))          return true;
        if (pkg.equals("com.miui.securitycenter"))       return true;  // ← added
        if (pkg.equals("com.miui.system"))               return true;  // ← added
        if (pkg.equals("com.miui.packageinstaller"))     return true;  // ← added
        // Common Android launchers
        if (pkg.equals("com.sec.android.app.launcher")) return true;
        if (pkg.equals("com.android.launcher3"))         return true;
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
    }

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes          = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "✅ Accessibility Service connected");
    }
}
