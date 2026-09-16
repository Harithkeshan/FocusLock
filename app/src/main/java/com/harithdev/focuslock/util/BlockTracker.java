package com.harithdev.focuslock.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * BlockTracker — lightweight tracker for intercepted app launches / impulse opens.
 *
 * Records every time a block screen is displayed to give users positive feedback
 * on how many distractions FocusLock prevented today.
 */
public class BlockTracker {

    private static final String PREFS_NAME = "focuslock_blocks";
    private static final String KEY_PREFIX = "blocks_";

    /**
     * Atomically increments the block counter for today.
     */
    public static synchronized void recordBlock(Context context) {
        if (context == null) return;
        String today = TimeUtils.todayString();
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int current = sp.getInt(KEY_PREFIX + today, 0);
        sp.edit().putInt(KEY_PREFIX + today, current + 1).apply();
    }

    /**
     * Returns the number of blocks/interventions recorded today.
     */
    public static int getBlocksToday(Context context) {
        if (context == null) return 0;
        return getBlocksForDate(context, TimeUtils.todayString());
    }

    /**
     * Returns the number of blocks/interventions recorded for a specific date (yyyy-MM-dd).
     */
    public static int getBlocksForDate(Context context, String dateStr) {
        if (context == null || dateStr == null) return 0;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_PREFIX + dateStr, 0);
    }
}
