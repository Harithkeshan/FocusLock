package com.harithdev.focuslock.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.harithdev.focuslock.database.AppRestrictionDao;
import com.harithdev.focuslock.database.DailyUsageDao;
import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.database.UsageHistoryDao;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailyUsage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FocusLockRepository — Central data repository for FocusLock.
 *
 * Abstract the database operations away from the UI/Activities, providing
 * asynchronous background execution and LiveData streams.
 */
public class FocusLockRepository {

    public interface Callback<T> {
        void onResult(T result);
    }

    private final AppRestrictionDao appRestrictionDao;
    private final DailyUsageDao     dailyUsageDao;
    private final UsageHistoryDao   usageHistoryDao;
    private final ExecutorService   executor;

    public FocusLockRepository(Context context) {
        FocusLockDatabase db = FocusLockDatabase.getInstance(context);
        this.appRestrictionDao = db.appRestrictionDao();
        this.dailyUsageDao     = db.dailyUsageDao();
        this.usageHistoryDao   = db.usageHistoryDao();
        this.executor          = Executors.newFixedThreadPool(3);
    }

    // ── App Restrictions ──────────────────────────────────────

    public LiveData<List<AppRestriction>> getAllRestrictions() {
        return appRestrictionDao.getAllRestrictions();
    }

    public void getRestriction(String packageName, Callback<AppRestriction> callback) {
        executor.execute(() -> {
            AppRestriction restriction = appRestrictionDao.getByPackageName(packageName);
            callback.onResult(restriction);
        });
    }

    public void saveRestriction(AppRestriction restriction, Runnable onComplete) {
        executor.execute(() -> {
            appRestrictionDao.insert(restriction);
            if (onComplete != null) onComplete.run();
        });
    }

    public void updateRestriction(AppRestriction restriction) {
        executor.execute(() -> appRestrictionDao.update(restriction));
    }

    public void deleteRestriction(String packageName) {
        executor.execute(() -> appRestrictionDao.deleteByPackageName(packageName));
    }

    public void getActiveRestrictions(Callback<List<AppRestriction>> callback) {
        executor.execute(() -> {
            List<AppRestriction> active = appRestrictionDao.getActiveRestrictions();
            callback.onResult(active);
        });
    }

    // ── Daily Usage ───────────────────────────────────────────

    public void getUsage(String packageName, String date, Callback<DailyUsage> callback) {
        executor.execute(() -> {
            DailyUsage usage = dailyUsageDao.getUsage(packageName, date);
            callback.onResult(usage);
        });
    }

    public void updateUsage(DailyUsage usage) {
        executor.execute(() -> dailyUsageDao.update(usage));
    }

    public ExecutorService getExecutor() {
        return executor;
    }
}
