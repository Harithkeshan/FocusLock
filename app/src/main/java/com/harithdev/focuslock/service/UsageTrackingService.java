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

        // ── Step 1: Identify Foreground App ──
        // Prioritize real-time Accessibility Service detection
        String foreground = FocusLockAccessibilityService.currentForegroundApp;
        long   lastUpdate = FocusLockAccessibilityService.lastEventTime;
        long   now        = System.currentTimeMillis();

        // If Accessibility data is older than 5 seconds, it might be stale
        // or the service might be dead/idle. Fall back to UsageStats.
        if (foreground == null || (now - lastUpdate) > 5_000) {
            Log.d(TAG, "⚠️ Accessibility stale or null, falling back to UsageStats");
            foreground = getForegroundAppFromStats();
        }

        if (foreground == null) {
            Log.d(TAG, "⚠️ No foreground app detected");
            return;
        }

        Log.d(TAG, "👁️ Foreground app: " + foreground);

        // ── Step 2: Handle Session Transitions ──
        // Ensure that any app previously marked as "active" is closed if it's no longer in the foreground.
        handleAppSwitch(foreground);

        // ── Step 3: Skip Safe Apps (FocusLock, Launchers, System UI) ──
        if (isSafeApp(foreground)) {
            Log.d(TAG, "✅ " + foreground + " is a safe app — skipping block logic");
            return;
        }

        // ── Step 4: Check Against Active Restrictions ──
        List<AppRestriction> restrictions = db.appRestrictionDao().getActiveRestrictions();
        for (AppRestriction restriction : restrictions) {
            if (restriction.packageName.equals(foreground)) {
                Log.d(TAG, "✅ Match found! Handling: " + foreground);
                handleRestrictedApp(restriction);
                return;
            }
        }

        Log.d(TAG, "✅ " + foreground + " is not restricted — no action");
    }

    /**
     * Called when the foreground app is NOT a restricted app.
     * We check if there's any app currently marked as 'inActiveSession'
     * and close that session since the user moved away.
     */
    private void handleAppSwitch(String currentPkg) {
        // We are already in a background thread here (called from performCheck)
        DailyUsage activeUsage = db.dailyUsageDao().getActiveUsage(TimeUtils.todayString());
        if (activeUsage != null && !activeUsage.packageName.equals(currentPkg)) {
            Log.d(TAG, "💾 Closing session for " + activeUsage.packageName + " because user moved to " + currentPkg);
            
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

    private String getForegroundAppFromStats() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;

        long now = System.currentTimeMillis();
        long past = now - 15_000; // Look back 15 seconds

        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, past, now);
        if (stats == null || stats.isEmpty()) return null;

        String bestPkg = null;
        long lastTime = 0;

        for (UsageStats s : stats) {
            // We DO NOT skip our own app here. If FocusLock is the most recent,
            // we should identify it as foreground so the block logic skips it.
            if (s.getLastTimeUsed() > lastTime) {
                lastTime = s.getLastTimeUsed();
                bestPkg = s.getPackageName();
            }
        }
        return bestPkg;
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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