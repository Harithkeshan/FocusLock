package com.harithdev.focuslock.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
 *   • FIX 6: Sends a "5 minutes remaining" warning notification when the
 *     user is approaching their daily or session limit.
 *   • FIX 8: Detects and ignores date/time manipulation (clock jumps back).
 *   • Also acts as a safety net: re-checks daily limit in case the
 *     Accessibility Service missed an event.
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/service/UsageTrackingService.java
 */
public class UsageTrackingService extends Service {

    private static final String TAG          = "FocusLock";
    private static final String CHANNEL_ID   = "focuslock_service";
    private static final String CHANNEL_NAME = "FocusLock Running";

    // ── FIX 6: Warning notification channel ───────────────────────────
    private static final String WARN_CHANNEL_ID   = "focuslock_warnings";
    private static final String WARN_CHANNEL_NAME = "FocusLock Limit Warnings";
    private static final int    NOTIF_ID          = 1001;
    /** Warning is sent when this many ms remain in the limit */
    private static final long   WARN_THRESHOLD_MS = 5 * 60_000L; // 5 minutes

    private static final long   CHECK_INTERVAL_MS = 15_000; // 15 seconds

    /** SharedPreferences key prefix for tracking per-app, per-day warning state.
     *  Format: "warned_com.instagram.android_2025-08-15" = true/false */
    private static final String PREFS_NAME         = "focuslock_prefs";
    private static final String KEY_WARN_PREFIX     = "warned_";

    // ── FIX 8: Clock manipulation detection ───────────────────────────
    /** Minimum forward-jump that we consider suspicious (5 minutes). */
    private static final long CLOCK_TOLERANCE_MS = 5 * 60_000L;
    private static final String KEY_LAST_KNOWN_MS = "last_known_timestamp_ms";

    private Handler  handler;
    private Runnable checkRunnable;
    private FocusLockDatabase db;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        db    = FocusLockDatabase.getInstance(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        handler = new Handler(Looper.getMainLooper());
        createNotificationChannels();
        startForeground(NOTIF_ID, buildServiceNotification());
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
    //  Core check
    // ══════════════════════════════════════════════════════════════

    private void performCheck() {
        long now = System.currentTimeMillis();

        // ── FIX 8: Clock manipulation detection ───────────────────────
        // If the system clock appears to have jumped backwards by more than
        // CLOCK_TOLERANCE_MS, the user likely manipulated the date/time to
        // bypass their daily limit. We reject this cycle and don't process
        // any enforcement, preventing a reset of their usage counter.
        long lastKnownMs = prefs.getLong(KEY_LAST_KNOWN_MS, 0);
        if (lastKnownMs > 0 && now < lastKnownMs - CLOCK_TOLERANCE_MS) {
            Log.w(TAG, "⚠️ Clock went backwards by "
                    + (lastKnownMs - now) / 1000 + "s — ignoring cycle (likely date manipulation)");
            // Don't update lastKnownMs — keep the high watermark
            return;
        }
        // Always store the highest timestamp we've seen (monotonic high watermark)
        if (now > lastKnownMs) {
            prefs.edit().putLong(KEY_LAST_KNOWN_MS, now).apply();
        }

        // ── Use Accessibility Service's real-time foreground detection ──
        String foreground = FocusLockAccessibilityService.currentForegroundApp;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        boolean isInteractive = pm != null && pm.isInteractive();

        if (foreground == null || !FocusLockAccessibilityService.isServiceRunning || !isInteractive) {
            Log.d(TAG, "⚠️ Accessibility data stale, screen off, or null — skipping cycle");
            return;
        }

        if (isSafeApp(foreground)) return;

        AppRestriction restriction = db.appRestrictionDao().getByPackageName(foreground);
        if (restriction == null || !restriction.isRestricted) return;

        String today = TimeUtils.todayString();

        // ALWAYS check daily limit safety net first, even in split sessions
        int dailyStatus = checkDailyLimitSafetyNet(restriction, foreground, now, today);
        if (dailyStatus == 2) {
            return;
        }

        if (!restriction.splitSessions) {
            return;
        }

        // ── Split-session mode: Behaviour 1 continuous session timeout ──
        DailyUsage usage = db.dailyUsageDao().getUsage(foreground, today);
        if (usage == null || !usage.inActiveSession) return;

        long liveElapsedMs  = (usage.sessionStartTimeMs > 0) ? (now - usage.sessionStartTimeMs) : 0;
        long totalSessionMs = usage.currentSessionUsedMs + liveElapsedMs;
        long slotDurationMs = restriction.getSlotDurationMinutes() * 60_000L;

        Log.d(TAG, "⏱️ [" + foreground + "] session: "
                + totalSessionMs / 1000 + "s / " + slotDurationMs / 1000 + "s");

        // FIX 6: Send warning when 5 minutes remain in the current session
        long remainingMs = slotDurationMs - totalSessionMs;
        if (remainingMs > 0 && remainingMs <= WARN_THRESHOLD_MS) {
            if (dailyStatus != 1) { // Smart prioritization: Skip if Daily Warning is active
                String appLabel = getAppLabel(foreground);
                sendLimitWarning(foreground, today, appLabel,
                        "session", Math.max(1, (int)(remainingMs / 60_000)), usage.sessionsUsedToday);
            }
        }

        if (totalSessionMs >= slotDurationMs) {
            Log.d(TAG, "⛔ BLOCKING — session timeout (Behaviour 1)");

            usage.totalUsedMs         += liveElapsedMs;
            usage.currentSessionUsedMs = 0;
            usage.inActiveSession      = false;
            usage.sessionStartTimeMs   = 0;
            usage.inCooldown           = true;
            usage.cooldownEndsAtMs     = now + (restriction.cooldownMinutes * 60_000L);
            usage.isEarlyExitCooldown  = false;
            db.dailyUsageDao().update(usage);

            showBlockScreen(foreground, BlockActivity.REASON_SESSION_TIMEOUT,
                    null, usage.cooldownEndsAtMs, 0);
        }
    }

    /**
     * Safety net for daily-limit mode (also applies in split-session mode).
     * Also sends the "5 minutes remaining" warning notification (FIX 6).
     * Returns 2 if blocked, 1 if warning sent/active, 0 if normal.
     */
    private int checkDailyLimitSafetyNet(AppRestriction restriction,
                                          String foreground, long now, String today) {
        DailyUsage usage = db.dailyUsageDao().getUsage(foreground, today);
        if (usage == null || !usage.inActiveSession) return 0;

        long liveElapsedMs = (usage.sessionStartTimeMs > 0) ? (now - usage.sessionStartTimeMs) : 0;
        long totalMs       = usage.totalUsedMs + liveElapsedMs;
        long dailyLimitMs  = restriction.dailyLimitMinutes * 60_000L;

        // FIX 6: Send warning when 5 minutes remain in the daily limit
        long remainingMs = dailyLimitMs - totalMs;
        boolean warningActive = (remainingMs > 0 && remainingMs <= WARN_THRESHOLD_MS);
        if (warningActive) {
            String appLabel = getAppLabel(foreground);
            sendLimitWarning(foreground, today, appLabel,
                    "daily", Math.max(1, (int)(remainingMs / 60_000)), usage.sessionsUsedToday);
        }

        if (totalMs >= dailyLimitMs) {
            Log.d(TAG, "🔒 BLOCKING — daily limit reached (safety net)");

            usage.totalUsedMs       += liveElapsedMs;
            usage.inActiveSession    = false;
            usage.sessionStartTimeMs = 0;
            db.dailyUsageDao().update(usage);

            showBlockScreen(foreground, BlockActivity.REASON_LIMIT, null, 0, 0);
            return 2;
        }
        return warningActive ? 1 : 0;
    }

    // ══════════════════════════════════════════════════════════════
    //  FIX 6 — "Approaching limit" warning notification
    // ══════════════════════════════════════════════════════════════

    /**
     * Sends a warning notification that the user is approaching their limit.
     *
     * De-duplication: uses SharedPreferences to ensure the warning is sent
     * at most ONCE per app per day. The key is cleared on the next day's
     * usage, so it fires fresh each new day.
     *
     * @param pkg        package name of the app being tracked
     * @param today      today's date string ("yyyy-MM-dd")
     * @param appLabel   human-readable app name
     * @param limitType  "session" or "daily"
     * @param minsLeft   approximate minutes remaining (shown in notification)
     * @param sessionsUsedToday current session index for unique keys
     */
    private void sendLimitWarning(String pkg, String today,
                                  String appLabel, String limitType, int minsLeft, int sessionsUsedToday) {
        String warnKey = KEY_WARN_PREFIX + pkg + "_" + today + "_" + limitType;
        if (limitType.equals("session")) {
            warnKey += "_" + sessionsUsedToday; // Fix Once-Per-Day bug
        }
        
        if (prefs.getBoolean(warnKey, false)) {
            return; // Already sent the warning for this app today/session
        }
        prefs.edit().putBoolean(warnKey, true).apply();

        String title   = "⏰ " + appLabel + " — " + minsLeft + " min left";
        String message = limitType.equals("session")
                ? "Your current session ends in " + minsLeft + " min. FocusLock will block the app after."
                : "You've almost used your daily limit for " + appLabel + ". " + minsLeft + " min left today.";

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Notification ID is unique per-app so multiple apps show separate notifications
        int warnNotifId = 2000 + Math.abs(pkg.hashCode() % 1000);
        if (limitType.equals("session")) {
            warnNotifId += 1000; // Separate IDs to prevent overwriting
        }

        Notification notification = new NotificationCompat.Builder(this, WARN_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();

        nm.notify(warnNotifId, notification);
        Log.d(TAG, "🔔 Limit warning sent for " + pkg + " [" + limitType + "] " + minsLeft + "min left");
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════

    private String getAppLabel(String pkg) {
        try {
            return getPackageManager()
                    .getApplicationLabel(
                            getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception e) {
            return pkg;
        }
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
    //  Notifications
    // ══════════════════════════════════════════════════════════════

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            // Foreground service channel (persistent, silent)
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            serviceChannel.setDescription("FocusLock is monitoring your app usage");
            nm.createNotificationChannel(serviceChannel);

            // FIX 6: Warning channel (high importance — pops up as heads-up notification)
            NotificationChannel warnChannel = new NotificationChannel(
                    WARN_CHANNEL_ID, WARN_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            warnChannel.setDescription("Alerts when you're close to your app limit");
            warnChannel.enableVibration(true);
            nm.createNotificationChannel(warnChannel);
        }
    }

    private Notification buildServiceNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FocusLock is active")
                .setContentText("Monitoring your app usage")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}