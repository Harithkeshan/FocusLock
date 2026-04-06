package com.harithdev.focuslock.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * AppRestriction — Room Entity
 *
 * One row per restricted app. Stores everything the user configured
 * on the detail screen for a given app (e.g. Instagram).
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/model/AppRestriction.java
 */
@Entity(tableName = "app_restrictions")
public class AppRestriction {

    // ── Primary key ───────────────────────────────────────────
    // The app's unique package name e.g. "com.instagram.android"
    @PrimaryKey
    @NonNull
    public String packageName;

    // Human-readable app name e.g. "Instagram" — stored for display
    public String appName;

    // ── Master switch ─────────────────────────────────────────
    // If false, all limits below are ignored even if they are set
    public boolean isRestricted;

    // ── Sleep mode ────────────────────────────────────────────
    // Whether sleep mode is enabled for this app
    public boolean sleepModeEnabled;

    // Sleep start time stored as "HH:mm" in 24-hour format
    // e.g. "01:00" means 1:00 AM
    public String sleepStartTime;

    // Sleep end time stored as "HH:mm" in 24-hour format
    // e.g. "12:00" means 12:00 PM (noon)
    public String sleepEndTime;

    // ── Daily time limit ──────────────────────────────────────
    // Total allowed usage per day in MINUTES
    // e.g. 60 = 1 hour per day
    public int dailyLimitMinutes;

    // ── Session splitting ─────────────────────────────────────
    // If false → user can use the full dailyLimitMinutes in one go
    // If true  → limit is split into equal slots
    public boolean splitSessions;

    // Number of sessions per day (2–6). Only used when splitSessions = true
    // e.g. 4 → four slots of (dailyLimitMinutes / 4) each
    public int sessionCount;

    // Cooldown duration in MINUTES between sessions
    // Min: 40, Max: 300 (5 hours)
    public int cooldownMinutes;

    // ── Constructor ───────────────────────────────────────────
    public AppRestriction(@NonNull String packageName, String appName) {
        this.packageName      = packageName;
        this.appName          = appName;
        this.isRestricted     = false;
        this.sleepModeEnabled = false;
        this.sleepStartTime   = "01:00";
        this.sleepEndTime     = "12:00";
        this.dailyLimitMinutes = 60;
        this.splitSessions    = false;
        this.sessionCount     = 4;
        this.cooldownMinutes  = 40;
    }

    // ── Helper: minutes per slot ──────────────────────────────
    // Returns how long each session is in minutes
    // e.g. dailyLimitMinutes=60, sessionCount=4 → returns 15
    public int getSlotDurationMinutes() {
        if (!splitSessions || sessionCount <= 0) return dailyLimitMinutes;
        return dailyLimitMinutes / sessionCount;
    }
}