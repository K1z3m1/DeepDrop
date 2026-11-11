import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: read from keystore.properties (git-ignored) or FLUXA_KEYSTORE_* environment variables (CI).
// Never commit a keystore or its passwords.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    (keystoreProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("storeFile", "FLUXA_KEYSTORE_FILE")
val hasReleaseKey = releaseStoreFile != null && rootProject.file(releaseStoreFile).isFile &&
    signingValue("storePassword", "FLUXA_KEYSTORE_PASSWORD") != null &&
    signingValue("keyAlias", "FLUXA_KEY_ALIAS") != null &&
    signingValue("keyPassword", "FLUXA_KEY_PASSWORD") != null

android {
    namespace = "app.fluxa"
    compileSdk = 36
    buildToolsVersion = "35.0.1"
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "app.fluxa"
        minSdk = 30 // screen capture + overlay path needs Android 11+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Vulkan frame-generation engine: arm64 phones + x86_64 (emulators, Intel/AMD Chromebooks, Windows subsystems)
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("fluxaRelease") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "FLUXA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "FLUXA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "FLUXA_KEY_PASSWORD")
                enableV1Signing = false // v1 (JAR) signatures are weak and unnecessary on minSdk 30
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // do not drop the public dependency manifest into the APK/bundle signing block
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("fluxaRelease")
            } else {
                // Local convenience only. An APK signed with the shared debug key must never be published:
                // anyone can re-sign an app under that key. Provide keystore.properties / FLUXA_KEYSTORE_* to avoid this.
                logger.warn("WARNING: no release keystore configured; signing release with the DEBUG key (do not publish).")
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures { buildConfig = true }

    // R8 full mode (set in gradle.properties) plus this gives the strongest shrinking/obfuscation R8 offers.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    packaging {
        // AGP already strips native debug symbols for release by default (keepDebugSymbols is empty
        // unless listed here), so release .so files carry no function names for a disassembler to read.
        jniLibs { useLegacyPackaging = false }
        resources {
            excludes += setOf(
                "META-INF/com.android.tools.proguard/coroutines.pro",
                "META-INF/proguard/coroutines.pro",
                "META-INF/versions/9/module-info.class",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    // display size / DPI tuning via hidden APIs
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
