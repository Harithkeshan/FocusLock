package com.harithdev.focuslock.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
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

import java.util.List;

public class UsageTrackingService extends Service {

    private static final String TAG          = "FocusLock";
    private static final String CHANNEL_ID   = "focuslock_service";
    private static final String CHANNEL_NAME = "FocusLock Running";
    private static final int    NOTIF_ID     = 1001;
    private static final long   CHECK_INTERVAL_MS = 15_000;

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
        Log.d(TAG, "✅ Service started");
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
        Log.d(TAG, "❌ Service destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Check loop ────────────────────────────────────────────

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

    // ── Core logic ────────────────────────────────────────────

    private void performCheck() {
        Log.d(TAG, "🔄 performCheck() running...");

        // ── Step 1: Check if UsageStats permission is granted ─
        UsageStatsManager usm = (UsageStatsManager)
                getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            Log.e(TAG, "❌ UsageStatsManager is null");
            return;
        }

        // ── Step 2: Query all apps used in the last 10 seconds ─
        long now  = System.currentTimeMillis();
        long past = now - 10_000;

        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, past, now);

        if (stats == null || stats.isEmpty()) {
            Log.w(TAG, "⚠️ No usage stats returned — permission may not be granted");
            return;
        }

        // ── Step 3: Find the most recently used app ───────────
        String foreground = getForegroundApp();
        long   lastTime   = 0;

        for (UsageStats s : stats) {
            Log.d(TAG, "  📱 " + s.getPackageName()
                    + " lastUsed=" + (now - s.getLastTimeUsed()) + "ms ago");

            if (s.getPackageName().equals(getPackageName())) continue;
            if (now - s.getLastTimeUsed() > 10_000) continue;

            if (s.getLastTimeUsed() > lastTime) {
                lastTime   = s.getLastTimeUsed();
                foreground = s.getPackageName();
            }
        }

        if (foreground == null) {
            Log.d(TAG, "⚠️ No foreground app detected in last 10 seconds");
            return;
        }

        Log.d(TAG, "👁️ Foreground app: " + foreground);

        // ── Step 4: Check against restrictions ────────────────
        List<AppRestriction> restrictions =
                db.appRestrictionDao().getActiveRestrictions();

        Log.d(TAG, "📋 Active restrictions count: " + restrictions.size());

        for (AppRestriction r : restrictions) {
            Log.d(TAG, "  🔒 Restricted: " + r.packageName);
        }

        for (AppRestriction restriction : restrictions) {
            if (!restriction.packageName.equals(foreground)) continue;
            Log.d(TAG, "✅ Match found! Handling: " + foreground);
            handleRestrictedApp(restriction);
            return;
        }

        Log.d(TAG, "✅ " + foreground + " is not restricted — no action");
    }

    private void handleRestrictedApp(AppRestriction restriction) {
        String today = TimeUtils.todayString();

        DailyUsage usage = db.dailyUsageDao().getUsage(restriction.packageName, today);
        if (usage == null) {
            usage = new DailyUsage(restriction.packageName, today);
            db.dailyUsageDao().insert(usage);
            Log.d(TAG, "📝 Created new DailyUsage for " + restriction.packageName);
        }

        long now = System.currentTimeMillis();

        Log.d(TAG, "📊 Usage — sessions: " + usage.sessionsUsedToday
                + "/" + restriction.sessionCount
                + " inActive: " + usage.inActiveSession
                + " inCooldown: " + usage.inCooldown);

        // Check 1: Sleep mode
        if (restriction.sleepModeEnabled &&
                TimeUtils.isInSleepWindow(restriction.sleepStartTime, restriction.sleepEndTime)) {
            Log.d(TAG, "🌙 BLOCKING — sleep mode");
            showBlockScreen(restriction.packageName,
                    BlockActivity.REASON_SLEEP, restriction.sleepEndTime, 0);
            return;
        }

        // Check 2: Cooldown
        if (usage.inCooldown) {
            if (now < usage.cooldownEndsAtMs) {
                Log.d(TAG, "⏳ BLOCKING — cooldown active until " + usage.cooldownEndsAtMs);
                showBlockScreen(restriction.packageName,
                        BlockActivity.REASON_COOLDOWN, null, usage.cooldownEndsAtMs);
                return;
            } else {
                Log.d(TAG, "✅ Cooldown finished — clearing");
                usage.inCooldown       = false;
                usage.cooldownEndsAtMs = 0;
                db.dailyUsageDao().update(usage);
            }
        }

        // Check 3: All sessions used
        int maxSessions = restriction.splitSessions ? restriction.sessionCount : 1;
        if (usage.sessionsUsedToday >= maxSessions && !usage.inActiveSession) {
            Log.d(TAG, "🔒 BLOCKING — daily limit reached");
            showBlockScreen(restriction.packageName,
                    BlockActivity.REASON_LIMIT, null, 0);
            return;
        }

        // Check 4: Start new session
        if (!usage.inActiveSession) {
            Log.d(TAG, "▶️ Starting new session #" + (usage.sessionsUsedToday + 1));
            usage.inActiveSession    = true;
            usage.sessionStartTimeMs = now;
            usage.sessionsUsedToday += 1;
            db.dailyUsageDao().update(usage);
            return;
        }

        // Check 5: Session time exceeded
        long elapsedMs      = now - usage.sessionStartTimeMs;
        long slotDurationMs = restriction.getSlotDurationMinutes() * 60_000L;

        Log.d(TAG, "⏱️ Session elapsed: " + (elapsedMs / 1000)
                + "s / limit: " + (slotDurationMs / 1000) + "s");

        if (elapsedMs >= slotDurationMs) {
            Log.d(TAG, "⛔ BLOCKING — session time exceeded");
            usage.inActiveSession  = false;
            usage.inCooldown       = true;
            usage.cooldownEndsAtMs = now + (restriction.cooldownMinutes * 60_000L);
            db.dailyUsageDao().update(usage);
            showBlockScreen(restriction.packageName,
                    BlockActivity.REASON_COOLDOWN, null, usage.cooldownEndsAtMs);
        }
    }

    private void showBlockScreen(String packageName, String reason,
                                 String sleepEndTime, long cooldownEndsAtMs) {
        Intent intent = new Intent(this, BlockActivity.class);
        intent.putExtra(BlockActivity.EXTRA_PACKAGE,         packageName);
        intent.putExtra(BlockActivity.EXTRA_REASON,          reason);
        intent.putExtra(BlockActivity.EXTRA_SLEEP_END,       sleepEndTime);
        intent.putExtra(BlockActivity.EXTRA_COOLDOWN_END_MS, cooldownEndsAtMs);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    // ── Get foreground app ────────────────────────────────────

    private String getForegroundApp() {
        String pkg = FocusLockAccessibilityService.currentForegroundApp;
        if (pkg != null && !pkg.equals(getPackageName())) {
            Log.d(TAG, "✅ Accessibility detected: " + pkg);
            return pkg;
        }
        Log.w(TAG, "⚠️ No foreground app from accessibility service");
        return null;
    }

    // ── Notification ──────────────────────────────────────────

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