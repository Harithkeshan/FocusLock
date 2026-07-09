package com.harithdev.focuslock.model;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.annotation.NonNull;

/**
 * DailyUsage — Room Entity
 *
 * Tracks how much of the daily allowance has been consumed TODAY
 * for a given app. One row per app per day.
 *
 * This gets reset every midnight by the MidnightResetWorker.
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/model/DailyUsage.java
 */
@Entity(
        tableName = "daily_usage",
        primaryKeys = {"packageName", "date"},
        foreignKeys = @ForeignKey(
                entity        = AppRestriction.class,
                parentColumns = "packageName",
                childColumns  = "packageName",
                onDelete      = ForeignKey.CASCADE  // delete usage records when app is removed
        )
)
public class DailyUsage {

    // Package name links this record to an AppRestriction row
    @NonNull
    public String packageName = "";

    // Date as "yyyy-MM-dd" e.g. "2025-08-15"
    // Combined with packageName as the composite primary key
    @NonNull
    public String date = "";

    // ── Session tracking ──────────────────────────────────────

    // How many sessions have been USED today (0 up to sessionCount)
    // Each session is counted as used the moment it starts (or closes early)
    public int sessionsUsedToday;

    // Whether the app is currently IN a session right now
    // True = user has an active open session at this moment
    public boolean inActiveSession;

    // When the current (or last) session started, as epoch millis
    // Used to calculate elapsed time inside a session
    public long sessionStartTimeMs;

    // ── Screen-time accumulation ──────────────────────────────

    // Total foreground screen time used today in milliseconds
    // Accumulated across all completed sessions. Compared against
    // dailyLimitMinutes * 60_000 to enforce the daily cap.
    public long totalUsedMs;

    // Screen time accumulated in the CURRENT open session in milliseconds.
    // Reset to 0 when a new session starts.
    // Compared against slotDurationMs to enforce per-session timeout (Behaviour 1).
    public long currentSessionUsedMs;

    // ── Cooldown tracking ─────────────────────────────────────

    // Whether the app is currently blocked due to cooldown
    public boolean inCooldown;

    // When the current cooldown period ends, as epoch millis
    // The block lifts when System.currentTimeMillis() > cooldownEndsAtMs
    public long cooldownEndsAtMs;

    // What caused this cooldown?
    // true  = user left the app mid-session (Behaviour 2 — early exit)
    // false = session slot ran out from continuous use (Behaviour 1 — timeout)
    // Used by BlockActivity to show the correct message.
    public boolean isEarlyExitCooldown;

    // ── Sleep tracking ────────────────────────────────────────

    // Whether the app is currently blocked due to sleep mode
    // (separate from session/cooldown blocking)
    public boolean inSleepBlock;

    // ── Constructor ───────────────────────────────────────────
    public DailyUsage(String packageName, String date) {
        this.packageName          = packageName;
        this.date                 = date;
        this.sessionsUsedToday    = 0;
        this.inActiveSession      = false;
        this.sessionStartTimeMs   = 0;
        this.totalUsedMs          = 0;
        this.currentSessionUsedMs = 0;
        this.inCooldown           = false;
        this.cooldownEndsAtMs     = 0;
        this.isEarlyExitCooldown  = false;
        this.inSleepBlock         = false;
    }
}