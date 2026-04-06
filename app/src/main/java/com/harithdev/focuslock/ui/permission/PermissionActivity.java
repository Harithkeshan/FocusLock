package com.harithdev.focuslock.ui.permission;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;

import com.harithdev.focuslock.databinding.ActivityPermissionBinding;
import com.harithdev.focuslock.service.UsageTrackingService;
import com.harithdev.focuslock.ui.applist.AppListActivity;

/**
 * PermissionActivity — Step 5
 *
 * Shown on first launch if required permissions are not yet granted.
 * Walks the user through granting:
 *   1. Usage Access  (PACKAGE_USAGE_STATS)
 *   2. Draw Over Other Apps (SYSTEM_ALERT_WINDOW)
 *
 * Once both are granted, starts the tracking service and goes to AppListActivity.
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/ui/permission/PermissionActivity.java
 */
public class PermissionActivity extends AppCompatActivity {

    private ActivityPermissionBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPermissionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupButtons();
        refreshUI();
    }

    // ── Refresh UI every time we come back ────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();

        // If both permissions are now granted → proceed
        if (hasUsageAccess() && hasOverlayPermission()) {
            proceedToApp();
        }
    }

    // ── Update button states ──────────────────────────────────

    private void refreshUI() {
        boolean usageOk   = hasUsageAccess();
        boolean overlayOk = hasOverlayPermission();

        // Usage Access row
        binding.txtUsageStatus.setText(usageOk ? "✓  Granted" : "Not granted");
        binding.txtUsageStatus.setTextColor(usageOk ? 0xFF4ADE80 : 0xFFF87171);
        binding.btnGrantUsage.setAlpha(usageOk ? 0.4f : 1.0f);
        binding.btnGrantUsage.setEnabled(!usageOk);

        // Overlay row
        binding.txtOverlayStatus.setText(overlayOk ? "✓  Granted" : "Not granted");
        binding.txtOverlayStatus.setTextColor(overlayOk ? 0xFF4ADE80 : 0xFFF87171);
        binding.btnGrantOverlay.setAlpha(overlayOk ? 0.4f : 1.0f);
        binding.btnGrantOverlay.setEnabled(!overlayOk);

        // Continue button — only enabled when both are granted
        binding.btnContinue.setAlpha((usageOk && overlayOk) ? 1.0f : 0.4f);
        binding.btnContinue.setEnabled(usageOk && overlayOk);
    }

    // ── Button listeners ──────────────────────────────────────

    private void setupButtons() {

        // Opens Settings → Apps → Special app access → Usage access
        binding.btnGrantUsage.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
        });

        // Opens Settings → Apps → Special app access → Display over other apps
        binding.btnGrantOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
            }
        });

        // Continue → start service + go to app list
        binding.btnContinue.setOnClickListener(v -> proceedToApp());
    }

    // ── Proceed ───────────────────────────────────────────────

    private void proceedToApp() {
        // Start the tracking service
        Intent serviceIntent = new Intent(this, UsageTrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Go to app list
        Intent intent = new Intent(this, AppListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ── Permission checks ─────────────────────────────────────

    /**
     * Returns true if Usage Access permission has been granted.
     * Uses AppOpsManager — this is the correct way to check this
     * special permission (not ContextCompat.checkSelfPermission).
     */
    public boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if Draw Over Other Apps permission is granted.
     * On Android 6+ this requires explicit user approval.
     */
    public boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true; // Granted by default on older Android versions
    }
}