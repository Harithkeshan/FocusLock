package com.harithdev.focuslock.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;

/**
 * FocusLockDeviceAdminReceiver — Device Administrator Receiver
 *
 * Provides uninstall protection friction. When Device Admin is active,
 * the user cannot simply drag the app to "Uninstall" without first
 * deactivating Device Administrator in Settings.
 */
public class FocusLockDeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "🛡️ Uninstall protection enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(@NonNull Context context, @NonNull Intent intent) {
        return "Disabling Device Admin will allow FocusLock to be uninstalled. Are you sure?";
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        super.onDisabled(context, intent);
        Toast.makeText(context, "⚠️ Uninstall protection disabled", Toast.LENGTH_SHORT).show();
    }
}
