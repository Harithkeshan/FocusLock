package com.harithdev.focuslock;

import android.app.Application;
import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.harithdev.focuslock.database.FocusLockDatabase;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * FocusLockApp — Application class
 *
 * Runs once when the app process starts.
 * Schedules the midnight reset job here so it always stays registered.
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/FocusLockApp.java
 *
 * IMPORTANT: Register this in AndroidManifest.xml inside <application>:
 *   android:name=".FocusLockApp"
 */
public class FocusLockApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        scheduleMidnightReset();
    }

    /**
     * Schedules MidnightResetWorker to run once every 24 hours.
     * KEEP policy means: if a job with this name already exists, keep it.
     * This prevents duplicate jobs after app restarts.
     */
    private void scheduleMidnightReset() {

        // Calculate how many minutes until the next midnight
        long delayMinutes = minutesUntilMidnight();

        PeriodicWorkRequest resetRequest =
                new PeriodicWorkRequest.Builder(
                        MidnightResetWorker.class,
                        24, TimeUnit.HOURS           // repeat every 24 hours
                )
                        .setInitialDelay(delayMinutes, TimeUnit.MINUTES) // first run at midnight
                        .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "midnight_reset",                // unique name — prevents duplicates
                ExistingPeriodicWorkPolicy.KEEP, // don't replace if already scheduled
                resetRequest
        );
    }

    /**
     * Calculates minutes from now until the next midnight (00:00).
     */
    private long minutesUntilMidnight() {
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        midnight.add(Calendar.DAY_OF_YEAR, 1); // next midnight, not past midnight

        long diffMs = midnight.getTimeInMillis() - System.currentTimeMillis();
        return TimeUnit.MILLISECONDS.toMinutes(diffMs);
    }

    // ═══════════════════════════════════════════════════════════
    //  MidnightResetWorker — inner class
    //  Runs every night at midnight. Zeroes out all daily usage.
    // ═══════════════════════════════════════════════════════════

    public class MidnightResetWorker extends Worker {

        public MidnightResetWorker(Context context, WorkerParameters params) {
            super(context, params);
        }

        @Override
        public Result doWork() {
            try {
                Context context = getApplicationContext();
                FocusLockDatabase db = FocusLockDatabase.getInstance(context);

                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(new Date());

                db.dailyUsageDao().resetAllForDate(today);
                db.dailyUsageDao().deleteOldRecords(today);

                return Result.success();

            } catch (Exception e) {
                return Result.retry();
            }
        }
    }
}