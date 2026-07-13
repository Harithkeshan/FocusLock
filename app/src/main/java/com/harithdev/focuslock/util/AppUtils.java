package com.harithdev.focuslock.util;

import android.content.Context;

public class AppUtils {
    
    public static boolean isSafeApp(Context context, String pkg) {
        if (pkg == null) return true;
        // FocusLock itself
        if (pkg.startsWith(context.getPackageName())) return true;
        // Android system packages
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return true;
        
        String lower = pkg.toLowerCase();
        if (lower.contains("launcher")) return true;
        if (lower.contains("home"))     return true;
        if (lower.contains("shell"))    return true;
        
        // MIUI-specific system packages
        if (pkg.equals("com.miui.home"))                       return true;
        if (pkg.equals("com.miui.systemui.plugin"))            return true;
        if (pkg.equals("miui.systemui.plugin"))                return true;
        if (pkg.equals("com.miui.securitycenter"))             return true;
        if (pkg.equals("com.miui.system"))                     return true;
        if (pkg.equals("com.miui.packageinstaller"))           return true;
        if (pkg.equals("com.miui.screenshot"))                 return true;
        if (pkg.equals("com.miui.contentextension"))           return true;
        
        // Common Android launchers
        if (pkg.equals("com.sec.android.app.launcher"))        return true;
        if (pkg.equals("com.android.launcher3"))               return true;
        if (pkg.equals("com.google.android.apps.nexuslauncher")) return true;
        
        return false;
    }

    public static boolean isHomeApp(String pkg) {
        if (pkg == null) return false;
        String lower = pkg.toLowerCase();
        if (lower.contains("launcher") || lower.contains("home")) return true;
        if (pkg.equals("com.miui.home") || pkg.equals("com.sec.android.app.launcher")
                || pkg.equals("com.android.launcher3")) return true;
        return false;
    }

    public static String getAppLabel(Context context, String pkg) {
        try {
            return context.getPackageManager()
                    .getApplicationLabel(
                            context.getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
