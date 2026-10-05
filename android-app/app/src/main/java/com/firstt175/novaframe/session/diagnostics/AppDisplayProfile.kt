package com.firstt175.novaframe.session.diagnostics

import com.firstt175.novaframe.session.NovaLog
import com.firstt175.novaframe.session.capture.CaptureEngine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

data class PhysicalDisplayInfo(
    val width: Int,
    val height: Int,
    val dpi: Int,
)

data class AppDisplayProfile(
    val enabled: Boolean = false,
    val percent: Int = 100,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val originalDpi: Int = 0,
    val calculatedWidth: Int = 0,
    val calculatedHeight: Int = 0,
    val calculatedDpi: Int = 0,
    val dynamicClean: Boolean = false,
    val maxBackgroundApps: Int = 1,
    val disableAnimations: Boolean = false,
    val keepAwake: Boolean = false,
    // Performance extras (see AdbDisplayController's Shizuku-backed setters below).
    val fixedPerformanceMode: Boolean = false,
    val dozeWhitelist: Boolean = false,
    val forceStopBackground: Boolean = false,
    val lockRefreshRateHz: Int = 0, // 0 = do not override
    val wifiHighPerfLock: Boolean = false,
    /**
     * Auto-end the session when this app stops producing new frames
     * (captured/real FPS pinned at 0 — e.g. the user backed out of the game)
     * for this many consecutive seconds. 0 disables the watchdog. Per-app
     * (not global) since how long a game legitimately sits at a loading/menu
     * screen with no new frames varies a lot title to title.
     */
    val sessionStallTimeoutSec: Int = 10,
)

object AppDisplayProfileStore {
    private const val PREFS = "per_app_display_profiles"
    private const val PREFIX = "app_"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context, pkg: String): AppDisplayProfile {
        val p = prefs(ctx)
        val k = PREFIX + pkg
        return AppDisplayProfile(
            enabled = p.getBoolean("$k.enabled", false),
            percent = p.getInt("$k.percent", 100),
            originalWidth = p.getInt("$k.w", 0),
            originalHeight = p.getInt("$k.h", 0),
            originalDpi = p.getInt("$k.dpi", 0),
            calculatedWidth = p.getInt("$k.calcW", 0),
            calculatedHeight = p.getInt("$k.calcH", 0),
            calculatedDpi = p.getInt("$k.calcDpi", 0),
            dynamicClean = p.getBoolean("$k.clean", false),
            maxBackgroundApps = p.getInt("$k.maxBg", 1),
            disableAnimations = p.getBoolean("$k.anim", false),
            keepAwake = p.getBoolean("$k.awake", false),
            fixedPerformanceMode = p.getBoolean("$k.perfMode", false),
            dozeWhitelist = p.getBoolean("$k.doze", false),
            forceStopBackground = p.getBoolean("$k.forceStop", false),
            lockRefreshRateHz = p.getInt("$k.refreshHz", 0),
            wifiHighPerfLock = p.getBoolean("$k.wifiLock", false),
            sessionStallTimeoutSec = p.getInt("$k.stallTimeoutSec", 10).coerceIn(0, 120),
        )
    }

    fun save(ctx: Context, pkg: String, profile: AppDisplayProfile) {
        val k = PREFIX + pkg
        prefs(ctx).edit()
            .putBoolean("$k.enabled", profile.enabled)
            .putInt("$k.percent", profile.percent.coerceIn(25, 100))
            .putInt("$k.w", profile.originalWidth)
            .putInt("$k.h", profile.originalHeight)
            .putInt("$k.dpi", profile.originalDpi)
            .putInt("$k.calcW", profile.calculatedWidth)
            .putInt("$k.calcH", profile.calculatedHeight)
            .putInt("$k.calcDpi", profile.calculatedDpi)
            .putBoolean("$k.clean", profile.dynamicClean)
            .putInt("$k.maxBg", profile.maxBackgroundApps.coerceAtLeast(1))
            .putBoolean("$k.anim", profile.disableAnimations)
            .putBoolean("$k.awake", profile.keepAwake)
            .putBoolean("$k.perfMode", profile.fixedPerformanceMode)
            .putBoolean("$k.doze", profile.dozeWhitelist)
            .putBoolean("$k.forceStop", profile.forceStopBackground)
            .putInt("$k.refreshHz", profile.lockRefreshRateHz.coerceAtLeast(0))
            .putBoolean("$k.wifiLock", profile.wifiHighPerfLock)
            .putInt("$k.stallTimeoutSec", profile.sessionStallTimeoutSec.coerceIn(0, 120))
            .apply()
    }

    fun captureOriginalIfMissing(ctx: Context, pkg: String, info: PhysicalDisplayInfo): AppDisplayProfile {
        val old = load(ctx, pkg)
        if (old.originalWidth > 0 && old.originalHeight > 0 && old.originalDpi > 0) return old
        val calc = calculate(info, old.percent)
        return old.copy(
            originalWidth = info.width,
            originalHeight = info.height,
            originalDpi = info.dpi,
            calculatedWidth = calc.first,
            calculatedHeight = calc.second,
            calculatedDpi = calc.third,
        ).also { save(ctx, pkg, it) }
    }

    fun withPercent(ctx: Context, pkg: String, percent: Int): AppDisplayProfile {
        val old = load(ctx, pkg)
        val base = PhysicalDisplayInfo(
            old.originalWidth,
            old.originalHeight,
            old.originalDpi,
        )
        val calc = calculate(base, percent)
        return old.copy(
            enabled = percent < 100,
            percent = percent.coerceIn(25, 100),
            calculatedWidth = calc.first,
            calculatedHeight = calc.second,
            calculatedDpi = calc.third,
        ).also { save(ctx, pkg, it) }
    }

    fun calculate(info: PhysicalDisplayInfo, percent: Int): Triple<Int, Int, Int> {
        if (info.width <= 0 || info.height <= 0) return Triple(0, 0, info.dpi)
        val scale = percent.coerceIn(25, 100) / 100.0
        // Keep the aspect ratio and use even dimensions for GPU/Vulkan images.
        val w = (((info.width * scale).roundToInt() / 2) * 2).coerceAtLeast(2)
        val h = (((info.height * scale).roundToInt() / 2) * 2).coerceAtLeast(2)
        val dpi = (info.dpi * scale).roundToInt().coerceAtLeast(1)
        return Triple(w, h, dpi)
    }
}

object AdbDisplayController {
    private const val TAG = "NovaAdbDisplay"
    const val WRITE_SECURE_SETTINGS_COMMAND =
        "adb shell pm grant com.firstt175.novaframe android.permission.WRITE_SECURE_SETTINGS"
    private const val DISPLAY_ID = android.view.Display.DEFAULT_DISPLAY
    // Do NOT pass USER_CURRENT (-2) to IWindowManager from an app UID.
    // WindowManagerService resolves that sentinel as a cross-user operation and
    // rejects it with INTERACT_ACROSS_USERS_FULL. Always resolve the actual
    // current user before calling the per-user density APIs.
    private const val USER_CURRENT = -2

    // Android 9+ (API 28+) enforces restrictions on reflective calls into
    // non-SDK (hidden) APIs, which is exactly what IWindowManager access
    // below relies on. Enforcement can vary by OEM/ROM and sometimes throws
    // NoSuchMethodException/SecurityException even when the method exists.
    // WRITE_SECURE_SETTINGS allows writing the policy settings below, but it
    // does NOT grant cross-user privileges to IWindowManager. In particular,
    // per-user density calls must use the concrete current user id.
    private val HIDDEN_API_POLICY_KEYS = arrayOf(
        "hidden_api_policy",
        "hidden_api_policy_pre_p_apps",
        "hidden_api_policy_p_apps",
    )
    @Volatile
    private var hiddenApiPolicyUnlocked = false

    /**
     * Disables hidden-API enforcement so the IWindowManager reflection calls
     * below run reliably across Android versions/OEMs. Idempotent and safe
     * to call before every reflective operation; only writes once per process.
     */
    private fun unlockHiddenApiPolicyIfNeeded(ctx: Context) {
        if (hiddenApiPolicyUnlocked) return
        runCatching {
            for (key in HIDDEN_API_POLICY_KEYS) {
                Settings.Global.putInt(ctx.contentResolver, key, 1)
            }
            hiddenApiPolicyUnlocked = true
            NovaLog.i(TAG, "hidden API policy unlocked")
        }.onFailure {
            NovaLog.e(TAG, "failed to unlock hidden API policy", it)
        }
    }

    /**
     * Display control no longer depends on Shizuku.
     *
     * The user grants WRITE_SECURE_SETTINGS once from a PC/ADB:
     *   adb shell pm grant com.firstt175.novaframe android.permission.WRITE_SECURE_SETTINGS
     *
     * After that, the app calls WindowManagerService directly through the
     * IWindowManager AIDL interface (the same calls the `wm size`/`wm density`
     * shell commands make), which WRITE_SECURE_SETTINGS is sufficient to
     * authorize without an actual shell UID.
     *
     * NOTE: writing Settings.Global("display_size_forced"/"display_density_forced")
     * directly does NOT work on modern Android (10+): WindowManagerService's
     * DisplayWindowSettings persists forced size/density to
     * /data/system/display_settings.xml and only reads the legacy Settings.Global
     * keys once, as a migration source at boot. A live write from an app is
     * silently ignored — confirmed on-device: the write call reports success
     * but CaptureEngine/DisplayMetrics keep reporting the untouched native
     * resolution and density. Calling IWindowManager directly goes through the
     * same live code path as `wm size`/`wm density` and reconfigures the
     * display immediately.
     */
    private fun windowManagerService(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.WINDOW_SERVICE) as android.os.IBinder
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        stub.getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
    }.onFailure { NovaLog.e(TAG, "windowManagerService() lookup failed", it) }.getOrNull()

    private fun iwm(): Class<*> = Class.forName("android.view.IWindowManager")

    fun isReady(ctx: Context): Boolean {
        val granted = ctx.checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            NovaLog.w(TAG, "WRITE_SECURE_SETTINGS is not granted")
        }
        return granted
    }

    /** Kept for source compatibility; ADB grants are performed outside the app. */
    fun requestPermission() {
        NovaLog.w(
            TAG,
            "ADB permission required. Run: $WRITE_SECURE_SETTINGS_COMMAND",
        )
    }

    fun grantCommand(): String = WRITE_SECURE_SETTINGS_COMMAND

    private fun shizukuReady(): Boolean = ShizukuDisplayPermission.isShizukuAvailable()

    private fun shizukuCommandBlocking(command: String): ShizukuDisplayPermission.CommandResult? =
        runCatching { runBlocking { ShizukuDisplayPermission.exec(command) } }
            .onFailure { NovaLog.e(TAG, "Shizuku command bridge failed: $command", it) }
            .getOrNull()

    private fun readCurrentViaWmShell(): PhysicalDisplayInfo? {
        val size = shizukuCommandBlocking("wm size") ?: return null
        val density = shizukuCommandBlocking("wm density") ?: return null
        if (!size.ok || !density.ok) return null

        val sizeMatch = Regex("Override size:\\s*(\\d+)x(\\d+)").find(size.stdout)
            ?: Regex("Physical size:\\s*(\\d+)x(\\d+)").find(size.stdout)
        val densityMatch = Regex("Override density:\\s*(\\d+)").find(density.stdout)
            ?: Regex("Physical density:\\s*(\\d+)").find(density.stdout)
        val w = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val h = sizeMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val dpi = densityMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        if (w <= 0 || h <= 0 || dpi <= 0) return null
        return PhysicalDisplayInfo(w, h, dpi)
    }

    /**
     * Returns the physical panel mode, not an existing forced size override.
     * Stable device density is used as the original DPI baseline.
     */
    fun readDisplay(ctx: Context): PhysicalDisplayInfo? {
        return runCatching {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return@runCatching null

            val mode = display.mode
            val width = mode.physicalWidth
            val height = mode.physicalHeight
            val dpi = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE

            if (width <= 0 || height <= 0 || dpi <= 0) {
                NovaLog.w(TAG, "readDisplay: invalid physical display ${width}x${height} @ ${dpi}dpi")
                null
            } else {
                NovaLog.i(TAG, "readDisplay: physical ${width}x${height} @ ${dpi}dpi")
                PhysicalDisplayInfo(width, height, dpi)
            }
        }.onFailure {
            NovaLog.e(TAG, "readDisplay failed", it)
        }.getOrNull()
    }

    /**
     * Reads the CURRENT base size/density — i.e. whatever [setForcedDisplaySize]/
     * [setForcedDisplayDensityForUser] most recently applied, or the native
     * panel values if nothing is currently forced. This is the WindowManager
     * counterpart to what `adb shell wm size`/`wm density` print, and is
     * deliberately different from [readDisplay] (which always reports the true
     * physical panel, ignoring any active override).
     */
    /**
     * Reads the CURRENT base size/density through WindowManager first.
     * Shizuku is only used as a fallback when WRITE_SECURE_SETTINGS is unavailable.
     */
    private fun readCurrentBaseDisplay(ctx: Context): PhysicalDisplayInfo? {
        if (isReady(ctx)) {
            val app = runCatching {
                val wm = windowManagerService() ?: return@runCatching null
                val cls = iwm()
                val point = android.graphics.Point()
                cls.getMethod("getBaseDisplaySize", Int::class.javaPrimitiveType, android.graphics.Point::class.java)
                    .invoke(wm, DISPLAY_ID, point)
                val density = cls.getMethod("getBaseDisplayDensity", Int::class.javaPrimitiveType)
                    .invoke(wm, DISPLAY_ID) as Int
                if (point.x <= 0 || point.y <= 0 || density <= 0) null
                else PhysicalDisplayInfo(point.x, point.y, density)
            }.onFailure {
                NovaLog.e(TAG, "readCurrentBaseDisplay failed: ${it.javaClass.simpleName}: ${it.message}", it)
            }.getOrNull()
            if (app != null) return app
        }
        if (shizukuReady()) return readCurrentViaWmShell()
        return null
    }
    /**
     * Safety net for the launcher's home screen: compares the display's
     * current base size/density against the true native panel values and,
     * if they don't match, immediately restores the native ones.
     *
     * This catches any forced size/density left over from a session that
     * never got to clean up after itself — e.g. the app or the target game
     * was killed/crashed mid-session, the device rebooted into a stale
     * `display_settings.xml` state, or [DisplayOverrideState] lost track of
     * the owner for any other reason — instead of leaving the whole system
     * scaled down until the user notices and reboots.
     *
     * Returns true if drift was detected and a reset was attempted.
     */
    fun restoreIfDrifted(ctx: Context): Boolean {
        if (!DisplayOverrideState.isPersistentlyActive(ctx)) return false
        if (!isReady(ctx) && !shizukuReady()) return false
        val native = readDisplay(ctx) ?: return false
        val current = readCurrentBaseDisplay(ctx) ?: return false

        val drifted = current.width != native.width ||
            current.height != native.height ||
            current.dpi != native.dpi
        if (!drifted) return false

        NovaLog.w(
            TAG,
            "restoreIfDrifted: current ${current.width}x${current.height}@${current.dpi}dpi != " +
                "native ${native.width}x${native.height}@${native.dpi}dpi — resetting",
        )
        val restored = reset(ctx)
        DisplayOverrideState.clear(ctx)
        NovaLog.i(TAG, "restoreIfDrifted: reset() returned $restored")
        return true
    }

    fun apply(ctx: Context, profile: AppDisplayProfile): Boolean {
        val secureReady = isReady(ctx)
        if ((!secureReady && !shizukuReady()) ||
            profile.originalWidth <= 0 || profile.originalHeight <= 0
        ) {
            NovaLog.w(
                TAG,
                "apply skipped: WRITE_SECURE_SETTINGS=$secureReady " +
                    "shizuku=${shizukuReady()} original=${profile.originalWidth}x${profile.originalHeight}",
            )
            return false
        }

        if (profile.percent >= 100) return reset(ctx)

        val (targetWidth, targetHeight, targetDpi) = AppDisplayProfileStore.calculate(
            PhysicalDisplayInfo(profile.originalWidth, profile.originalHeight, profile.originalDpi),
            profile.percent,
        )
        if (targetWidth <= 0 || targetHeight <= 0 || targetDpi <= 0) return false

        // PRIMARY PATH:
        // The ADB command
        //   adb shell pm grant com.firstt175.novaframe android.permission.WRITE_SECURE_SETTINGS
        // grants the app exactly the permission required by
        // IWindowManager.setForcedDisplaySize()/setForcedDisplayDensityForUser().
        // Do this directly from the app process so Shizuku is not required
        // during normal use.
        if (secureReady) {
            unlockHiddenApiPolicyIfNeeded(ctx)
            val appPath = runCatching {
                val wm = windowManagerService() ?: return@runCatching false
                val cls = iwm()

                val okSize = setForcedSize(wm, cls, targetWidth, targetHeight)

                val okDensity = if (okSize) {
                    setForcedDensity(wm, cls, targetDpi)
                } else {
                    false
                }

                if (!okSize || !okDensity) {
                    resetAppSideOnly(wm, cls)
                    false
                } else {
                    val current = readCurrentBaseDisplay(ctx)
                    val verified = current?.width == targetWidth &&
                        current.height == targetHeight &&
                        current.dpi == targetDpi
                    if (!verified) resetAppSideOnly(wm, cls)
                    verified
                }
            }.onFailure {
                NovaLog.e(TAG, "app-side display apply failed", it)
            }.getOrDefault(false)

            if (appPath) {
                NovaLog.i(
                    TAG,
                    "apply verified with WRITE_SECURE_SETTINGS: " +
                        "${targetWidth}x${targetHeight}@${targetDpi}",
                )
                return true
            }
        }

        // FALLBACK:
        // Keep Shizuku support for OEM builds where app-side hidden-API
        // reflection is blocked despite WRITE_SECURE_SETTINGS.
        if (shizukuReady()) {
            val size = shizukuCommandBlocking("wm size ${targetWidth}x${targetHeight}")
            val density = if (size?.ok == true) {
                shizukuCommandBlocking("wm density $targetDpi")
            } else {
                null
            }
            if (size?.ok == true && density?.ok == true) {
                val current = readCurrentViaWmShell()
                val verified = current?.width == targetWidth &&
                    current.height == targetHeight &&
                    current.dpi == targetDpi
                if (verified) {
                    NovaLog.i(TAG, "apply verified via Shizuku fallback")
                    return true
                }
            }
            shizukuCommandBlocking("wm size reset")
            shizukuCommandBlocking("wm density reset")
        }

        return false
    }

    /** Clears an app-side forced display override using every known OEM/API signature. */
    private fun resetAppSideOnly(wm: Any, cls: Class<*>) {
        val sizeAttempts: List<Pair<String, () -> Unit>> = listOf(
            "clearForcedDisplaySize(displayId)" to {
                cls.getMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType)
                    .invoke(wm, DISPLAY_ID)
            },
            "clearForcedDisplaySize()" to {
                cls.getMethod("clearForcedDisplaySize").invoke(wm)
            },
        )
        for ((label, attempt) in sizeAttempts) {
            val result = runCatching(attempt)
            if (result.isSuccess) {
                NovaLog.i(TAG, "$label succeeded")
                break
            }
            val err = result.exceptionOrNull()
            NovaLog.w(TAG, "$label failed: ${err?.javaClass?.simpleName}: ${err?.message}", err)
        }

        val userId = resolveCurrentUserId()
        val densityAttempts: List<Pair<String, () -> Unit>> = listOf(
            "clearForcedDisplayDensityForUser(displayId,resolvedUserId)" to {
                cls.getMethod(
                    "clearForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID, userId)
            },
            "clearForcedDisplayDensityForUser(displayId)" to {
                cls.getMethod(
                    "clearForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID)
            },
            "clearForcedDisplayDensity()" to {
                cls.getMethod("clearForcedDisplayDensity").invoke(wm)
            },
        )
        for ((label, attempt) in densityAttempts) {
            val result = runCatching(attempt)
            if (result.isSuccess) {
                NovaLog.i(TAG, "$label succeeded")
                break
            }
            val err = result.exceptionOrNull()
            NovaLog.w(TAG, "$label failed: ${err?.javaClass?.simpleName}: ${err?.message}", err)
        }
    }

    /**
     * Tries all known IWindowManager setForcedDisplaySize signatures.
     * Android branches and OEM builds expose different hidden-AIDL shapes.
     */
    private fun setForcedSize(wm: Any, cls: Class<*>, width: Int, height: Int): Boolean {
        val attempts: List<Pair<String, () -> Unit>> = listOf(
            "setForcedDisplaySize(displayId,width,height)" to {
                cls.getMethod("setForcedDisplaySize",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(wm, DISPLAY_ID, width, height)
            },
            "setForcedDisplaySize(width,height)" to {
                cls.getMethod("setForcedDisplaySize",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(wm, width, height)
            },
            "setForcedDisplaySize(displayId,width,height,userId)" to {
                cls.getMethod("setForcedDisplaySize",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(wm, DISPLAY_ID, width, height, resolveCurrentUserId())
            },
        )
        for ((label, attempt) in attempts) {
            val result = runCatching(attempt)
            if (result.isSuccess) {
                NovaLog.i(TAG, "$label succeeded (${width}x${height})")
                return true
            }
            val err = result.exceptionOrNull()
            NovaLog.w(TAG, "$label failed: ${err?.javaClass?.simpleName}: ${err?.message}", err)
        }
        return false
    }

    /**
     * Tries every known IWindowManager signature/user-id combination for
     * forcing display density, in order, stopping at the first one that
     * doesn't throw. Logs each failed attempt with the concrete exception
     * type/message so a real failure is diagnosable from logcat instead of
     * just "resolution changed, DPI silently didn't".
     */
    private fun setForcedDensity(wm: Any, cls: Class<*>, densityDpi: Int): Boolean {
        val userId = resolveCurrentUserId()

        // USER_CURRENT (-2) is invalid for an ordinary application caller on
        // modern Android: WMS treats it as a cross-user request and throws
        // INTERACT_ACROSS_USERS_FULL. Use the concrete current user instead.
        val attempts: List<Pair<String, () -> Unit>> = listOf(
            "setForcedDisplayDensityForUser(resolvedUserId)" to {
                cls.getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID, densityDpi, userId)
            },
            "setForcedDisplayDensityForUser(displayId,density,user,flags)" to {
                cls.getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID, densityDpi, userId, 0)
            },
            "setForcedDisplayDensity(legacy displayId,density)" to {
                cls.getMethod(
                    "setForcedDisplayDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(wm, DISPLAY_ID, densityDpi)
            },
            "setForcedDisplayDensity(density)" to {
                cls.getMethod(
                    "setForcedDisplayDensity",
                    Int::class.javaPrimitiveType,
                ).invoke(wm, densityDpi)
            },
        )

        for ((label, attempt) in attempts) {
            val result = runCatching(attempt)
            if (result.isSuccess) {
                NovaLog.i(
                    TAG,
                    "setForcedDisplayDensity via $label succeeded (dpi=$densityDpi,user=$userId)",
                )
                return true
            }
            val err = result.exceptionOrNull()
            NovaLog.w(
                TAG,
                "setForcedDisplayDensity via $label failed: ${err?.javaClass?.simpleName}: ${err?.message}",
                err,
            )
        }
        return false
    }

    /** Best-effort resolution of the actual current-user id, as a fallback for OEMs that mishandle USER_CURRENT (-2) from an app process. */
    private fun resolveCurrentUserId(): Int = runCatching {
        Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
    }.getOrElse {
        runCatching {
            val handle = android.os.Process.myUserHandle()
            Class.forName("android.os.UserHandle").getMethod("getIdentifier").invoke(handle) as Int
        }.getOrDefault(0)
    }

    fun reset(ctx: Context): Boolean {
        // PRIMARY PATH: use the ADB-granted WRITE_SECURE_SETTINGS permission.
        if (isReady(ctx)) {
            unlockHiddenApiPolicyIfNeeded(ctx)
            val appResult = runCatching {
                val wm = windowManagerService() ?: return@runCatching false
                val cls = iwm()
                resetAppSideOnly(wm, cls)

                val native = readDisplay(ctx)
                val current = readCurrentBaseDisplay(ctx)
                native != null && current != null &&
                    current.width == native.width &&
                    current.height == native.height &&
                    current.dpi == native.dpi
            }.onFailure {
                NovaLog.e(TAG, "app-side display reset failed", it)
            }.getOrDefault(false)

            if (appResult) {
                DisplayOverrideState.clear(ctx)
                NovaLog.i(TAG, "reset verified with WRITE_SECURE_SETTINGS")
                return true
            }
        }

        // FALLBACK: Shizuku remains supported, but is no longer required.
        if (shizukuReady()) {
            val size = shizukuCommandBlocking("wm size reset")
            val density = shizukuCommandBlocking("wm density reset")
            val shellReset = size?.ok == true && density?.ok == true
            if (shellReset) {
                val native = readDisplay(ctx)
                val current = readCurrentViaWmShell()
                val verified = native != null && current != null &&
                    current.width == native.width &&
                    current.height == native.height &&
                    current.dpi == native.dpi
                if (verified) {
                    DisplayOverrideState.clear(ctx)
                    NovaLog.i(TAG, "reset verified via Shizuku fallback")
                    return true
                }
            }
        }

        DisplayOverrideState.clear(ctx)
        return false
    }

    /**
     * These global settings are also covered by WRITE_SECURE_SETTINGS, so they
     * no longer need a Shizuku shell.
     */
    fun getActivityManagerConstants(ctx: Context): String? =
        Settings.Global.getString(ctx.contentResolver, "activity_manager_constants")

    fun restoreActivityManagerConstants(ctx: Context, value: String?) {
        val resolver = ctx.contentResolver
        if (value == null || value == "null" || value.isBlank()) {
            Settings.Global.putString(resolver, "activity_manager_constants", null)
        } else {
            Settings.Global.putString(resolver, "activity_manager_constants", value)
        }
    }

    fun setMaxBackgroundApps(ctx: Context, limit: Int) {
        val n = limit.coerceAtLeast(1)
        Settings.Global.putString(
            ctx.contentResolver,
            "activity_manager_constants",
            "max_cached_processes=$n",
        )
    }

    // ---- System animation scale (Settings.Global, same WRITE_SECURE_SETTINGS
    // permission as the display-size controls above). Turning these to 0
    // removes every system transition/window animation, which shaves a small
    // but real amount of per-frame overhead — the same effect as enabling
    // Developer Options → "Window/Transition/Animator duration scale: Off".
    private val ANIMATION_SCALE_KEYS = arrayOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    fun areAnimationsEnabled(ctx: Context): Boolean {
        val scale = Settings.Global.getFloat(ctx.contentResolver, "window_animation_scale", 1f)
        return scale > 0f
    }

    fun setAnimationsEnabled(ctx: Context, enabled: Boolean): Boolean {
        if (!isReady(ctx)) return false
        val scale = if (enabled) 1f else 0f
        return runCatching {
            for (key in ANIMATION_SCALE_KEYS) {
                Settings.Global.putFloat(ctx.contentResolver, key, scale)
            }
            NovaLog.i(TAG, "setAnimationsEnabled($enabled)")
            true
        }.onFailure {
            NovaLog.e(TAG, "setAnimationsEnabled failed", it)
        }.getOrDefault(false)
    }

    // ---- Stay awake while charging. BATTERY_PLUGGED_AC(1) | USB(2) | WIRELESS(4).
    // Useful during a session so the screen doesn't dim/lock mid-game when the
    // device is on a charger; restored to whatever the user had before.
    private const val STAY_ON_KEY = "stay_on_while_plugged_in"
    private const val STAY_ON_ALL_SOURCES = 7

    fun getStayOnWhilePluggedIn(ctx: Context): Int =
        Settings.Global.getInt(ctx.contentResolver, STAY_ON_KEY, 0)

    fun isStayAwakeEnabled(ctx: Context): Boolean = getStayOnWhilePluggedIn(ctx) != 0

    fun setStayOnWhilePluggedIn(ctx: Context, value: Int): Boolean {
        if (!isReady(ctx)) return false
        return runCatching {
            Settings.Global.putInt(ctx.contentResolver, STAY_ON_KEY, value)
            NovaLog.i(TAG, "setStayOnWhilePluggedIn($value)")
            true
        }.onFailure {
            NovaLog.e(TAG, "setStayOnWhilePluggedIn failed", it)
        }.getOrDefault(false)
    }

    fun enableStayAwake(ctx: Context): Boolean = setStayOnWhilePluggedIn(ctx, STAY_ON_ALL_SOURCES)

    /**
     * There is no supported in-app ADB equivalent of `am kill-all` after
     * switching away from Shizuku. Display resolution/DPI remain fully
     * functional; this optional cleanup is intentionally a no-op.
     */
    fun killCachedProcesses() {
        NovaLog.i(TAG, "killCachedProcesses skipped: no shell/Shizuku dependency")
    }

    // ---- Performance extras --------------------------------------------
    // These require Shizuku (shell UID), unlike the WRITE_SECURE_SETTINGS
    // controls above. They no-op (return false) if Shizuku isn't available;
    // callers should treat that as "not applied" and not silently pretend
    // success — same pattern as killCachedProcesses().

    /**
     * `cmd power set-fixed-performance-mode-enabled` is a real AOSP
     * PowerManagerService shell command (requires DEVICE_POWER, which the
     * shell UID has). It pins CPU/GPU clocks at max for as long as it's
     * enabled — this is what stops mid-session throttling variance, not a
     * cosmetic setting. Always call with false again when the session ends;
     * leaving it on burns battery/heat even at idle.
     */
    fun setFixedPerformanceMode(enabled: Boolean): Boolean {
        if (!shizukuReady()) {
            NovaLog.w(TAG, "setFixedPerformanceMode($enabled) skipped: Shizuku not available")
            return false
        }
        val result = shizukuCommandBlocking("cmd power set-fixed-performance-mode-enabled $enabled")
        NovaLog.i(TAG, "setFixedPerformanceMode($enabled) -> ok=${result?.ok}")
        return result?.ok ?: false
    }

    /**
     * Adds/removes the target package from the Doze/App-Standby whitelist
     * (`dumpsys deviceidle whitelist`). The foreground activity itself isn't
     * Doze-restricted, but background services the game spawns (voice
     * chat, overlays, download threads) are — whitelisting prevents those
     * from being throttled mid-session. Always remove the whitelist entry
     * again on session stop; leaving a game permanently whitelisted defeats
     * battery optimization outside of play.
     */
    fun setDozeWhitelist(pkg: String, add: Boolean): Boolean {
        if (!shizukuReady()) {
            NovaLog.w(TAG, "setDozeWhitelist($pkg,$add) skipped: Shizuku not available")
            return false
        }
        val op = if (add) "+" else "-"
        val result = shizukuCommandBlocking("dumpsys deviceidle whitelist $op$pkg")
        NovaLog.i(TAG, "setDozeWhitelist($pkg,$add) -> ok=${result?.ok}")
        return result?.ok ?: false
    }

    /**
     * Locks the display refresh rate via Settings.System peak/min_refresh_rate
     * (covered by WRITE_SECURE_SETTINGS, same as the display-size controls).
     * hz<=0 clears the override and lets the system pick its own rate again.
     * This stops the panel from switching refresh rates mid-session (a real
     * source of frame-pacing stutter on many OEM VRR implementations) — it
     * does not raise the panel's max rate above what it already supports.
     */
    fun setPeakRefreshRate(ctx: Context, hz: Int): Boolean {
        if (isReady(ctx)) {
            val ok = runCatching {
                val resolver = ctx.contentResolver
                if (hz <= 0) {
                    Settings.System.putString(resolver, "min_refresh_rate", null)
                    Settings.System.putString(resolver, "peak_refresh_rate", null)
                } else {
                    Settings.System.putFloat(resolver, "min_refresh_rate", hz.toFloat())
                    Settings.System.putFloat(resolver, "peak_refresh_rate", hz.toFloat())
                }
                NovaLog.i(TAG, "setPeakRefreshRate($hz) applied")
                true
            }.onFailure {
                // Some OEM builds block min_refresh_rate/peak_refresh_rate from the
                // app's own Binder call even with WRITE_SECURE_SETTINGS granted
                // (throws IllegalArgumentException from SettingsProvider). Fall
                // through to the Shizuku shell path below instead of failing outright.
                NovaLog.w(TAG, "setPeakRefreshRate($hz) via ContentResolver failed, trying Shizuku fallback", it)
            }.getOrDefault(false)
            if (ok) return true
        } else {
            NovaLog.w(TAG, "setPeakRefreshRate($hz) skipped ContentResolver path: WRITE_SECURE_SETTINGS not granted")
        }

        if (!shizukuReady()) {
            NovaLog.w(TAG, "setPeakRefreshRate($hz) skipped: no Shizuku fallback available")
            return false
        }
        val minResult: ShizukuDisplayPermission.CommandResult?
        val peakResult: ShizukuDisplayPermission.CommandResult?
        if (hz <= 0) {
            minResult = shizukuCommandBlocking("settings delete system min_refresh_rate")
            peakResult = shizukuCommandBlocking("settings delete system peak_refresh_rate")
        } else {
            minResult = shizukuCommandBlocking("settings put system min_refresh_rate $hz")
            peakResult = shizukuCommandBlocking("settings put system peak_refresh_rate $hz")
        }
        val ok = (minResult?.ok ?: false) && (peakResult?.ok ?: false)
        NovaLog.i(TAG, "setPeakRefreshRate($hz) via Shizuku -> ok=$ok")
        return ok
    }

    /**
     * Force-stops every non-system, non-protected launcher-visible app via
     * `am force-stop` (a real shell command, distinct from killCachedProcesses()
     * above which is currently a no-op). This is meaningfully more aggressive
     * than the max_cached_processes trick: it kills apps outright rather than
     * just capping how many stay cached, freeing RAM/CPU immediately before
     * the game launches. Never touches the game's own package, this app, or
     * flagged system apps — force-stopping system components can break the
     * device's running state until reboot.
     *
     * Uses the same ACTION_MAIN/CATEGORY_LAUNCHER query as GameLauncherScreen's
     * loadLaunchableApps(), not PackageManager.getInstalledApplications(). On
     * Android 11+, getInstalledApplications() is filtered by package
     * visibility and would silently return only a handful of apps without the
     * QUERY_ALL_PACKAGES permission (which Play restricts heavily) — the
     * <queries> block already declared in AndroidManifest.xml for the launcher
     * screen is what makes this query see every installed app instead.
     */
    fun forceStopOtherApps(ctx: Context, keepPkg: String) {
        if (!shizukuReady()) {
            NovaLog.w(TAG, "forceStopOtherApps skipped: Shizuku not available")
            return
        }
        val pm = ctx.packageManager
        val protectedPkgs = setOf(ctx.packageName, keepPkg)
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val targets = runCatching {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
                .asSequence()
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName !in protectedPkgs }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
        NovaLog.i(TAG, "forceStopOtherApps: stopping ${targets.size} app(s)")
        for (pkg in targets) {
            shizukuCommandBlocking("am force-stop $pkg")
        }
    }
}

/**
 * Keeps the Wi-Fi radio out of power-save sleep for the duration of a
 * session. This is a plain WifiManager API — no Shizuku/root/ADB needed —
 * and it is the one thing on this list that genuinely reduces latency
 * spikes/ping variance from software alone: it does not lower baseline
 * ping to a server, it only stops the radio from dozing between packets.
 */
object WifiPerfLock {
    private const val TAG = "NovaWifiPerfLock"
    private var lock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(ctx: Context) {
        if (lock?.isHeld == true) return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lsfg_perf").apply {
                setReferenceCounted(false)
                acquire()
            }
            NovaLog.i(TAG, "wifi high-perf lock acquired")
        }.onFailure {
            NovaLog.e(TAG, "wifi high-perf lock failed", it)
        }
    }

    @Synchronized
    fun release() {
        runCatching {
            lock?.takeIf { it.isHeld }?.release()
        }.onFailure {
            NovaLog.e(TAG, "wifi high-perf lock release failed", it)
        }
        lock = null
    }
}
