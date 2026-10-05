# JNI symbol-discovery binding: the native side resolves Java methods by their
# mangled symbol name (Java_com_firstt175_novaframe_session_NativeBridge_xxx), so the
# class FQN and every `external fun` must be preserved exactly. RegisterNatives
# is NOT used.
-keep class com.firstt175.novaframe.session.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Service / Activity / Receiver / AccessibilityService / Application: loaded by
# name from the manifest by the system.
-keep class * extends android.app.Service
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider
-keep class * extends android.accessibilityservice.AccessibilityService

# Shizuku API uses reflection / dynamic proxies for the manager service binder.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# libsu (core) is only used for the read-only root status row on the device profile screen.
-keep class com.topjohnwu.superuser.** { *; }
-keep interface com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Compose runtime needs Signature/InnerClasses for state-handling reflection.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Parcelables / Binder stubs declared anywhere in the app.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Strip log calls from the release build to shave a bit more.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# Stop R8 from stripping the line numbers we use to map native crash reports.
-renamesourcefileattribute SourceFile

# Video editor preview: ExoPlayer's video-effects path (CompositingVideoSinkProvider) looks up
# ScaleAndRotateTransformation.Builder by name via reflection whenever a clip carries a rotation.
# Media3 1.4.1 ships keep rules for its other two effect lookups but not this one, so without this
# R8 renames it and previewing a rotated (e.g. portrait) recording crashes in release builds.
-keep class androidx.media3.effect.ScaleAndRotateTransformation$Builder {
    <init>();
    androidx.media3.effect.ScaleAndRotateTransformation$Builder setRotationDegrees(float);
    androidx.media3.effect.ScaleAndRotateTransformation build();
}
