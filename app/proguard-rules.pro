# ---- JNI bridge: method names/signatures are looked up by the native side (Java_app_fluxa_FluxaNative_*),
# so this one class must keep its real names. Everything else in the app is free to be renamed/obfuscated;
# activities, services and the accessibility/notification-listener components are already kept
# automatically by the Android Gradle plugin because they're declared in the manifest.
-keep class app.fluxa.FluxaNative { *; }
-keepclasseswithmembernames class app.fluxa.FluxaNative { native <methods>; }

# HiddenApiBypass probes this Android runtime class reflectively; it is not
# available on the compile classpath on every Android SDK/R8 configuration.
-dontwarn dalvik.system.VMRuntime

# ---- harden the shrunk/obfuscated output against straightforward decompiling ----
-repackageclasses 'app.fluxa.o'
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively

# strip logging and debug-only calls so no internal strings/identifiers leak into the release build
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# remove line numbers from stack traces but keep a mapping file (build/outputs/mapping) for your own
# crash symbolication; do not ship that mapping file alongside the APK
-keepattributes !LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable
-renamesourcefileattribute ""
