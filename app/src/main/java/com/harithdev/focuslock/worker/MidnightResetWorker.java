package com.harithdev.focuslock.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.util.TimeUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MidnightResetWorker — Top-level WorkManager Worker.
 *
 * Runs every night at midnight. Zeroes out all daily usage.
 * Extracted to a top-level class so WorkManager can instantiate it via reflection.
 */
public class MidnightResetWorker extends Worker {

    public MidnightResetWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context context = getApplicationContext();
            FocusLockDatabase db = FocusLockDatabase.getInstance(context);

            String today = TimeUtils.todayString();
            String yesterday = TimeUtils.yesterdayString();

            // 1. Archive yesterday's usage to usage_history table
            archiveUsage(context, db, yesterday);

            // 2. Reset daily usage for today
            db.dailyUsageDao().resetAllForDate(today);
            db.dailyUsageDao().deleteOldRecords(today);

            // 3. Purge history older than 30 days
            String cutoffDate = get30DaysAgoDate();
            db.usageHistoryDao().deleteOldHistory(cutoffDate);

            return Result.success();

        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void archiveUsage(Context context, FocusLockDatabase db, String date) {
        java.util.List<com.harithdev.focuslock.model.AppRestriction> activeApps =
                db.appRestrictionDao().getActiveRestrictions();

        java.util.List<com.harithdev.focuslock.model.UsageHistory> historyList = new java.util.ArrayList<>();

        for (com.harithdev.focuslock.model.AppRestriction app : activeApps) {
            com.harithdev.focuslock.model.DailyUsage usage = db.dailyUsageDao().getUsage(app.packageName, date);
            long totalUsedMs = usage != null ? usage.totalUsedMs : 0;
            int sessionsUsed = usage != null ? usage.sessionsUsedToday : 0;

            com.harithdev.focuslock.model.UsageHistory history =
                    new com.harithdev.focuslock.model.UsageHistory(app.packageName, date);
            history.appName           = app.appName;
            history.totalUsedMs       = totalUsedMs;
            history.sessionsUsed      = sessionsUsed;
            history.sessionsAllowed   = app.enforcedSessionCount;
            history.dailyLimitMinutes = app.enforcedDailyLimitMinutes;

            historyList.add(history);
        }

        if (!historyList.isEmpty()) {
            db.usageHistoryDao().insertAll(historyList);
        }
    }

    private String get30DaysAgoDate() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DATE, -30);
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(cal.getTime());
    }
}
