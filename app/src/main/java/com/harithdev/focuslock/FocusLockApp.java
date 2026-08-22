package com.harithdev.focuslock;

import android.app.Application;
import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.harithdev.focuslock.worker.MidnightResetWorker;

import java.util.Calendar;
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

        // ── Initialize Timber for structured diagnostics ──────────
        timber.log.Timber.plant(new timber.log.Timber.DebugTree());
        timber.log.Timber.d("🚀 FocusLock Application initialized");

        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            timber.log.Timber.e(throwable, "💥 FATAL CRASH on thread %s: %s", thread.getName(), throwable.getMessage());
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        // ── App Lifecycle Tracker for PIN Session ───────────────────
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            private int activityReferences = 0;
            private boolean isActivityChangingConfigurations = false;

            @Override
            public void onActivityCreated(@androidx.annotation.NonNull android.app.Activity activity, android.os.Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(@androidx.annotation.NonNull android.app.Activity activity) {
                if (++activityReferences == 1 && !isActivityChangingConfigurations) {
                    // App entered foreground
                }
            }

            @Override
            public void onActivityResumed(@androidx.annotation.NonNull android.app.Activity activity) {}

            @Override
            public void onActivityPaused(@androidx.annotation.NonNull android.app.Activity activity) {}

            @Override
            public void onActivityStopped(@androidx.annotation.NonNull android.app.Activity activity) {
                isActivityChangingConfigurations = activity.isChangingConfigurations();
                if (--activityReferences == 0 && !isActivityChangingConfigurations) {
                    // App entered background (user went home or switched app) -> lock PIN session!
                    com.harithdev.focuslock.security.PinManager.lockSession();
                }
            }

            @Override
            public void onActivitySaveInstanceState(@androidx.annotation.NonNull android.app.Activity activity, @androidx.annotation.NonNull android.os.Bundle outState) {}

            @Override
            public void onActivityDestroyed(@androidx.annotation.NonNull android.app.Activity activity) {}
        });

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
}