package com.harithdev.focuslock.ui.applist;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.repository.FocusLockRepository;
import com.harithdev.focuslock.util.AppCategoryHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppListViewModel extends AndroidViewModel {

    private final FocusLockRepository repository;
    private final MutableLiveData<List<AppInfo>> filteredApps = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);

    private List<AppInfo> allApps = new ArrayList<>();
    private String currentSearch = "";
    private String currentCategory = AppCategoryHelper.CATEGORY_ALL;

    public AppListViewModel(@NonNull Application application) {
        super(application);
        this.repository = new FocusLockRepository(application);
    }

    public LiveData<List<AppInfo>> getFilteredApps() {
        return filteredApps;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    public String getCurrentCategory() {
        return currentCategory;
    }

    public void loadInstalledApps() {
        isLoading.postValue(true);
        repository.getExecutor().execute(() -> {
            PackageManager pm = getApplication().getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);
            List<AppInfo> appList = new ArrayList<>();

            for (ResolveInfo resolveInfo : resolveInfos) {
                String pkg = resolveInfo.activityInfo.packageName;
                if (pkg.equals(getApplication().getPackageName())) continue;

                AppInfo appInfo     = new AppInfo();
                appInfo.packageName = pkg;
                appInfo.appName     = resolveInfo.loadLabel(pm).toString();
                appInfo.icon        = resolveInfo.loadIcon(pm);
                appInfo.category    = AppCategoryHelper.categorize(pkg);

                appList.add(appInfo);
            }

            Collections.sort(appList, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
            allApps = appList;

            applyFilter();
            isLoading.postValue(false);
        });
    }

    public void updateRestrictedStatus(List<AppRestriction> restrictions) {
        if (restrictions == null) return;
        for (AppInfo app : allApps) {
            app.isRestricted = false;
            app.dailyLimitMinutes = 0;
            for (AppRestriction r : restrictions) {
                if (r.packageName.equals(app.packageName) && r.isRestricted) {
                    app.isRestricted = true;
                    app.dailyLimitMinutes = r.dailyLimitMinutes;
                    break;
                }
            }
        }
        applyFilter();
    }

    public void setSearchQuery(String query) {
        this.currentSearch = query != null ? query.trim().toLowerCase() : "";
        applyFilter();
    }

    public void setCategory(String category) {
        this.currentCategory = category != null ? category : AppCategoryHelper.CATEGORY_ALL;
        applyFilter();
    }

    private void applyFilter() {
        List<AppInfo> result = new ArrayList<>();
        boolean isAllCategory = currentCategory.equals(AppCategoryHelper.CATEGORY_ALL);

        for (AppInfo app : allApps) {
            boolean matchesCategory = isAllCategory || app.category.equalsIgnoreCase(currentCategory);
            boolean matchesSearch   = currentSearch.isEmpty() || app.appName.toLowerCase().contains(currentSearch);

            if (matchesCategory && matchesSearch) {
                result.add(app);
            }
        }

        filteredApps.postValue(result);
    }
}
