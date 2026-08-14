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
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/database/FocusLockDatabase.java
 */
@Database(
        entities  = {AppRestriction.class, DailyUsage.class},
        version   = 4,          // ← bumped from 3 to 4
        exportSchema = false
)
public abstract class FocusLockDatabase extends RoomDatabase {

    public abstract AppRestrictionDao appRestrictionDao();
    public abstract DailyUsageDao     dailyUsageDao();

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
    // Adds the cooldown-cause flag so BlockActivity shows the right message:
    //   0 = session timed out (Behaviour 1)
    //   1 = user exited early (Behaviour 2)
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
            // Add enforced value columns
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN enforcedDailyLimitMinutes INTEGER NOT NULL DEFAULT 60");
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN enforcedSessionCount INTEGER NOT NULL DEFAULT 4");
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN enforcedCooldownMinutes INTEGER NOT NULL DEFAULT 40");
            db.execSQL("ALTER TABLE app_restrictions ADD COLUMN lastEnforcedSyncDate TEXT");
            
            // Sync enforced = desired for ALL existing restrictions
            db.execSQL("UPDATE app_restrictions SET "
                + "enforcedDailyLimitMinutes = dailyLimitMinutes, "
                + "enforcedSessionCount = sessionCount, "
                + "enforcedCooldownMinutes = cooldownMinutes");
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
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}