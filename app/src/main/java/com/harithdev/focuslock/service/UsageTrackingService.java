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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;
import com.harithdev.focuslock.ui.block.BlockActivity;
import com.harithdev.focuslock.util.TimeUtils;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * UsageTrackingService — Step 4
 *
 * Foreground service that runs permanently in the background.
 * Every 60 seconds it:
 *   1. Gets the currently active app
 *   2. Checks if that app has any active restrictions
 *   3. Decides whether to block it (sleep / cooldown / limit reached)
 *   4. Updates the DailyUsage record in the DB
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/service/UsageTrackingService.java
 */
public class UsageTrackingService extends Service {

    private static final String CHANNEL_ID   = "focuslock_service";
    private static final String CHANNEL_NAME = "FocusLock Running";
    private static final int    NOTIF_ID     = 1001;

    // Check interval: every 60 seconds
    private static final long CHECK_INTERVAL_MS = 60_000;

    private Handler  handler;
    private Runnable checkRunnable;
    private FocusLockDatabase db;

    // ── Service lifecycle ─────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        db      = FocusLockDatabase.getInstance(this);
        handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());

        startChecking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if Android kills the service, restart it automatically
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(checkRunnable);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ── Periodic check loop ───────────────────────────────────

    private void startChecking() {
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                // Run the actual check on a background thread
                new Thread(() -> performCheck()).start();
                // Schedule the next check
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        // Start immediately
        handler.post(checkRunnable);
    }

    // ── Core check logic ──────────────────────────────────────

    /**
     * This runs every 60 seconds on a background thread.
     * Gets the foreground app and checks it against all restrictions.
     */
    private void performCheck() {
        String foregroundPackage = getForegroundApp();
        if (foregroundPackage == null) return;

        // Ignore our own app
        if (foregroundPackage.equals(getPackageName())) return;

        // Get all active restrictions from DB
        List<AppRestriction> restrictions = db.appRestrictionDao().getActiveRestrictions();

        for (AppRestriction restriction : restrictions) {
            if (!restriction.packageName.equals(foregroundPackage)) continue;

            // This restricted app is currently in the foreground — check it
            handleRestrictedApp(restriction);
            return;
        }
    }

    /**
     * Decides what to do for a restricted app that is currently open.
     * Order of checks:
     *   1. Sleep mode → block with sleep message
     *   2. Cooldown active → block with cooldown message
     *   3. All sessions used → block with daily limit message
     *   4. New session starts → record session start
     *   5. Session ongoing → check if slot time exceeded
     */
    private void handleRestrictedApp(AppRestriction restriction) {
        String today = TimeUtils.todayString();

        // Get or create today's usage record
        DailyUsage usage = db.dailyUsageDao().getUsage(restriction.packageName, today);
        if (usage == null) {
            usage = new DailyUsage(restriction.packageName, today);
            db.dailyUsageDao().insert(usage);
        }

        long now = System.currentTimeMillis();

        // ── Check 1: Sleep mode ───────────────────────────────
        if (restriction.sleepModeEnabled &&
                TimeUtils.isInSleepWindow(restriction.sleepStartTime, restriction.sleepEndTime)) {

            showBlockScreen(restriction.packageName,
                    BlockActivity.REASON_SLEEP,
                    restriction.sleepEndTime,
                    0);
            return;
        }

        // ── Check 2: Active cooldown ──────────────────────────
        if (usage.inCooldown) {
            if (now < usage.cooldownEndsAtMs) {
                // Still in cooldown — keep blocking
                showBlockScreen(restriction.packageName,
                        BlockActivity.REASON_COOLDOWN,
                        null,
                        usage.cooldownEndsAtMs);
                return;
            } else {
                // Cooldown just finished — clear it
                usage.inCooldown       = false;
                usage.cooldownEndsAtMs = 0;
                db.dailyUsageDao().update(usage);
            }
        }

        // ── Check 3: All sessions used up ─────────────────────
        int maxSessions = restriction.splitSessions ? restriction.sessionCount : 1;

        if (usage.sessionsUsedToday >= maxSessions && !usage.inActiveSession) {
            showBlockScreen(restriction.packageName,
                    BlockActivity.REASON_LIMIT,
                    null,
                    0);
            return;
        }

        // ── Check 4: Start a new session ──────────────────────
        if (!usage.inActiveSession) {
            usage.inActiveSession    = true;
            usage.sessionStartTimeMs = now;
            usage.sessionsUsedToday += 1; // count as used immediately (your rule)
            db.dailyUsageDao().update(usage);
            return; // session just started — nothing to block yet
        }

        // ── Check 5: Check if current session has exceeded its slot ──
        long sessionElapsedMs  = now - usage.sessionStartTimeMs;
        long slotDurationMs    = restriction.getSlotDurationMinutes() * 60_000L;

        if (sessionElapsedMs >= slotDurationMs) {
            // Slot time is up — end session and start cooldown
            usage.inActiveSession = false;
            usage.inCooldown      = true;
            usage.cooldownEndsAtMs = now + (restriction.cooldownMinutes * 60_000L);
            db.dailyUsageDao().update(usage);

            showBlockScreen(restriction.packageName,
                    BlockActivity.REASON_COOLDOWN,
                    null,
                    usage.cooldownEndsAtMs);
        }
        // else: session still within allowed time — do nothing
    }

    // ── Show block overlay ────────────────────────────────────

    /**
     * Launches BlockActivity as a full-screen overlay on top of the blocked app.
     *
     * @param packageName      the blocked app's package name
     * @param reason           BlockActivity.REASON_SLEEP / REASON_COOLDOWN / REASON_LIMIT
     * @param sleepEndTime     "HH:mm" string — used only for REASON_SLEEP
     * @param cooldownEndsAtMs epoch millis — used only for REASON_COOLDOWN
     */
    private void showBlockScreen(String packageName, String reason,
                                 String sleepEndTime, long cooldownEndsAtMs) {
        Intent intent = new Intent(this, BlockActivity.class);
        intent.putExtra(BlockActivity.EXTRA_PACKAGE,         packageName);
        intent.putExtra(BlockActivity.EXTRA_REASON,          reason);
        intent.putExtra(BlockActivity.EXTRA_SLEEP_END,       sleepEndTime);
        intent.putExtra(BlockActivity.EXTRA_COOLDOWN_END_MS, cooldownEndsAtMs);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    // ── Get foreground app ────────────────────────────────────

    /**
     * Returns the package name of the app currently visible on screen.
     * Uses UsageStatsManager — requires PACKAGE_USAGE_STATS permission.
     * Returns null if permission not granted or no app detected.
     */
    private String getForegroundApp() {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long now  = System.currentTimeMillis();
            long past = now - CHECK_INTERVAL_MS * 2; // look back 2 minutes

            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, past, now);

            if (stats == null || stats.isEmpty()) return null;

            // Find the app with the most recent last-used time
            SortedMap<Long, UsageStats> sortedMap = new TreeMap<>();
            for (UsageStats s : stats) {
                sortedMap.put(s.getLastTimeUsed(), s);
            }

            return sortedMap.get(sortedMap.lastKey()).getPackageName();

        } catch (Exception e) {
            return null;
        }
    }

    // ── Foreground notification ───────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW // silent — no sound
            );
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
                .setOngoing(true) // cannot be dismissed by user
                .build();
    }
}