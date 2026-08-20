# PingMon ProGuard Rules

# Keep Firebase & Google
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepattributes *Annotation*

# Keep our Service & Receiver names (Android needs these)
-keep class com.example.pingmon.PingService { *; }
-keep class com.example.pingmon.FirebaseService { *; }
-keep class com.example.pingmon.BootReceiver { *; }
-keep class com.example.pingmon.ReviveJob { *; }
-keep class com.example.pingmon.SmsReceiver { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okio.**

# Keep Kotlin
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlin.**

# Obfuscate everything else
-optimizationpasses 3
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Remove all logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
