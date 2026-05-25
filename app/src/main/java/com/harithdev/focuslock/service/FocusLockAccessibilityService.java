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

public class FocusLockAccessibilityService extends AccessibilityService {

    private static final String TAG = "FocusLock";
    public static String currentForegroundApp = null;
    public static long   lastEventTime        = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString();

        // Always update the current foreground app and timestamp so UsageTrackingService
        // knows what's actually on screen and how fresh the data is.
        currentForegroundApp = pkg;
        lastEventTime        = System.currentTimeMillis();
        Log.d(TAG, "🪟 Window changed → " + pkg);

        // Immediately check if this app should be blocked or if we should clear sessions.
        // We DO NOT return early here for FocusLock or launchers, because checkAndBlock
        // needs to run to clear any active sessions when switching to a "safe" app.
        new Thread(() -> checkAndBlock(pkg)).start();
    }

    private void checkAndBlock(String pkg) {
        // If the user moved to a non-restricted app (FocusLock, Home, etc.),
        // ensure any active sessions for other apps are closed.
        if (isSafeApp(pkg)) {
            clearOtherActiveSessions(pkg);
            return;
        }

        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(pkg);

        // Not restricted or restriction not active — clear sessions and do nothing
        if (restriction == null || !restriction.isRestricted) {
            clearOtherActiveSessions(pkg);
            return;
        }

        String today = TimeUtils.todayString();
        DailyUsage usage = db.dailyUsageDao().getUsage(pkg, today);
        if (usage == null) {
            usage = new DailyUsage(pkg, today);
            db.dailyUsageDao().insert(usage);
        }

        long now = System.currentTimeMillis();

        // Check 1: Sleep mode
        if (restriction.sleepModeEnabled &&
                TimeUtils.isInSleepWindow(restriction.sleepStartTime, restriction.sleepEndTime)) {
            Log.d(TAG, "🌙 INSTANT BLOCK — sleep");
            showBlock(pkg, BlockActivity.REASON_SLEEP, restriction.sleepEndTime, 0);
            return;
        }

        // Check 2: Cooldown
        if (usage.inCooldown && now < usage.cooldownEndsAtMs) {
            int maxSessions = restriction.splitSessions ? restriction.sessionCount : 1;
            if (usage.sessionsUsedToday >= maxSessions) {
                showBlock(pkg, BlockActivity.REASON_LIMIT, null, 0);
                return;
            }

            Log.d(TAG, "⏳ INSTANT BLOCK — cooldown");
            showBlock(pkg, BlockActivity.REASON_COOLDOWN, null, usage.cooldownEndsAtMs);
            return;
        } else if (usage.inCooldown) {
            usage.inCooldown = false;
            usage.cooldownEndsAtMs = 0;
            db.dailyUsageDao().update(usage);
        }

        // Check 3: All sessions used
        int maxSessions = restriction.splitSessions ? restriction.sessionCount : 1;
        if (usage.sessionsUsedToday >= maxSessions && !usage.inActiveSession) {
            Log.d(TAG, "🔒 INSTANT BLOCK — limit reached");
            showBlock(pkg, BlockActivity.REASON_LIMIT, null, 0);
            return;
        }

        // Check 4: Start new session
        if (!usage.inActiveSession) {
            Log.d(TAG, "▶️ New session started for " + pkg);
            usage.inActiveSession    = true;
            usage.sessionStartTimeMs = now;
            usage.sessionsUsedToday += 1;
            db.dailyUsageDao().update(usage);

            // Ensure no other app is marked as "active"
            clearOtherActiveSessions(pkg);
        }

        Log.d(TAG, "✅ " + pkg + " allowed — session active");
    }

    private void clearOtherActiveSessions(String currentPkg) {
        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        DailyUsage activeUsage = db.dailyUsageDao().getActiveUsage(TimeUtils.todayString());
        if (activeUsage != null && !activeUsage.packageName.equals(currentPkg)) {
            Log.d(TAG, "💾 Accessibility: Closing session for " + activeUsage.packageName);
            
            // Start Cooldown when app is closed early
            AppRestriction restriction = db.appRestrictionDao().getByPackageName(activeUsage.packageName);
            if (restriction != null && activeUsage.inActiveSession) {
                activeUsage.inCooldown = true;
                activeUsage.cooldownEndsAtMs = System.currentTimeMillis() + (restriction.cooldownMinutes * 60_000L);
            }

            activeUsage.inActiveSession = false;
            db.dailyUsageDao().update(activeUsage);
        }
    }

    private boolean isSafeApp(String pkg) {
        if (pkg == null) return true;

        // 1. FocusLock itself
        if (pkg.startsWith("com.harithdev.focuslock")) return true;

        // 2. System components
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return true;

        // 3. Launchers / Home apps
        String lowerPkg = pkg.toLowerCase();
        if (lowerPkg.contains("launcher") || lowerPkg.contains("home") || lowerPkg.contains("shell")) return true;

        // Specific OEM launchers and plugins
        if (pkg.equals("com.miui.home") || pkg.equals("com.miui.systemui.plugin") ||
                pkg.equals("miui.systemui.plugin") || pkg.equals("com.sec.android.app.launcher")) return true;

        return false;
    }

    private void showBlock(String pkg, String reason,
                           String sleepEnd, long cooldownEndsMs) {
        Intent intent = new Intent(this, BlockActivity.class);
        intent.putExtra(BlockActivity.EXTRA_PACKAGE,         pkg);
        intent.putExtra(BlockActivity.EXTRA_REASON,          reason);
        intent.putExtra(BlockActivity.EXTRA_SLEEP_END,       sleepEnd);
        intent.putExtra(BlockActivity.EXTRA_COOLDOWN_END_MS, cooldownEndsMs);
        // Use NEW_TASK for singleInstance activity with its own taskAffinity
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onInterrupt() {}

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
