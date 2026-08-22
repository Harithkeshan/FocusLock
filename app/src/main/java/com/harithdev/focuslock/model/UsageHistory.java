package com.harithdev.focuslock.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/**
 * UsageHistory — Room Entity
 *
 * Stores archived daily usage records for historical trend analytics.
 * Archived every midnight by MidnightResetWorker before daily resetting.
 */
@Entity(
        tableName = "usage_history",
        primaryKeys = {"packageName", "date"}
)
public class UsageHistory {

    @NonNull
    public String packageName = "";

    @NonNull
    public String date = ""; // "yyyy-MM-dd"

    public String appName;
    public long totalUsedMs;
    public int sessionsUsed;
    public int sessionsAllowed;
    public int dailyLimitMinutes;

    public UsageHistory(@NonNull String packageName, @NonNull String date) {
        this.packageName       = packageName;
        this.date              = date;
        this.appName           = "";
        this.totalUsedMs       = 0;
        this.sessionsUsed      = 0;
        this.sessionsAllowed   = 0;
        this.dailyLimitMinutes = 0;
    }
}
