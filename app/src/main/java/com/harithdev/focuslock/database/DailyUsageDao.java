package com.harithdev.focuslock.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.harithdev.focuslock.model.DailyUsage;

/**
 * DailyUsageDao — Room DAO
 *
 * All database operations for the daily_usage table.
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/database/DailyUsageDao.java
 */
@Dao
public interface DailyUsageDao {

    // ── Insert / update ───────────────────────────────────────

    /**
     * Insert a new daily usage row.
     * IGNORE strategy: if a row already exists for this app+date, do nothing.
     * We call this at the start of each day / first-time tracking.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(DailyUsage dailyUsage);

    /**
     * Update an existing daily usage row.
     * Called by the background service whenever session state changes.
     */
    @Update
    void update(DailyUsage dailyUsage);

    // ── Queries ───────────────────────────────────────────────

    /**
     * Get today's usage record for a specific app.
     * Returns null if no record exists yet for today (first open of the day).
     *
     * @param packageName  e.g. "com.instagram.android"
     * @param date         today's date as "yyyy-MM-dd"
     */
    @Query("SELECT * FROM daily_usage WHERE packageName = :packageName AND date = :date LIMIT 1")
    DailyUsage getUsage(String packageName, String date);

    /**
     * Delete all usage records older than today.
     * Called by MidnightResetWorker to keep the DB clean.
     *
     * @param today  today's date as "yyyy-MM-dd" — rows before this are deleted
     */
    @Query("DELETE FROM daily_usage WHERE date < :today")
    void deleteOldRecords(String today);

    /**
     * Reset all today's usage records — called at midnight.
     * Zeroes out session counts, clears cooldown flags for the new day.
     *
     * @param today  today's date string — only today's rows are reset
     */
    @Query("UPDATE daily_usage SET " +
            "sessionsUsedToday = 0, " +
            "inActiveSession = 0, " +
            "sessionStartTimeMs = 0, " +
            "inCooldown = 0, " +
            "cooldownEndsAtMs = 0, " +
            "inSleepBlock = 0 " +
            "WHERE date = :today")
    void resetAllForDate(String today);
}