package com.harithdev.focuslock.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;

/**
 * FocusLockDatabase — Room Database
 *
 * Single entry point for all database access.
 * Singleton pattern — only one instance exists in the whole app.
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/database/FocusLockDatabase.java
 */
@Database(
        entities  = {AppRestriction.class, DailyUsage.class},
        version   = 1,
        exportSchema = false
)
public abstract class FocusLockDatabase extends RoomDatabase {

    // Abstract methods — Room generates the implementations automatically
    public abstract AppRestrictionDao appRestrictionDao();
    public abstract DailyUsageDao     dailyUsageDao();

    // ── Singleton ─────────────────────────────────────────────
    private static volatile FocusLockDatabase INSTANCE;

    /**
     * Get the single database instance.
     * Creates it on first call, returns the same instance on every call after.
     *
     * Usage from anywhere in the app:
     *   FocusLockDatabase db = FocusLockDatabase.getInstance(context);
     *   AppRestrictionDao dao = db.appRestrictionDao();
     */
    public static FocusLockDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (FocusLockDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    FocusLockDatabase.class,
                                    "focuslock_db"          // name of the .db file on device
                            )
                            // If you change the schema in a future version,
                            // add a proper Migration instead of this line.
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}