package com.harithdev.focuslock.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;
import com.harithdev.focuslock.model.UsageHistory;

/**
 * FocusLockDatabase — Room Database
 *
 * Single entry point for all database access.
 * Singleton pattern — only one instance exists in the whole app.
 *
 * Schema version history:
 *   v1 → initial schema
 *   v2 → added totalUsedMs, currentSessionUsedMs columns
 *   v3 → added isEarlyExitCooldown column (to show correct block message)
 *   v4 → added enforced columns
 *   v5 → added usage_history table for dashboard analytics
 *   v6 → added category column to app_restrictions
 */
@Database(
        entities  = {AppRestriction.class, DailyUsage.class, UsageHistory.class},
        version   = 6,
        exportSchema = false
)
public abstract class FocusLockDatabase extends RoomDatabase {

    public abstract AppRestrictionDao appRestrictionDao();
    public abstract DailyUsageDao     dailyUsageDao();
    public abstract UsageHistoryDao   usageHistoryDao();

    // ── Migration v1 → v2 ─────────────────────────────────────
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "ALTER TABLE daily_usage ADD COLUMN totalUsedMs INTEGER NOT NULL DEFAULT 0"
            );
            db.execSQL(
                "ALTER TABLE daily_usage ADD COLUMN currentSessionUsedMs INTEGER NOT NULL DEFAULT 0"
            );
        }
    };

    // ── Migration v2 → v3 ─────────────────────────────────────
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "ALTER TABLE daily_usage ADD COLUMN isEarlyExitCooldown INTEGER NOT NULL DEFAULT 0"
            );
        }
    };

    // ── Migration v3 → v4 ─────────────────────────────────────
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN enforcedDailyLimitMinutes INTEGER NOT NULL DEFAULT 60");
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN enforcedSessionCount INTEGER NOT NULL DEFAULT 4");
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN enforcedCooldownMinutes INTEGER NOT NULL DEFAULT 40");
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN lastEnforcedSyncDate TEXT");

            db.execSQL("UPDATE app_restrictions SET "
                + "enforcedDailyLimitMinutes = dailyLimitMinutes, "
                + "enforcedSessionCount = sessionCount, "
                + "enforcedCooldownMinutes = cooldownMinutes");
        }
    };

    // ── Migration v4 → v5 ─────────────────────────────────────
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS usage_history ("
                    + "packageName TEXT NOT NULL, "
                    + "date TEXT NOT NULL, "
                    + "appName TEXT, "
                    + "totalUsedMs INTEGER NOT NULL DEFAULT 0, "
                    + "sessionsUsed INTEGER NOT NULL DEFAULT 0, "
                    + "sessionsAllowed INTEGER NOT NULL DEFAULT 0, "
                    + "dailyLimitMinutes INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY(packageName, date))");
        }
    };

    // ── Migration v5 → v6 ─────────────────────────────────────
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN category TEXT DEFAULT 'Other'");
        }
    };

    // ── Singleton ─────────────────────────────────────────────
    private static volatile FocusLockDatabase INSTANCE;

    public static FocusLockDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (FocusLockDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    FocusLockDatabase.class,
                                    "focuslock_db"
                            )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}