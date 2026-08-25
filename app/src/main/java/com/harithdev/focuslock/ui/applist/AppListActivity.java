package com.harithdev.focuslock.ui.applist;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import com.harithdev.focuslock.util.MiuiHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppListActivity extends AppCompatActivity {

    private ActivityAppListBinding binding;
    private AppListAdapter adapter;
    private AppListViewModel viewModel;

    private static final int REQUEST_PIN_VERIFY = 3001;
    private static final int REQUEST_PIN_SETUP  = 3002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.content.SharedPreferences prefs = getSharedPreferences("focuslock_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean(com.harithdev.focuslock.ui.onboarding.OnboardingActivity.KEY_ONBOARDING_DONE, false)) {
            startActivity(new Intent(this, com.harithdev.focuslock.ui.onboarding.OnboardingActivity.class));
            finish();
            return;
        }

        if (!hasUsageAccess() || !hasOverlayPermission() || !hasAccessibilityPermission() || !hasNotificationPermission()) {
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

        viewModel = new androidx.lifecycle.ViewModelProvider(this).get(AppListViewModel.class);

        setupRecyclerView();
        setupSearch();
        setupCategoryChips();
        setupMiuiBanner();
        setupSettingsButton();
        setupBottomNavigation();
        observeViewModel();

        viewModel.loadInstalledApps();
    }

    private void observeViewModel() {
        viewModel.getFilteredApps().observe(this, apps -> {
            if (apps != null) {
                adapter.updateList(apps);
            }
        });

        viewModel.getIsLoading().observe(this, loading -> {
            binding.progressBar.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE);
        });

        FocusLockDatabase.getInstance(this).appRestrictionDao().getAllRestrictions()
                .observe(this, restrictions -> {
                    viewModel.updateRestrictedStatus(restrictions);
                });
    }

    // ── Category Filter Chips ─────────────────────────────────

    private void setupCategoryChips() {
        binding.chipAll.setOnClickListener(v -> selectCategoryChip(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_ALL));
        binding.chipSocial.setOnClickListener(v -> selectCategoryChip(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_SOCIAL));
        binding.chipVideo.setOnClickListener(v -> selectCategoryChip(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_VIDEO));
        binding.chipMessaging.setOnClickListener(v -> selectCategoryChip(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_MESSAGING));
        binding.chipGaming.setOnClickListener(v -> selectCategoryChip(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_GAMING));
        binding.chipProductivity.setOnClickListener(v -> selectCategoryChip(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_PRODUCTIVITY));
        binding.chipOther.setOnClickListener(v -> selectCategoryChip(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_OTHER));
    }

    private void selectCategoryChip(String category) {
        viewModel.setCategory(category);

        int activeBg   = com.harithdev.focuslock.R.drawable.bg_chip_active;
        int inactiveBg = com.harithdev.focuslock.R.drawable.bg_chip_inactive;
        int activeText = androidx.core.content.ContextCompat.getColor(this, com.harithdev.focuslock.R.color.white);
        int dimText    = androidx.core.content.ContextCompat.getColor(this, com.harithdev.focuslock.R.color.text_secondary);

        updateChipStyle(binding.chipAll, category.equals(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_ALL), activeBg, inactiveBg, activeText, dimText);
        updateChipStyle(binding.chipSocial, category.equals(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_SOCIAL), activeBg, inactiveBg, activeText, dimText);
        updateChipStyle(binding.chipVideo, category.equals(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_VIDEO), activeBg, inactiveBg, activeText, dimText);
        updateChipStyle(binding.chipMessaging, category.equals(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_MESSAGING), activeBg, inactiveBg, activeText, dimText);
        updateChipStyle(binding.chipGaming, category.equals(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_GAMING), activeBg, inactiveBg, activeText, dimText);
        updateChipStyle(binding.chipProductivity, category.equals(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_PRODUCTIVITY), activeBg, inactiveBg, activeText, dimText);
        updateChipStyle(binding.chipOther, category.equals(com.harithdev.focuslock.util.AppCategoryHelper.CATEGORY_OTHER), activeBg, inactiveBg, activeText, dimText);
    }

    private void updateChipStyle(android.widget.TextView chip, boolean isActive, int activeBg, int inactiveBg, int activeText, int dimText) {
        chip.setBackgroundResource(isActive ? activeBg : inactiveBg);
        chip.setTextColor(isActive ? activeText : dimText);
    }

    // ── FIX 1: MIUI battery optimization warning ──────────────────────

    private void setupMiuiBanner() {
        binding.btnDisableBatteryOpt.setOnClickListener(v ->
                MiuiHelper.requestIgnoreBatteryOptimizations(this));

        binding.btnMiuiAutostart.setOnClickListener(v ->
                MiuiHelper.openMiuiAutostart(this));
    }

    private void checkMiuiBanner() {
        boolean show = MiuiHelper.shouldShowWarning(this);
        binding.bannerMiuiWarning.setVisibility(show ? View.VISIBLE : View.GONE);
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
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { 
                String query = s.toString();
                binding.btnClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                viewModel.setSearchQuery(query);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        
        binding.btnClearSearch.setOnClickListener(v -> binding.searchInput.setText(""));
    }

    private void setupSettingsButton() {
        binding.btnSettings.setOnClickListener(v -> showSettingsBottomSheet());
    }

    private void showSettingsBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        com.harithdev.focuslock.databinding.LayoutSettingsBottomSheetBinding sheetBinding =
                com.harithdev.focuslock.databinding.LayoutSettingsBottomSheetBinding.inflate(getLayoutInflater());
        dialog.setContentView(sheetBinding.getRoot());

        // Update Device Admin live status badge
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        android.content.ComponentName admin =
                new android.content.ComponentName(this, com.harithdev.focuslock.receiver.FocusLockDeviceAdminReceiver.class);
        boolean isAdmin = dpm != null && dpm.isAdminActive(admin);

        sheetBinding.txtAdminStatus.setText(isAdmin ? "Protected ✓" : "Disabled");
        sheetBinding.txtAdminStatus.setTextColor(isAdmin ? 0xFFFFFFFF : 0xFF94A3B8);
        sheetBinding.txtAdminStatus.setBackgroundResource(isAdmin ?
                com.harithdev.focuslock.R.drawable.bg_chip_active :
                com.harithdev.focuslock.R.drawable.bg_chip_inactive);

        // 1. Change PIN -> uses MODE_CHANGE if PIN already exists
        sheetBinding.rowChangePin.setOnClickListener(v -> {
            dialog.dismiss();
            Intent pinIntent = new Intent(this, com.harithdev.focuslock.ui.pin.PinActivity.class);
            boolean pinSet = com.harithdev.focuslock.security.PinManager.isPinSet(this);
            pinIntent.putExtra(com.harithdev.focuslock.ui.pin.PinActivity.EXTRA_MODE,
                    pinSet ? com.harithdev.focuslock.ui.pin.PinActivity.MODE_CHANGE : com.harithdev.focuslock.ui.pin.PinActivity.MODE_SETUP);
            startActivityForResult(pinIntent, REQUEST_PIN_SETUP);
        });

        // 2. Uninstall Protection
        sheetBinding.rowDeviceAdmin.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects FocusLock from being uninstalled without permission.");
            startActivity(intent);
        });

        // 3. System Permissions
        sheetBinding.rowPermissions.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, com.harithdev.focuslock.ui.permission.PermissionActivity.class));
        });

        // 4. About FocusLock
        sheetBinding.rowAbout.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, com.harithdev.focuslock.ui.about.AboutActivity.class));
        });

        dialog.show();
    }

    private void setupBottomNavigation() {
        binding.tabApps.setOnClickListener(v -> {
            if (adapter != null && adapter.getItemCount() > 0) {
                binding.recyclerView.smoothScrollToPosition(0);
            }
        });

        binding.tabDashboard.setOnClickListener(v -> {
            startActivity(new Intent(this, com.harithdev.focuslock.ui.dashboard.DashboardActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Gate access behind PIN verification on first open / app foreground
        if (com.harithdev.focuslock.security.PinManager.isPinSet(this)
                && !com.harithdev.focuslock.security.PinManager.isSessionAuthenticated()) {
            Intent pinIntent = new Intent(this, com.harithdev.focuslock.ui.pin.PinActivity.class);
            pinIntent.putExtra(com.harithdev.focuslock.ui.pin.PinActivity.EXTRA_MODE, com.harithdev.focuslock.ui.pin.PinActivity.MODE_VERIFY);
            startActivityForResult(pinIntent, REQUEST_PIN_VERIFY);
            return;
        }

        // Re-check MIUI banner every time the user comes back
        checkMiuiBanner();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PIN_VERIFY) {
            if (resultCode == RESULT_OK) {
                com.harithdev.focuslock.security.PinManager.setSessionAuthenticated(true);
            } else {
                finish(); // PIN verification cancelled/failed
            }
        } else if (requestCode == REQUEST_PIN_SETUP) {
            if (resultCode == RESULT_OK) {
                com.harithdev.focuslock.security.PinManager.setSessionAuthenticated(true);
                android.widget.Toast.makeText(this, "PIN updated ✓", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
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

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}