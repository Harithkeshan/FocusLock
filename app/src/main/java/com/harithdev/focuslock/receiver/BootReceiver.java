package com.harithdev.focuslock.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.harithdev.focuslock.service.UsageTrackingService;

/**
 * BootReceiver — BroadcastReceiver
 *
 * Listens for BOOT_COMPLETED. When the phone reboots, Android kills all
 * services. This receiver restarts UsageTrackingService automatically
 * so FocusLock keeps working after a reboot.
 *
 * File location:
 *   app/src/main/java/com/harith/focuslock/receiver/BootReceiver.java
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {

            Intent serviceIntent = new Intent(context, UsageTrackingService.class);

            // On Android 8.0+ you must use startForegroundService()
            // for services that run in the background from a receiver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}