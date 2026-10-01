# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# HexShard ProGuard Rules

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Crypto classes (needed for reflection)
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class android.security.** { *; }

# Keep Room database classes
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep Kotlin classes
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }

# Keep data classes
-keepclassmembers class * {
    *** component*(...);
}

# Remove logging in release builds
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Obfuscate sensitive method names
-repackageclasses com.ventaxiscorp.hexshard.obf
-allowaccessmodification

