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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString();

        // Ignore our own app and block screen
        if (pkg.equals(getPackageName())) return;
        if (pkg.equals("com.harithdev.focuslock")) return;

        currentForegroundApp = pkg;
        Log.d(TAG, "🪟 Window changed → " + pkg);

        // Immediately check if this app should be blocked
        new Thread(() -> checkAndBlock(pkg)).start();
    }

    private void checkAndBlock(String pkg) {
        FocusLockDatabase db = FocusLockDatabase.getInstance(this);
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(pkg);

        // Not restricted or restriction not active — do nothing
        if (restriction == null || !restriction.isRestricted) return;

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

            // ADD THIS — if last session is also done, show limit instead of cooldown
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
        }

        Log.d(TAG, "✅ " + pkg + " allowed — session active");
    }

    private void showBlock(String pkg, String reason,
                           String sleepEnd, long cooldownEndsMs) {
        Intent intent = new Intent(this, BlockActivity.class);
        intent.putExtra(BlockActivity.EXTRA_PACKAGE,         pkg);
        intent.putExtra(BlockActivity.EXTRA_REASON,          reason);
        intent.putExtra(BlockActivity.EXTRA_SLEEP_END,       sleepEnd);
        intent.putExtra(BlockActivity.EXTRA_COOLDOWN_END_MS, cooldownEndsMs);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP);
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