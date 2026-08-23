package com.harithdev.focuslock.util;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.Calendar;

/**
 * UsageCalculator — shared screen-time calculation utility.
 *
 * This is the ONLY place that queries UsageStatsManager.
 * Both the UI (AppDetailActivity) and the enforcement services
 * (UsageTrackingService) use this class to ensure they always
 * agree on how much time has been used.
 *
 * Why queryEvents() instead of queryUsageStats()?
 * ──────────────────────────────────────────────
 * On Xiaomi / MIUI devices, queryUsageStats(INTERVAL_DAILY) returns
 * inflated numbers that include background service time, foreground
 * service time, and delayed/batched events. This makes it disagree
 * with what Digital Wellbeing shows.
 *
 * queryEvents() gives us raw ACTIVITY_RESUMED / ACTIVITY_PAUSED
 * pairs, so we can manually sum only the time the app was actually
 * on the screen. This matches Digital Wellbeing's methodology.
 *
 * MIUI-specific workaround:
 * ─────────────────────────
 * MIUI sometimes delivers events in bursts (delayed). If the app is
 * still in the foreground (last event was RESUMED with no PAUSED),
 * we add the live elapsed time from that last resume up to `now`.
 * This prevents under-counting when the user is actively using the app.
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/util/UsageCalculator.java
 */
public class UsageCalculator {

    // UsageEvents event type constants (not in the public API by name on older SDKs)
    private static final int ACTIVITY_RESUMED = UsageEvents.Event.ACTIVITY_RESUMED; // 10
    private static final int ACTIVITY_PAUSED  = UsageEvents.Event.ACTIVITY_PAUSED;  // 11

    /**
     * Returns the total foreground screen time (in milliseconds) for a given
     * app package since midnight today.
     *
     * This uses UsageEvents to iterate through ACTIVITY_RESUMED /
     * ACTIVITY_PAUSED pairs and sum up only the actual on-screen time.
     *
     * If the app is currently in the foreground (unmatched RESUMED at the end),
     * we include the live time up to `now` as well.
     *
     * @param context      any Android context
     * @param packageName  the app's package name e.g. "com.instagram.android"
     * @return total screen time in milliseconds, or 0 if permission not granted
     */
    public static long getScreenTimeToday(Context context, String packageName) {
        return getScreenTimeForDate(context, packageName, TimeUtils.todayString());
    }

    /**
     * Returns total screen time in ms for a given app package on a specific date (yyyy-MM-dd).
     */
    public static long getScreenTimeForDate(Context context, String packageName, String dateStr) {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null || dateStr == null) return 0;

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
            java.util.Date date = sdf.parse(dateStr);
            if (date == null) return 0;

            Calendar startCal = Calendar.getInstance();
            startCal.setTime(date);
            startCal.set(Calendar.HOUR_OF_DAY, 0);
            startCal.set(Calendar.MINUTE, 0);
            startCal.set(Calendar.SECOND, 0);
            startCal.set(Calendar.MILLISECOND, 0);
            long startTime = startCal.getTimeInMillis();

            Calendar endCal = Calendar.getInstance();
            endCal.setTime(date);
            endCal.set(Calendar.HOUR_OF_DAY, 23);
            endCal.set(Calendar.MINUTE, 59);
            endCal.set(Calendar.SECOND, 59);
            endCal.set(Calendar.MILLISECOND, 999);
            long endTime = Math.min(endCal.getTimeInMillis(), System.currentTimeMillis());

            if (startTime > System.currentTimeMillis()) return 0;

            UsageEvents events = usm.queryEvents(startTime, endTime);
            if (events == null) return 0;

            UsageEvents.Event event = new UsageEvents.Event();

            long totalScreenTimeMs = 0;
            long lastResumedMs     = 0;

            while (events.hasNextEvent()) {
                events.getNextEvent(event);

                if (!packageName.equals(event.getPackageName())) continue;

                int type = event.getEventType();

                if (type == ACTIVITY_RESUMED) {
                    lastResumedMs = event.getTimeStamp();

                } else if (type == ACTIVITY_PAUSED) {
                    if (lastResumedMs > 0) {
                        long sessionMs = event.getTimeStamp() - lastResumedMs;
                        if (sessionMs > 0 && sessionMs < 24 * 60 * 60 * 1000L) {
                            totalScreenTimeMs += sessionMs;
                        }
                        lastResumedMs = 0;
                    }
                }
            }

            if (lastResumedMs > 0 && dateStr.equals(TimeUtils.todayString())) {
                totalScreenTimeMs += (System.currentTimeMillis() - lastResumedMs);
            }

            return totalScreenTimeMs;

        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Returns total screen time in ms across all restricted apps on a specific date (yyyy-MM-dd).
     */
    public static long getTotalRestrictedScreenTimeForDate(Context context,
                                                           java.util.List<com.harithdev.focuslock.model.AppRestriction> restrictions,
                                                           String dateStr) {
        if (restrictions == null || restrictions.isEmpty()) return 0;
        long totalMs = 0;
        for (com.harithdev.focuslock.model.AppRestriction app : restrictions) {
            totalMs += getScreenTimeForDate(context, app.packageName, dateStr);
        }
        return totalMs;
    }
}
