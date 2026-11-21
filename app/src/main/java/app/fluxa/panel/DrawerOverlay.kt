package app.fluxa

import app.fluxa.R
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import java.util.Date
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Settings drawer drawn above the game as accessibility overlays.
 *  - a thin strip on the right edge: swipe right -> left opens the drawer
 *  - the drawer: compact panel with CPU/GPU dials, a grid of on/off switches (tap = toggle, long-press = settings sheet), pinned stop button
 * Dragging any slider fades the drawer out so the change can be judged on the live game; it comes
 * back on release. Tapping the dim area or the X closes it.
 */
class DrawerOverlay(
    private val host: Context,
    private val wm: WindowManager,
    private val screenW: Int,
    private val screenH: Int,
    private val onStop: () -> Unit,
    private val onSettingsChanged: () -> Unit,
    /** Output % changed (already saved to prefs): the service rebuilds the presenter at the new size. */
    private val onOutputPercentChanged: () -> Unit = {},
    /** True while the output size cannot change (an mp4 is being recorded at a fixed size). */
    private val outputLocked: () -> Boolean = { false },
    /** Elapsed recording time in ms, or -1 when not recording (mode "ปกติ"). */
    private val recordElapsedMs: () -> Long = { -1L },
    /** Window type of every window the drawer adds (accessibility overlay for the capture path, an activity panel inside the player). */
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    /** The thin swipe-in strip on the right edge; the player opens the drawer from its own button instead. */
    private val showEdge: Boolean = true,
    /** Current window size when the drawer is opened (the player can rotate); null = the fixed size above. */
    private val sizeProvider: (() -> Pair<Int, Int>)? = null,
    /** Panel-only session (app opened from the launcher): nothing generates frames, so only the tool tiles are shown. */
    private val panelOnly: Boolean = false,
    /** Start / stop screen recording from the panel's record button; null = no record button (the player has none). */
    private val onRecordToggle: (() -> Unit)? = null,
    /**
     * Non-null = the panel is not a floating window but lives inside this view (the Arsenal tab of the launcher):
     * same buttons, same order, same drag editor and settings pages, but no dim background, no close / stop strip
     * and it never closes itself.
     */
    private val embedHost: FrameLayout? = null,
) {
    private val ctx = ContextThemeWrapper(host, android.R.style.Theme_DeviceDefault)
    private val handler = Handler(Looper.getMainLooper())
    private val density = host.resources.displayMetrics.density
    private val kit = Kit(ctx)
    private val appName = host.getString(R.string.fluxa_app_name)
    private val prefs = host.getSharedPreferences("fluxa", Context.MODE_PRIVATE)

    private var edge: View? = null
    private var dialBox: LinearLayout? = null
    // performance-panel page (dials, stats, power mode, refresh rate, brightness) + the right-hand rail
    private var statGame: TextView? = null
    private var statBat: TextView? = null
    private var statOutFps = -1f
    private var sessionStart = 0L
    private var cpuTile: DialTile? = null
    private var gpuTile: DialTile? = null
    private var dialPollAt = 0L
    private var dialReading = false
    /** Panel side changed in the settings sheet: the drawer is rebuilt on the new side when the sheet closes. */
    private var panelRebuild = false
    private var dialsDirty = false
    /** Moves the swipe-in strip to the other edge as soon as the side setting changes. */
    private val sideListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // HUD size changed from the app's settings page while a session is running
        if (key == PanelPrefs.K_HUD_SCALE && attached) {
            val v = PanelPrefs.hudScale(host)
            if (v != (hudScale * 100f + 0.5f).toInt()) applyHudScale(v)
        }
        if (key == PanelPrefs.K_SIDE && attached && showEdge) {
            edge?.let { runCatching { wm.removeViewImmediate(it) } }
            edge = null
            addEdge()
        }
    }
    private var scrim: FrameLayout? = null
    private var panel: View? = null
    private var dim: View? = null
    private var panelW = 0
    private var sheet: View? = null
    private var tileRepaint: (() -> Unit)? = null
    /** The drag-enabled button grid, its hint line and the save / cancel bar (edit mode). */
    private var gridView: TileGrid? = null
    private var hintText: TextView? = null
    private var editBar: View? = null
    private val repaintList = ArrayList<() -> Unit>()
    /** Edit mode: the buttons are being rearranged; nothing is stored until the user presses save. */
    private var editing = false
    private var workOrder = mutableListOf<String>()
    private var workHidden = mutableSetOf<String>()
    /** True while a settings sheet is being built: groups drop their card (the sheet is the card). */
    private var flat = false
    private var hud: View? = null
    private var recordBtn: TextView? = null
    private var hudText: TextView? = null
    private var hudClockText: TextView? = null
    private var hudClock = prefs.getBoolean("hud_clock", true)
    // third HUD line: CPU / GPU / RAM load in percent
    private var hudStats = prefs.getBoolean("hud_stats", true)
    private var hudStatText: TextView? = null
    private var usageCpu = -1
    private var usageGpu = -1
    private var usageRam = -1
    private var usageAt = 0L
    private var usageReading = false
    private var hudGraph: FrameGraphView? = null
    private val frameBuf = IntArray(FrameGraphView.SHOWN)
    private var open = false
    private var hudEnabled = prefs.getBoolean("hud", true)
    /** Locked = click-through (as before). Unlocked = the HUD can be dragged anywhere on screen. */
    private var hudLocked = prefs.getBoolean("hud_lock", true)
    // portrait and landscape keep their own spot: one shared pixel pair would land off-screen after a rotation
    private val hudKeyX = if (screenW > screenH) "hud_x_land" else "hud_x_port"
    private val hudKeyY = if (screenW > screenH) "hud_y_land" else "hud_y_port"
    private var hudX = prefs.getInt(hudKeyX, (8 * density).toInt()).coerceIn(0, max(0, screenW - (48 * density).toInt()))
    private var hudY = prefs.getInt(hudKeyY, (24 * density).toInt()).coerceIn(0, max(0, screenH - (32 * density).toInt()))
    private var hudParams: WindowManager.LayoutParams? = null
    /** HUD size setting as a factor (1.0 = 100 %), changed live from the HUD page. */
    private var hudScale = PanelPrefs.hudScale(host) / 100f
    /** Burn the fps HUD into the recorded video too (live toggle, independent of the on-screen HUD). */
    private var hudInVideo = prefs.getBoolean("hud_rec", true)
    private var hudSentToVideo = false
    /** Frametime graph under the fps counter (live toggle). */
    private var hudGraphEnabled = prefs.getBoolean("hud_graph", true)

    private var lastReal = 0L
    private var lastGen = 0L
    private var lastTime = 0L

    private fun dp(v: Int) = (v * density).toInt()
    private fun dp(v: Int, scale: Float) = (v * density * scale).toInt()

    /** HUD size slider: resizes the texts, padding and graph of the on-screen HUD at once (no rebuild). */
    private fun applyHudScale(percent: Int) {
        val s = percent / 100f
        hudScale = s
        hudText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * s)
        hudClockText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * s)
        hudStatText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * s)
        hudGraph?.setUiScale(s)
        (hud as? LinearLayout)?.setPadding(dp(9, s), dp(4, s), dp(9, s), dp(4, s))
        hud?.requestLayout()
        // the video copy of the HUD follows the new size on its next refresh
        lastVideoHudKey = ""
        updateVideoHud()
    }

    // ---------------------------------------------------------------- edge strip

    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        prefs.registerOnSharedPreferenceChangeListener(sideListener)
        if (showEdge && embedHost == null) addEdge()
        if (embedHost == null) addHud()
        if (torch.supported) torch.register(handler)
        lastTime = 0L
        handler.post(ticker)
    }

    private fun addEdge() {
        val left = PanelPrefs.left(host)
        val side = if (left) Gravity.START else Gravity.END
        val strip = FrameLayout(ctx)
        val pill = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.a(Palette.PRIMARY, 0x88), Palette.a(Palette.SECONDARY, 0x88)),
            ).apply { cornerRadius = dp(2).toFloat() }
        }
        strip.addView(pill, FrameLayout.LayoutParams(dp(4), dp(56), side or Gravity.CENTER_VERTICAL).apply {
            if (left) marginStart = dp(3) else marginEnd = dp(3)
        })

        var downX = 0f
        var triggered = false
        strip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    triggered = false
                }
                // right edge: swipe right -> left, left edge: swipe left -> right
                MotionEvent.ACTION_MOVE -> {
                    val dx = if (left) e.rawX - downX else downX - e.rawX
                    if (!triggered && dx > dp(40)) {
                        triggered = true
                        show()
                    }
                }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            dp(20),
            (screenH * 0.5f).toInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = side or Gravity.CENTER_VERTICAL
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        edge = strip
        runCatching { wm.addView(strip, lp) }
    }

    /** Small click-through "in/out" fps counter in the top-left corner. */
    private fun addHud() {
        val label = text("--/--", 12f * hudScale, bold = true, color = Palette.PRIMARY)
        val graph = FrameGraphView(ctx).apply { visibility = View.GONE; setUiScale(hudScale) }
        val clock = text("--:--", 11f * hudScale, bold = true, color = Palette.MUTED).apply {
            visibility = if (hudClock) View.VISIBLE else View.GONE
        }
        val stats = text("CPU --%  GPU --%  RAM --%", 11f * hudScale, bold = true, color = Palette.MUTED).apply {
            visibility = if (hudStats) View.VISIBLE else View.GONE
            fontFeatureSettings = "tnum"
        }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9, hudScale), dp(4, hudScale), dp(9, hudScale), dp(4, hudScale))
            background = kit.rounded(Palette.a(Palette.BG, 0xB3), 10, Palette.STROKE, 1)
            visibility = if (hudEnabled) View.VISIBLE else View.GONE
            addView(label)
            addView(clock, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            addView(stats, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            addView(graph, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        }
        hudText = label
        hudClockText = clock
        hudStatText = stats
        hudGraph = graph
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (hudLocked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = hudX
        lp.y = hudY
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        hudParams = lp
        view.setOnTouchListener(hudDragListener())
        hud = view
        if (runCatching { wm.addView(view, lp) }.isFailure) hud = null
        else view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> view.post { clampHud() } }
    }

    /** Drag the HUD around while it is unlocked; the position is saved when the finger lifts. */
    private fun hudDragListener(): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        return View.OnTouchListener { v, e ->
            val lp = hudParams ?: return@OnTouchListener false
            if (hudLocked) return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = max(0, screenW - v.width)
                    val maxY = max(0, screenH - v.height)
                    lp.x = (startX + (e.rawX - downX)).toInt().coerceIn(0, maxX)
                    lp.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, maxY)
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hudX = lp.x
                    hudY = lp.y
                    prefs.edit().putInt(hudKeyX, hudX).putInt(hudKeyY, hudY).apply()
                    // the HUD copy burned into the recording follows the new spot
                    lastVideoHudKey = ""
                    updateVideoHud()
                    true
                }
                else -> false
            }
        }
    }

    /** Keeps the whole HUD on screen (after the first layout and after a size change). */
    private fun clampHud() {
        val v = hud ?: return
        val lp = hudParams ?: return
        if (v.width <= 0) return
        val x = lp.x.coerceIn(0, max(0, screenW - v.width))
        val y = lp.y.coerceIn(0, max(0, screenH - v.height))
        if (x == lp.x && y == lp.y) return
        lp.x = x
        lp.y = y
        hudX = x
        hudY = y
        runCatching { wm.updateViewLayout(v, lp) }
        lastVideoHudKey = ""
    }

    private fun setHudLocked(locked: Boolean) {
        hudLocked = locked
        prefs.edit().putBoolean("hud_lock", locked).apply()
        val v = hud ?: return
        val lp = hudParams ?: return
        lp.flags = if (locked) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(v, lp) }
    }

    fun detach() {
        attached = false
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(sideListener) }
        torch.unregister()
        handler.removeCallbacksAndMessages(null)
        open = false
        scrim?.let { removeRoot(it) }
        scrim = null
        panel = null
        dim = null
        sheet = null
        tileRepaint = null
        gridView = null
        hintText = null
        editBar = null
        editing = false
        dialBox = null
        cpuTile = null
        gpuTile = null
        recordBtn = null
        edge?.let { runCatching { wm.removeViewImmediate(it) } }
        edge = null
        hud?.let { runCatching { wm.removeViewImmediate(it) } }
        hud = null
        hudParams = null
        hudText = null
        hudClockText = null
        hudStatText = null
        hudGraph = null
        FluxaNotificationListener.onChange = null
    }

    // ---------------------------------------------------------------- drawer

    // ---------------------------------------------------------------- drawer

    /** Opens the drawer (the player's button calls this). */
    fun openDrawer() = show()

    private fun removeRoot(r: View) {
        val h = embedHost
        if (h != null) h.removeView(r) else runCatching { wm.removeViewImmediate(r) }
    }

    fun closeDrawer() = hide()

    val isOpen: Boolean get() = open

    private fun show() {
        if (open) return
        open = true
        if (sessionStart == 0L) sessionStart = SystemClock.elapsedRealtime()
        val (screenW, screenH) = sizeProvider?.invoke() ?: (this.screenW to this.screenH)

        val root = FrameLayout(ctx)
        val dimView = View(ctx).apply {
            if (embedHost == null) {
                setBackgroundColor(0x66000000)
                setOnClickListener { if (sheet != null) closeSheet() else if (!editing) hide() }
            }
        }
        root.addView(dimView, FrameLayout.LayoutParams(-1, -1))
        val embedded = embedHost
        // compact panel: narrower than before so more of the game stays visible behind it
        // panel width follows the "panel size" setting (80-130 %), never wider than 92 % of the screen
        val pScale = PanelPrefs.panelScale(host) / 100f
        val panelWidth = if (embedded != null) screenW else minOf(
            if (screenW > screenH) minOf(dp(470), (screenW * 0.52f).toInt()) * pScale
            else minOf(dp(340), (screenW * 0.78f).toInt()) * pScale,
            screenW * 0.92f,
        ).toInt()
        panelW = panelWidth
        val card = buildPanel()
        root.addView(
            card,
            FrameLayout.LayoutParams(
                if (embedded != null) FrameLayout.LayoutParams.MATCH_PARENT else panelWidth,
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (embedded != null || PanelPrefs.left(host)) Gravity.START else Gravity.END,
            ),
        )
        if (embedded != null) {
            scrim = root
            panel = card
            dim = dimView
            embedded.addView(root, FrameLayout.LayoutParams(-1, -1))
            return
        }

        val lp = WindowManager.LayoutParams(
            screenW,
            screenH,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        scrim = root
        panel = card
        dim = dimView
        if (runCatching { wm.addView(root, lp) }.isFailure) {
            scrim = null
            panel = null
            dim = null
            open = false
            return
        }

        // Show the drawer in its final position immediately; avoid a transient GPU animation
        // when the player is already interacting with a demanding game.
        card.translationX = 0f
        root.alpha = 1f
    }

    /** The floating panel closes; the embedded one (Arsenal tab) stays where it is. */
    private fun hide() {
        if (embedHost != null) return
        removePanel()
    }

    private fun removePanel() {
        if (!open) return
        open = false
        val root = scrim
        val p = panel
        if (root == null || p == null) return
        if (scrim === root) {
            removeRoot(root)
            scrim = null
            panel = null
            dim = null
            sheet = null
            tileRepaint = null
            gridView = null
            hintText = null
            editBar = null
            editing = false // leaving the panel while editing = cancel
            dialBox = null
            statGame = null
            statBat = null
            cpuTile = null
            gpuTile = null
            FluxaNotificationListener.onChange = null
            recordBtn = null
        }
    }

    /** While a slider is held the drawer and scrim become transparent immediately. */
    private fun peek(on: Boolean) {
        (sheet ?: panel)?.alpha = if (on) 0.14f else 1f
        dim?.alpha = if (on) 0f else 1f
    }

    private val ticker = object : Runnable {
        override fun run() {
            val real = Fluxa.realFrames()
            val gen = Fluxa.generatedFrames()
            val now = SystemClock.elapsedRealtime()
            if (lastTime != 0L && now > lastTime && real >= lastReal && gen >= lastGen) {
                val dt = (now - lastTime) / 1000f
                val realFps = (real - lastReal) / dt
                val outFps = (real - lastReal + gen - lastGen) / dt
                statOutFps = outFps
                val rec = recordLabel()
                val res = Fluxa.resolutionLabel().let { if (it.isEmpty()) "" else " · $it" }
                if (panelOnly) {
                    // no frame generation here: the counters only show the recording state
                    hudText?.let { t ->
                        t.text = rec.trim()
                        t.visibility = if (rec.isEmpty()) View.GONE else View.VISIBLE
                    }
                } else {
                    val line = String.format("%s%.0f/%.0f%s", rec, realFps, outFps, res)
                    hudText?.text = line
                    lastHudLine = line
                    updateVideoHud()
                }
            }
            updateRecordButton()
            updateGraph()
            updateClock()
            refreshUsage()
            updateStats()
            pollDials()
            lastReal = real
            lastGen = gen
            lastTime = now
            handler.postDelayed(this, 1000)
        }
    }

    private var lastHudLine = ""
    private var lastVideoHudKey = ""

    /** Pct + charging flag + "time  {ic:bolt}pct%" text for the HUD second line. */
    private var lastClockPct = -1
    private var lastClockCharging = false
    // the sticky battery intent is re-read at most every 4 s (it changes slowly; clockLine runs several times a second)
    private var batIntent: Intent? = null
    private var batAt = 0L
    private fun batteryIntent(): Intent? {
        val now = SystemClock.elapsedRealtime()
        if (batIntent == null || now - batAt > 4000) {
            batIntent = host.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batAt = now
        }
        return batIntent
    }

    // clockLine runs for the on-screen HUD and for the video HUD every second: build it once per ~0.9 s
    private var clockLineCache = ""
    private var clockLineAt = 0L
    private var timeFormat: java.text.DateFormat? = null
    private var timeFormatAt = 0L
    private val batteryManager: BatteryManager? by lazy { host.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }
    private val clockDate = Date()

    private fun clockLine(): String {
        val now = SystemClock.elapsedRealtime()
        if (clockLineCache.isNotEmpty() && now - clockLineAt < 900) return clockLineCache
        clockLineAt = now
        clockLineCache = buildClockLine()
        return clockLineCache
    }

    private fun buildClockLine(): String {
        val bat = batteryIntent()
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        lastClockPct = pct
        lastClockCharging = charging
        val now = SystemClock.elapsedRealtime()
        // the 12 / 24 h setting is re-read once a minute, not on every tick
        val tf = timeFormat?.takeIf { now - timeFormatAt < 60_000 }
            ?: android.text.format.DateFormat.getTimeFormat(host).also { timeFormat = it; timeFormatAt = now }
        clockDate.time = System.currentTimeMillis()
        val time = tf.format(clockDate)
        val watt = batteryWatts(bat)
        val base = if (pct >= 0) "$time  ${if (charging) "{ic:bolt}" else ""}$pct%" else time
        return if (watt.isNotEmpty()) "$base  $watt" else base
    }

    /** Current battery power as "x.x W" (empty when the phone does not report current / voltage). */
    private fun batteryWatts(bat: Intent?): String {
        val raw = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
        val mv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        // phones report the current in µA or mA, positive or negative: normalise to amps
        val amps = Math.abs(raw).let { if (it >= 10_000L) it / 1_000_000.0 else it / 1000.0 }
        val volts = if (mv > 1000) mv / 1000.0 else mv.toDouble()
        return if (raw == 0L || volts <= 0.0) "" else String.format("%.1f W", amps * volts)
    }

    private fun usageLine(): String {
        fun p(v: Int) = if (v >= 0) "$v%" else "--%"
        return "CPU ${p(usageCpu)}  GPU ${p(usageGpu)}  RAM ${p(usageRam)}"
    }

    /** Reads CPU / GPU clock load and RAM use off the main thread about every 2 s while the HUD line is shown. */
    private fun refreshUsage() {
        if (!hudStats || !hudEnabled || usageReading) return
        val now = SystemClock.elapsedRealtime()
        if (now - usageAt < 1800) return
        usageAt = now
        usageReading = true
        Bg.run {
            val cpu = ClockReader.cpuKhz()?.let { (c, m) -> if (m > 0) (c * 100 / m).toInt().coerceIn(0, 100) else -1 } ?: -1
            val gpu = ClockReader.gpuMhz()?.let { (c, m) -> if (m > 0) (c * 100 / m).toInt().coerceIn(0, 100) else -1 } ?: -1
            val ram = runCatching {
                val am = host.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt().coerceIn(0, 100) else -1
            }.getOrDefault(-1)
            handler.post {
                usageReading = false
                usageCpu = cpu
                usageGpu = gpu
                usageRam = ram
                hudStatText?.let { s -> if (hudStats && hudEnabled) s.text = usageLine() }
            }
        }
    }

    /** Time, battery % and watts line under the fps counter. */
    private fun updateClock() {
        val t = hudClockText ?: return
        if (!hudClock || !hudEnabled) return
        val line = clockLine()
        if (t.text.toString() != line) t.text = line
        t.setTextColor(if (lastClockPct in 0..15 && !lastClockCharging) Palette.DANGER else Palette.MUTED)
    }

    /** Refreshes the frametime graph from the presenter's ring (skipped while hidden). */
    private fun updateGraph() {
        val g = hudGraph ?: return
        if (!hudEnabled || !hudGraphEnabled) {
            g.visibility = View.GONE
            return
        }
        val n = Fluxa.frameTimes(frameBuf)
        if (n < 2) {
            g.visibility = View.GONE
            return
        }
        g.setSamples(frameBuf, n)
        g.visibility = View.VISIBLE
    }

    /** Draws the HUD into a small opaque bitmap and hands it to the presenter (or hides it). */
    private fun updateVideoHud() {
        if (panelOnly) return // no presenter to burn the HUD into
        if (!hudInVideo || recordElapsedMs() < 0 || lastHudLine.isEmpty()) {
            if (hudSentToVideo) {
                Fluxa.setRecordHud(null, 0, 0, 0, 0)
                hudSentToVideo = false
                lastVideoHudKey = ""
            }
            return
        }
        val (vw, vh) = Fluxa.recordSize()
        if (vw <= 0 || vh <= 0 || screenW <= 0 || screenH <= 0) return
        // the shown frame is aspect-fit inside the video, so one screen px = `scale` video px
        val scale = min(vw.toFloat() / screenW, vh.toFloat() / screenH)
        val second = listOf(if (hudClock) clockLine() else "", if (hudStats) usageLine() else "")
            .filter { it.isNotEmpty() }.joinToString("  ")
        // same text, same size, same colour as the last upload: skip the bitmap + buffer allocation
        val key = "$lastHudLine|$second|$scale|$hudScale|$hudX,$hudY|${lastClockPct in 0..15 && !lastClockCharging}"
        if (hudSentToVideo && key == lastVideoHudKey) return
        val bmp = renderHud(lastHudLine, second, scale) ?: return
        lastVideoHudKey = key
        val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(buf)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        Fluxa.setRecordHud(buf.array(), w, h, (hudX * scale).toInt(), (hudY * scale).toInt())
        hudSentToVideo = true
    }

    /** Same look as the on-screen HUD (aqua bold text on deep navy, hairline), solid background. */
    private fun renderHud(line: String, second: String, scale: Float): Bitmap? {
        val px = density * scale * hudScale // dp -> video px (follows the HUD size setting)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.PRIMARY
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, host.resources.displayMetrics) * scale * hudScale
        }
        val padX = 9 * px
        val padY = 4 * px
        val fm = textPaint.fontMetrics
        val secondPaint = Paint(textPaint).apply {
            color = if (lastClockPct in 0..15 && !lastClockCharging) Palette.DANGER else Palette.MUTED
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, host.resources.displayMetrics) * scale * hudScale
        }
        val lineH = fm.descent - fm.ascent
        val secondH = if (second.isEmpty()) 0f else secondPaint.fontMetrics.let { it.descent - it.ascent } + px
        val textW = max(Glyphs.measure(textPaint, line), if (second.isEmpty()) 0f else Glyphs.measure(secondPaint, second))
        var w = (textW + 2 * padX).toInt() + 1
        var h = (lineH + secondH + 2 * padY).toInt() + 1
        // the native overlay buffer holds at most 640 x 160 px
        val fit = min(1f, min(640f / w, 160f / h))
        if (fit < 1f) {
            textPaint.textSize *= fit
            secondPaint.textSize *= fit
            w = (w * fit).toInt()
            h = (h * fit).toInt()
        }
        w = max(8, min(640, w))
        h = max(8, min(160, h))
        val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val c = Canvas(bmp)
        c.drawColor(Palette.BG)
        val border = Paint().apply {
            style = Paint.Style.STROKE
            color = Palette.STROKE
            strokeWidth = max(1f, px)
        }
        c.drawRect(border.strokeWidth / 2, border.strokeWidth / 2, w - border.strokeWidth / 2, h - border.strokeWidth / 2, border)
        val tp = textPaint.fontMetrics
        val sp = secondPaint.fontMetrics
        val l1 = tp.descent - tp.ascent
        val l2 = if (second.isEmpty()) 0f else (sp.descent - sp.ascent) + px * fit
        var top = (h - (l1 + l2)) / 2f
        Glyphs.draw(host, c, line, (w - Glyphs.measure(textPaint, line)) / 2f, top - tp.ascent, textPaint)
        if (second.isNotEmpty()) {
            top += l1 + px * fit
            Glyphs.draw(host, c, second, (w - Glyphs.measure(secondPaint, second)) / 2f, top - sp.ascent, secondPaint)
        }
        return bmp
    }

    /** "{ic:dot} 01:23 " while recording, empty otherwise. */
    private fun recordLabel(): String {
        val ms = recordElapsedMs()
        if (ms < 0) return ""
        val sec = ms / 1000
        return String.format("{ic:dot} %02d:%02d  ", sec / 60, sec % 60)
    }

    private var lastRecordShown = Int.MIN_VALUE

    /** Record tile label: "อัดจอ" while idle, "หยุดอัด mm:ss" while recording (the plate lights up via the tile state). */
    private fun updateRecordButton() {
        val label = tileLabels["อัดจอ"] ?: return
        val ms = recordElapsedMs()
        val shown = if (ms < 0) -1 else (ms / 1000).toInt()
        if (shown == lastRecordShown) return
        lastRecordShown = shown
        label.text = if (shown >= 0) String.format("หยุดอัด %02d:%02d", shown / 60, shown % 60) else "อัดจอ"
        tileRepaint?.invoke()
    }

    /** Audio + orientation of the next recording (read when the record button is pressed). */
    private fun recordPage(page: LinearLayout) {
        val g = group(page, "อัดจอ")
        chips(
            g, "เสียงที่อัด", listOf("เสียงแอพ", "เสียงแอพ + เสียงนอก"),
            RecordOptions.clampAudio(prefs.getInt(RecordOptions.KEY_AUDIO, RecordOptions.AUDIO_APP)),
        ) { i ->
            prefs.edit().putInt(RecordOptions.KEY_AUDIO, i).apply(); true
        }
        chips(
            g, "แนววิดีโอ", listOf("ตามหน้าจอ", "แนวนอน", "แนวตั้ง"),
            prefs.getInt(RecordOptions.KEY_ORIENT, RecordOptions.ORIENT_AUTO).coerceIn(0, 2),
        ) { i ->
            prefs.edit().putInt(RecordOptions.KEY_ORIENT, i).apply(); true
        }
        note(
            g,
            "อัดเฟรมที่แสดงบนจอ (รวมเฟรมที่เจนเพิ่ม) เป็น mp4 · ไฟล์อยู่ใน Movies/$appName · " +
                "\"เสียงนอก\" = ไมโครโฟน · ถ้าแนวไม่ตรงกับเกมจะมีขอบดำ",
        )
    }

    // ---------------------------------------------------------------- panel content

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int = Palette.TEXT) =
        TrTextView(ctx).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(null, Typeface.BOLD)
        }


    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)

    private fun buildPanel(): LinearLayout {
        val r = dp(28).toFloat()
        val left = PanelPrefs.left(host)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Palette.PANEL)
                setStroke(dp(1), Palette.STROKE)
                // rounded on the side that faces the game
                cornerRadii =
                    if (left) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            isClickable = true // swallow taps so they don't reach the scrim
        }

        if (embedHost != null) card.background = null

        // ---- tools page: switch grid (tap = on/off, long-press = settings sheet)
        buildTiles()
        val cols = when {
            panelW >= dp(560) -> 5
            panelW >= dp(400) -> 4
            else -> 3
        }
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(22), dp(12), dp(14))
        }
        val tg = TileGrid(ctx).apply {
            this.cols = cols
            cellHeight = dp(116)
            host = gridHost
        }
        gridView = tg
        grid.addView(tg, lp())
        val hint = text("", 11.5f, color = Palette.DIM).apply { gravity = Gravity.CENTER }
        hintText = hint
        grid.addView(hint, lp().apply { topMargin = dp(18) })
        editing = false
        fillGrid()
        // one page: performance dials + sliders on top, the switch grid below, one shared scroll
        // (the embedded page keeps only the grid)
        val single = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (embedHost == null) {
            single.addView(buildPerfPage(), lp())
            single.addView(
                View(ctx).apply { setBackgroundColor(Palette.DIVIDER) },
                lp(-1, dp(1)).apply { marginStart = dp(18); marginEnd = dp(18) },
            )
        }
        single.addView(grid, lp())
        val pages = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(single)
        }

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(pages, lp(-1, 0).apply { weight = 1f })
        val bar = LinearLayout(ctx).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        bar.addView(actionButton("ยกเลิก", Palette.MUTED, filled = false) { cancelEdit() }, lp(0, -2).apply { weight = 1f; marginEnd = dp(8) })
        bar.addView(actionButton("บันทึก", Palette.PRIMARY) { saveEdit() }, lp(0, -2).apply { weight = 1f })
        editBar = bar
        content.addView(bar, lp())

        // ---- rail: edit button, close + stop at the bottom
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(16), dp(6), dp(14))
        }
        strip.addView(
            kit.icon(R.drawable.fluxa_ic_edit, 24, Palette.TEXT).apply {
                setPadding(dp(13), dp(13), dp(13), dp(13))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.a(Palette.TEXT, 0x33)) }
                isClickable = true
                contentDescription = I18n.tr("แก้ไขปุ่ม")
                setOnClickListener { enterEdit() }
            },
            lp(dp(50), dp(50)),
        )
        strip.addView(View(ctx), lp(1, 0).apply { weight = 1f })
        strip.addView(kit.icon(R.drawable.fluxa_ic_close, 40, Palette.MUTED).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            contentDescription = I18n.tr("ปิดแผง")
            setOnClickListener { if (editing) cancelEdit() else hide() }
        }, lp(dp(40), dp(40)))
        strip.addView(
            kit.icon(R.drawable.fluxa_ic_stop, 36, Palette.DANGER).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = Glass.make(kit, Glass.Tone.DANGER, 12, null)
                isClickable = true
                contentDescription = I18n.f("หยุด {0}", appName)
                setOnClickListener { onStop() }
            },
            lp(dp(36), dp(36)).apply { topMargin = dp(10) },
        )
        val divider = View(ctx).apply { setBackgroundColor(Palette.DIVIDER) }
        card.orientation = LinearLayout.HORIZONTAL
        val railW = dp(92)
        if (embedHost != null) {
            // embedded page: no rail, the tools page is the page
            card.addView(content, lp(0, -1).apply { weight = 1f })
        } else if (left) {
            // panel on the left: the edge facing the game is its right side
            card.addView(content, lp(0, -1).apply { weight = 1f })
            card.addView(divider, lp(dp(1), -1))
            card.addView(strip, lp(railW, -1))
        } else {
            card.addView(strip, lp(railW, -1))
            card.addView(divider, lp(dp(1), -1))
            card.addView(content, lp(0, -1).apply { weight = 1f })
        }

        return card
    }

    // ---------------------------------------------------------------- performance page

    private fun statCell(row: LinearLayout, label: String, align: Int): TextView {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = align }
        val v = text("--", 22f, bold = true).apply { fontFeatureSettings = "tnum" }
        col.addView(v, lp(-2, -2))
        col.addView(text(I18n.tr(label), 12f, color = Palette.MUTED), lp(-2, -2).apply { topMargin = dp(2) })
        row.addView(col, lp(0, -2).apply { weight = 1f })
        return v
    }

    private fun buildPerfPage(): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
        }
        // dials: CPU left, GPU right
        val dials = LinearLayout(ctx).apply { setPadding(dp(8), dp(4), dp(8), dp(2)) }
        dialBox = dials
        col.addView(dials, lp())
        renderDials()

        // game time / battery
        val stats = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        statGame = statCell(stats, "ระยะเวลาเล่น", Gravity.START)
        statBat = statCell(stats, "แบตเตอรี่คงเหลือ", Gravity.END)
        col.addView(stats, lp().apply { topMargin = dp(10) })
        updateStats()

        // screen brightness + media volume (both apply live)
        val sliders = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cr = host.contentResolver
        val nowRaw = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        if (Settings.System.canWrite(host)) {
            slider(sliders, I18n.tr("แสงหน้าจอ"), 1, 100, (nowRaw * 100 / 255).coerceIn(1, 100), format = { "$it%" }) { pct ->
                setScreenBrightness(pct)
            }
        }
        val audio = host.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        slider(sliders, I18n.tr("เสียง"), 0, maxVol, curVol, format = { "${it * 100 / maxVol}%" }) { v ->
            runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
        }
        col.addView(View(ctx).apply { setBackgroundColor(Palette.DIVIDER) }, lp(-1, dp(1)).apply { topMargin = dp(16) })
        col.addView(sliders, lp().apply { topMargin = dp(4) })

        return col
    }

    /** Sets the real screen brightness (manual mode); needs "Modify system settings". */
    private fun setScreenBrightness(pct: Int) {
        if (!Settings.System.canWrite(host)) {
            tools.handle(GameTools.Need.WRITE_SETTINGS)
            return
        }
        val cr = host.contentResolver
        runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255))
        }
        tools.setLevel(pct) // keeps the brightness-lock level in step
    }

    private fun updateStats() {
        val g = statGame ?: return
        val hours = (SystemClock.elapsedRealtime() - sessionStart) / 3_600_000.0
        g.text = String.format("%.1f h", hours)
        clockLine() // refreshes lastClockPct
        statBat?.text = if (lastClockPct >= 0) "$lastClockPct %" else "--"
    }

    // ---------------------------------------------------------------- switch grid

    private class Tile(
        /** Stable name used to save the order / hidden state ([PanelLayout]). */
        val id: String,
        val label: String,
        val icon: Int,
        /** true / false = on / off switch; null = plain action button. */
        val state: () -> Boolean?,
        val tap: () -> Unit,
        val long: (() -> Unit)?,
        /** False = this phone / session cannot do it, so the button is not shown at all. */
        val supported: () -> Boolean = { true },
        /** Text that changes with the state (e.g. the current orientation); [label] is the fallback / settings name. */
        val dynLabel: (() -> String)? = null,
    )

    private var tiles: List<Tile> = emptyList()
    /** Every tile in default order (what the button manager lists), shown or not. */
    private var allTiles: List<Tile> = emptyList()
    private val tileLabels = HashMap<String, TextView>()
    private var cleaning = false

    private fun reapplyEnhance() {
        val c = DisplayTuner.config(host)
        if (DisplayTuner.enhanceOn(host)) Fluxa.setEnhance(c.deband, c.denoise, c.aa, c.dither)
        else Fluxa.setEnhance(0, 0, DisplayTuner.AA_OFF, false)
    }

    private fun reapplyLook() {
        Fluxa.setLook(if (DisplayTuner.lookOn(host)) DisplayTuner.look(host) else DisplayTuner.Look())
    }

    private fun setGenerating(on: Boolean): Boolean {
        Fluxa.setGenerating(on)
        onSettingsChanged()
        return Fluxa.generating
    }

    private fun setUpscaleOn(on: Boolean): Boolean {
        if (outputLocked()) {
            I18n.toastMake(host, "เปลี่ยนไม่ได้ขณะอัดวิดีโอ", Toast.LENGTH_SHORT).show()
            return DisplayTuner.config(host).upscale
        }
        DisplayTuner.setUpscaleOn(host, on)
        onOutputPercentChanged()
        return on
    }

    private fun setEnhanceOn(on: Boolean): Boolean {
        DisplayTuner.setEnhanceOn(host, on)
        reapplyEnhance()
        return on
    }

    private fun setLookOn(on: Boolean): Boolean {
        DisplayTuner.setLookOn(host, on)
        reapplyLook()
        return on
    }

    private fun setHud(on: Boolean): Boolean {
        hudEnabled = on
        prefs.edit().putBoolean("hud", on).apply()
        hud?.visibility = if (on) View.VISIBLE else View.GONE
        return on
    }

    private fun cleanRam() {
        if (cleaning) return
        cleaning = true
        tileLabels["ล้าง RAM"]?.text = "กำลังทำความสะอาด…"
        tools.clean { freed ->
            cleaning = false
            tileLabels["ล้าง RAM"]?.text = "ล้าง RAM"
            I18n.toastMake(host, tools.cleanMessage(freed), Toast.LENGTH_LONG).show()
        }
    }

    private fun buildTiles() {
        tileLabels.clear()
        val all = listOf(
            Tile("frame", "เจนเฟรม", R.drawable.fluxa_ic_frame, { Fluxa.generating }, { setGenerating(!Fluxa.generating) }, {
                openSheet("เจนเฟรม", Fluxa.generating, ::setGenerating) { framesPage(it) }
            }),
            Tile("upscale", "อัปสเกล", R.drawable.fluxa_ic_fit, { DisplayTuner.config(host).upscale }, { setUpscaleOn(!DisplayTuner.config(host).upscale) }, {
                openSheet("อัปสเกล", DisplayTuner.config(host).upscale, ::setUpscaleOn) { upscalePage(it) }
            }),
            Tile("enhance", "เพิ่มคุณภาพ", R.drawable.fluxa_ic_sparkle, { DisplayTuner.enhanceOn(host) }, { setEnhanceOn(!DisplayTuner.enhanceOn(host)) }, {
                openSheet("เพิ่มคุณภาพ", DisplayTuner.enhanceOn(host), ::setEnhanceOn) { enhancePage(it) }
            }),
            Tile("look", "ปรับสี", R.drawable.fluxa_ic_palette, { DisplayTuner.lookOn(host) }, { setLookOn(!DisplayTuner.lookOn(host)) }, {
                openSheet("ปรับสี", DisplayTuner.lookOn(host), ::setLookOn) { colourPage(it) }
            }),
            Tile("profile", "โปรไฟล์เกม", R.drawable.fluxa_ic_star, { GameProfiles.has(host, currentGamePkg()) }, { toggleGameProfile() }, {
                openSheet("โปรไฟล์เกม") { gameProfilePage(it) }
            }),
            Tile("hud", "HUD", R.drawable.fluxa_ic_screen, { hudEnabled }, { setHud(!hudEnabled) }, {
                openSheet("HUD", hudEnabled, ::setHud) { hudPage(it) }
            }),
            Tile(
                "dnd", "ห้ามรบกวน", R.drawable.fluxa_ic_block, { tools.dndOn() }, { tools.handle(tools.toggleDnd()) }, null,
                supported = { host.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true },
            ),
            Tile("bright", "ล็อกความสว่าง", R.drawable.fluxa_ic_sun, { tools.lockOn() }, { tools.handle(tools.toggleLock()) }, {
                openSheet("ล็อกความสว่าง", tools.lockOn(), { _ -> tools.handle(tools.toggleLock()); tools.lockOn() }) { brightnessPage(it) }
            }, supported = { Settings.System.canWrite(host) }),
            Tile("ram", "ล้าง RAM", R.drawable.fluxa_ic_delete, { null }, { cleanRam() }, null),
            Tile("notif", "แจ้งเตือน", R.drawable.fluxa_ic_bell, { null }, { openSheet("แจ้งเตือน") { notificationsPage(it) } }, null),
            Tile("record", "อัดจอ", R.drawable.fluxa_ic_dot, { recordElapsedMs() >= 0 }, { onRecordToggle?.invoke(); hide() }, {
                openSheet("ตั้งค่าอัดจอ") { recordPage(it) }
            }),

            // ---- phone toggles
            Tile(
                "rotate", "หมุนจอ", R.drawable.fluxa_ic_rotate, { phone.rotationMode() != 0 },
                { if (!phone.cycleRotation()) tools.handle(GameTools.Need.WRITE_SETTINGS) }, null,
                supported = { phone.rotationSupported() && Settings.System.canWrite(host) },
                dynLabel = {
                    when (phone.rotationMode()) {
                        1 -> "แนวนอน"
                        2 -> "แนวตั้ง"
                        else -> "หมุนอัตโนมัติ"
                    }
                },
            ),
            Tile(
                "torch", "ไฟฉาย", R.drawable.fluxa_ic_torch, { torch.on },
                { if (!torch.toggle()) I18n.toastMake(host, "เปิดไฟฉายไม่ได้ (กล้องอาจถูกใช้อยู่)", Toast.LENGTH_SHORT).show() }, null,
                supported = { torch.supported },
            ),

            // ---- the phone's buttons
            Tile(
                "volup", "เพิ่มเสียง", R.drawable.fluxa_ic_volume_up, { null }, { phone.volume(true) }, null,
                supported = { phone.volumeSupported() },
            ),
            Tile(
                "voldn", "ลดเสียง", R.drawable.fluxa_ic_volume_down, { null }, { phone.volume(false) }, null,
                supported = { phone.volumeSupported() },
            ),
            Tile(
                "ringer", "โหมดเสียง", R.drawable.fluxa_ic_volume_off, { phone.ringerMode() != 0 },
                { tools.handle(phone.cycleRinger()) }, null,
                supported = { phone.volumeSupported() },
                dynLabel = {
                    when (phone.ringerMode()) {
                        1 -> "สั่น"
                        2 -> "เงียบ"
                        else -> "เสียงปกติ"
                    }
                },
            ),
            globalTile("back", "ย้อนกลับ", R.drawable.fluxa_ic_back, PhoneButtons.BACK),
            globalTile("home", "หน้าหลัก", R.drawable.fluxa_ic_home, PhoneButtons.HOME),
            globalTile("recents", "แอพล่าสุด", R.drawable.fluxa_ic_recents, PhoneButtons.RECENTS),
            globalTile("shade", "แถบแจ้งเตือน", R.drawable.fluxa_ic_shade, PhoneButtons.SHADE),
            globalTile("quick", "ตั้งค่าด่วน", R.drawable.fluxa_ic_tune, PhoneButtons.QUICK),
            // the panel has to be gone before the shot is taken, or it would be in the picture
            globalTile("shot", "แคปหน้าจอ", R.drawable.fluxa_ic_screenshot, PhoneButtons.SHOT, delayMs = 420L),
            globalTile("lock", "ล็อกจอ", R.drawable.fluxa_ic_lock, PhoneButtons.LOCK),
            globalTile("power", "เมนูปิดเครื่อง", R.drawable.fluxa_ic_power, PhoneButtons.POWER),

            Tile(PanelLayout.PINNED, "ตั้งค่า panel", R.drawable.fluxa_ic_settings, { null }, { openSheet("ตั้งค่า panel") { panelPage(it) } }, null),
        )
        allTiles = all
        tiles = shownTiles(all)
    }

    /** A button that sends one of the phone's navigation / power actions through the accessibility service. */
    private fun globalTile(id: String, label: String, icon: Int, action: Int, delayMs: Long = 160L) = Tile(
        id, label, icon, { null },
        {
            afterHide(delayMs) {
                if (!phone.global(action)) failToast()
            }
        },
        null,
        supported = { phone.globalSupported() },
    )

    private fun failToast() {
        I18n.toastMake(host, "เครื่องนี้ไม่รองรับปุ่มนี้", Toast.LENGTH_SHORT).show()
    }

    /** Closes the panel first (so it is not on top of the result), then runs [action] a moment later. */
    private fun afterHide(delayMs: Long = 160L, action: () -> Unit) {
        hide()
        handler.postDelayed(action, delayMs)
    }

    /** True when the tile makes sense in this kind of session (frame tools need the presenter, record needs the callback). */
    private fun sessionOk(t: Tile): Boolean {
        if (t.id == "record" && onRecordToggle == null) return false
        // launcher panel: the upscale / enhance / colour settings are only saved (used at the next start); frame tools and game profile need a running game
        val needsGame = if (embedHost != null) setOf("frame", "profile") else setOf("frame", "upscale", "enhance", "look", "profile")
        if (panelOnly && t.id in needsGame) return false
        return true
    }

    /** Tiles in the saved order that this session, this phone and the user's choices all allow. */
    private fun shownTiles(all: List<Tile>): List<Tile> {
        val byId = all.associateBy { it.id }
        val hidden = PanelLayout.hidden(host)
        return PanelLayout.order(host, all.map { it.id })
            .mapNotNull { byId[it] }
            .filter { sessionOk(it) && it.supported() && it.id !in hidden }
    }

    /** Touches on the grid: tap / hold / long hold (edit) all come from [TileGrid]. */
    private val gridHost = object : TileGrid.Host {
        override fun onTap(id: String) {
            tiles.firstOrNull { it.id == id }?.let { it.tap(); tileRepaint?.invoke() }
        }

        override fun onLong(id: String) {
            tiles.firstOrNull { it.id == id }?.long?.invoke()
        }

        override fun hasLong(id: String) = tiles.firstOrNull { it.id == id }?.long != null

        override fun onEnterEdit() = enterEdit()

        override fun onEditTap(id: String) {
            if (!editing || id == PanelLayout.PINNED) return
            if (!workHidden.add(id)) workHidden.remove(id)
            fillGrid()
        }

        override fun onOrderChanged(order: List<String>) {
            if (editing) workOrder = order.toMutableList()
        }
    }

    /** (Re)builds every cell of the grid: the saved buttons normally, every usable button while editing. */
    private fun fillGrid() {
        val g = gridView ?: return
        tileLabels.clear()
        repaintList.clear()
        val list: List<Tile> =
            if (editing) {
                val byId = allTiles.associateBy { it.id }
                workOrder.mapNotNull { byId[it] }
            } else tiles
        val map = LinkedHashMap<String, View>()
        list.forEach { map[it.id] = tileView(it, repaintList) }
        g.editMode = editing
        g.setCells(list.map { it.id }, map)
        tileRepaint = { repaintList.forEach { it() } }
        lastRecordShown = Int.MIN_VALUE
        updateRecordButton()
        updateEditChrome()
    }

    private fun updateEditChrome() {
        editBar?.visibility = if (editing) View.VISIBLE else View.GONE
        hintText?.text =
            if (editing) "ลากปุ่มเพื่อย้ายตำแหน่ง · แตะปุ่มเพื่อซ่อน/แสดง"
            else "แตะเพื่อเปิด/ปิด · กดค้างเพื่อตั้งค่า · กดค้างนานเพื่อย้ายปุ่ม"
    }

    /** Starts rearranging: works on a copy, nothing is saved until [saveEdit]. */
    private fun enterEdit() {
        if (editing || gridView == null) return
        val byId = allTiles.associateBy { it.id }
        workOrder = PanelLayout.order(host, allTiles.map { it.id })
            .filter { id -> byId[id]?.let { sessionOk(it) && it.supported() } == true }
            .toMutableList()
        workHidden = PanelLayout.hidden(host).toMutableSet()
        editing = true
        fillGrid()
    }

    private fun saveEdit() {
        if (!editing) return
        PanelLayout.save(host, workOrder, workHidden)
        editing = false
        buildTiles()
        fillGrid()
    }

    private fun cancelEdit() {
        if (!editing) return
        editing = false
        fillGrid()
    }

    private fun tileView(t: Tile, repaint: MutableList<() -> Unit>): View {
        val icon = kit.icon(t.icon, 28, Palette.MUTED)
        val plate = FrameLayout(ctx).apply {
            addView(icon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        val label = text(t.label, 11.5f, bold = true, color = Palette.MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        tileLabels[t.label] = label
        t.dynLabel?.let { label.text = it() }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(plate, lp(dp(60), dp(60)))
            addView(label, lp(-1, -2).apply { topMargin = dp(6) })
        }
        if (editing && t.id != PanelLayout.PINNED) {
            val hid = t.id in workHidden
            val badge = text(if (hid) "+" else "\u2212", 14f, bold = true, color = Palette.BG).apply {
                gravity = Gravity.CENTER
                background = kit.rounded(if (hid) Palette.PRIMARY else Palette.DANGER, 10)
            }
            plate.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
        }
        val dimmed = editing && t.id in workHidden
        repaint.add {
            val s = t.state()
            plate.background = if (s == true) kit.lit(18) else kit.rounded(Palette.CHIP_HI, 18, Palette.STROKE, 1)
            icon.setColorFilter(if (s == true) Palette.PRIMARY else if (s == null) Palette.TEXT else Palette.MUTED)
            label.setTextColor(if (s == true) Palette.TEXT else Palette.MUTED)
            t.dynLabel?.let { d -> label.text = d() }
            plate.alpha = if (dimmed) 0.4f else 1f
            label.alpha = if (dimmed) 0.4f else 1f
        }
        repaint.last().invoke()
        return cell
    }

    // ---------------------------------------------------------------- settings sheet

    /**
     * Centered settings card over the dimmed game: title (+ the feature's on/off switch), the page
     * built by [build], and a DONE button. [onSwitch] returns the state that really took effect.
     */
    private fun openSheet(
        title: String,
        on: Boolean? = null,
        onSwitch: ((Boolean) -> Boolean)? = null,
        build: (LinearLayout) -> Unit,
    ) {
        val root = scrim ?: return
        closeSheet()
        FluxaNotificationListener.onChange = null
        val (sw, sh) = sizeProvider?.invoke() ?: (screenW to screenH)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Palette.PANEL)
                setStroke(dp(1), Palette.STROKE)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
        }
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(20), dp(14))
        }
        head.addView(text(title, 19f, bold = true), lp(0, -2).apply { weight = 1f })
        if (on != null && onSwitch != null) {
            // same ปิด / เปิด control as the other buttons in the app
            var seg: Segmented? = null
            seg = Segmented(kit, listOf("ปิด", "เปิด"), if (on) 1 else 0, textSp = 12.5f) { i ->
                val checked = i == 1
                val real = onSwitch(checked)
                tileRepaint?.invoke()
                if (real != checked) seg?.select(if (real) 1 else 0)
                true
            }
            head.addView(seg, lp(dp(132), -2).apply { marginStart = dp(8) })
        }
        card.addView(head)
        card.addView(View(ctx).apply { setBackgroundColor(Palette.DIVIDER) }, lp(-1, dp(1)))

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(2), dp(24), dp(10))
        }
        flat = true
        try { build(content) } finally { flat = false }
        card.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(content) }, lp(-1, 0).apply { weight = 1f })

        card.addView(
            text("เสร็จ", 15f, bold = true, color = Palette.PRIMARY).apply {
                gravity = Gravity.CENTER
                setPadding(dp(36), dp(11), dp(36), dp(11))
                background = kit.lit(14)
                isClickable = true
                setOnClickListener { closeSheet() }
            },
            lp(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6); bottomMargin = dp(16) },
        )

        panel?.visibility = View.INVISIBLE
        sheet = card
        root.addView(
            card,
            FrameLayout.LayoutParams(minOf(dp(560), (sw * 0.8f).toInt()), minOf(dp(440), (sh * 0.9f).toInt()), Gravity.CENTER),
        )
    }

    private fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        (scrim as? FrameLayout)?.removeView(s)
        panel?.visibility = View.VISIBLE
        panel?.alpha = 1f
        dim?.alpha = 1f
        FluxaNotificationListener.onChange = null
        if (panelRebuild) {
            // the panel moved to the other side: build it again there
            panelRebuild = false
            dialsDirty = false
            removePanel()
            show()
            return
        }
        if (dialsDirty) {
            dialsDirty = false
            renderDials()
        }
        tileRepaint?.invoke()
    }

    // ---- building blocks

    private fun group(page: LinearLayout, title: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (!flat) {
            background = kit.panelCard(18)
            setPadding(dp(14), dp(12), dp(14), dp(16))
        }
        addView(text(title, 12f, bold = true, color = Palette.MUTED).apply { letterSpacing = 0.06f })
        page.addView(this, lp().apply { topMargin = dp(12) })
    }

    private fun gap(g: LinearLayout, v: View, top: Int = 16) = g.addView(v, lp().apply { topMargin = dp(top) })

    private fun slider(
        g: LinearLayout, title: String, min: Int, max: Int, value: Int, step: Int = 1,
        format: (Int) -> String = { it.toString() }, onCommit: (Int) -> Unit,
    ): Slider = Slider(
        kit, min, max, value, step = step, format = format,
        title = title, showRange = false, onDrag = ::peek, onCommit = onCommit,
    ).also { gap(g, it) }

    private fun chips(
        g: LinearLayout, title: String, labels: List<String>, selected: Int, perRow: Int = labels.size,
        onPick: (Int) -> Boolean,
    ): Segmented {
        gap(g, text(title, 13.5f, bold = true))
        val seg = Segmented(kit, labels, selected, perRow = perRow, textSp = 13f, onPick = onPick)
        g.addView(seg, lp().apply { topMargin = dp(8) })
        return seg
    }

    /** One-row on/off setting: label on the left, switch on the right (no animation over the game). */
    private fun toggle(g: LinearLayout, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val sw = FlatSwitch(ctx, checked, animate = false) { onChange(it) }
        row.addView(text(title, 13.5f, bold = true), lp(0, -2).apply { weight = 1f; marginEnd = dp(12) })
        row.addView(sw, lp(dp(48), dp(28)))
        row.isClickable = true
        row.setOnClickListener { sw.toggle() }
        gap(g, row)
    }

    private fun note(g: LinearLayout, value: String) =
        g.addView(text(value, 11.5f, color = Palette.DIM), lp().apply { topMargin = dp(8) })

    // ---- CPU / GPU dials in the panel

    private fun renderDials() {
        val box = dialBox ?: return
        box.removeAllViews()
        cpuTile = null
        gpuTile = null
        box.visibility = View.VISIBLE
        box.gravity = Gravity.CENTER_VERTICAL
        fun add(t: DialTile) {
            t.applyLook(host)
            box.addView(t, lp(-2, -2))
        }
        if (PanelPrefs.showCpu(host)) cpuTile = DialTile(kit, "CPU", "GHz", 2, bare = true).also { add(it) }

        // only the CPU and GPU dials: an empty spacer keeps them at the two ends
        box.addView(View(ctx), lp(0, 1).apply { weight = 1f })

        if (PanelPrefs.showGpu(host)) gpuTile = DialTile(kit, "GPU", "MHz", 0, bare = true).also { add(it) }
        dialPollAt = 0L
        pollDials()
    }

    /** Reads the clocks off the main thread about once a second, only while the panel is open. */
    private fun pollDials() {
        if (!open || dialReading || sheet != null) return
        val cpuT = cpuTile
        val gpuT = gpuTile
        if (cpuT == null && gpuT == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - dialPollAt < 900) return
        dialPollAt = now
        dialReading = true
        Bg.run {
            val cpu = if (cpuT != null) ClockReader.cpuKhz() else null
            val gpu = if (gpuT != null) ClockReader.gpuMhz() else null
            handler.post {
                dialReading = false
                if (!open) return@post
                cpuT?.update(cpu?.first?.div(1_000_000.0), cpu?.second?.div(1_000_000.0))
                gpuT?.update(gpu?.first, gpu?.second)
            }
        }
    }

    // ---- per-game profile

    private fun currentGamePkg(): String = OverlayAccessibilityService.currentGame()

    /** Tile tap: save the current values for the game in front, or drop its profile if it has one. */
    private fun toggleGameProfile() {
        val pkg = currentGamePkg()
        if (!GameProfiles.knownGame(pkg)) {
            I18n.toastMake(host, "ยังไม่รู้ว่าเกมไหนอยู่ข้างหลัง · เปิดเกมก่อนแล้วลองอีกครั้ง", Toast.LENGTH_LONG).show()
            return
        }
        val name = GameProfiles.label(host, pkg)
        if (GameProfiles.has(host, pkg)) {
            GameProfiles.remove(host, pkg)
            I18n.toastMake(host, I18n.f("ลบโปรไฟล์ของ {0} แล้ว", name), Toast.LENGTH_SHORT).show()
        } else {
            GameProfiles.save(host, pkg, GameProfiles.capture(host))
            I18n.toastMake(host, I18n.f("บันทึกโปรไฟล์ของ {0} แล้ว", name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun gameProfilePage(page: LinearLayout) {
        val pkg = currentGamePkg()
        val known = GameProfiles.knownGame(pkg)
        val g = group(page, "เกมนี้")
        val status = text(
            if (!known) "ยังไม่รู้ว่าเกมไหนอยู่ข้างหลัง"
            else GameProfiles.label(host, pkg) + if (GameProfiles.has(host, pkg)) " · มีโปรไฟล์แล้ว" else " · ยังไม่มีโปรไฟล์",
            13.5f, bold = true,
        )
        gap(g, status)
        fun button(label: String, color: Int, onClick: () -> Unit): TextView = text(label, 14f, bold = true, color = color).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = kit.lit(14)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val save = button("บันทึกค่าปัจจุบันให้เกมนี้", Palette.PRIMARY) {
            if (!known) toggleGameProfile()
            else {
                GameProfiles.save(host, pkg, GameProfiles.capture(host))
                status.text = GameProfiles.label(host, pkg) + " · มีโปรไฟล์แล้ว"
                I18n.toastMake(host, I18n.f("บันทึกโปรไฟล์ของ {0} แล้ว", GameProfiles.label(host, pkg)), Toast.LENGTH_SHORT).show()
            }
        }
        g.addView(save, lp().apply { topMargin = dp(12) })
        val del = button("ลบโปรไฟล์ของเกมนี้", Palette.DANGER) {
            if (known && GameProfiles.has(host, pkg)) {
                GameProfiles.remove(host, pkg)
                status.text = GameProfiles.label(host, pkg) + " · ยังไม่มีโปรไฟล์"
                I18n.toastMake(host, I18n.f("ลบโปรไฟล์ของ {0} แล้ว", GameProfiles.label(host, pkg)), Toast.LENGTH_SHORT).show()
            }
        }
        g.addView(del, lp().apply { topMargin = dp(8) })
        note(g, "เก็บ: ตัวคูณ/เป้า fps, อัลกอริทึม, คุณภาพ, โหมดแสดงผล, อัปสเกล, เพิ่มคุณภาพ และปรับสี · ไม่รวมความละเอียด/DPI ของจอ")

        val a = group(page, "สลับอัตโนมัติ")
        toggle(a, "ใช้โปรไฟล์เมื่อเปิดเกมที่บันทึกไว้", GameProfiles.autoOn(host)) { on -> GameProfiles.setAuto(host, on) }
        note(a, "เปิดเกมอื่นที่ไม่มีโปรไฟล์ แอพจะคืนค่าที่ใช้ตอนเริ่มเซสชันให้เอง")

        val saved = GameProfiles.all(host)
        if (saved.isNotEmpty()) {
            val l = group(page, "เกมที่มีโปรไฟล์ (${saved.size})")
            note(l, saved.joinToString(" · ") { GameProfiles.label(host, it) })
        }
    }

    /** Filled / outlined button used inside the sheets and the edit bar. */
    private fun actionButton(label: String, color: Int, filled: Boolean = true, onClick: () -> Unit): TextView =
        text(label, 14f, bold = true, color = color).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = if (filled) kit.lit(14) else kit.glassBtn(14)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Button manager: rearranging happens directly on the panel (long hold + drag), this page only starts it. */
    private fun buttonsGroup(page: LinearLayout) {
        val g = group(page, "ปุ่มบนแผง")
        note(g, "ย้ายตำแหน่งปุ่มบนแผงโดยตรง: กดค้างที่ปุ่มบนแผงให้นานแล้วลาก · มีปุ่มบันทึกและยกเลิก · แตะปุ่มในโหมดนี้เพื่อซ่อน/แสดง")
        g.addView(
            actionButton("จัดเรียงปุ่มบนแผง", Palette.PRIMARY) {
                closeSheet()
                handler.post { enterEdit() }
            },
            lp().apply { topMargin = dp(12) },
        )
        g.addView(
            actionButton("คืนค่าเริ่มต้น", Palette.MUTED, filled = false) {
                PanelLayout.reset(host)
                panelRebuild = true
            },
            lp().apply { topMargin = dp(8) },
        )
        note(g, "ปุ่มระบบ (ย้อนกลับ, หน้าหลัก ฯลฯ) ต้องเปิดบริการการช่วยเหลือพิเศษของแอพ")
    }

    private fun panelPage(page: LinearLayout) {
        val pos = group(page, "ตำแหน่งแผง")
        chips(pos, "แผงแสดงและเปิดจาก", listOf("ขวา", "ซ้าย"), if (PanelPrefs.left(host)) 1 else 0) { i ->
            prefs.edit().putInt(PanelPrefs.K_SIDE, i).apply()
            panelRebuild = true
            true
        }
        note(pos, "ปัดจากขอบด้านที่เลือกเพื่อเปิดแผง · ถ้าชนกับท่าทางย้อนกลับของ Android ให้เลือกอีกด้าน")
        slider(pos, "ขนาดแผง (ความกว้าง)", PanelPrefs.MIN_PANEL_SCALE, PanelPrefs.MAX_PANEL_SCALE, PanelPrefs.panelScale(host), step = 5, format = { "$it %" }) { v ->
            prefs.edit().putInt(PanelPrefs.K_PANEL_SCALE, v).apply()
            panelRebuild = true
        }

        buttonsGroup(page)

        val d = group(page, "หน้าปัดบนแผง")
        toggle(d, "แสดง CPU (GHz)", PanelPrefs.showCpu(host)) { on ->
            prefs.edit().putBoolean(PanelPrefs.K_CPU, on).apply()
            dialsDirty = true
        }
        toggle(d, "แสดง GPU (MHz)", PanelPrefs.showGpu(host)) { on ->
            prefs.edit().putBoolean(PanelPrefs.K_GPU, on).apply()
            dialsDirty = true
        }
        chips(d, "รูปแบบหน้าปัด", listOf("ตามธีม", "เส้นโค้ง", "ขีด"), PanelPrefs.style(host), perRow = 3) { i ->
            prefs.edit().putInt(PanelPrefs.K_STYLE, i).apply()
            dialsDirty = true
            true
        }
        slider(d, "ขนาดหน้าปัด", PanelPrefs.MIN_SIZE, PanelPrefs.MAX_SIZE, PanelPrefs.size(host), step = 4, format = { "$it dp" }) { v ->
            prefs.edit().putInt(PanelPrefs.K_SIZE, v).apply()
            dialsDirty = true
        }
        chips(d, "สีหน้าปัด", listOf("สีเน้น", "ตามโหลด", "สีรอง"), PanelPrefs.color(host), perRow = 3) { i ->
            prefs.edit().putInt(PanelPrefs.K_COLOR, i).apply()
            dialsDirty = true
            true
        }
        toggle(d, "แสดงค่าสูงสุดและ % ใต้หน้าปัด", PanelPrefs.showPct(host)) { on ->
            prefs.edit().putBoolean(PanelPrefs.K_PCT, on).apply()
            dialsDirty = true
        }
        note(d, "GPU อ่านจากไฟล์ระบบของเครื่อง · บางรุ่นไม่อนุญาตให้แอพอ่านจะขึ้น --")
    }

    // ---- pages


    private fun framesPage(page: LinearLayout) {
        val g = group(page, "การสร้างเฟรม")
        var multSlider: Slider? = null
        var fpsSlider: Slider? = null
        fun showMode(target: Boolean) {
            multSlider?.visibility = if (target) View.GONE else View.VISIBLE
            fpsSlider?.visibility = if (target) View.VISIBLE else View.GONE
        }
        chips(
            g, "โหมดเอาต์พุต", FluxaOptions.OUT_MODE_LABELS,
            if (Fluxa.targetMode) FluxaOptions.OUT_MODE_TARGET else FluxaOptions.OUT_MODE_MULTIPLIER,
        ) { i ->
            val target = i == FluxaOptions.OUT_MODE_TARGET
            Fluxa.setTargetMode(target)
            prefs.edit().putInt("ui_out_mode", i).apply()
            showMode(target)
            onSettingsChanged()
            true
        }
        multSlider = slider(
            g, "ตัวคูณเฟรม", FluxaOptions.MIN_MULTIPLIER, FluxaOptions.MAX_MULTIPLIER,
            FluxaOptions.clampMultiplier(Fluxa.multiplier), format = FluxaOptions::multiplierLabel,
        ) { v ->
            Fluxa.reconfigure(v, Fluxa.flowSide)
            prefs.edit().putInt("ui_mult_x", v).apply()
            onSettingsChanged()
        }
        fpsSlider = slider(
            g, "เป้าหมาย fps", FluxaOptions.MIN_TARGET_FPS, FluxaOptions.MAX_TARGET_FPS,
            FluxaOptions.clampTargetFps(Fluxa.targetFps), step = FluxaOptions.TARGET_FPS_STEP,
            format = FluxaOptions::targetFpsLabel,
        ) { v ->
            Fluxa.setTargetFps(v)
            prefs.edit().putInt("ui_target_fps", v).apply()
            onSettingsChanged()
        }
        showMode(Fluxa.targetMode)
        slider(
            g, "คุณภาพ", FluxaOptions.MIN_FLOW_SIDE, FluxaOptions.MAX_FLOW_SIDE,
            FluxaOptions.clampFlowSide(Fluxa.flowSide), step = FluxaOptions.FLOW_SIDE_STEP, format = { "$it px" },
        ) { v ->
            Fluxa.reconfigure(Fluxa.multiplier, v)
            prefs.edit().putInt("ui_flow_side", v).apply()
            onSettingsChanged()
        }
        chips(
            g, "อัลกอริทึมเจนเฟรม", FluxaOptions.GEN_MODE_LABELS,
            FluxaOptions.clampGenMode(Fluxa.genMode),
        ) { i ->
            Fluxa.setGenMode(i)
            prefs.edit().putInt("ui_gen_mode", i).apply()
            true
        }
        chips(
            g, "โหมดแสดงผล", listOf("FIFO", "MAILBOX"),
            if (Fluxa.presentMode == FluxaOptions.PRESENT_MAILBOX) 1 else 0,
        ) { i ->
            Fluxa.setPresentMode(if (i == 1) FluxaOptions.PRESENT_MAILBOX else FluxaOptions.PRESENT_FIFO)
            prefs.edit().putInt("ui_present", i).apply()
            true
        }
    }

    private fun upscalePage(page: LinearLayout) {
        val cfg = DisplayTuner.config(host)
        var upMode = cfg.upscaleFilter
        var upSharp = cfg.upscaleSharp
        var upSharpMethod = cfg.upscaleSharpMethod
        fun applyUpscale() {
            Fluxa.setUpscale(upMode, upSharp, upSharpMethod)
            DisplayTuner.saveUpscale(host, upMode, upSharp, upSharpMethod)
        }

        val up = group(page, "อัปสเกล")
        val outLocked = outputLocked() || !cfg.upscale
        slider(
            up, "ระดับอัปสเกล (เพิ่มจากที่เรนเดอร์)", DisplayTuner.MIN_UP_PERCENT, DisplayTuner.MAX_UP_PERCENT, cfg.upPercent,
            step = DisplayTuner.UP_PERCENT_STEP, format = { if (it == 0) "0 · ไม่อัป" else "+$it%" },
        ) { v ->
            if (v != DisplayTuner.config(host).upPercent) {
                DisplayTuner.saveUpPercent(host, v)
                onOutputPercentChanged()
            }
        }.setLocked(outLocked)
        if (outLocked) {
            note(up, if (!cfg.upscale) "ปิดอัปสเกลอยู่ (เปิดได้ในหน้าแอป)" else "ล็อกระหว่างอัดจอ (วิดีโอขนาดคงที่)")
        }
        val upModes = DisplayTuner.upscaleModes(host)
        chips(up, "ฟิลเตอร์", upModes.map { it.second }, upModes.indexOfFirst { it.first == upMode }.coerceAtLeast(0), perRow = 3) { i ->
            upMode = upModes[i].first
            applyUpscale()
            true
        }
        slider(up, "ความคมหลังอัปสเกล", 0, DisplayTuner.STRENGTH_MAX, upSharp, step = 5, format = { if (it == 0) "ปิด" else "$it%" }) { v ->
            upSharp = v
            applyUpscale()
        }
        chips(
            up, "วิธีเพิ่มความคม", DisplayTuner.sharpMethods.map { it.second },
            DisplayTuner.sharpMethods.indexOfFirst { it.first == upSharpMethod }.coerceAtLeast(0),
        ) { i ->
            upSharpMethod = DisplayTuner.sharpMethods[i].first
            applyUpscale()
            true
        }
        note(up, "ฟิลเตอร์และความคมใช้เมื่ออัปสเกลอยู่")
    }

    private fun enhancePage(page: LinearLayout) {
        val cfg = DisplayTuner.config(host)
        var enDeband = cfg.deband
        var enDenoise = cfg.denoise
        var enAa = cfg.aa
        var enDither = cfg.dither
        fun applyEnhance() {
            if (DisplayTuner.enhanceOn(host)) Fluxa.setEnhance(enDeband, enDenoise, enAa, enDither)
            DisplayTuner.saveEnhance(host, enDeband, enDenoise, enAa, enDither)
        }
        val fx = group(page, "ปรับคุณภาพภาพ")
        slider(fx, "ลดสัญญาณรบกวน/บล็อก", 0, DisplayTuner.STRENGTH_MAX, enDenoise, step = 5, format = { if (it == 0) "ปิด" else "$it%" }) { v ->
            enDenoise = v
            applyEnhance()
        }
        chips(
            fx, "ลดฟันปลา (Anti-aliasing)", DisplayTuner.aaModes.map { it.second },
            DisplayTuner.aaModes.indexOfFirst { it.first == enAa }.coerceAtLeast(0),
        ) { i ->
            enAa = DisplayTuner.aaModes[i].first
            applyEnhance()
            true
        }
        slider(fx, "ลดแถบสี (Deband)", 0, DisplayTuner.STRENGTH_MAX, enDeband, step = 5, format = { if (it == 0) "ปิด" else "$it%" }) { v ->
            enDeband = v
            applyEnhance()
        }
        toggle(fx, "Dither", enDither) { on ->
            enDither = on
            applyEnhance()
        }
    }

    private fun colourPage(page: LinearLayout) {
        var look = DisplayTuner.look(host)
        val lookSliders = ArrayList<Pair<Slider, (DisplayTuner.Look) -> Int>>()
        fun applyLook() {
            Fluxa.setLook(if (DisplayTuner.lookOn(host)) look else DisplayTuner.Look())
            DisplayTuner.saveLook(host, look)
        }
        fun signed(v: Int) = if (v > 0) "+$v" else "$v"
        fun lookSlider(
            g: LinearLayout, label: String, min: Int, max: Int, read: (DisplayTuner.Look) -> Int,
            format: (Int) -> String, write: (DisplayTuner.Look, Int) -> DisplayTuner.Look,
        ) {
            val s = slider(g, label, min, max, read(look), step = if (max - min > 10) 5 else 1, format = format) { v ->
                look = write(look, v)
                applyLook()
            }
            lookSliders.add(s to read)
        }

        val gr = DisplayTuner.GRADE_RANGE
        val color = group(page, "ปรับสี")
        lookSlider(color, "ความสว่าง", -gr, gr, { it.brightness }, ::signed) { l, v -> l.copy(brightness = v) }
        lookSlider(color, "คอนทราสต์", -gr, gr, { it.contrast }, ::signed) { l, v -> l.copy(contrast = v) }
        lookSlider(color, "ความอิ่มสี", -gr, gr, { it.saturation }, ::signed) { l, v -> l.copy(saturation = v) }
        lookSlider(color, "Vibrance (เร่งสีที่จืด)", -gr, gr, { it.vibrance }, ::signed) { l, v -> l.copy(vibrance = v) }
        lookSlider(color, "อุณหภูมิสี (เย็น {ic:swap} อุ่น)", -gr, gr, { it.temperature }, ::signed) { l, v -> l.copy(temperature = v) }
        lookSlider(color, "Tint (เขียว {ic:swap} ม่วงแดง)", -gr, gr, { it.tint }, ::signed) { l, v -> l.copy(tint = v) }

        page.addView(
            text("รีเซ็ตสี", 14f, bold = true, color = Palette.PRIMARY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                background = kit.glassBtn(16)
                setOnClickListener {
                    look = DisplayTuner.Look()
                    lookSliders.forEach { (slider, read) -> slider.set(read(look)) }
                    applyLook()
                }
            },
            lp().apply { topMargin = dp(14) },
        )
    }

    // ---- brightness lock sheet

    private val tools by lazy { GameTools(host) }
    private val phone by lazy { PhoneButtons(host) }
    private val torch by lazy { Torch(host) { handler.post { tileRepaint?.invoke() } } }

    private fun brightnessPage(page: LinearLayout) {
        val g = group(page, "โหมดเล่นเกม")
        slider(g, "ความสว่างที่ล็อก", 1, 100, tools.savedLevel(), format = { "$it%" }) { tools.setLevel(it) }
    }

    // ---- notifications sheet (music players, messages, ...)

    private fun notificationsPage(page: LinearLayout) {
        // notifications (music players, messages, ...)
        val n = group(page, "การแจ้งเตือน")
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        gap(n, list, 8)
        fun render() {
            list.removeAllViews()
            if (!FluxaNotificationListener.enabled(host)) {
                list.addView(text("ให้สิทธิ์อ่านการแจ้งเตือนเพื่อดูเพลงที่เล่นอยู่ ข้อความ และอื่น ๆ ได้ที่นี่ โดยไม่ต้องออกจากเกม", 12f, color = Palette.MUTED))
                list.addView(
                    text("ให้สิทธิ์อ่านการแจ้งเตือน", 14f, bold = true, color = Palette.PRIMARY).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(12), 0, dp(12))
                        background = kit.lit(16)
                        isClickable = true
                        setOnClickListener { tools.open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    },
                    lp().apply { topMargin = dp(10) },
                )
                return
            }
            val items = FluxaNotificationListener.snapshot().take(8)
            if (items.isEmpty()) {
                list.addView(text("ไม่มีการแจ้งเตือน", 12.5f, color = Palette.DIM))
                return
            }
            items.forEachIndexed { i, it ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = kit.panelCard(14)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(
                    text((if (it.media) "{ic:note} " else "") + it.app, 11.5f, bold = true, color = if (it.media) Palette.PRIMARY else Palette.MUTED)
                        .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    lp(0, -2).apply { weight = 1f },
                )
                if (it.clearable && !it.media) {
                    head.addView(
                        kit.icon(R.drawable.fluxa_ic_close, 28, Palette.MUTED).apply {
                            setPadding(dp(6), dp(6), dp(6), dp(6))
                            isClickable = true
                            setOnClickListener { _ -> FluxaNotificationListener.dismiss(it.key) }
                        },
                    )
                }
                card.addView(head)
                if (it.title.isNotBlank()) {
                    card.addView(text(it.title, 13.5f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp().apply { topMargin = dp(4) })
                }
                if (it.text.isNotBlank()) {
                    card.addView(text(it.text, 12f, color = Palette.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp().apply { topMargin = dp(2) })
                }
                if (it.actions.isNotEmpty()) {
                    val ar = LinearLayout(ctx)
                    it.actions.forEach { a ->
                        ar.addView(
                            text(a.title, 12.5f, bold = true, color = Palette.PRIMARY).apply {
                                gravity = Gravity.CENTER
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                setPadding(dp(4), dp(8), dp(4), dp(8))
                                background = kit.glassBtn(12)
                                isClickable = true
                                setOnClickListener { _ -> runCatching { a.intent?.send() } }
                            },
                            lp(0, -2).apply { weight = 1f; marginEnd = dp(6) },
                        )
                    }
                    card.addView(ar, lp().apply { topMargin = dp(8) })
                }
                list.addView(card, lp().apply { if (i > 0) topMargin = dp(8) })
            }
        }
        render()
        FluxaNotificationListener.onChange = { if (list.isAttachedToWindow) render() }
        note(n, "รายการอัปเดตเองเมื่อมีการแจ้งเตือนใหม่ · ปุ่มควบคุมเพลงใช้ได้จากที่นี่")
    }

    private fun hudPage(page: LinearLayout) {
        val g = group(page, "HUD")
        slider(g, "ขนาด HUD", PanelPrefs.MIN_HUD_SCALE, PanelPrefs.MAX_HUD_SCALE, (hudScale * 100).toInt(), step = 10, format = { "$it %" }) { v ->
            prefs.edit().putInt(PanelPrefs.K_HUD_SCALE, v).apply()
            applyHudScale(v)
        }
        toggle(g, "ล็อก HUD (ปิดเพื่อลากย้ายตำแหน่ง)", hudLocked) { on -> setHudLocked(on) }
        toggle(g, "แสดงเวลาและแบตเตอรี่บนจอ", hudClock) { on ->
            hudClock = on
            prefs.edit().putBoolean("hud_clock", on).apply()
            hudClockText?.visibility = if (on) View.VISIBLE else View.GONE
            updateClock()
        }
        toggle(g, "แสดง CPU / GPU / RAM บน HUD", hudStats) { on ->
            hudStats = on
            prefs.edit().putBoolean("hud_stats", on).apply()
            hudStatText?.visibility = if (on) View.VISIBLE else View.GONE
            usageAt = 0L
            refreshUsage()
        }
        toggle(g, "แสดงกราฟ frametime บน HUD", hudGraphEnabled) { on ->
            hudGraphEnabled = on
            prefs.edit().putBoolean("hud_graph", on).apply()
            updateGraph()
        }
        toggle(g, "ใส่ HUD ลงในวิดีโอที่อัด", hudInVideo) { on ->
            hudInVideo = on
            prefs.edit().putBoolean("hud_rec", on).apply()
            updateVideoHud()
        }
    }
}