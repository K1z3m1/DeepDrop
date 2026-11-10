package app.fluxa

import app.fluxa.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.content.SharedPreferences
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.max

/**
 * Screen (single app) -> MediaProjection -> Fluxa producer surface -> native DIS presenter
 * -> output SurfaceView in a click-through overlay window.
 *
 * Recording is started from the panel's record button: the same presenter additionally feeds every
 * frame it shows (generated + real) into a [ScreenRecorder] (mp4). No extra capture path is created.
 *
 * Panel-only session ([ACTION_PANEL], app opened from the launcher): no frame generation and no
 * capture at all - only the panel is shown. The record button then asks for the screen-capture
 * permission (RecordConsentActivity) and mirrors the screen straight into the recorder.
 */
class CaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var overlay: SurfaceView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var drawer: DrawerOverlay? = null
    private var pipelineUp = false
    private var stopping = false
    private var modeId = 0
    private var recordOptions = RecordOptions()
    private var recorder: ScreenRecorder? = null
    /** Panel-only session: the panel is up, but nothing is captured or generated until Record is pressed. */
    private var panelOnly = false
    private var panelStartedAt = 0L
    private val prefs: SharedPreferences by lazy { getSharedPreferences("fluxa", Context.MODE_PRIVATE) }
    private var overlayW = 0 // logical display size = frame generation (source) size
    private var overlayH = 0
    private var outW = 0 // upscaled output size (0 = no upscale)
    private var outH = 0
    private var overlayGen = 0 // bumped on every overlay rebuild so stale surface callbacks are ignored
    private var resizingTo: Pair<Int, Int>? = null // surface size we are waiting for after a live output-% change

    // play statistics (profile page)
    private var stats: StatsStore.Session? = null
    private var statAt = 0L
    private var statReal = 0L
    private var statGen = 0L

    // 0-fps watchdog
    private var lastReal = -1L
    private var lastChangeAt = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            handler.removeCallbacks(rotationCheck)
            handler.postDelayed(rotationCheck, 200)
        }
    }

    private val outputResize = Runnable { applyOutputPercentNow() }

    private val rotationCheck = Runnable {
        if (stopping || (overlay == null && !panelOnly)) return@Runnable
        val (w, h) = currentSize()
        if (w != overlayW || h != overlayH) rebuildOverlay()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopping) return
            val now = SystemClock.elapsedRealtime()
            if (Fluxa.active) {
                val real = Fluxa.realFrames()
                val gen = Fluxa.generatedFrames()
                val dt = (now - statAt) / 1000f
                if (statAt != 0L && dt > 0.3f && real > statReal && gen >= statGen) {
                    val dr = real - statReal
                    val dg = gen - statGen
                    stats?.tick(OverlayAccessibilityService.currentGame(), dr / dt, (dr + dg) / dt, dg)
                }
                statAt = now; statReal = real; statGen = gen
                if (real != lastReal) {
                    lastReal = real
                    lastChangeAt = now
                } else if (now - lastChangeAt >= ZERO_FPS_TIMEOUT_MS) {
                    toast(I18n.f("FPS เป็น 0 นานเกิน 20 วินาที หยุด {0} อัตโนมัติ", getString(R.string.fluxa_app_name)))
                    shutdown()
                    return
                }
            } else {
                // presenter being rebuilt (rotation etc.): don't count it as a freeze
                lastReal = -1L
                lastChangeAt = now
                statAt = 0L
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        AppSettings.load(applicationContext)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> start(intent)
            ACTION_PANEL -> startPanel()
            ACTION_RECORD_GRANT -> onRecordGrant(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (panelOnly) {
            // "เริ่มใช้งาน" pressed while only the panel is up: turn this session into a frame-generation one
            if (recorder != null || projection != null) {
                toast("กำลังอัดจออยู่ · หยุดอัดก่อนเริ่มเจนเฟรม")
                return
            }
            drawer?.detach()
            drawer = null
            handler.removeCallbacks(homeStop)
            OverlayAccessibilityService.onHome = null
            OverlayAccessibilityService.onAppForeground = null
            runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
            panelOnly = false
            panelRunning = false
        }
        if (projection != null) return
        running = true
        stats = StatsStore.Session(this)
        // per-game profiles: apply the saved look/frame settings of whichever game comes to the front
        GameProfiles.beginSession()
        OverlayAccessibilityService.resetGameNotify()
        OverlayAccessibilityService.onGameChanged = { pkg ->
            handler.post {
                if (!stopping && !panelOnly) {
                    GameProfiles.onGameChanged(this, pkg) { onOutputPercentChanged() }?.let {
                        toast(I18n.f("ใช้โปรไฟล์เกม: {0}", it))
                    }
                }
            }
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            stopSelf()
            return
        }

        startForegroundWithNotification(capture = true)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data)
        if (mp == null) {
            toast("เริ่มจับภาพหน้าจอไม่ได้")
            shutdown()
            return
        }
        // Android 14+: the callback must be registered before createVirtualDisplay.
        mp.registerCallback(projectionCallback(mp), handler)
        projection = mp

        modeId = intent.getIntExtra(EXTRA_DISPLAY_MODE_ID, 0)

        // optional: lower the screen resolution + DPI first (needs WRITE_SECURE_SETTINGS)
        val plan = DisplayTuner.planForStart(this)
        if (plan != null) {
            if (DisplayTuner.apply(this, plan)) {
                waitForDisplay(plan) {
                    continueStart(mp)
                    // safety: 10 s countdown; without a confirm the screen is restored and the session ends
                    if (!stopping) DisplayConfirm.show(this) { kept -> if (!kept) handler.post { shutdown() } }
                }
                return
            }
            toast("ปรับความละเอียด/DPI ไม่ได้ · ใช้ค่าเดิมต่อ")
        } else if (DisplayTuner.config(this).enabled && !DisplayTuner.canWrite(this)) {
            toast("ยังไม่ได้ให้สิทธิ์ WRITE_SECURE_SETTINGS · ข้ามการปรับความละเอียด")
        }
        continueStart(mp)
    }

    /** Waits (max ~3 s) until the display reports the new size, then runs [then]. */
    private fun waitForDisplay(plan: DisplayTuner.Plan, then: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        val check = object : Runnable {
            override fun run() {
                if (stopping) return
                val (w, h) = currentSize()
                val match = maxOf(w, h) == maxOf(plan.width, plan.height) &&
                    minOf(w, h) == minOf(plan.width, plan.height)
                if (match || SystemClock.elapsedRealtime() >= deadline) {
                    // let the window manager / SurfaceFlinger settle before the overlay is created
                    handler.postDelayed({ if (!stopping) then() }, 300)
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.post(check)
    }

    private fun continueStart(mp: MediaProjection) {
        if (stopping) return
        addOverlay()
        if (stopping) return

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        lastReal = -1L
        lastChangeAt = SystemClock.elapsedRealtime()
        handler.postDelayed(watchdog, 1000)
    }

    /**
     * Output size the frames are upscaled to: the render size (w x h) enlarged by the chosen
     * upscale amount, or 0 x 0 for none (upscale off, or the amount is 0).
     */
    private fun outputSizeFor(w: Int, h: Int): Pair<Int, Int> {
        val cfg = DisplayTuner.config(this)
        if (!cfg.upscale) return 0 to 0
        return DisplayTuner.upscaleTarget(w, h, cfg.upPercent)
    }

    private fun currentDpi(): Int {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return resources.displayMetrics.densityDpi
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.densityDpi
    }

    /**
     * Record button: opens the mp4 + encoders. With frame generation the presenter then feeds them
     * (see Fluxa.setRecordTarget); in a panel-only session [direct] mirrors the screen instead.
     */
    private fun startRecorder(mp: MediaProjection, direct: Boolean = false) {
        val (cw, ch) = currentSize()
        val (ow, oh) = if (direct) 0 to 0 else outputSizeFor(cw, ch)
        val w = if (ow > 0) ow else cw // record the upscaled picture when upscaling
        val h = if (ow > 0) oh else ch
        recordOptions = RecordOptions.fromPrefs(prefs, screenPortrait = ch > cw)
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val hz = display?.refreshRate?.let { Math.round(it) } ?: 60
        val rec = ScreenRecorder(
            applicationContext, mp, recordOptions, w, h, { toast(it) },
            direct = direct, directDpi = currentDpi(), directHz = hz,
        )
        if (rec.start()) {
            recorder = rec
        } else {
            toast("อัดจอไม่ได้")
        }
    }

    /** The panel's record button. */
    private fun toggleRecording() {
        if (stopping) return
        val rec = recorder
        if (rec != null) {
            if (rec.isRecording) stopRecording() // else: the file is still being finished
            return
        }
        if (panelOnly) {
            // every recording asks for its own screen-capture permission (one token per recording)
            launchConsent()
        } else {
            val mp = projection ?: return
            startRecorder(mp)
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        if (!rec.isRecording) return
        rec.stopAsync { uri ->
            handler.post {
                if (recorder === rec) recorder = null
                if (uri != null) toast("บันทึกวิดีโอแล้ว (Movies/${getString(R.string.fluxa_app_name)})")
                if (panelOnly && !stopping) endPanelCapture()
            }
        }
    }

    private fun launchConsent() {
        val intent = Intent(this, RecordConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // the accessibility service may start activities from the background, a plain service may not
        val starter: Context = OverlayAccessibilityService.instance ?: this
        runCatching { starter.startActivity(intent) }.onFailure { toast("เปิดหน้าขอสิทธิ์ไม่ได้") }
    }

    /** Screen-capture permission granted from the panel's record button: start recording the screen. */
    private fun onRecordGrant(intent: Intent) {
        if (!panelOnly || stopping || recorder != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) return
        startForegroundWithNotification(capture = true) // before getMediaProjection (Android 14+)
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (mp == null) {
            toast("เริ่มจับภาพหน้าจอไม่ได้")
            startForegroundWithNotification(capture = false)
            return
        }
        mp.registerCallback(projectionCallback(mp), handler)
        projection = mp
        startRecorder(mp, direct = true)
        if (recorder == null) endPanelCapture()
    }

    /** Recording done in a panel-only session: drop the capture permission and go back to "panel only". */
    private fun endPanelCapture() {
        val mp = projection
        projection = null // before stop(): the callback must not take it for the user ending the share
        runCatching { mp?.stop() }
        if (!stopping) startForegroundWithNotification(capture = false)
    }

    private fun projectionCallback(mp: MediaProjection) = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post {
                if (!panelOnly) shutdown()
                else if (projection === mp) stopRecording() // the user ended the share from the system chip
            }
        }
    }

    /** Real display size in the current rotation (maximumWindowMetrics can lag behind on non-visual contexts). */
    private fun currentSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** Screen rotated: rebuild overlay + presenter at the new size. The projection/VirtualDisplay stay alive. */
    private fun rebuildOverlay() {
        if (panelOnly) {
            // rotation: only the panel has to follow the new size
            drawer?.detach()
            drawer = null
            val host = OverlayAccessibilityService.instance
            val wm = windowManager
            if (host == null || wm == null) return
            val (w, h) = currentSize()
            overlayW = w
            overlayH = h
            drawer = buildDrawer(host, wm, w, h).also { it.attach() }
            return
        }
        overlayGen++ // ignore callbacks from the view we're about to remove
        resizingTo = null
        drawer?.detach()
        drawer = null
        stopPresenter()
        overlay?.let { v -> runCatching { windowManager?.removeViewImmediate(v) } }
        overlay = null
        addOverlay()
    }

    /**
     * Drawer changed the output % live. Debounced; the presenter is rebuilt at the new output size
     * on the same overlay view (the drawer stays open, the VirtualDisplay is reused like on rotation).
     */
    private fun onOutputPercentChanged() {
        handler.removeCallbacks(outputResize)
        handler.postDelayed(outputResize, 250)
    }

    private fun applyOutputPercentNow() {
        val view = overlay ?: return
        if (stopping || recorder != null) return // the recording encoder has a fixed size
        val (ow, oh) = outputSizeFor(overlayW, overlayH)
        if (ow == outW && oh == outH) return
        val gen = overlayGen
        stopPresenter()
        outW = ow
        outH = oh
        resizingTo = if (ow > 0) ow to oh else overlayW to overlayH
        if (ow > 0) view.holder.setFixedSize(ow, oh) else view.holder.setSizeFromLayout()
        // surfaceChanged should rebuild the pipeline; if it never comes, fall back to the rotation path
        handler.postDelayed({
            if (stopping || gen != overlayGen || pipelineUp) return@postDelayed
            resizingTo = null
            rebuildOverlay()
        }, 1500)
    }

    /** Picks a display mode that fits the (possibly changed) multiplier and applies it to the overlay window. */
    private fun applyDisplayMode() {
        val view = overlay ?: return
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val id = Fluxa.selectDisplayMode(display)
        if (id == 0 || id == modeId) return
        modeId = id
        overlayParams?.let { lp ->
            lp.preferredDisplayModeId = id
            runCatching { windowManager?.updateViewLayout(view, lp) }
        }
    }

    private fun addOverlay() {
        val host = OverlayAccessibilityService.instance
        if (host == null) {
            toast(I18n.f("ยังไม่ได้เปิดบริการการช่วยเหลือพิเศษของ {0}", getString(R.string.fluxa_app_name)))
            shutdown()
            return
        }
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val (w, h) = currentSize()
        overlayW = w
        overlayH = h
        val (ow, oh) = outputSizeFor(w, h)
        outW = ow
        outH = oh
        val gen = ++overlayGen

        val view = SurfaceView(host)
        // fixed buffer size larger than the window: the presenter renders at the original resolution
        if (ow > 0) view.holder.setFixedSize(ow, oh)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (gen != overlayGen) return
                resizingTo?.let { want ->
                    if (width != want.first || height != want.second) return // stale size, wait for the new one
                    resizingTo = null
                }
                if (width > 0 && height > 0) beginPipeline(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (gen != overlayGen) return
                stopPresenter()
            }
        })

        val lp = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // touches go to the app underneath
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (modeId != 0) lp.preferredDisplayModeId = modeId

        overlay = view
        overlayParams = lp
        wm.addView(view, lp)

        // added after the output overlay so the drawer sits above it
        drawer = buildDrawer(host, wm, w, h).also { it.attach() }
    }

    private fun buildDrawer(host: Context, wm: WindowManager, w: Int, h: Int) = DrawerOverlay(
        host, wm, w, h,
        onStop = { handler.post { shutdown() } },
        onSettingsChanged = { applyDisplayMode() },
        onOutputPercentChanged = { onOutputPercentChanged() },
        outputLocked = { recorder != null },
        recordElapsedMs = { recorder?.takeIf { it.isRecording }?.elapsedMs ?: -1L },
        panelOnly = panelOnly,
        onRecordToggle = { handler.post { toggleRecording() } },
    )

    // ------------------------------------------------------------------ panel-only session

    /** App opened from the launcher: show the panel (with the record button) and nothing else. */
    private fun startPanel() {
        if (panelOnly || projection != null || running) return
        panelOnly = true
        panelRunning = true
        panelStartedAt = SystemClock.elapsedRealtime()
        startForegroundWithNotification(capture = false) // must follow startForegroundService quickly
        val host = OverlayAccessibilityService.instance
        if (host == null) {
            toast(I18n.f("ยังไม่ได้เปิดบริการการช่วยเหลือพิเศษของ {0}", getString(R.string.fluxa_app_name)))
            shutdown()
            return
        }
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val (w, h) = currentSize()
        overlayW = w
        overlayH = h
        drawer = buildDrawer(host, wm, w, h).also { it.attach() }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        // leaving to the home screen ends a panel-only session (a recording keeps it alive)
        OverlayAccessibilityService.onHome = { handler.post { scheduleHomeStop() } }
        OverlayAccessibilityService.onAppForeground = { handler.removeCallbacks(homeStop) }
    }

    private val homeStop = Runnable {
        if (panelOnly && !stopping && recorder == null) shutdown()
    }

    private fun scheduleHomeStop() {
        if (!panelOnly || stopping) return
        handler.removeCallbacks(homeStop)
        // the app just opened from the launcher can still report the home screen for a moment
        val wait = max(HOME_STOP_MS, panelStartedAt + HOME_GRACE_MS - SystemClock.elapsedRealtime())
        handler.postDelayed(homeStop, wait)
    }

    private fun beginPipeline(holder: SurfaceHolder, width: Int, height: Int) {
        if (pipelineUp) return
        val mp = projection ?: return
        val output = holder.surface

        // upscaling: the surface is the (large) output size, generation runs at the logical display size
        val upscaled = outW > 0 && width == outW && height == outH
        val srcW = if (upscaled) overlayW else width
        val srcH = if (upscaled) overlayH else height
        val producer = Fluxa.wrap(
            output, srcW, srcH, if (upscaled) width else 0, if (upscaled) height else 0,
            DisplayTuner.config(this).upscaleFilter,
            DisplayTuner.config(this).upscaleSharp,
            DisplayTuner.config(this).upscaleSharpMethod,
        )
        if (producer == null || producer === output) {
            // wrap() hands the output back untouched when the presenter could not start.
            toast("เริ่ม Fluxa ไม่ได้")
            handler.post { shutdown() }
            return
        }

        val dpi = currentDpi()
        val existing = virtualDisplay
        if (existing == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "fluxa-capture", srcW, srcH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                producer, null, null,
            )
        } else {
            // Android 14+ allows only one createVirtualDisplay per projection token -> reuse it on rotation.
            existing.resize(srcW, srcH, dpi)
            existing.setSurface(producer)
        }
        pipelineUp = virtualDisplay != null
        if (!pipelineUp) {
            toast("สร้าง virtual display ไม่ได้")
            handler.post { shutdown() }
            return
        }
    }

    /** Detaches the capture from the presenter and destroys the presenter; keeps the VirtualDisplay. */
    private fun stopPresenter() {
        runCatching { virtualDisplay?.setSurface(null) }
        Fluxa.release()
        pipelineUp = false
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        overlayGen++
        OverlayAccessibilityService.onGameChanged = null
        GameProfiles.endSession(this)
        handler.removeCallbacks(watchdog)
        stats?.end()
        stats = null
        handler.removeCallbacks(rotationCheck)
        handler.removeCallbacks(outputResize)
        handler.removeCallbacks(homeStop)
        OverlayAccessibilityService.onHome = null
        OverlayAccessibilityService.onAppForeground = null
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        drawer?.detach()
        drawer = null
        // stop feeding the encoder while the presenter still exists, then tear the pipeline down
        val rec = recorder
        recorder = null
        rec?.detachFeed()
        if (!panelOnly) stopPresenter() // a panel-only session never owned the presenter
        virtualDisplay?.release()
        virtualDisplay = null
        overlay?.let { v -> runCatching { windowManager?.removeViewImmediate(v) } }
        overlay = null
        if (rec == null) {
            projection?.stop()
            projection = null
            finishService()
            return
        }
        // finish the mp4 first (app-audio capture needs the projection alive until the last sample)
        rec.stopAsync { uri ->
            handler.post {
                if (uri != null) {
                    toast("บันทึกวิดีโอแล้ว (Movies/${getString(R.string.fluxa_app_name)})")
                }
                projection?.stop()
                projection = null
                finishService()
            }
        }
    }

    private fun finishService() {
        running = false
        panelRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Do not leave screen capture/rendering active after the user removes the app task. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (!stopping) shutdown()
        super.onDestroy()
    }

    /**
     * Android requires a foreground-service notification while MediaProjection is active.
     * Keep only the required, silent, non-interactive status notification; no ordinary app
     * notifications are published.
     */
    private fun startForegroundWithNotification(capture: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.fluxa_app_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = I18n.tr("สถานะที่จำเป็นระหว่างจับภาพหน้าจอ")
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fluxa)
            .setContentTitle(getString(R.string.fluxa_app_name))
            .setContentText(I18n.tr(if (capture) "กำลังจับภาพหน้าจอขณะใช้งาน" else "แผงควบคุมทำงานอยู่"))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        var type = if (capture || Build.VERSION.SDK_INT < 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        // the recorder may take the microphone: the type has to be declared when the service starts
        if (capture && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, notification, type)
    }

    private fun toast(text: String) {
        showToast(text)
    }

    private fun showToast(text: String) =
        handler.post { I18n.toastMake(applicationContext, text, Toast.LENGTH_LONG).show() }

    companion object {
        private const val CHANNEL = "fluxa"
        private const val NOTIF_ID = 1
        private const val ZERO_FPS_TIMEOUT_MS = 20_000L
        private const val HOME_STOP_MS = 4_000L
        private const val HOME_GRACE_MS = 6_000L

        /** True from start until the service has finished (used to tell a dead session from a live one). */
        @Volatile var running = false
            private set
        /** True while a panel-only session (app opened from the launcher) is up. */
        @Volatile var panelRunning = false
            private set
        const val ACTION_START = "app.fluxa.START"
        const val ACTION_PANEL = "app.fluxa.PANEL"
        const val ACTION_RECORD_GRANT = "app.fluxa.RECORD_GRANT"
        const val ACTION_STOP = "app.fluxa.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DISPLAY_MODE_ID = "display_mode_id"
    }
}
