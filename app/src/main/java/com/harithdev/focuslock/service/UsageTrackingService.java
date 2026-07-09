package com.harithdev.focuslock.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;
import com.harithdev.focuslock.ui.block.BlockActivity;
import com.harithdev.focuslock.util.TimeUtils;

/**
 * UsageTrackingService — background polling service (foreground).
 *
 * Runs every 15 seconds while FocusLock is active.
 *
 * ── Responsibility ─────────────────────────────────────────────────
 *
 * This service handles BEHAVIOUR 1: continuous session timeout.
 *
 * The AccessibilityService (event-driven) handles:
 *   • Instantly blocking an app on open
 *   • Recording session start/end screen time
 *   • Behaviour 2 (early exit → cooldown)
 *
 * This service handles:
 *   • Checking every 15s whether the current session's screen time
 *     has exceeded the slot duration (continuous use timeout).
 *   • If yes → it ends the session, starts cooldown, and shows the block.
 *   • Also acts as a safety net: re-checks daily limit in case the
 *     Accessibility Service missed an event.
 *
 * ── Why wall-clock is NOT used for session duration ────────────────
 *
 * We use `currentSessionUsedMs` (accumulated actual screen time from
 * the Accessibility Service) rather than `now - sessionStartTimeMs`.
 * This is because the user may have switched away briefly (causing
 * the Accessibility Service to pause the timer) and come back.
 *
 * However, for simplicity, this poller also adds the LIVE elapsed time
 * since sessionStartTimeMs when calculating whether to block — this is
 * safe because if the app is currently in the foreground (confirmed by
 * AccessibilityService.currentForegroundApp), the time IS accruing.
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/service/UsageTrackingService.java
 */
public class UsageTrackingService extends Service {

    private static final String TAG           = "FocusLock";
    private static final String CHANNEL_ID    = "focuslock_service";
    private static final String CHANNEL_NAME  = "FocusLock Running";
    private static final int    NOTIF_ID      = 1001;
    private static final long   CHECK_INTERVAL_MS = 15_000; // 15 seconds

    private Handler  handler;
    private Runnable checkRunnable;
    private FocusLockDatabase db;

    @Override
    public void onCreate() {
        super.onCreate();
        db      = FocusLockDatabase.getInstance(this);
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        Log.d(TAG, "✅ UsageTrackingService started");
        startChecking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(checkRunnable);
        Log.d(TAG, "❌ UsageTrackingService stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ══════════════════════════════════════════════════════════════
    //  Polling loop
    // ══════════════════════════════════════════════════════════════

    private void startChecking() {
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> performCheck()).start();
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        handler.post(checkRunnable);
    }

    // ══════════════════════════════════════════════════════════════
    //  Core check — Behaviour 1: continuous session timeout
    // ══════════════════════════════════════════════════════════════

    private void performCheck() {
        // Use the Accessibility Service's real-time foreground detection.
        // This is far more reliable on MIUI than querying UsageStatsManager.
        String foreground = FocusLockAccessibilityService.currentForegroundApp;
        long   lastUpdate = FocusLockAccessibilityService.lastEventTime;
        long   now        = System.currentTimeMillis();

        // If the accessibility data is stale (> 30s), we can't reliably know
        // what's in the foreground — skip this check cycle.
        if (foreground == null || (now - lastUpdate) > 30_000) {
            Log.d(TAG, "⚠️ Accessibility data stale or null — skipping this cycle");
            return;
        }

        // Skip safe apps entirely
        if (isSafeApp(foreground)) {
            return;
        }

        // Check if this foreground app has an active restriction
        AppRestriction restriction = db.appRestrictionDao().getByPackageName(foreground);
        if (restriction == null || !restriction.isRestricted) {
            return;
        }

        // Only relevant in split-session mode (Behaviour 1 is a session concept)
        // In daily-limit-only mode, the Accessibility Service handles blocking on open.
        if (!restriction.splitSessions) {
            // Safety net: re-check daily limit in case accessibility missed something
            checkDailyLimitSafetyNet(restriction, foreground, now);
            return;
        }

        String today = TimeUtils.todayString();
        DailyUsage usage = db.dailyUsageDao().getUsage(foreground, today);
        if (usage == null || !usage.inActiveSession) {
            return; // No active session to time out
        }

        // ── Behaviour 1: continuous session timeout ──
        // Calculate total screen time for this session:
        //   accumulated time (from before any mid-session switches) +
        //   live time since the session (re-)started.
        long liveElapsedMs = (usage.sessionStartTimeMs > 0)
                ? (now - usage.sessionStartTimeMs) : 0;
        long totalSessionMs = usage.currentSessionUsedMs + liveElapsedMs;

        long slotDurationMs = restriction.getSlotDurationMinutes() * 60_000L;

        Log.d(TAG, "⏱️ [" + foreground + "] session time: "
                + (totalSessionMs / 1000) + "s / limit: " + (slotDurationMs / 1000) + "s");

        if (totalSessionMs >= slotDurationMs) {
            Log.d(TAG, "⛔ BLOCKING — session time exceeded (Behaviour 1 — timeout)");

            // Close the session in DB
            usage.totalUsedMs          += liveElapsedMs;
            usage.currentSessionUsedMs  = 0;
            usage.inActiveSession       = false;
            usage.sessionStartTimeMs    = 0;
            usage.inCooldown            = true;
            usage.cooldownEndsAtMs      = now + (restriction.cooldownMinutes * 60_000L);
            usage.isEarlyExitCooldown   = false;   // ← B1 timeout, not early exit
            db.dailyUsageDao().update(usage);

            // Show the "Time's up for this session!" screen
            showBlockScreen(foreground, BlockActivity.REASON_SESSION_TIMEOUT,
                    null, usage.cooldownEndsAtMs, 0);
        }
    }

    /**
     * Safety net for daily-limit-only mode.
     * If `totalUsedMs` already exceeds the limit but the app is still open,
     * we block it here. This shouldn't normally happen (the Accessibility
     * Service handles it on open) but covers edge cases where the app was
     * already open when the limit was set.
     */
    private void checkDailyLimitSafetyNet(AppRestriction restriction,
                                          String foreground, long now) {
        String today = TimeUtils.todayString();
        DailyUsage usage = db.dailyUsageDao().getUsage(foreground, today);
        if (usage == null || !usage.inActiveSession) return;

        long liveElapsedMs  = (usage.sessionStartTimeMs > 0) ? (now - usage.sessionStartTimeMs) : 0;
        long totalMs        = usage.totalUsedMs + liveElapsedMs;
        long dailyLimitMs   = restriction.dailyLimitMinutes * 60_000L;

        if (totalMs >= dailyLimitMs) {
            Log.d(TAG, "🔒 BLOCKING — daily limit reached (safety net)");

            usage.totalUsedMs       += liveElapsedMs;
            usage.inActiveSession    = false;
            usage.sessionStartTimeMs = 0;
            db.dailyUsageDao().update(usage);

            // Show "That's your daily dose!" screen
            showBlockScreen(foreground, BlockActivity.REASON_LIMIT, null, 0, 0);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════

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

    private void showBlockScreen(String packageName, String reason,
                                 String sleepEndTime, long cooldownEndsAtMs, int sessionCount) {
        Intent intent = new Intent(this, BlockActivity.class);
        intent.putExtra(BlockActivity.EXTRA_PACKAGE,         packageName);
        intent.putExtra(BlockActivity.EXTRA_REASON,          reason);
        intent.putExtra(BlockActivity.EXTRA_SLEEP_END,       sleepEndTime);
        intent.putExtra(BlockActivity.EXTRA_COOLDOWN_END_MS, cooldownEndsAtMs);
        intent.putExtra(BlockActivity.EXTRA_SESSION_COUNT,   sessionCount);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ══════════════════════════════════════════════════════════════
    //  Notification (required for foreground service)
    // ══════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("FocusLock is monitoring your app usage");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FocusLock is active")
                .setContentText("Monitoring your app usage")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}