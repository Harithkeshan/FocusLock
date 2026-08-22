# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ── Room ──────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# ── WorkManager ───────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker

# ── Accessibility Service (keep service declaration) ──
-keep class com.harithdev.focuslock.service.FocusLockAccessibilityService

# ── Keep line numbers for crash reports ───────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile