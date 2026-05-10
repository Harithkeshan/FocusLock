package com.harithdev.focuslock.ui.applist;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.databinding.ActivityAppListBinding;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.service.UsageTrackingService;
import com.harithdev.focuslock.ui.detail.AppDetailActivity;
import com.harithdev.focuslock.ui.permission.PermissionActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppListActivity extends AppCompatActivity {

    private ActivityAppListBinding binding;
    private AppListAdapter adapter;
    private List<AppInfo> allApps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!hasUsageAccess() || !hasOverlayPermission() || !hasAccessibilityPermission()) {
            startActivity(new Intent(this, PermissionActivity.class));
            finish();
            return;
        }

        Intent serviceIntent = new Intent(this, UsageTrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        binding = ActivityAppListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupRecyclerView();
        setupSearch();
        loadInstalledApps();
    }

    private void setupRecyclerView() {
        adapter = new AppListAdapter(new ArrayList<>(), appInfo -> {
            Intent intent = new Intent(this, AppDetailActivity.class);
            intent.putExtra("packageName", appInfo.packageName);
            intent.putExtra("appName",     appInfo.appName);
            startActivity(intent);
        });
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
    }

    private void setupSearch() {
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterApps(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void filterApps(String query) {
        if (query.isEmpty()) { adapter.updateList(allApps); return; }
        List<AppInfo> filtered = new ArrayList<>();
        String lower = query.toLowerCase();
        for (AppInfo app : allApps) {
            if (app.appName.toLowerCase().contains(lower)) filtered.add(app);
        }
        adapter.updateList(filtered);
    }

    private void loadInstalledApps() {
        binding.progressBar.setVisibility(View.VISIBLE);
        AsyncTask.execute(() -> {
            PackageManager pm = getPackageManager();

            // Use queryIntentActivities instead of getInstalledApplications
            // This works correctly on MIUI/Xiaomi devices
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<android.content.pm.ResolveInfo> resolveInfos =
                    pm.queryIntentActivities(mainIntent, 0);

            List<AppInfo> appList = new ArrayList<>();

            for (android.content.pm.ResolveInfo resolveInfo : resolveInfos) {
                String pkg = resolveInfo.activityInfo.packageName;

                // Skip our own app
                if (pkg.equals(getPackageName())) continue;

                AppInfo appInfo     = new AppInfo();
                appInfo.packageName = pkg;
                appInfo.appName     = resolveInfo.loadLabel(pm).toString();
                appInfo.icon        = resolveInfo.loadIcon(pm);

                AppRestriction existing = FocusLockDatabase
                        .getInstance(this)
                        .appRestrictionDao()
                        .getByPackageName(pkg);
                appInfo.isRestricted = existing != null && existing.isRestricted;
                appList.add(appInfo);
            }

            Collections.sort(appList, (a, b) ->
                    a.appName.compareToIgnoreCase(b.appName));

            runOnUiThread(() -> {
                binding.progressBar.setVisibility(View.GONE);
                allApps = appList;
                adapter.updateList(appList);
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!allApps.isEmpty()) loadInstalledApps();
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private boolean hasAccessibilityPermission() {
        try {
            String services = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (services == null || services.isEmpty()) return false;

            // Check case-insensitively and handle both / and . separators
            String target = getPackageName().toLowerCase() +
                    "/com.harithdev.focuslock.service.focuslockaccessibilityservice";

            for (String service : services.split(":")) {
                if (service.toLowerCase().replace("/.", "/").equals(target)) {
                    return true;
                }
                // Also check simplified format
                if (service.toLowerCase().contains("focuslockaccessibilityservice")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}