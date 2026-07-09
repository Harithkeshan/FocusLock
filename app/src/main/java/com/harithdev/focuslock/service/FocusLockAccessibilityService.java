package com.harithdev.focuslock.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
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
 * ── Block reasons used ─────────────────────────────────────────────
 *
 *   REASON_SLEEP           → user opened app during sleep window
 *   REASON_EARLY_EXIT      → user re-opened during cooldown caused by mid-session exit (B2)
 *   REASON_SESSION_TIMEOUT → user re-opened during cooldown caused by slot expiry (B1)
 *   REASON_LIMIT           → total daily screen time exceeded (no-split mode)
 *   REASON_ALL_SESSIONS    → all N session slots consumed for the day (split mode)
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/service/FocusLockAccessibilityService.java
 */
public class FocusLockAccessibilityService extends AccessibilityService {

    private static final String TAG = "FocusLock";

    // ── Shared state (read by UsageTrackingService) ────────────
    public static volatile String currentForegroundApp = null;
    public static volatile long   lastEventTime        = 0;

    // ── Internal session state ─────────────────────────────────
    private String activeRestrictedPkg = null;
    private long   sessionStartMs      = 0;

    // ══════════════════════════════════════════════════════════════
    //  Accessibility event entry point
    // ══════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════
    //  Core logic
    // ══════════════════════════════════════════════════════════════

    private void handleWindowChange(String newPkg) {
        long now = System.currentTimeMillis();

        // ── Step 1: Close any active restricted session if user moved away ──
        if (activeRestrictedPkg != null && !activeRestrictedPkg.equals(newPkg)) {
            closeActiveSession(activeRestrictedPkg, now);
        }

        // ── Step 2: Safe apps — nothing to enforce ──
        if (isSafeApp(newPkg)) {
            Log.d(TAG, "✅ Safe app: " + newPkg);
            return;
        }

        // ── Step 3: Look up restriction ──
        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(newPkg);

        if (restriction == null || !restriction.isRestricted) {
            Log.d(TAG, "✅ Not restricted: " + newPkg);
            return;
        }

        // ── Step 4: Enforce block conditions ──
        String today = TimeUtils.todayString();
        DailyUsage usage = getOrCreateUsage(db, newPkg, today);

        // 4a. Sleep mode
        if (restriction.sleepModeEnabled &&
                TimeUtils.isInSleepWindow(restriction.sleepStartTime, restriction.sleepEndTime)) {
            Log.d(TAG, "🌙 BLOCK — sleep");
            showBlock(newPkg, BlockActivity.REASON_SLEEP, restriction.sleepEndTime, 0, 0);
            return;
        }

        // 4b. Cooldown active?
        if (usage.inCooldown) {
            if (now < usage.cooldownEndsAtMs) {
                if (isDailyLimitReached(restriction, usage)) {
                    // All sessions + time also exhausted — show permanent limit block
                    showLimitBlock(newPkg, restriction, usage);
                } else {
                    // Cooldown still running — show correct message based on what caused it
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

        // 4c. Daily / session limit reached?
        if (isDailyLimitReached(restriction, usage)) {
            showLimitBlock(newPkg, restriction, usage);
            return;
        }

        // ── Step 5: Allow — start session ──
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

    // ══════════════════════════════════════════════════════════════
    //  Session close (Behaviour 2 — early exit)
    // ══════════════════════════════════════════════════════════════

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
            usage.isEarlyExitCooldown = true;   // ← marks this as a B2 cooldown
            Log.d(TAG, "🧘 Early exit — cooldown until " + usage.cooldownEndsAtMs);
        }

        db.dailyUsageDao().update(usage);
        activeRestrictedPkg = null;
        sessionStartMs      = 0;
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * Shows the correct "limit reached" block — either:
     *   • REASON_ALL_SESSIONS (split mode, all N slots done)
     *   • REASON_LIMIT        (daily-cap mode, time exhausted)
     */
    private void showLimitBlock(String pkg, AppRestriction restriction, DailyUsage usage) {
        if (restriction.splitSessions) {
            Log.d(TAG, "🏁 BLOCK — all sessions done (" + restriction.sessionCount + ")");
            showBlock(pkg, BlockActivity.REASON_ALL_SESSIONS,
                    null, 0, restriction.sessionCount);
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

    private boolean isSafeApp(String pkg) {
        if (pkg == null) return true;
        if (pkg.startsWith("com.harithdev.focuslock")) return true;
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return true;
        String lower = pkg.toLowerCase();
        if (lower.contains("launcher") || lower.contains("home") || lower.contains("shell")) return true;
        if (pkg.equals("com.miui.home") || pkg.equals("com.miui.systemui.plugin") ||
                pkg.equals("miui.systemui.plugin") || pkg.equals("com.sec.android.app.launcher") ||
                pkg.equals("com.android.launcher3")) return true;
        return false;
    }

    /**
     * @param sessionCount  pass restriction.sessionCount for REASON_ALL_SESSIONS,
     *                      or 0 for all other reasons
     */
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

    @Override
    public void onInterrupt() {
        if (activeRestrictedPkg != null) {
            closeActiveSession(activeRestrictedPkg, System.currentTimeMillis());
        }
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
