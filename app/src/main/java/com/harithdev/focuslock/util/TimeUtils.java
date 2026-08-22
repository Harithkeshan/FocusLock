package com.harithdev.focuslock.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * TimeUtils — helper class
 *
 * Shared date/time helpers used across the service, workers, and UI.
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/util/TimeUtils.java
 */
public class TimeUtils {

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()));

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm", Locale.getDefault()));

    // ── Date helpers ──────────────────────────────────────────

    /** Returns today's date as "yyyy-MM-dd" e.g. "2025-08-15" */
    public static String todayString() {
        return DATE_FORMAT.get().format(new Date());
    }

    /** Returns yesterday's date as "yyyy-MM-dd" — used to carry over
     *  cooldowns that span the midnight boundary. */
    public static String yesterdayString() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1);
        return DATE_FORMAT.get().format(cal.getTime());
    }

    // ── Sleep mode helpers ────────────────────────────────────

    /**
     * Returns true if the current time is within the sleep window.
     *
     * Handles overnight ranges (e.g. 01:00 → 12:00 means
     * block from 1 AM through noon).
     *
     * @param sleepStart  "HH:mm" e.g. "01:00"
     * @param sleepEnd    "HH:mm" e.g. "12:00"
     */
    public static boolean isInSleepWindow(String sleepStart, String sleepEnd) {
        try {
            int nowMins   = currentMinutesOfDay();
            int startMins = parseTimeToMinutes(sleepStart);
            int endMins   = parseTimeToMinutes(sleepEnd);

            if (startMins < endMins) {
                // Same-day range: e.g. 01:00 → 12:00
                return nowMins >= startMins && nowMins < endMins;
            } else {
                // Overnight range: e.g. 22:00 → 06:00
                return nowMins >= startMins || nowMins < endMins;
            }
        } catch (Exception e) {
            timber.log.Timber.e(e, "Error evaluating sleep window %s - %s", sleepStart, sleepEnd);
            return false;
        }
    }

    /**
     * Returns a human-readable "come back at X" string.
     * e.g. sleepEnd = "12:00" → "Come back at 12:00 PM"
     */
    public static String formatSleepEndTime(String sleepEnd) {
        try {
            int totalMins = parseTimeToMinutes(sleepEnd);
            int h = totalMins / 60;
            int m = totalMins % 60;
            String ampm = h >= 12 ? "PM" : "AM";
            int displayH = h > 12 ? h - 12 : (h == 0 ? 12 : h);
            return String.format(Locale.getDefault(), "%d:%02d %s", displayH, m, ampm);
        } catch (Exception e) {
            timber.log.Timber.e(e, "Error formatting sleep end time %s", sleepEnd);
            return sleepEnd;
        }
    }

    /**
     * Returns a human-readable cooldown end time string.
     * e.g. cooldownEndsAtMs → "2:45 PM"
     */
    public static String formatCooldownEnd(long cooldownEndsAtMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(cooldownEndsAtMs);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        String ampm = h >= 12 ? "PM" : "AM";
        int displayH = h > 12 ? h - 12 : (h == 0 ? 12 : h);
        return String.format(Locale.getDefault(), "%d:%02d %s", displayH, m, ampm);
    }

    // ── Private helpers ───────────────────────────────────────

    /** Current time as total minutes since midnight. e.g. 13:30 → 810 */
    private static int currentMinutesOfDay() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    /**
     * Parses "HH:mm" string to total minutes since midnight.
     * e.g. "01:30" → 90
     */
    public static int parseTimeToMinutes(String time) {
        String[] parts = time.split(":");
        int hours   = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        return hours * 60 + minutes;
    }
}