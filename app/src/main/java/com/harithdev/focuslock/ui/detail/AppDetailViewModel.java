package com.harithdev.focuslock.ui.detail;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.repository.FocusLockRepository;
import com.harithdev.focuslock.util.AppCategoryHelper;
import com.harithdev.focuslock.util.UsageCalculator;

public class AppDetailViewModel extends AndroidViewModel {

    private final FocusLockRepository repository;
    private final MutableLiveData<AppRestriction> restrictionLiveData = new MutableLiveData<>();
    private final MutableLiveData<Long> screenTimeLiveData = new MutableLiveData<>(0L);
    private final MutableLiveData<Boolean> saveSuccessLiveData = new MutableLiveData<>();

    public AppDetailViewModel(@NonNull Application application) {
        super(application);
        this.repository = new FocusLockRepository(application);
    }

    public LiveData<AppRestriction> getRestriction() {
        return restrictionLiveData;
    }

    public LiveData<Long> getScreenTime() {
        return screenTimeLiveData;
    }

    public LiveData<Boolean> getSaveSuccess() {
        return saveSuccessLiveData;
    }

    public void load(String packageName, String appName) {
        repository.getRestriction(packageName, existing -> {
            if (existing != null) {
                restrictionLiveData.postValue(existing);
            } else {
                AppRestriction defaultRest = new AppRestriction(packageName, appName);
                defaultRest.category = AppCategoryHelper.categorize(packageName);
                restrictionLiveData.postValue(defaultRest);
            }
        });

        repository.getExecutor().execute(() -> {
            long usedMs = UsageCalculator.getScreenTimeToday(getApplication(), packageName);
            screenTimeLiveData.postValue(usedMs);
        });
    }

    public void save(AppRestriction restriction, boolean anyDelayed) {
        repository.getExecutor().execute(() -> {
            // 1. Insert/update parent AppRestriction
            com.harithdev.focuslock.database.FocusLockDatabase db =
                    com.harithdev.focuslock.database.FocusLockDatabase.getInstance(getApplication());
            db.appRestrictionDao().insert(restriction);

            // 2. Pre-populate totalUsedMs from system so enforcement starts accurately
            if (restriction.isRestricted) {
                String today = com.harithdev.focuslock.util.TimeUtils.todayString();
                com.harithdev.focuslock.model.DailyUsage usage = db.dailyUsageDao().getUsage(restriction.packageName, today);
                if (usage == null) {
                    usage = new com.harithdev.focuslock.model.DailyUsage(restriction.packageName, today);
                    db.dailyUsageDao().insert(usage);
                }

                long systemUsedMs = UsageCalculator.getScreenTimeToday(getApplication(), restriction.packageName);
                usage.totalUsedMs = systemUsedMs;

                if (restriction.splitSessions && restriction.getSlotDurationMinutes() > 0) {
                    long slotMs     = restriction.getSlotDurationMinutes() * 60_000L;
                    long dailyMs    = restriction.dailyLimitMinutes * 60_000L;
                    int slotsUsed   = (int) (systemUsedMs / slotMs);
                    int maxSessions = restriction.sessionCount;
                    if (systemUsedMs < dailyMs) {
                        usage.sessionsUsedToday = Math.min(slotsUsed, maxSessions - 1);
                    } else {
                        usage.sessionsUsedToday = maxSessions;
                    }
                } else {
                    usage.sessionsUsedToday = 0;
                }

                db.dailyUsageDao().update(usage);
            }

            saveSuccessLiveData.postValue(anyDelayed);
        });
    }
}
