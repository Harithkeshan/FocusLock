package com.harithdev.focuslock.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * MiuiHelper — detects MIUI/Xiaomi devices and provides helpers
 * to guide the user through battery optimization settings.
 *
 * WHY THIS MATTERS:
 *   MIUI (Xiaomi's Android skin) aggressively kills background services
 *   to save battery. Without explicit "Autostart" permission and battery
 *   optimization disabled, FocusLock's UsageTrackingService and even the
 *   Accessibility Service can be killed after the phone sits idle. When
 *   this happens, the app enforces nothing and sessions are never tracked.
 *
 * FIX 1 — MIUI Service Killing
 *   This helper is called from AppListActivity.onResume(). If the device
 *   is Xiaomi AND battery optimization is still ON for FocusLock, a banner
 *   is shown guiding the user to both:
 *     1. Request Android's standard "Ignore Battery Optimizations"
 *     2. Open MIUI Security Center → Manage Apps → Autostart (manual step)
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/util/MiuiHelper.java
 */
public class MiuiHelper {

    private static final String TAG = "FocusLock.MIUI";

    /** Returns true if the device is made by Xiaomi (includes POCO, Redmi). */
    public static boolean isXiaomiDevice() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.equals("xiaomi")
                || manufacturer.equals("poco")
                || manufacturer.equals("redmi");
    }

    /**
     * Returns true if Android's battery optimization is STILL active for
     * this app (i.e. the app has NOT been whitelisted yet).
     *
     * On standard Android this is sufficient. On MIUI, even after this,
     * the user also needs to enable Autostart in Security Center.
     */
    public static boolean isBatteryOptimizationActive(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return false;
    }

    /**
     * Returns true if a warning banner should be shown to this user.
     * Condition: Xiaomi device AND battery optimization is still enabled.
     * This covers the vast majority of cases where services get killed.
     */
    public static boolean shouldShowWarning(Context context) {
        return isXiaomiDevice() && isBatteryOptimizationActive(context);
    }

    /**
     * Opens Android's standard "Disable Battery Optimization" dialog for
     * FocusLock. This adds the app to the system's whitelist.
     *
     * On MIUI, this alone is NOT sufficient — the user also needs to enable
     * Autostart in Security Center (openMiuiAutostart()).
     */
    public static void requestIgnoreBatteryOptimizations(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "Opened battery optimization dialog");
        } catch (Exception e) {
            // Fallback: open the battery optimization list
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Opens MIUI Security Center's Autostart management screen.
     * This is a MIUI-specific screen not available on stock Android.
     *
     * The user must manually find FocusLock in this list and toggle it ON.
     * Without autostart, MIUI kills FocusLock's services after the phone
     * is idle for a few minutes, even if battery optimization is disabled.
     */
    public static void openMiuiAutostart(Context context) {
        // Try MIUI-specific autostart intent first
        String[] miuiIntents = {
            "com.miui.securitycenter/.MainActivity",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        };

        for (String component : miuiIntents) {
            try {
                Intent intent = new Intent();
                String[] parts = component.split("/");
                intent.setClassName(parts[0],
                        parts[0] + (parts[1].startsWith(".") ? parts[1] : "." + parts[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.d(TAG, "Opened MIUI autostart screen: " + component);
                return;
            } catch (Exception ignored) {}
        }

        // Fallback: just open the main security center
        try {
            Intent fallback = new Intent();
            fallback.setClassName("com.miui.securitycenter",
                    "com.miui.securitycenter.MainActivity");
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
        } catch (Exception e) {
            Log.w(TAG, "Could not open MIUI Security Center: " + e.getMessage());
        }
    }
}
