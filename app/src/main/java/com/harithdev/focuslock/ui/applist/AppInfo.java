package com.harithdev.focuslock.ui.applist;

import android.graphics.drawable.Drawable;

/**
 * AppInfo — simple data holder for the app list screen.
 * Not a Room entity — just used to pass data between the
 * list screen and the adapter.
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/ui/applist/AppInfo.java
 */
public class AppInfo {
    public String   packageName;
    public String   appName;
    public Drawable icon;
    public boolean  isRestricted; // true = has an active restriction saved
}