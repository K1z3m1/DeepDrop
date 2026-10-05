package com.firstt175.novaframe.session.service

import com.firstt175.novaframe.session.NovaLog
import com.firstt175.novaframe.session.NativeBridge
import com.firstt175.novaframe.session.capture.CaptureEngine
import com.firstt175.novaframe.session.capture.CaptureMetrics
import com.firstt175.novaframe.session.capture.GameScreenRecorder
import com.firstt175.novaframe.session.diagnostics.AdbDisplayController
import com.firstt175.novaframe.session.diagnostics.AppDisplayProfile
import com.firstt175.novaframe.session.diagnostics.AppDisplayProfileStore
import com.firstt175.novaframe.session.diagnostics.DisplayOverrideState
import com.firstt175.novaframe.session.diagnostics.RamCleaner
import com.firstt175.novaframe.session.diagnostics.SystemStatsSampler
import com.firstt175.novaframe.session.diagnostics.WifiPerfLock
import com.firstt175.novaframe.session.models.BundledIfrnetModel
import com.firstt175.novaframe.session.models.BundledRifeModel
import com.firstt175.novaframe.session.overlay.OverlayManager
import com.firstt175.novaframe.session.overlay.SettingsDrawerOverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.AiEngine
import com.firstt175.novaframe.prefs.FramegenBackend
import com.firstt175.novaframe.prefs.NovaConfig
import com.firstt175.novaframe.prefs.NovaPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs for the lifetime of an LSFG session. Owns the MediaProjection token, the
 * CaptureEngine, and the OverlayManager.
 *
 * Startup sequence (important for stable overlay behaviour):
 *   1. Post foreground notification (required by FGS + mediaProjection rules).
 *   2. Acquire MediaProjection from the consent intent.
 *   3. Show overlay (adds window via WindowManager — synchronous).
 *   4. **Wait for surfaceCreated before starting capture.** On-create is async.
 *   5. Never launch the target app from this service. The Launcher opens the target
 *      directly; this service only owns the independent LSFG overlay/session.
 */
class NovaForegroundService : Service() {

    // Matches MainActivity/ProjectionRequestActivity's override: without this,
    // getString()/getText() calls made from this service — including the
    // notification text and everything the in-game SettingsDrawerOverlay
    // resolves via ctx.getString() — would ignore the user's AppLanguage
    // choice and fall back to whatever the system locale happens to be,
    // since a Service's base context is never wrapped automatically.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.firstt175.novaframe.prefs.AppLanguagePrefs.wrap(newBase))
    }

    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null
    private var overlay: OverlayManager? = null
    private var drawer: SettingsDrawerOverlay? = null
    private var recorder: GameScreenRecorder? = null
    // Presentation size the recording swapchain was last attached at — needed to
    // re-attach NativeBridge.setRecordingSurface() after reinitNovaContext()
    // tears down and recreates the native context (destroyContext() releases
    // the native recording window along with everything else; see there).
    private var recordingOutputW: Int = 0
    private var recordingOutputH: Int = 0
    @Volatile private var recordingMic = false
    private val recordingHudHandler = Handler(Looper.getMainLooper())
    private var recordingHudRunnable: Runnable? = null
    private var targetPkgPending: String? = null
    // The target package for the current session, kept for the lifetime of the
    // session (unlike targetPkgPending, which is consumed/nulled once the target
    // app is launched). Reinit paths (geometry changes, etc.) need this after the
    // initial launch has already happened.
    private var activeTargetPackage: String? = null
    // Per-app display/background session state. The physical baseline is captured
    // once for presentation/reinitialization calculations. Display restoration is
    // intentionally manual from the main app.
    private var sessionDisplayProfile: AppDisplayProfile? = null
    private var sessionDisplayApplied: Boolean = false
    private var sessionOriginalActivityManagerConstants: String? = null
    // Session-stall watchdog (see handleSessionStallWatchdog()): auto-stops the
    // overlay when the target app has stopped producing new frames — e.g. the
    // user backed out to the launcher/home and left the overlay running with a
    // frozen "0/0" HUD instead of dismissing it from the drawer themselves.
    // 0 = disabled (previous, always-manual-stop behavior).
    private var sessionStallTimeoutSec: Int = 0
    // elapsedRealtime() of the last sample where captured (real) fps was > 0.
    // 0L is the "not yet armed" sentinel — set on the first 0-fps sample after
    // a session (re)start so a session that starts at 0/0 during warm-up
    // doesn't immediately look "stalled".
    private var lastNonStalledAtMs: Long = 0L
    private var sessionBackgroundPolicyApplied: Boolean = false
    private var sessionAnimationsDisabled: Boolean = false
    private var sessionOriginalStayOnValue: Int? = null
    private var sessionStayAwakeApplied: Boolean = false
    // Performance extras — same apply-on-start/restore-on-stop pattern as the
    // display/background/animation/stay-awake flags above.
    private var sessionPerfModeApplied: Boolean = false
    private var sessionDozeWhitelisted: Boolean = false
    private var sessionRefreshRateApplied: Boolean = false
    private var sessionWifiLockApplied: Boolean = false
    private var initialCaptureStarted: Boolean = false
    private var lsfgContextActive: Boolean = false
    private var lastSurface: Surface? = null
    private var lastSurfaceW: Int = 0
    private var lastSurfaceH: Int = 0
    @Volatile
    private var activeRenderW: Int = 0
    @Volatile
    private var activeRenderH: Int = 0
    // Capture/context input size (post renderResolutionScale) and the backend
    // label, kept for the HUD "backend · in → out" line — see pushStreamInfo().
    @Volatile
    private var activeInputW: Int = 0
    @Volatile
    private var activeInputH: Int = 0
    @Volatile
    private var activeBackendLabel: String = ""
    @Volatile
    private var currentPostGpuEnabled: Boolean = false
    @Volatile
    private var reinitInFlight: Boolean = false
    // Signalled when the in-flight reinit thread finishes. Lets onDestroy() wait
    // for completion without busy-polling the Main thread (the previous code
    // burned ~75 cycles of Thread.sleep(20) before timeout, blocking the looper
    // and starving system input — causing visible freeze on swipe-out).
    @Volatile
    private var reinitDoneLatch: CountDownLatch? = null
    // When the user changes a parameter while a previous reinit is still in
    // flight, we can't start a second one concurrently (it would race on the
    // native context). Instead we mark a pending request and the in-flight
    // reinit re-runs itself once it finishes, picking up the freshest prefs.
    // Without this, mid-reinit changes were silently dropped — that's why
    // toggling "Bypass" appeared to "make settings apply": users were
    // accidentally triggering a second reinit by changing something else.
    @Volatile
    private var reinitRequested: Boolean = false
    @Volatile
    private var pendingReinitW: Int = 0
    @Volatile
    private var pendingReinitH: Int = 0
    // Set in onDestroy. While true, new reinit requests are dropped on the
    // floor — we're tearing down the service and any allocation we'd do here
    // would just have to be undone (and would race the shutdown).
    @Volatile
    private var shuttingDown: Boolean = false
    @Volatile
    private var pendingFpsCounter: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    // HUD CPU/GPU/RAM readout — shown as part of the single HUD switch, sampled on a
    // simple timer rather than tied to capture/frame-gen listeners since these
    // numbers change on their own schedule, not per rendered frame.
    @Volatile private var hudStatsEnabled: Boolean = false
    private var statsPollRunnable: Runnable? = null

    private fun startStatsPollingIfNeeded() {
        if (statsPollRunnable != null) return
        if (!hudStatsEnabled) return
        SystemStatsSampler.resetTrackers()
        val r = object : Runnable {
            override fun run() {
                val ov = overlay
                if (ov != null && hudStatsEnabled) {
                    val stats = SystemStatsSampler.sample(applicationContext)
                    ov.updateStats(
                        cpuPercent = stats.cpuPercent,
                        gpuPercent = stats.gpuPercent,
                        ramUsedMb = stats.ramUsedMb,
                        ramTotalMb = stats.ramTotalMb,
                        tempC = stats.tempC,
                    )
                }
                if (statsPollRunnable === this) {
                    mainHandler.postDelayed(this, STATS_POLL_INTERVAL_MS)
                }
            }
        }
        statsPollRunnable = r
        mainHandler.post(r)
    }

    private fun stopStatsPolling() {
        statsPollRunnable?.let { mainHandler.removeCallbacks(it) }
        statsPollRunnable = null
    }

    /**
     * Runs off every CaptureMetrics fps sample (~1 Hz) while a session is active.
     * `capturedFps` is the "real" unique-capture rate — the same number shown as
     * the left half of the HUD's "captured/posted" fps pair — so it goes to 0 and
     * stays there once the target app stops handing the capture pipeline new
     * frames (backed out to the launcher/home, app killed, etc.), independent of
     * whatever frame generation is doing with the last real frame it already has.
     *
     * When [sessionStallTimeoutSec] is 0 the watchdog is off and this is a no-op,
     * matching the previous manual-stop-only behavior.
     */
    private fun handleSessionStallWatchdog(capturedFps: Float) {
        val timeoutSec = sessionStallTimeoutSec
        if (timeoutSec <= 0) return

        val now = SystemClock.elapsedRealtime()
        if (capturedFps > 0.5f) {
            lastNonStalledAtMs = now
            return
        }
        // First 0-fps sample after (re)start / after the last non-stalled sample:
        // arm the clock instead of comparing against the 0L sentinel.
        if (lastNonStalledAtMs == 0L) {
            lastNonStalledAtMs = now
            return
        }
        if (now - lastNonStalledAtMs >= timeoutSec * 1000L) {
            NovaLog.i(
                TAG,
                "Session stalled at 0 fps for ${timeoutSec}s — auto-stopping (target=$activeTargetPackage)",
            )
            // Prevent handleSessionStallWatchdog from firing again on the way down
            // while stopSelf()/onDestroy() are tearing things down.
            sessionStallTimeoutSec = 0
            // Target app is gone — stop the overlay, but keep any explicit
            // display override until the user restores it from the main app.
            stopSelf()
        }
    }

    // Display rotation listener — Service.onConfigurationChanged only fires
    // for configChanges declared in the manifest, but services can't declare
    // them. DisplayManager.DisplayListener fires on every rotation regardless.
    private var displayListener: DisplayManager.DisplayListener? = null
    // Held for the lifetime of an active session so Doze/App Standby can't
    // deprioritize this process's CPU scheduling once the screen dims or the
    // device decides it's been idle — see the PARTIAL_WAKE_LOCK acquire in
    // onCreate for why FLAG_KEEP_SCREEN_ON-style approaches aren't enough on
    // their own (this app doesn't own the foreground Activity/Window, so it
    // has no window flag to set; a wake lock is the only lever available
    // from a background Service).
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The service can also host the always-available SettingsDrawer while
        // idle. Therefore service-alive != session-running. Only ACTION_START
        // flips isRunning to true.
        _isRunning.value = false
        ensureChannel()
        registerDisplayListener()
        // PARTIAL_WAKE_LOCK keeps the CPU awake (screen/keyboard state
        // untouched) for as long as this session runs, regardless of Doze,
        // App Standby, or the screen dimming — all of which can otherwise
        // throttle this process's scheduling priority the moment the system
        // decides it's been idle. Explicitly requested as part of "pull full
        // performance, ignore the power/thermal tradeoffs" — this does cost
        // battery for the session's duration. acquire() with no timeout is
        // intentional; release() happens in onDestroy() alongside every
        // other teardown step, so it can never outlive the session.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:lsfg-session")
                .apply { setReferenceCounted(false) }
        }.onSuccess { lock ->
            wakeLock = lock
            runCatching { lock.acquire() }
                .onFailure { NovaLog.w(TAG, "wakeLock.acquire() failed", it) }
        }.onFailure {
            NovaLog.w(TAG, "newWakeLock() failed — continuing without it", it)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Belt-and-braces: in addition to DisplayListener we also handle the
        // platform configuration callback so very quick rotations don't slip
        // through (some OEMs deliver one but not the other).
        propagateDisplayChange()
    }

    // Logged so field logs can confirm/deny the low-memory-killer hypothesis
    // when a session's log simply stops mid-frame with no shutdown message
    // (the process was SIGKILLed, not stopped through our own teardown path).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        NovaLog.w(TAG, "onTrimMemory level=$level — system is reclaiming memory, session may be killed soon")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        NovaLog.w(TAG, "onLowMemory — system-wide low memory, session may be killed soon")
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                propagateDisplayChange()
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        runCatching { dm.registerDisplayListener(listener, mainHandler) }
            .onSuccess { displayListener = listener }
            .onFailure { NovaLog.w(TAG, "registerDisplayListener failed", it) }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        displayListener = null
        val dm = getSystemService(DisplayManager::class.java) ?: return
        runCatching { dm.unregisterDisplayListener(l) }
    }

    private fun propagateDisplayChange() {
        // Always run on the main thread — WindowManager rejects updateViewLayout
        // calls from binder threads on some OEMs, and OverlayManager /
        // SettingsDrawerOverlay both touch the WM internally.
        mainHandler.post {
            runCatching { overlay?.onDisplayConfigurationChanged() }
                .onFailure { NovaLog.w(TAG, "overlay.onDisplayConfigurationChanged failed", it) }
            runCatching { drawer?.onDisplayConfigurationChanged() }
                .onFailure { NovaLog.w(TAG, "drawer.onDisplayConfigurationChanged failed", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NovaLog.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_SHOW_DRAWER -> {
                handleShowDrawer(intent)
                return START_STICKY
            }
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                NovaLog.i(TAG, "ACTION_STOP received — stopSelf()")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        NovaLog.i(TAG, "onDestroy — tearing down service (caller triggered stopSelf or system killed us)")
        // Flip first: teardown below can take up to ~1.5s (reinit latch wait),
        // and the Home screen's Start/Stop button should reflect "not running"
        // the moment shutdown begins, not after every native resource is freed.
        _isRunning.value = false
        super.onDestroy()
        unregisterDisplayListener()
        stopStatsPolling()
        // Block any further reinit requests and wait for one already in flight
        // to finish. Without this, a parameter change happening concurrently
        // with stopSelf() races destroyContext() on the C++ side: the reinit
        // worker is in the middle of initRenderLoop(), allocating AHB images
        // and starting the worker thread, while onDestroy calls
        // shutdownRenderLoop() which joins that same worker and frees the
        // images — SIGSEGV on the next access. This was the "stop overlay
        // crashes when settings stop applying" symptom.
        shuttingDown = true
        // Wait on the latch instead of polling — frees the Main thread looper
        // to deliver pending input events instead of sleeping in 20 ms ticks.
        val latch = reinitDoneLatch
        if (latch != null && reinitInFlight) {
            val finished = latch.await(1500, TimeUnit.MILLISECONDS)
            if (!finished) {
                NovaLog.w(TAG, "onDestroy: reinit still in flight after 1.5s — proceeding anyway")
            }
        }
        CaptureMetrics.reset()
        stopGameRecording()
        capture?.stop()
        capture = null
        if (lsfgContextActive) {
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            runCatching { NativeBridge.destroyContext() }
            lsfgContextActive = false
        }
        drawer?.hide()
        drawer = null
        overlay?.hide()
        overlay = null
        projection?.stop()
        projection = null
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure { NovaLog.w(TAG, "wakeLock.release() failed", it) }
        wakeLock = null

        // Display size/DPI is intentionally NOT restored here. A stopped, crashed, or
        // killed session must not silently change the user's screen back. The only
        // restore path is the explicit "Restore original screen" action in the app.
        if (sessionDisplayApplied) {
            NovaLog.i(TAG, "Leaving display resolution/DPI override in place; user must restore it explicitly")
        }
        if (sessionBackgroundPolicyApplied) {
            runCatching { AdbDisplayController.restoreActivityManagerConstants(applicationContext, sessionOriginalActivityManagerConstants) }
                .onFailure { NovaLog.w(TAG, "Failed to restore activity manager constants", it) }
        }
        if (sessionAnimationsDisabled) {
            runCatching { AdbDisplayController.setAnimationsEnabled(applicationContext, true) }
                .onFailure { NovaLog.w(TAG, "Failed to restore system animation scale", it) }
        }
        if (sessionStayAwakeApplied) {
            runCatching {
                AdbDisplayController.setStayOnWhilePluggedIn(applicationContext, sessionOriginalStayOnValue ?: 0)
            }.onFailure { NovaLog.w(TAG, "Failed to restore stay-awake setting", it) }
        }
        if (sessionPerfModeApplied) {
            runCatching { AdbDisplayController.setFixedPerformanceMode(false) }
                .onFailure { NovaLog.w(TAG, "Failed to disable fixed performance mode", it) }
        }
        if (sessionDozeWhitelisted) {
            activeTargetPackage?.let { pkg ->
                runCatching { AdbDisplayController.setDozeWhitelist(pkg, false) }
                    .onFailure { NovaLog.w(TAG, "Failed to remove doze whitelist entry", it) }
            }
        }
        if (sessionRefreshRateApplied) {
            runCatching { AdbDisplayController.setPeakRefreshRate(applicationContext, 0) }
                .onFailure { NovaLog.w(TAG, "Failed to clear refresh rate override", it) }
        }
        if (sessionWifiLockApplied) {
            runCatching { WifiPerfLock.release() }
                .onFailure { NovaLog.w(TAG, "Failed to release wifi high-perf lock", it) }
        }
        sessionDisplayApplied = false
        sessionBackgroundPolicyApplied = false
        sessionAnimationsDisabled = false
        sessionStayAwakeApplied = false
        sessionOriginalStayOnValue = null
        sessionDisplayProfile = null
        sessionOriginalActivityManagerConstants = null
        sessionPerfModeApplied = false
        sessionDozeWhitelisted = false
        sessionRefreshRateApplied = false
        sessionWifiLockApplied = false

        // target app is still in the foreground.
    }

    /**
     * Hosts only the SettingsDrawer affordance after a game is launched from
     * Game Launcher. No MediaProjection, capture engine, Vulkan/LSFG context,
     * frame-generation worker, or full-screen render overlay is started here.
     */
    private fun handleShowDrawer(intent: Intent) {
        // Callers always launch us via ContextCompat.startForegroundService()
        // (see GameLauncherScreen's overlay-on-launch path), which requires
        // startForeground() to be called within a few seconds or the OS
        // kills the service — on Android 12+ this throws
        // ForegroundServiceDidNotStartInTimeException outright. handleStart()
        // already does this for the real session, but this idle "just show
        // the drawer, no session yet" path never did, so the service (and
        // the WindowManager-hosted drawer/handle it owns) was getting killed
        // a few seconds after being shown. That's why the drawer icon kept
        // disappearing even though it should stay pinned once opened.
        promoteToForegroundSpecialUse()

        val target = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (target != null) {
            targetPkgPending = target
            activeTargetPackage = target
        }

        if (drawer == null) {
            val dr = SettingsDrawerOverlay(this)
            dr.setRestartSessionListener {
                val pkg = activeTargetPackage ?: targetPkgPending
                NovaLog.i(TAG, "START SESSION requested from idle drawer (target=$pkg)")
                if (pkg != null) {
                    val appCtx = applicationContext
                    val projectionIntent =
                        com.firstt175.novaframe.ui.ProjectionRequestActivity.buildIntent(appCtx, pkg)
                    appCtx.startActivity(projectionIntent)
                } else {
                    NovaLog.w(TAG, "START SESSION requested but no target package is known")
                }
                // Keep the idle drawer host alive while projection permission is
                // requested. ACTION_START will reuse this same drawer, so the
                // entry icon never disappears during the hand-off.
            }
            dr.show()
            drawer = dr
        } else {
            drawer?.show()
        }
        NovaLog.i(TAG, "Idle SettingsDrawer shown; session not started (target=$target)")
    }

    /**
     * Promotes this service to FOREGROUND_SERVICE_TYPE_SPECIAL_USE (the
     * subtype declared in the manifest, unconditionally valid without a
     * projection token). Used by the idle "show drawer, no session started"
     * path — see [handleShowDrawer] — where we have nothing session-specific
     * to base a type on yet. [handleStart] calls startForeground() again
     * once a MediaProjection token is available, which is allowed and simply
     * updates the notification/type in place.
     */
    private fun promoteToForegroundSpecialUse() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun handleStart(intent: Intent) {
        _isRunning.value = true
        // Capture is always MediaProjection: the FGS type must be
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION and requires a live projection
        // token (handled below) plus the matching uses-permission in the manifest.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val initialHud = NovaPreferences(this).load().hudEnabled
        if (data == null || resultCode == 0) {
            NovaLog.e(TAG, "Missing MediaProjection result intent; stopping")
            stopSelf()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data).also {
            projection = it
            // Android 14 (API 34) requires a non-null Handler for registerCallback on
            // some OEMs (MediaTek/PowerVR devices observed revoking the projection
            // token instantly when callback handler is null). Register BEFORE any
            // VirtualDisplay is created so we also hear about system-initiated stops.
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    NovaLog.i(TAG, "MediaProjection.onStop")
                    stopSelf()
                }
            }, mainHandler)
        }

        val ov = OverlayManager(this)
        overlay = ov
        targetPkgPending = targetPkg
        activeTargetPackage = targetPkg
        initialCaptureStarted = false

        // Apply the launcher card's per-app display policy before the overlay
        // surface is created. We always keep the stored physical dimensions as
        // the output/present size; only capture + frame-gen input are reduced.
        if (targetPkg != null && AdbDisplayController.isReady(applicationContext)) {
            runCatching {
                val current = AdbDisplayController.readDisplay(applicationContext)
                if (current != null) {
                    val stored = AppDisplayProfileStore.captureOriginalIfMissing(this, targetPkg, current)
                    sessionDisplayProfile = stored
                    val needsBackgroundPolicy =
                        stored.originalWidth > 0 && (stored.dynamicClean || stored.maxBackgroundApps > 1 || stored.enabled)
                    if (needsBackgroundPolicy) {
                        sessionOriginalActivityManagerConstants =
                            AdbDisplayController.getActivityManagerConstants(applicationContext)
                        AdbDisplayController.setMaxBackgroundApps(applicationContext, stored.maxBackgroundApps)
                        sessionBackgroundPolicyApplied = true
                        if (stored.dynamicClean) AdbDisplayController.killCachedProcesses()
                    }
                    if (stored.originalWidth > 0 && stored.originalHeight > 0) {
                        // Mark the session as owning display state even at 100%, so
                        // a stale override from a previous interrupted run is reset.
                        sessionDisplayApplied = true
                        if (stored.enabled && stored.percent < 100) {
                            val applied = AdbDisplayController.apply(applicationContext, stored)
                            if (applied) {
                                DisplayOverrideState.markApplied(applicationContext, targetPkg)
                                NovaLog.i(
                                    TAG,
                                    "Per-app display ${stored.percent}% -> " +
                                        "${stored.calculatedWidth}x${stored.calculatedHeight} @ ${stored.calculatedDpi}dpi; " +
                                        "present stays ${stored.originalWidth}x${stored.originalHeight}"
                                )
                            } else {
                                NovaLog.w(TAG, "Per-app display override failed; continuing at native resolution")
                            }
                        } else {
                            AdbDisplayController.reset(applicationContext)
                            DisplayOverrideState.clearIfOwner(applicationContext, targetPkg)
                        }
                    }
                    if (stored.disableAnimations) {
                        sessionAnimationsDisabled = AdbDisplayController.setAnimationsEnabled(applicationContext, false)
                    }
                    if (stored.keepAwake) {
                        sessionOriginalStayOnValue = AdbDisplayController.getStayOnWhilePluggedIn(applicationContext)
                        sessionStayAwakeApplied = AdbDisplayController.enableStayAwake(applicationContext)
                    }
                    // Performance extras — best-effort; each setter returns false
                    // (and logs) instead of throwing when Shizuku isn't available,
                    // so a missing Shizuku grant never blocks session start.
                    if (stored.forceStopBackground) {
                        AdbDisplayController.forceStopOtherApps(applicationContext, targetPkg)
                    }
                    if (stored.fixedPerformanceMode) {
                        sessionPerfModeApplied = AdbDisplayController.setFixedPerformanceMode(true)
                    }
                    if (stored.dozeWhitelist) {
                        sessionDozeWhitelisted = AdbDisplayController.setDozeWhitelist(targetPkg, true)
                    }
                    if (stored.lockRefreshRateHz > 0) {
                        sessionRefreshRateApplied =
                            AdbDisplayController.setPeakRefreshRate(applicationContext, stored.lockRefreshRateHz)
                    }
                    if (stored.wifiHighPerfLock) {
                        WifiPerfLock.acquire(applicationContext)
                        sessionWifiLockApplied = true
                    }
                }
            }.onFailure { NovaLog.w(TAG, "Per-app display policy failed", it) }
        }

        // It owns the single launcher entry window and hides it while the session runs.

        val cap = CaptureEngine(this, proj)
        capture = cap
        // FPS/frame-graph telemetry is engine-independent (it only reads
        // NativeBridge's global render-loop counters), so it's wired once
        // here.
        CaptureMetrics.setFpsListener { captured, posted ->
            ov.updateFps(captured, posted)
            handleSessionStallWatchdog(captured)
        }
        CaptureMetrics.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        // Apply persisted LSFG on/off preference before the first frame is captured. The drawer
        // toggle persists `lsfgEnabled`; we mirror that into the bypass path (lsfgEnabled=false ⇒
        // bypass=true ⇒ raw passthrough).
        val persistedNovaEnabled = com.firstt175.novaframe.prefs.NovaPreferences(this).load().lsfgEnabled
        // Stall-watchdog timeout is per-app (set from Game Launcher's per-app settings
        // sheet), not a global pref — how long a title legitimately sits at 0 real fps
        // on a loading/menu screen varies a lot title to title. Snapshot it once here,
        // same as the other session-start-only settings above.
        sessionStallTimeoutSec = targetPkg?.let {
            com.firstt175.novaframe.session.diagnostics.AppDisplayProfileStore.load(this, it).sessionStallTimeoutSec
        } ?: 0
        lastNonStalledAtMs = 0L
        if (!persistedNovaEnabled) {
            runCatching { NativeBridge.setBypass(true) }
                .onFailure { NovaLog.w(TAG, "initial setBypass failed", it) }
            ov.updateStatus("NovaFrame: bypass (raw capture)")
        }
        // NOTE: deferring the initial HUD wiring until AFTER ov.show() —
        // setHudVisible() is a no-op when the HUD cluster hasn't been created yet,
        // and the view is only created inside show().

        ov.onSurfaceReady { surface, w, h ->
            // Android delivers surfaceCreated immediately followed by surfaceChanged
            // on the same Surface instance. Retargeting the VirtualDisplay onto the
            // identical Surface a second time stops frame delivery on PowerVR drivers
            // (the overlay freezes on the first frame). Coalesce duplicate ready
            // events when nothing actually changed.
            val sameAsLast = surface === lastSurface && w == lastSurfaceW && h == lastSurfaceH
            NovaLog.i(TAG, "onSurfaceReady ${w}x${h} initial=$initialCaptureStarted sameAsLast=$sameAsLast")
            if (sameAsLast && initialCaptureStarted) {
                NovaLog.i(TAG, "onSurfaceReady coalesced — identical Surface, skipping retarget")
                return@onSurfaceReady
            }
            lastSurface = surface
            lastSurfaceW = w
            lastSurfaceH = h
            // IMPORTANT: `w`/`h` must never be treated as the presentation
            // resolution. Android reports the CURRENT display/surface geometry,
            // which changes after `wm size` is applied. The per-app profile has
            // already captured the real panel size before that override, and
            // that saved size is the presentation/output size for the whole
            // session.
            //
            // Example: physical panel 1080x2408, display override 480x1068:
            //   input/capture  = 480x1068 (or the selected render scale)
            //   presentation   = 1080x2408 (saved original size)
            //
            // Keeping these two domains separate prevents the generated frame
            // from being silently re-targeted to the temporary low resolution.
            val presentationW = sessionDisplayProfile
                ?.originalWidth
                ?.takeIf { it > 0 } ?: w
            val presentationH = sessionDisplayProfile
                ?.originalHeight
                ?.takeIf { it > 0 } ?: h

            NovaLog.i(
                TAG,
                "Output geometry: surface=${w}x${h}, presentation=${presentationW}x${presentationH}" +
                    " (saved original; display override must not change output)",
            )

            // Always tell native about the PRESENTATION surface dimensions, not
            // the current forced display dimensions.
            runCatching { NativeBridge.setOutputSurface(surface, presentationW, presentationH) }
                .onFailure { NovaLog.w(TAG, "setOutputSurface failed", it) }

            // Recording is a session mode, not a second capture path. When enabled,
            // attach MediaRecorder to the same final-frame tee used by normal display.
            // This is deliberately done after the output surface exists and before the
            // first captured frame can be presented.
            //
            // BUG FIX: this must size the recorder/encoder swapchain to
            // presentationW/H — the SAME fixed physical size just passed to
            // NativeBridge.setOutputSurface() above — never to the raw w/h this
            // callback received. w/h track the CURRENT (possibly `wm size`
            // downscaled) surface; the final frame actually being blitted by the
            // native worker is always sized to presentationW/H (see the big
            // comment above). Passing w/h here silently created the encoder
            // swapchain at the wrong resolution, so the tee blit
            // (blitOutputToRecordingSurfaceLocked) never matched the source
            // image and every "recording" produced an empty/near-instant file —
            // this was the actual cause of recording "not working".
            if (getSharedPreferences("recording", MODE_PRIVATE)
                    .getBoolean("session_recording", false) &&
                recorder?.isRecording != true) {
                startGameRecording(recordingAudioPref(), presentationW, presentationH)
                // Reflect the carried-over choice in the drawer too (if it's already
                // built by this point) so SESSION MODE/Microphone show locked instead
                // of looking editable while a recording neither button click started
                // is actually running.
            }

            if (!initialCaptureStarted) {
                initialCaptureStarted = true
                // CRITICAL ORDER (Android 14+ MediaTek/PowerVR):
                // getMediaProjection() opens a short window (~200 ms on some OEMs)
                // during which we MUST call createVirtualDisplay, or the system
                // revokes the projection with MediaProjection.onStop. We used to
                // run Vulkan init (100-200 ms) before the first createVirtualDisplay
                // and occasionally blew past that deadline. In MediaProjection
                // mode start capture first on the LSFG ImageReader so the token
                // is consumed immediately without ever mirroring the visible
                // overlay surface back into MediaProjection. On Orange Pi /
                // RK3588 Android builds, that mirror bootstrap can make the
                // framegen path capture its own overlay frames.
                val cfg = NovaPreferences(this).load()
                // Render resolution scale shrinks the actual capture/context buffers
                // (not just a post-process pass), so both the ImageReader/VirtualDisplay
                // size below and the native context width/height must use the same
                // scaled dimensions — activeRenderW/H and the output surface stay at
                // the full display size (see the geometry-change check further down).
                val (scaledW, scaledH) = scaledRenderSize(presentationW, presentationH, cfg.renderResolutionScale)

                NovaLog.i(TAG, "Starting ImageReader capture first to consume MediaProjection token")
                cap.setNovaNativeInputEnabled(false)
                cap.setNovaMode(scaledW, scaledH)
                ov.updateStatus("NovaFrame: starting ${scaledW}×${scaledH} (input) → ${presentationW}×${presentationH} output…")

                val cacheDir = File(filesDir, "spirv").absolutePath
                val ai = aiBackendArgs(cfg)
                val rc = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = scaledW,
                        height = scaledH,
                        multiplier = effectiveMultiplier(cfg),
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = cfg.hdrMode,
                        framegenFp16 = cfg.framegenFp16,
                        aiBackend = ai.enabled,
                        aiModelDir = ai.modelDir,
                        aiEngine = ai.engine,
                    )
                }.getOrElse { e ->
                    NovaLog.w(TAG, "initContext threw", e)
                    -1
                }
                applyImageEnhancementSettings()
                when {
                    rc == 0 -> {
                        // Framegen active: the ImageReader path is already running;
                        // now allow frames into the native render loop.
                        lsfgContextActive = true
                        activeRenderW = presentationW
                        activeRenderH = presentationH
                        activeInputW = scaledW
                        activeInputH = scaledH
                        activeBackendLabel = backendLabel(cfg, ai)
                        applySchedulingAndBacklogSettings()
                        pushStreamInfo()
                        cap.setNovaNativeInputEnabled(true)
                        ov.updateStatus("NovaFrame: frame-gen active ${scaledW}×${scaledH} → ${presentationW}×${presentationH} ×${cfg.multiplier}")
                    }
                    rc > 0 -> {
                        NovaLog.w(TAG, "initContext rc=$rc — framegen disabled, staying in mirror mode")
                        lsfgContextActive = true
                        cap.setSurface(surface, presentationW, presentationH)
                        activeRenderW = 0
                        activeRenderH = 0
                        // Retarget the bootstrap ImageReader capture to mirror
                        // mode because framegen is unavailable.
                        ov.updateStatus("NovaFrame: mirror ${presentationW}×${presentationH} (GPU lacks required Vulkan ext)")
                    }
                    else -> {
                        NovaLog.w(TAG, "initContext failed rc=$rc — staying in mirror mode")
                        activeRenderW = 0
                        activeRenderH = 0
                        ov.updateStatus("NovaFrame: mirror active ${presentationW}×${presentationH} (init rc=$rc)")
                    }
                }
                // IMPORTANT: the Frame Generation service must never launch the target app.
                // The Launcher owns app launching and opens the selected package directly.
                // This service is started independently by the overlay/session flow after
                // the target app is already in the foreground. Keeping launchTarget() here
                // caused START_SESSION to intercept the launcher flow and block/delay entry
                // into the target app.
                val pkg = activeTargetPackage ?: targetPkgPending
                NovaLog.i(TAG, "Session ready; target launch is owned by Launcher: pkg=$pkg")

                if (pkg != null) {
                    // The target app is already open. Only keep the LSFG overlay above it.
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 150)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 600)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 1500)
                } else {
                    NovaLog.w(TAG, "No target package set — session starts without target metadata")
                }
                // FPS counter no longer creates a VirtualDisplay; it piggybacks on
                // the main LSFG-mode ImageReader. Safe to start immediately.
                if (pendingFpsCounter) {
                    pendingFpsCounter = false
                    runCatching { CaptureMetrics.startFpsCounter() }
                        .onFailure { NovaLog.w(TAG, "startFpsCounter failed", it) }
                }
            } else if (!lsfgContextActive || activeRenderW == 0) {
                // Mirror mode (framegen disabled or context not yet active): retarget
                // the existing VirtualDisplay onto the new Surface. activeRenderW==0
                // is our "running in mirror" sentinel — don't try to reinit the
                // render loop, just keep the capture alive.
                cap.setSurface(surface, presentationW, presentationH)
            } else {
                // We already passed the sameAsLast coalescing check above, so a
                // genuinely new Surface or size reached us here (rotation, a
                // fresh SurfaceView instance, etc.). Previously this branch only
                // reinitialized when presentationW/H (the FIXED saved physical
                // size — see the comment above) differed from activeRenderW/H,
                // which never happens on a pure orientation change: the WM
                // overlay's live Surface gets swapped dimensions on rotation,
                // but the saved "original" presentation size does not track
                // that swap, so the old check silently no-opped and left the
                // native swapchain built against stale/mismatched geometry
                // (symptom: overlay goes blank after rotating until the user
                // manually taps "Apply changes", which calls reinitNovaContext()
                // unconditionally). Just always reinit here — it's the same
                // repair "Apply changes" performs, so rotation now self-heals
                // the same way.
                NovaLog.i(
                    TAG,
                    "New surface/geometry ${lastSurfaceW}x${lastSurfaceH} " +
                        "(presentation ${presentationW}x${presentationH}); reinitializing LSFG context",
                )
                reinitNovaContext(presentationW, presentationH)
            }
        }
        ov.onSurfaceLost {
            NovaLog.i(TAG, "onSurfaceLost — detaching output until a new Surface arrives")
            lastSurface = null
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            if (!lsfgContextActive) {
                capture?.clearSurface()
            }
        }
        val presentProfile = sessionDisplayProfile
        if (sessionDisplayApplied && presentProfile != null) {
            ov.show(presentProfile.originalWidth, presentProfile.originalHeight)
        } else {
            ov.show()
        }

        // Now that ov.show() has actually created the FPS TextView, we can safely
        // make the UI visible. The actual counter (second VirtualDisplay) is
        // started AFTER the main capture is running — on Android 14 MediaTek/
        // PowerVR the system revokes MediaProjection if a second VirtualDisplay
        // is created while the first token is still unconsumed.
        //
        // HUD readout line (CPU GPU RAM FPS) and the frame graph have separate switches.
        val effectiveHud = initialHud
        // Always (re)set, so a flag left over from a previous session can't leak in.
        hudStatsEnabled = effectiveHud
        if (effectiveHud) {
            ov.setHudVisible(true)
            startStatsPollingIfNeeded()
        }
        // Frame pacing graph — read the persisted pref directly so it restores across
        // service restarts, same as before it got its own switch.
        val initialFrameGraph = NovaPreferences(this).load().frameGraphEnabled
        if (initialFrameGraph) {
            ov.setFrameGraphVisible(true)
            CaptureMetrics.startFrameGraph()
        }
        // The stall watchdog reads its samples off the same poller as the visible
        // HUD counter, so keep the poller running whenever the watchdog is armed —
        // even if the user hasn't turned the HUD on.
        pendingFpsCounter = effectiveHud || sessionStallTimeoutSec > 0

        // The main overlay and drawer both stay in TYPE_APPLICATION_OVERLAY so
        // the drawer/icon remains visible above the full-screen output surface.
        val dr = drawer ?: SettingsDrawerOverlay(this).also { drawer = it }
        dr.setBypassListener { bypass ->
            NovaLog.i(TAG, "frameGenBypass=$bypass")
            runCatching { NativeBridge.setBypass(bypass) }
                .onFailure { NovaLog.w(TAG, "setBypass failed", it) }
            ov.updateStatus(if (bypass) "NovaFrame: bypass (raw capture)" else "NovaFrame: frame-gen active")
        }
        dr.setStopOverlayListener { _ ->
            NovaLog.i(TAG, "Stop overlay requested from drawer; leaving display override unchanged")
            stopSelf()
        }
        dr.setRestartSessionListener {
            val target = activeTargetPackage
            NovaLog.i(TAG, "Restart session requested from drawer (target=$target)")
            if (target != null) {
                // Re-request MediaProjection consent (Android requires a fresh token
                // per session; the service can't reuse the current one) and relaunch
                // for the same target app once the user grants it. Keep the current
                // display override in place — this is a restart, not an end.
                val appCtx = applicationContext
                val intent = com.firstt175.novaframe.ui.ProjectionRequestActivity.buildIntent(appCtx, target)
                appCtx.startActivity(intent)
            }
            stopSelf()
        }
        dr.setHudListener { enabled ->
            NovaLog.i(TAG, "hud=$enabled")
            hudStatsEnabled = enabled
            if (enabled) {
                CaptureMetrics.startFpsCounter()
                overlay?.setHudVisible(true)
                startStatsPollingIfNeeded()
            } else {
                overlay?.setHudVisible(false)
                // Leave the fps poller running if the stall watchdog still needs its
                // samples — only stop it when nothing is consuming them anymore.
                if (sessionStallTimeoutSec <= 0) {
                    CaptureMetrics.stopFpsCounter()
                }
                statsPollRunnable?.let { mainHandler.removeCallbacks(it) }
                statsPollRunnable = null
            }
        }
        dr.setFrameGraphListener { enabled ->
            NovaLog.i(TAG, "frameGraph=$enabled")
            if (enabled) {
                CaptureMetrics.startFrameGraph()
                overlay?.setFrameGraphVisible(true)
            } else {
                CaptureMetrics.stopFrameGraph()
                overlay?.setFrameGraphVisible(false)
            }
        }
        dr.setHudPositionListener { unlocked ->
            NovaLog.i(TAG, "hudPositionUnlocked=$unlocked")
            overlay?.setHudPositionUnlocked(unlocked)
        }
        dr.setRamCleanListener { cleanRamNow(dr) }
        dr.setInitialHudState(effectiveHud)
        dr.setInitialFrameGraphState(initialFrameGraph)
        dr.setInitialHudPositionUnlockedState(NovaPreferences(this).load().hudPositionUnlocked)
        dr.setLiveParamsListener {
            reinitNovaContext()
        }
        dr.show()
        // dr.show() is a no-op when the idle drawer (ACTION_SHOW_DRAWER) already
        // attached its window before this session started — ov.show() above just
        // added the capture/render overlay's window on top of it, burying the
        // handle. Force the drawer window back to the top of the stack so the
        // icon/handle never disappears when a session starts. See
        // SettingsDrawerOverlay.bringToFront() for the full explanation.
        dr.bringToFront()
        drawer = dr
    }

    /**
     * "CLEAN RAM" button on the drawer's HUD & OVERLAY page. Runs
     * [RamCleaner] off the main thread (it's cheap binder IPC, not real I/O,
     * but the before/after RAM samples plus the settle delay below add up to
     * a few hundred ms we don't want on the UI thread), then reports back to
     * the drawer. The actively captured/mirrored app and this app's own
     * process are the only ones ever protected from being killed.
     */
    private fun cleanRamNow(dr: SettingsDrawerOverlay) {
        val appCtx = applicationContext
        val keep = setOfNotNull(appCtx.packageName, activeTargetPackage)
        Thread({
            val before = SystemStatsSampler.sample(appCtx).ramUsedMb
            val killedCount = runCatching { RamCleaner.cleanBackgroundApps(appCtx, keep) }
                .onFailure { NovaLog.e(TAG, "cleanRamNow failed", it) }
                .getOrDefault(0)
            // killBackgroundProcesses() is async on the system side — give the
            // freed pages a moment to actually leave getMemoryInfo()'s tally
            // before re-sampling, or "after" reads no better than "before".
            Thread.sleep(700)
            val after = SystemStatsSampler.sample(appCtx).ramUsedMb
            val freedMb = if (before != null && after != null) (before - after).coerceAtLeast(0) else null
            val message = if (freedMb != null) {
                "ล้าง RAM แล้ว — ปิด $killedCount แอปพื้นหลัง, ว่างเพิ่ม ~${freedMb}MB"
            } else {
                "ล้าง RAM แล้ว — ปิด $killedCount แอปพื้นหลัง"
            }
            NovaLog.i(TAG, "cleanRamNow: killed=$killedCount before=$before after=$after")
            mainHandler.post { dr.showRamCleanResult(message) }
        }, "ram-clean").start()
    }

    /**
     * Entry point for a SESSION MODE choice made in the drawer — either
     * "ปกติ" (normal) or "อัดหน้าจอ" (recording), each carrying the current
     * Microphone toggle. RECORD_AUDIO is a runtime permission the service
     * itself cannot prompt for (it has no Activity context to host the
     * system dialog), so when the user asked for mic audio and we don't
     * have it yet, hand off to [com.firstt175.novaframe.ui.MicPermissionRequestActivity]
     * and finish the selection once they answer — see [onMicPermissionResolved].
     * Every other case (recording off, mic off, or permission already
     * granted) applies immediately so the drawer feels instant.
     */
    private fun handleRecordingSelection(recording: Boolean, mic: Boolean) {
        val micPermissionMissing = recording && mic &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micPermissionMissing) {
            NovaLog.i(TAG, "recording+mic selected but RECORD_AUDIO not granted — requesting before start")
            val intent = com.firstt175.novaframe.ui.MicPermissionRequestActivity.buildIntent(applicationContext)
            runCatching { startActivity(intent) }
                .onFailure { NovaLog.w(TAG, "failed to launch mic permission prompt", it) }
            return
        }
        finalizeRecordingSelection(recording, mic)
    }

    /**
     * Actually starts/stops the recorder and tells the drawer to lock its
     * SESSION MODE + Microphone controls on the chosen value. The drawer
     * only unlocks again once a fresh instance is built for the next
     * session (END SESSION tears the whole service — and drawer — down).
     */
    private fun finalizeRecordingSelection(recording: Boolean, mic: Boolean) {
        if (recording) {
            startGameRecording(if (mic) GameScreenRecorder.Audio.MIC else GameScreenRecorder.Audio.OFF)
        } else {
            stopGameRecording()
        }
    }

    /**
     * Called by [com.firstt175.novaframe.ui.MicPermissionRequestActivity] once the
     * user answers the RECORD_AUDIO prompt. A denial doesn't cancel the
     * recording choice — the user still asked for "อัดหน้าจอ" — it just
     * falls back to a video-only recording instead of silently doing
     * nothing (the old, permission-check-only behavior).
     */
    fun onMicPermissionResolved(granted: Boolean) {
        mainHandler.post {
            if (!granted) {
                Toast.makeText(
                    this,
                    "ไม่ได้รับสิทธิ์ไมโครโฟน จะอัดหน้าจอแบบไม่มีเสียง",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            getSharedPreferences("recording", MODE_PRIVATE).edit()
                .putBoolean("mic", granted)
                .apply()
            finalizeRecordingSelection(true, granted)
        }
    }

    /**
     * Starts the shared-frame screen recorder. [presentW]/[presentH] MUST be the
     * fixed presentation/output size (the same value passed to
     * NativeBridge.setOutputSurface — i.e. [activeRenderW]/[activeRenderH] once
     * a session is up, or the freshly-computed presentationW/H when called from
     * onSurfaceReady before those fields are set). Using the raw current
     * surface size here (e.g. lastSurfaceW/H, which follows a `wm size`
     * override) mismatches the native encoder swapchain against the frames
     * actually being blitted and silently produces an empty recording.
     */
    private fun startGameRecording(
        requestedAudio: GameScreenRecorder.Audio,
        presentW: Int = activeRenderW,
        presentH: Int = activeRenderH,
    ) {
        if (recorder?.isRecording == true) return
        if (presentW <= 0 || presentH <= 0) {
            NovaLog.w(TAG, "recording requires an active output surface")
            return
        }
        // The presentation size is the saved physical panel size and does not track
        // rotation, so it can be portrait while the game is running in landscape (or
        // vice versa). The encoder canvas is fixed for the whole file, so it is either the
        // orientation the user picked or, on "auto", whatever is on screen right now;
        // later rotations are fitted into it with black bars by the native tee instead
        // of being stretched.
        val (outputW, outputH) = when (
            getSharedPreferences("recording", MODE_PRIVATE).getString("orientation", "auto")
        ) {
            // User-chosen canvas orientation (Recordings → Video orientation).
            "portrait" -> minOf(presentW, presentH) to maxOf(presentW, presentH)
            "landscape" -> maxOf(presentW, presentH) to minOf(presentW, presentH)
            else -> orientToSurface(presentW, presentH, lastSurfaceW, lastSurfaceH)
        }
        // Both the microphone and app-audio (playback capture) need RECORD_AUDIO.
        var audio = requestedAudio
        if (audio != GameScreenRecorder.Audio.OFF && androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NovaLog.w(TAG, "audio recording ($audio) requested but RECORD_AUDIO is not granted — video only")
            mainHandler.post {
                Toast.makeText(this, "ไม่ได้รับสิทธิ์เสียง จะอัดเฉพาะภาพ", Toast.LENGTH_SHORT).show()
            }
            audio = GameScreenRecorder.Audio.OFF
        }
        val r = (recorder ?: GameScreenRecorder(this).also { recorder = it })
        if (!r.start(outputW, outputH, audio, projection)) {
            NovaLog.w(TAG, "screen recorder start failed")
            return
        }
        if (audio != GameScreenRecorder.Audio.OFF && !r.audioActive) {
            mainHandler.post {
                Toast.makeText(this, "เปิดการอัดเสียงไม่ได้ จะอัดเฉพาะภาพ", Toast.LENGTH_SHORT).show()
            }
        }
        val s = r.recordingSurface()
        if (s == null || !s.isValid) {
            r.abort()
            return
        }
        recordingMic = r.audioActive
        recordingOutputW = outputW
        recordingOutputH = outputH
        runCatching { NativeBridge.setRecordingSurface(s, outputW, outputH) }
            .onFailure { NovaLog.w(TAG, "attach recording surface failed", it); r.abort() }
        overlay?.setRecordingHudVisible(true)
        recordingHudRunnable?.let { recordingHudHandler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (r.isRecording) {
                    overlay?.updateRecordingHud(r.elapsedMs())
                    recordingHudHandler.postDelayed(this, 500L)
                }
            }
        }
        recordingHudRunnable = tick
        recordingHudHandler.post(tick)
        NovaLog.i(TAG, "shared-frame recording ON mic=$recordingMic")
    }

    /** Audio source chosen in Recordings ("audio_source"); falls back to the old mic switch. */
    private fun recordingAudioPref(): GameScreenRecorder.Audio {
        val p = getSharedPreferences("recording", MODE_PRIVATE)
        return when (p.getString("audio_source", null) ?: if (p.getBoolean("mic", false)) "mic" else "off") {
            "mic" -> GameScreenRecorder.Audio.MIC
            "app" -> GameScreenRecorder.Audio.APP
            else -> GameScreenRecorder.Audio.OFF
        }
    }

    /** Swaps [w]/[h] when their orientation differs from the live surface's. */
    private fun orientToSurface(w: Int, h: Int, surfaceW: Int, surfaceH: Int): Pair<Int, Int> =
        if (surfaceW > 0 && surfaceH > 0 && (w > h) != (surfaceW > surfaceH)) h to w else w to h

    private fun stopGameRecording() {
        val r = recorder ?: return
        runCatching { NativeBridge.setRecordingSurface(null, 0, 0) }
        recordingHudRunnable?.let { recordingHudHandler.removeCallbacks(it) }
        recordingHudRunnable = null
        overlay?.setRecordingHudVisible(false)
        val uri = r.stop()
        if (uri != null) NovaLog.i(TAG, "recording saved to Gallery: $uri")
        recordingMic = false
        recordingOutputW = 0
        recordingOutputH = 0
    }

    /**
     * Tear down and re-create the native LSFG context so a parameter change from
     * the live drawer (multiplier, flow scale, performance/HDR switch) actually
     * takes effect. The shaders and pipeline state are baked at initContext time;
     * there's no in-place update path.
     *
     * Runs on a worker thread because destroyContext blocks on vkDeviceWaitIdle
     * and initContext can take 100-300ms while it recompiles the shader chain
     * (observed up to ~1-3s in the field, since it also tears down/recreates the
     * whole Vulkan session and swapchain — see SettingsDrawerOverlay's frame
     * profile logs). Because each pass is this expensive, the drawer batches
     * control changes locally (SettingsDrawerOverlay.markParamsDirty()) and only
     * calls this once, when the user taps "Apply changes" — NOT on every slider
     * tick. This function always re-reads the latest saved prefs regardless of
     * who triggered it, so a rotation-driven call (see onSurfaceReady's geometry-
     * change branch) picks up whatever was last applied from the drawer too.
     */
    /**
     * Resolves the initContext() AI-backend arguments from [cfg]. AI mode is
     * requested whenever the user has selected [FramegenBackend.NCNN_AI] —
     * which engine actually runs is [NovaConfig.aiEngine] (RIFE or IFRNet,
     * see [com.firstt175.novaframe.prefs.AiEngine]). Both engines' models ship as
     * bundled APK assets ([BundledRifeModel]/[BundledIfrnetModel]), not
     * something the user has to import first, so there's no separate
     * "ready" gate to check beyond the extraction itself succeeding. If
     * extraction fails (corrupt install, out of disk space, ...) this falls
     * back to the LSFG shader path instead of failing initContext outright,
     * matching how a missing/corrupt Lossless.dll already degrades to mirror
     * mode elsewhere in this file.
     * The model directory is engine-specific — `ctx.filesDir/ai_model` for
     * RIFE ([BundledRifeModel.ensureExtracted]) or
     * `ctx.filesDir/ai_model_ifrnet` for IFRNet
     * ([BundledIfrnetModel.ensureExtracted]) — see each interpolator's
     * load() doc comment for the expected file names within.
     */
    /**
     * The multiplier actually sent to the native render loop. The AI (ncnn) backend is
     * hard-locked to ×2 here regardless of what's stored in prefs — RIFE/IFRNet are only
     * validated for the single-midpoint case, and ParamsScreen already disables/forces the
     * multiplier slider back to 2 while this backend is selected, but this clamp keeps the
     * session honest even if a stale/out-of-range value ever reaches [NovaConfig.multiplier]
     * (e.g. a value saved before this lock existed, or set outside the UI).
     */
    private fun effectiveMultiplier(cfg: NovaConfig): Int =
        if (cfg.framegenBackend == FramegenBackend.NCNN_AI) 2 else cfg.multiplier

    /**
     * Applies the user's scheduling/backlog preferences to the freshly
     * (re-)initialized native render loop. All of these default to OFF
     * (except the device-lost safety net, which defaults ON) in
     * [NovaPreferences] — the user must explicitly configure them from
     * Settings — so this must run after every successful initContext()
     * (native state doesn't reset automatically, but re-applying here keeps
     * behaviour in lockstep with whatever is currently saved, the same way
     * pacing params are re-applied on every (re-)init).
     */
    private fun applySchedulingAndBacklogSettings() {
        val prefs = NovaPreferences(this)
        runCatching { NativeBridge.setWaitForBusyGeneration(prefs.isWaitForBusyGenerationEnabled()) }
            .onFailure { NovaLog.w(TAG, "setWaitForBusyGeneration failed", it) }
        runCatching { NativeBridge.setAllowGenerationWhenBusy(prefs.isAllowGenerationWhenBusyEnabled()) }
            .onFailure { NovaLog.w(TAG, "setAllowGenerationWhenBusy failed", it) }
        runCatching { NativeBridge.setLosslessQueue(prefs.isLosslessQueueEnabled()) }
            .onFailure { NovaLog.w(TAG, "setLosslessQueue failed", it) }
        runCatching { NativeBridge.setMaxQueuedRealFrames(prefs.getMaxQueuedRealFrames()) }
            .onFailure { NovaLog.w(TAG, "setMaxQueuedRealFrames failed", it) }
        runCatching { NativeBridge.setGenerationDeadlineMs(prefs.getGenerationDeadlineMs()) }
            .onFailure { NovaLog.w(TAG, "setGenerationDeadlineMs failed", it) }
        runCatching { NativeBridge.setBypassGenDeadlineMs(prefs.getBypassGenDeadlineMs()) }
            .onFailure { NovaLog.w(TAG, "setBypassGenDeadlineMs failed", it) }
        runCatching { NativeBridge.setBypassGenResumeDelayMs(prefs.getBypassGenResumeDelayMs()) }
            .onFailure { NovaLog.w(TAG, "setBypassGenResumeDelayMs failed", it) }
        runCatching { NativeBridge.setAutoDisableOnDeviceLostEnabled(prefs.isAutoDisableOnDeviceLostEnabled()) }
            .onFailure { NovaLog.w(TAG, "setAutoDisableOnDeviceLostEnabled failed", it) }
        applyImageEnhancementSettings()
        applyUpscaleFilterSettings()
        applyPresentModeSettings()
    }

    private fun applyImageEnhancementSettings() {
        val cfg = NovaPreferences(this).load()
        runCatching {
            NativeBridge.setImageEnhancement(
                cfg.imageEnhancementEnabled,
                1,
                cfg.imageEnhancementContrast,
                cfg.imageEnhancementSaturation,
            )
        }.onFailure { NovaLog.w(TAG, "setImageEnhancement failed", it) }
    }

    private fun applyUpscaleFilterSettings() {
        val cfg = NovaPreferences(this).load()
        runCatching {
            NativeBridge.setUpscaleEnabled(cfg.upscaleEnabled)
            NativeBridge.setUpscaleFilter(cfg.upscaleFilter.ordinal)
        }.onFailure { NovaLog.w(TAG, "setUpscale settings failed", it) }
    }

    private fun applyPresentModeSettings() {
        val cfg = NovaPreferences(this).load()
        runCatching {
            NativeBridge.setPresentMode(cfg.presentMode.vkValue)
        }.onFailure { NovaLog.w(TAG, "setPresentMode failed", it) }
    }

    private fun aiBackendArgs(cfg: NovaConfig): AiBackendArgs {
        val wantsAi = cfg.framegenBackend == FramegenBackend.NCNN_AI
        val customDir = cfg.activeModelDir?.let(::File)
        val customEngine = cfg.activeModelEngine

        // HARD RULE: an explicitly selected model is authoritative. Do not
        // silently switch to a bundled asset if the selected MY MODEL/ASSET MODEL
        // is missing, corrupt, or has the wrong engine. The native side receives
        // only this selected directory; failure is reported instead of fallback.
        if (wantsAi && customDir != null) {
            if (customEngine == null) {
                NovaLog.e(TAG, "Selected AI model has no engine; refusing bundled fallback")
                return AiBackendArgs(true, customDir.absolutePath, cfg.aiEngine.nativeValue)
            }
            val param = if (customEngine == 1) File(customDir, "ifrnet.param") else File(customDir, "flownet.param")
            val bin = if (customEngine == 1) File(customDir, "ifrnet.bin") else File(customDir, "flownet.bin")
            if (!customDir.isDirectory || param.length() <= 0 || bin.length() <= 0) {
                NovaLog.e(TAG, "Selected AI model is invalid; refusing bundled fallback: ${customDir.absolutePath}")
                return AiBackendArgs(true, customDir.absolutePath, customEngine)
            }
            return AiBackendArgs(true, customDir.absolutePath, customEngine)
        }
        val requested = wantsAi && when (cfg.aiEngine) {
            AiEngine.IFRNET -> BundledIfrnetModel.ensureExtracted(this, cfg.ifrnetModel)
            AiEngine.RIFE -> BundledRifeModel.ensureExtracted(this, cfg.rifeModel)
        }
        val modelDir = when (cfg.aiEngine) {
            AiEngine.IFRNET -> BundledIfrnetModel.modelDir(this, cfg.ifrnetModel)
            AiEngine.RIFE -> BundledRifeModel.modelDir(this, cfg.rifeModel)
        }
        return AiBackendArgs(requested, if (requested) modelDir.absolutePath else "", cfg.aiEngine.nativeValue)
    }

    private data class AiBackendArgs(
        val enabled: Boolean,
        val modelDir: String,
        val engine: Int,
    )

    /**
     * Human-readable label for the HUD's "backend" line. Reflects what actually
     * ended up running — not just what's selected in prefs — so a silent
     * AI→DLL fallback (model missing, engine unavailable, etc.) shows up in
     * the overlay instead of lying to the user about which path is active.
     */
    private fun backendLabel(cfg: NovaConfig, ai: AiBackendArgs): String {
        val wantsAi = cfg.framegenBackend == FramegenBackend.NCNN_AI
        return when {
            wantsAi && ai.enabled -> "AI (${cfg.aiEngine.name})"
            wantsAi -> "AI requested, DLL fallback"
            else -> "LSFG-DLL"
        }
    }

    /**
     * Pushes the current backend + input→output resolution to the HUD. Call
     * this any time activeInputW/H, activeRenderW/H, or the backend changes —
     * i.e. right after a successful (or fallback) initContext, both on first
     * start and on reinit. Cheap and idempotent; the overlay only redraws
     * this line, not the whole fps text, so it's safe to call often.
     */
    private fun pushStreamInfo() {
        if (activeInputW <= 0 || activeInputH <= 0 || activeRenderW <= 0 || activeRenderH <= 0) return

        // HUD deliberately reports only the frame pipeline input/output sizes.
        // Do NOT expose the post-processing/upscale resolution here: the
        // upscale stage is an internal post-process detail, not the frame-gen
        // output resolution shown to the user.
        val postLabel = if (currentPostGpuEnabled) " · POST" else ""
        val line = "$activeBackendLabel$postLabel · Input: ${activeInputW}×${activeInputH} → Output: ${activeRenderW}×${activeRenderH}"
        overlay?.setStreamInfo(line)
    }

    /**
     * Applies [NovaPreferences.renderResolutionScale] to a display/surface size,
     * clamped to [NovaPreferences.MIN_RENDER_RESOLUTION_SCALE] so capture/context
     * buffers never collapse to 0 pixels. Both dimensions are floored at 1px.
     */
    private fun scaledRenderSize(w: Int, h: Int, scale: Float): Pair<Int, Int> {
        val s = scale.coerceIn(NovaPreferences.MIN_RENDER_RESOLUTION_SCALE, 1.0f)
        val sw = (w * s).toInt().coerceAtLeast(1)
        val sh = (h * s).toInt().coerceAtLeast(1)
        return sw to sh
    }

    private fun reinitNovaContext(width: Int = lastSurfaceW, height: Int = lastSurfaceH) {
        if (shuttingDown) {
            NovaLog.i(TAG, "reinitNovaContext skipped — shutting down")
            return
        }
        val cap = capture
        val ov = overlay ?: return
        if (width == 0 || height == 0) {
            NovaLog.w(TAG, "reinitNovaContext skipped — no surface yet")
            return
        }
        // Coalesce concurrent requests: if a reinit is already running, just
        // mark that another one is wanted. The running worker will pick up the
        // newest prefs in a follow-up pass before clearing the in-flight flag.
        if (reinitInFlight) {
            pendingReinitW = width
            pendingReinitH = height
            NovaLog.i(TAG, "reinitNovaContext queued ${width}x${height} while another pass is running")
            reinitRequested = true
            return
        }
        reinitInFlight = true
        reinitRequested = false
        pendingReinitW = width
        pendingReinitH = height
        val doneLatch = CountDownLatch(1)
        reinitDoneLatch = doneLatch
        Thread {
            try {
            val cacheDir = File(filesDir, "spirv").absolutePath
            // Drain any pending requests that arrived while we were running.
            // Each pass re-reads prefs so the final native state matches the
            // most recent UI value, even if the user spammed slider releases.
            var pass = 0
            do {
                reinitRequested = false
                pass++
                // Keep the two resolution domains separate:
                // - input/capture follows the CURRENT surface (after wm size).
                // - output/presentation ALWAYS follows the physical size saved
                //   before the per-app display override was applied.
                // Never use the requested reinit geometry as the output size.
                pendingReinitW = 0
                pendingReinitH = 0
                val inputBaseW = lastSurfaceW.takeIf { it > 0 }
                    ?: width.coerceAtLeast(1)
                val inputBaseH = lastSurfaceH.takeIf { it > 0 }
                    ?: height.coerceAtLeast(1)
                val presentationW = sessionDisplayProfile
                    ?.originalWidth
                    ?.takeIf { it > 0 }
                    ?: inputBaseW
                val presentationH = sessionDisplayProfile
                    ?.originalHeight
                    ?.takeIf { it > 0 }
                    ?: inputBaseH
                val cfg = NovaPreferences(this).load()
                NovaLog.i(
                    TAG,
                    "Re-init LSFG context pass=$pass input=${inputBaseW}x${inputBaseH} " +
                        "presentation=${presentationW}x${presentationH} " +
                        "multiplier=${cfg.multiplier} flowScale=${cfg.flowScale} " +
                        "perf=${cfg.performanceMode} hdr=${cfg.hdrMode}"
                )

                if (lsfgContextActive) {
                    // Stop pushing new captures BEFORE we tear down the native
                    // context. shutdownRenderLoop() joins the C++ worker which
                    // can sit inside vkDeviceWaitIdle for tens of ms; if a new
                    // pushFrame arrives concurrently it can leave the framegen
                    // device with in-flight commands and the next waitIdle
                    // hangs forever (multi-second). Symptom from logs: re-init
                    // started but "Render loop shut down" never came.
                    runCatching { cap?.pauseNovaInput() }
                        .onFailure { NovaLog.w(TAG, "pauseNovaInput failed", it) }
                    runCatching { NativeBridge.destroyContext() }
                    lsfgContextActive = false
                }
                // Capture/context buffers follow the CURRENT display geometry.
                // The generated frame is presented at the saved physical size.
                val (scaledW, scaledH) =
                    scaledRenderSize(inputBaseW, inputBaseH, cfg.renderResolutionScale)
                val ai = aiBackendArgs(cfg)
                val rc = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = scaledW,
                        height = scaledH,
                        multiplier = effectiveMultiplier(cfg),
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = cfg.hdrMode,
                        framegenFp16 = cfg.framegenFp16,
                        aiBackend = ai.enabled,
                        aiModelDir = ai.modelDir,
                        aiEngine = ai.engine,
                    )
                }.getOrElse { -1 }
                applyImageEnhancementSettings()
                if (rc == 0 || rc > 0) {
                    lsfgContextActive = true
                    // CRITICAL: destroyContext() above released the native ANativeWindow
                    // handle, so initContext() came up with no output surface attached.
                    // Without this re-attach, blitOutputToWindow() short-circuits and
                    // the overlay freezes on whatever was last posted.
                    val surface = lastSurface
                    if (surface != null) {
                        runCatching { NativeBridge.setOutputSurface(surface, presentationW, presentationH) }
                            .onFailure { NovaLog.w(TAG, "setOutputSurface (re-init) failed", it) }
                    } else {
                        NovaLog.w(TAG, "reinit: no cached surface to re-attach")
                    }
                    // destroyContext() above also tore down the native recording
                    // swapchain and released its encoder window (see
                    // lsfg_render_loop.cpp's destroyContext: it clears
                    // g.recordWindow unconditionally). Without this, a recording
                    // that was running before "Apply changes" / a rotation-driven
                    // reinit would silently stop receiving frames — the HUD timer
                    // and MediaRecorder keep running, but the output freezes on
                    // the last frame from before the reinit. The MediaRecorder
                    // session itself is untouched (only the native tee needs
                    // re-attaching), so just hand the same encoder Surface back in.
                    if (recorder?.isRecording == true) {
                        val recSurface = recorder?.recordingSurface()
                        if (recSurface != null && recSurface.isValid) {
                            // Keep the encoder canvas the recording started with — the
                            // MediaRecorder size cannot change mid-file, and the native
                            // tee fits rotated frames into it.
                            val recW = recordingOutputW.takeIf { it > 0 } ?: presentationW
                            val recH = recordingOutputH.takeIf { it > 0 } ?: presentationH
                            recordingOutputW = recW
                            recordingOutputH = recH
                            runCatching { NativeBridge.setRecordingSurface(recSurface, recW, recH) }
                                .onFailure { NovaLog.w(TAG, "re-attach recording surface (re-init) failed", it) }
                        } else {
                            NovaLog.w(TAG, "reinit: recording was active but encoder surface is gone")
                        }
                    }
                    if (rc == 0) {
                        activeRenderW = presentationW
                        activeRenderH = presentationH
                        activeInputW = scaledW
                        activeInputH = scaledH
                        activeBackendLabel = backendLabel(cfg, ai)
                        applySchedulingAndBacklogSettings()
                        cap?.setNovaMode(scaledW, scaledH)
                        cap?.setNovaNativeInputEnabled(true)
                        mainHandler.post {
                            ov.updateStatus("NovaFrame: ${lastSurfaceW}×${lastSurfaceH} ×${cfg.multiplier} flow=${"%.2f".format(cfg.flowScale)}")
                            pushStreamInfo()
                        }
                        // Reveal the overlay again now that setOutputSurface has
                        // re-attached at the new size. A short delay gives the
                        // native worker thread time to actually blit the first
                        // frame at the new dimensions rather than fading in onto
                        // whatever stale content is still sitting in the buffer.
                        mainHandler.postDelayed({ ov.endGeometryTransition() }, 120L)
                    } else {
                        NovaLog.w(TAG, "reinit rc=$rc — framegen disabled, staying in mirror mode")
                        // Mirror mode bypasses the Vulkan blit tee entirely (cap
                        // writes straight to the display surface), so there is no
                        // path left to feed the encoder. Stop cleanly instead of
                        // leaving the recorder running against a source that will
                        // never produce another frame.
                        if (recorder?.isRecording == true) {
                            NovaLog.w(TAG, "stopping recording — falling back to mirror mode has no frame tee")
                            stopGameRecording()
                        }
                        activeRenderW = 0
                        activeRenderH = 0
                        if (surface != null) cap?.setSurface(surface, inputBaseW, inputBaseH)
                        mainHandler.post {
                            if (cap != null) {
                                ov.updateStatus("NovaFrame: mirror ${width}×${height} (GPU lacks required Vulkan ext)")
                            } else {
                                ov.updateStatus("NovaFrame: frame-gen unavailable (init rc=$rc)")
                            }
                        }
                        // Mirror fallback path also re-attaches a surface at the
                        // new size above (cap?.setSurface) — reveal once that's done.
                        mainHandler.postDelayed({ ov.endGeometryTransition() }, 120L)
                    }
                } else {
                    NovaLog.w(TAG, "reinit failed rc=$rc")
                    activeRenderW = 0
                    activeRenderH = 0
                }
            } while (reinitRequested)
            } finally {
                reinitInFlight = false
                doneLatch.countDown()
            }
        }.start()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_session),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, NovaForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_session_title))
            .setContentText(getString(R.string.notif_session_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(stopPending)
            .build()
    }

    companion object {
        private const val TAG = "NovaFGS"
        private const val CHANNEL_ID = "lsfg_session"
        private const val NOTIF_ID = 1001
        // HUD CPU/GPU/RAM readout poll cadence — cheap reads, but no need to
        // sample faster than a human can read the numbers anyway.
        private const val STATS_POLL_INTERVAL_MS = 1000L

        private val _isRunning = MutableStateFlow(false)

        /** True whenever a session's foreground service is alive (from onCreate
         *  until onDestroy flips it back). Home screen collects this to show
         *  "Start session" vs "Stop session" instead of a button that always
         *  reads STOP SESSION. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        private var instance: NovaForegroundService? = null

        /** Called by [com.firstt175.novaframe.ui.MicPermissionRequestActivity] once
         *  the RECORD_AUDIO prompt is answered. No-op if the service isn't running
         *  (e.g. the user backgrounded the app while the prompt was up). */
        fun notifyMicPermissionResolved(granted: Boolean) {
            instance?.onMicPermissionResolved(granted)
        }

        const val ACTION_SHOW_DRAWER = "com.firstt175.novaframe.action.SHOW_SETTINGS_DRAWER"
        const val ACTION_START = "com.firstt175.novaframe.action.START_SESSION"
        const val ACTION_STOP = "com.firstt175.novaframe.action.STOP_SESSION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_HUD = "hud"

        fun buildShowDrawerIntent(
            ctx: Context,
            targetPackage: String,
        ): Intent = Intent(ctx, NovaForegroundService::class.java)
            .setAction(ACTION_SHOW_DRAWER)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)

        fun buildStartIntent(
            ctx: Context,
            resultCode: Int,
            resultData: Intent,
            targetPackage: String?,
            hudEnabled: Boolean,
        ): Intent = Intent(ctx, NovaForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_HUD, hudEnabled)

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, NovaForegroundService::class.java).setAction(ACTION_STOP)
            )
        }

    }
}
