package com.harithdev.focuslock.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.harithdev.focuslock.model.AppRestriction;

import java.util.List;

/**
 * AppRestrictionDao — Room DAO
 *
 * All database operations for the app_restrictions table.
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/database/AppRestrictionDao.java
 */
@Dao
public interface AppRestrictionDao {

    // ── Insert / update ───────────────────────────────────────

    /**
     * Insert a new restriction row.
     * REPLACE strategy: if the same packageName exists, overwrite it.
     * Used when user saves settings on the detail screen.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AppRestriction restriction);

    /**
     * Update an existing restriction row.
     * Used when toggling isRestricted on/off from the list screen.
     */
    @Update
    void update(AppRestriction restriction);

    // ── Queries ───────────────────────────────────────────────

    /**
     * Get all restricted apps as LiveData.
     * The UI observes this — it auto-refreshes whenever the DB changes.
     */
    @Query("SELECT * FROM app_restrictions ORDER BY appName ASC")
    LiveData<List<AppRestriction>> getAllRestrictions();

    /**
     * Get a single app's settings by package name.
     * Used when opening the detail screen for a specific app.
     */
    @Query("SELECT * FROM app_restrictions WHERE packageName = :packageName LIMIT 1")
    AppRestriction getByPackageName(String packageName);

    /**
     * Get only apps that have restriction actively enabled.
     * Used by the background service — it only needs to track active apps.
     */
    @Query("SELECT * FROM app_restrictions WHERE isRestricted = 1")
    List<AppRestriction> getActiveRestrictions();

    /**
     * Delete a restriction row entirely.
     * Used if user wants to completely remove an app from FocusLock.
     */
    @Query("DELETE FROM app_restrictions WHERE packageName = :packageName")
    void deleteByPackageName(String packageName);
}