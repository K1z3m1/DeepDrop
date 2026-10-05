plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.firstt175.novaframe"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.firstt175.novaframe"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.3"

        // ABI filtering is handled by the splits { abi } block below (which
        // both restricts the build set to arm64-v8a/x86_64 and emits per-ABI
        // APKs). Setting ndk.abiFilters here as well is a Gradle conflict
        // ("ndk abiFilters cannot be present when splits abi filters are set")
        // and is redundant — the splits filters already gate which ABIs the
        // NDK toolchain ever gets invoked for.

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++20",
                    "-DNDEBUG",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    "-ffunction-sections",
                    "-fdata-sections"
                )
                arguments += listOf(
                    // Only one .so is produced (lsfg-android), so statically
                    // linking libc++ avoids packaging a separate
                    // libc++_shared.so per ABI. Combined with gc-sections it
                    // pulls in only the libc++ symbols actually referenced,
                    // shrinking total installed size vs the shared runtime.
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-29",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--gc-sections,--icf=safe"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("LSFGAndroid_STORE_FILE") as String?
            val storePwd = project.findProperty("LSFGAndroid_STORE_PASSWORD") as String?
            val keyAliasProp = project.findProperty("LSFGAndroid_KEY_ALIAS") as String?
            val keyPwd = project.findProperty("LSFGAndroid_KEY_PASSWORD") as String?
            if (storeFilePath != null && storePwd != null && keyAliasProp != null && keyPwd != null) {
                storeFile = file(storeFilePath)
                storePassword = storePwd
                keyAlias = keyAliasProp
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "none"
            }
            val relCfg = signingConfigs.getByName("release")
            signingConfig = if (relCfg.storeFile != null) relCfg else signingConfigs.getByName("debug")
            // ThinLTO: cross-TU inlining between lsfg_render_loop.cpp, framegen,
            // and volk. NDK r27 supports it stably. Release-only because
            // LTO link times are noticeably slower.
            externalNativeBuild {
                cmake {
                    cppFlags += "-flto=thin"
                    arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-flto=thin -Wl,--gc-sections,--icf=safe"
                }
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

composeCompiler {
    enableStrongSkippingMode = true
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Lifts the hidden-API blocklist on Android 9-14 so the overlay can be flagged
    // "skip screenshot" (excluded from MediaProjection) on stock Android 13 too.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:5.3.0")
    // GIF decoding/playback for the animated YouTube-channel avatar on the Credits screen.
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    // Clip preview + trim/export for the Recording Gallery's video editor.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
