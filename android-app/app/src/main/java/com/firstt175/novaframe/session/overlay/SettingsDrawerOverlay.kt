package com.firstt175.novaframe.session.overlay

import com.firstt175.novaframe.session.NovaLog
import com.firstt175.novaframe.session.NativeBridge
import com.firstt175.novaframe.session.diagnostics.CrashReporter
import com.firstt175.novaframe.session.diagnostics.SoundTunerController
import com.firstt175.novaframe.session.service.NovaAccessibilityService
import com.firstt175.novaframe.session.service.NovaForegroundService

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.AppAppearancePrefs
import com.firstt175.novaframe.prefs.AppStyle
import com.firstt175.novaframe.prefs.AppThemeMode
import com.firstt175.novaframe.prefs.FramegenBackend
import com.firstt175.novaframe.prefs.NovaPreferences
import java.io.File

/**
 * Right-edge settings drawer for the in-game overlay.
 *
 * Collapsed: a thin touchable edge strip + a vertical "handle" pill visible at mid-height.
 *            The handle pulses subtly so the user can find it.
 * Drag:      swiping leftward from the strip moves the panel with the finger 1:1. Releasing
 *            past the halfway point snaps open (with a slight overshoot); otherwise snaps back.
 * Expanded:  scrim darkens the game area; tapping outside the panel closes it.
 *
 * Always hosted as TYPE_APPLICATION_OVERLAY (or legacy TYPE_SYSTEM_ALERT). The drawer is
 * always touchable (it has its own UI) so the Android 12+ 0.8-alpha clamp does not apply;
 * scrim and panel fade are driven via View.setAlpha, which is unaffected by the window
 * clamp.
 */
class SettingsDrawerOverlay(
    private val ctx: Context,
) {

    fun interface BypassToggleListener {
        fun onBypassChanged(bypass: Boolean)
    }

    fun interface StopOverlayListener {
        /** [resetDisplay] — true to restore the original resolution/DPI, false to leave the current override in place. */
        fun onStopOverlay(resetDisplay: Boolean)
    }

    fun interface RestartSessionListener {
        /** Re-request MediaProjection consent and relaunch the session for the current target app. */
        fun onRestartSession()
    }

    fun interface HudListener {
        fun onHudChanged(enabled: Boolean)
    }

    fun interface FrameGraphListener {
        fun onFrameGraphChanged(enabled: Boolean)
    }

    fun interface HudPositionListener {
        fun onHudPositionUnlocked(unlocked: Boolean)
    }

    fun interface RamCleanListener {
        fun onCleanRam()
    }

    fun interface LiveParamsListener {
        fun onParamsChanged()
    }

    fun interface RecordingListener {
        fun onRecordingModeChanged(recording: Boolean, microphone: Boolean)
    }

    private var hostWindowManager: WindowManager? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var handleView: HandleView? = null
    private var scrim: View? = null
    private var panelContainer: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var bypassListener: BypassToggleListener? = null
    private var stopListener: StopOverlayListener? = null
    private var restartListener: RestartSessionListener? = null
    private var hudListener: HudListener? = null
    private var frameGraphListener: FrameGraphListener? = null
    private var hudPositionListener: HudPositionListener? = null
    private var liveParamsListener: LiveParamsListener? = null
    private var recordingListener: RecordingListener? = null
    private var ramCleanListener: RamCleanListener? = null
    private var ramCleanButtonView: Button? = null
    private var ramCleanResultView: TextView? = null
    private var initialHud: Boolean = false
    private var initialFrameGraph: Boolean = false
    private var initialHudPositionUnlocked: Boolean = false
    /** Set by [NovaForegroundService] once it knows whether this session actually
     *  applied a resolution/DPI override — gates whether END SESSION asks the
     *  reset-or-keep question at all. */

    /**
     * Owns the global (session-0) Equalizer/BassBoost/Virtualizer/LoudnessEnhancer
     * effects for the "SOUND TUNER" section. Lives only as long as the drawer's
     * window does — attached lazily when the user enables the tuner (or immediately
     * on [buildPanel] if it was already enabled last session), released in [hide].
     */
    private var soundTuner: SoundTunerController? = null

    // "Apply" bar for ALL native-affecting settings (frame-gen multiplier/flow
    // scale/render resolution/performance/HDR/FP16, frame scheduling, image
    // enhancement, upscale filter, present mode, frame transfer mode, ...).
    //
    // Nothing in this drawer is allowed to call into NativeBridge the instant the
    // user touches a control — every one of those calls is queued instead and
    // only actually fires when APPLY CHANGES is tapped, applying every pending
    // change together in one place. Letting individual sliders/buttons poke the
    // native session live, possibly while a heavier reinit (below) is still
    // tearing down/recreating the Vulkan context, is what caused overlapping
    // native calls to squeeze/stomp on each other and crash the app — gating
    // everything behind one explicit Apply avoids that race entirely.
    //
    // [reinitDirty] tracks the subset (multiplier, flow scale, render resolution,
    // performance/HDR/FP16) that needs a full native context re-init (destroy +
    // recreate Vulkan session, reload shaders — ~1-3s of stalled/frozen output).
    // [pendingNativeActions] holds the lighter runtime setters (image
    // enhancement, upscale, present mode, frame transfer, frame scheduling, ...)
    // keyed by setting name so repeated tweaks before Apply just overwrite the
    // pending action instead of piling up duplicates.
    private var applyBar: View? = null
    private var applyButton: Button? = null
    private var paramsDirty: Boolean = false
    private var reinitDirty: Boolean = false
    private val pendingNativeActions = LinkedHashMap<String, () -> Unit>()

    private fun markParamsDirty() {
        paramsDirty = true
        applyButton?.isEnabled = true
        applyBar?.alpha = 1f
    }

    /** Call from controls that require the full native context re-init on Apply. */
    private fun markReinitDirty() {
        reinitDirty = true
        markParamsDirty()
    }

    /**
     * Queue a lighter runtime native setter to run when Apply is tapped, instead
     * of calling it immediately. [key] identifies the setting so a second tweak
     * of the same control before Apply replaces the pending action rather than
     * both firing.
     */
    private fun queueNativeAction(key: String, action: () -> Unit) {
        pendingNativeActions[key] = action
        markParamsDirty()
    }

    private fun clearParamsDirty() {
        paramsDirty = false
        applyButton?.isEnabled = false
        applyBar?.alpha = 0.5f
    }

    /** 0 = collapsed, 1 = fully expanded. During drag this tracks the finger. */
    private var progress: Float = 0f
    private var expanded: Boolean = false
    private var dragActive: Boolean = false
    private var dragStartX: Float = 0f
    private var dragStartProgress: Float = 0f
    private var settleAnimator: ValueAnimator? = null
    private var handlePulseAnimator: ValueAnimator? = null

    private var edgeStripWidthPx = 0
    private var handleWidthPx = 0
    private var handleHeightPx = 0
    private var panelWidthPx = 0
    private var panelMarginPx = 0
    private var screenW = 0
    private var screenH = 0

    fun setBypassListener(l: BypassToggleListener) { bypassListener = l }
    fun setStopOverlayListener(l: StopOverlayListener) { stopListener = l }
    fun setRestartSessionListener(l: RestartSessionListener) { restartListener = l }
    fun setHudListener(l: HudListener) { hudListener = l }
    fun setFrameGraphListener(l: FrameGraphListener) { frameGraphListener = l }
    fun setHudPositionListener(l: HudPositionListener) { hudPositionListener = l }
    fun setLiveParamsListener(l: LiveParamsListener) { liveParamsListener = l }
    fun setRecordingListener(l: RecordingListener) { recordingListener = l }
    fun setRamCleanListener(l: RamCleanListener) { ramCleanListener = l }

    /**
     * Called by [com.firstt175.novaframe.session.service.NovaForegroundService]
     * once the background cleanup kicked off by [ramCleanListener] finishes,
     * to show the result under the button and re-enable it. Safe to call from
     * any thread other than one already holding the main looper's lock — the
     * caller is expected to post this to the main thread itself, same as
     * every other cross-thread call into this class.
     */
    fun showRamCleanResult(message: String) {
        ramCleanResultView?.text = message
        ramCleanButtonView?.isEnabled = true
    }

    fun setInitialHudState(enabled: Boolean) { initialHud = enabled }
    fun setInitialFrameGraphState(enabled: Boolean) { initialFrameGraph = enabled }
    fun setInitialHudPositionUnlockedState(enabled: Boolean) { initialHudPositionUnlocked = enabled }
    // --- END SESSION ---------------------------------------------------------------------

    // --- Log viewer ----------------------------------------------------------------------

    private var logViewerView: View? = null

    /**
     * Entry point for the END SESSION button. If this session actually applied a
     * resolution/DPI override, ask the user whether to restore the original
     * screen size first — otherwise just stop (nothing to restore).
     */
    private fun requestStop() {
        // Stopping a session must never change the system display. Screen
        // restoration is an explicit action from the main app only.
        stopListener?.onStopOverlay(false)
    }

    /**
     * On-device-only log viewer: reads the tail of [CrashReporter.logFile] (written by
     * [NovaLog]) and shows it in a scrollable, monospace, read-only panel. Deliberately no
     * share/export intent — CrashReporter's own docs call that out as intentional, so this
     * stays consistent with it; a "COPY" button is the only way the text leaves the panel,
     * and that stays on-device via the clipboard.
     *
     * Follows the same scrim + centered card pattern as the overlay rather than a
     * system AlertDialog, since this view already lives inside our own overlay window.
     */
    private fun showLogViewer() {
        val r = root ?: return
        if (logViewerView != null) return

        val logText = runCatching {
            val f = CrashReporter.logFile(ctx)
            if (!f.exists()) {
                "No log file yet."
            } else {
                // Tail-only: read at most the last ~120,000 chars so a long-running
                // session's log can't stall the UI thread or blow up the TextView.
                val maxChars = 120_000L
                val len = f.length()
                val skip = (len - maxChars).coerceAtLeast(0L)
                f.inputStream().use { ins ->
                    if (skip > 0) ins.skip(skip)
                    val text = ins.readBytes().toString(Charsets.UTF_8)
                    if (skip > 0) "…(earlier lines truncated)…\n$text" else text
                }
            }
        }.getOrElse { "Failed to read log: ${it.message}" }

        val scrimView = View(ctx).apply {
            setBackgroundColor(0xAA000000.toInt())
            isClickable = true
            setOnClickListener { dismissLogViewer() }
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PANEL_BG)
                cornerRadius = rad(10)
                setStroke(dp(1), COLOR_PANEL_STROKE)
            }
            setPadding(dp(16), dp(16), dp(16), dp(12))
            isClickable = true // swallow taps so they don't fall through to the scrim
        }

        card.addView(TextView(ctx).apply {
            text = "Log"
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        })

        val logBody = TextView(ctx).apply {
            text = logText
            setTextColor(COLOR_ON_SURFACE)
            alpha = 0.85f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
            addView(logBody, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        card.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ).apply { topMargin = dp(10); bottomMargin = dp(12) })

        val buttonRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val copyBtn = Button(ctx).apply {
            text = "COPY"
            setTextColor(COLOR_STOP_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_STOP_BG)
                cornerRadius = rad(3)
                setStroke(dp(1), COLOR_STOP_STROKE)
            }
            setPadding(0, dp(12), 0, dp(12))
            stateListAnimator = null
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("lsfg log", logText))
            }
        }
        buttonRow.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            setTextColor(COLOR_ON_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PRIMARY)
                cornerRadius = rad(3)
            }
            setPadding(0, dp(12), 0, dp(12))
            stateListAnimator = null
            setOnClickListener { dismissLogViewer() }
        }
        buttonRow.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })

        card.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val overlayContainer = FrameLayout(ctx)
        overlayContainer.addView(scrimView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val cardWidth = (screenW * 0.92f).toInt().coerceAtMost(dp(520))
        overlayContainer.addView(card, FrameLayout.LayoutParams(
            cardWidth,
            (screenH * 0.75f).toInt(),
            Gravity.CENTER,
        ))

        r.addView(overlayContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        logViewerView = overlayContainer
    }

    private fun dismissLogViewer() {
        val v = logViewerView ?: return
        logViewerView = null
        (v.parent as? FrameLayout)?.removeView(v)
    }

    /** Opens the settings panel without starting a capture/frame-generation session.
     *  Used by the external launcher icon; the actual session only begins when the
     *  user explicitly presses START SESSION in this drawer.
     */
    fun openPanel() {
        if (root == null) {
            show()
        }
        mainHandler.post {
            if (!expanded) {
                expandWindow()
                animateTo(1f)
            }
        }
    }

    fun show() {
        if (root != null) return

        // Pick up the current Normal / Liquid Glass choice before any view is built.
        AppAppearancePrefs.get(ctx).let {
            glassMode = it.style == AppStyle.LIQUID_GLASS
            glassDark = it.theme == AppThemeMode.DARK
            glassIntensity = it.glassIntensity
        }

        // Match OverlayManager's host choice — they MUST live in the same
        // layer family or the drawer disappears behind the capture overlay.
        // See OverlayManager.show() for the trusted-overlay rationale.
        val a11y = NovaAccessibilityService.instance
        val useTrusted = a11y != null
        val hostCtx: Context = if (useTrusted) a11y!! else ctx
        val wm = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hostWindowManager = wm

        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        edgeStripWidthPx = dp(16)
        handleWidthPx = dp(5)
        handleHeightPx = dp(68)
        panelWidthPx = minOf(dp(430), (screenW * 0.92f).toInt())
        panelMarginPx = dp(12)

        val layoutType = when {
            useTrusted -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        // Start narrow so only the entry affordance captures touches. When the user opens
        // the panel we expand to MATCH_PARENT so scrim + panel can be laid out across the
        // whole screen.
        val lp = WindowManager.LayoutParams(
            collapsedWindowWidth(),
            collapsedWindowHeight(),
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = collapsedWindowGravity()
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        params = lp

        val rootLayout = FrameLayout(ctx)
        root = rootLayout

        // Scrim — darkens game area when drawer is open. Alpha is bound to progress so it
        // fades in as the user drags / after the icon is tapped.
        val scrimView = View(ctx).apply {
            setBackgroundColor(0xFF000000.toInt()) // solid black, we drive alpha separately
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { animateTo(0f) }
        }
        scrim = scrimView
        rootLayout.addView(
            scrimView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val panelView = buildPanel()
        panelContainer = panelView
        panelView.visibility = View.GONE
        applyPanelProgress(panelView, 0f)
        rootLayout.addView(
            panelView,
            panelLayoutParams(),
        )

        val handle = HandleView(ctx).apply {
            isClickable = false
            isFocusable = false
        }
        handleView = handle
        rootLayout.addView(handle, handleLayoutParams())
        attachEdgeSwipeBehavior(rootLayout)
        startHandlePulse()

        runCatching { wm.addView(rootLayout, lp) }
            .onFailure { Log.w(TAG, "addView failed", it) }
    }

    fun hide() {
        val r = root ?: return
        val wm = hostWindowManager
        settleAnimator?.cancel()
        settleAnimator = null
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        soundTuner?.disable()
        soundTuner = null
        if (wm != null) {
            runCatching { wm.removeView(r) }
                .onFailure { Log.w(TAG, "removeView failed", it) }
        }
        root = null
        handleView = null
        scrim = null
        panelContainer = null
        params = null
        hostWindowManager = null
        expanded = false
        progress = 0f
    }

    /**
     * Re-adds this window to the WindowManager so it draws above any other
     * same-type overlay window that got added after it.
     *
     * Bug this fixes: the idle drawer (shown via ACTION_SHOW_DRAWER, before a
     * session exists) is already attached to the WindowManager when the user
     * taps START SESSION. NovaForegroundService.handleStart() then builds a
     * brand-new OverlayManager and calls its show(), which does its own
     * wm.addView(...) for the full-screen capture/render surface. WindowManager
     * stacks same-type windows in add order, so that freshly-added capture
     * overlay lands on top of the already-attached drawer window, silently
     * burying the handle/icon — the settings drawer becomes untouchable even
     * though its view tree is still alive underneath. Since dr.show() itself
     * is a no-op once root != null, nothing was pulling the drawer back on
     * top afterwards. updateViewLayout() (see OverlayManager.bringToFront())
     * does NOT reorder windows, only remove+re-add does, so that's what we do
     * here — same root View, so handle/panel state is untouched.
     */
    fun bringToFront() {
        val r = root ?: return
        val wm = hostWindowManager ?: return
        val lp = params ?: return
        if (!r.isAttachedToWindow) return
        runCatching {
            wm.removeViewImmediate(r)
            wm.addView(r, lp)
        }.onFailure { Log.w(TAG, "bringToFront failed", it) }
    }

    // --- panel ---------------------------------------------------------------------------

    /** Fires on every progress tick, no coalescing. For sliders with no expensive commit. */
    private fun SeekBar.onProgress(onChange: (progress: Int, fromUser: Boolean) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) = onChange(p, fromUser)
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Updates the display on every tick, but only commits (prefs write + reinit) on tap or
     * drag-release — never mid-drag — to avoid flooding SharedPreferences with ~60 writes/sec.
     */
    private fun <T> SeekBar.wireLiveDrag(
        label: String,
        toValue: (progress: Int) -> T,
        display: (T) -> Unit,
        commit: (T) -> Unit,
    ) {
        var dragging = false
        var pending: T = toValue(progress)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                val v = toValue(p)
                display(v)
                if (fromUser) {
                    pending = v
                    if (!dragging) {
                        commit(v)
                        Log.i(TAG, "live: $label tap → $v")
                        markParamsDirty()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                commit(pending)
                Log.i(TAG, "live: $label release → $pending")
                markParamsDirty()
            }
        })
    }

    private fun paintSessionRecordingButtons(buttons: List<Button>, selected: Int, locked: Boolean = false) {
        buttons.forEachIndexed { index, button ->
            val selectedNow = index == selected
            button.isEnabled = !locked
            val lockDim = if (locked) 0.45f else 1f
            button.alpha = (if (selectedNow) 1f else 0.58f) * lockDim
            button.setTextColor(if (selectedNow) COLOR_START_TEXT else COLOR_ON_SURFACE)
            button.background = GradientDrawable().apply {
                cornerRadius = dp(19).toFloat()
                setColor(if (selectedNow) COLOR_START_BG else COLOR_CHIP_BG)
                if (selectedNow) setStroke(dp(1), COLOR_START_STROKE)
            }
        }
    }

    private fun buildPanel(): View {
        val prefs = NovaPreferences(ctx)
        val initial = prefs.load()

        // Outer container holds the rounded panel plus a small floating margin on the right
        // so the panel does not touch the screen edge (gives it a card/sheet feel).
        val container = FrameLayout(ctx).apply {
            setPadding(0, panelMarginPx, dp(8), panelMarginPx)
            isClickable = true // absorb taps so they don't bubble to scrim
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = buildPanelBackground()
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(86))
        }

        // Header — brand mark + large bold title + close, Quick-Settings style.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        header.addView(brandMark())
        header.addView(
            TextView(ctx).apply {
                text = "LSFG // CONTROL CONSOLE"
                setTextColor(COLOR_ON_SURFACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { leftMargin = dp(12) }
                layoutParams = lp
            },
        )
        val closeBtn = ImageView(ctx).apply {
            setImageDrawable(crossDrawable())
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { animateTo(0f) }
        }
        header.addView(closeBtn)
        panel.addView(header)

        panel.addView(TextView(ctx).apply {
            text = "REALTIME RENDER / VULKAN / FRAME GENERATION"
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            alpha = 0.55f
            letterSpacing = 0.10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        })

        // Each settings category is a real page. Only one page is attached/visible
        // at a time, so the drawer no longer becomes one giant scrolling settings list.
        val enhancementPage = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(86))
        }
        val overlayPage = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(86))
        }
        val audioPage = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(86))
        }
        val sessionPage = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(86))
        }

        panel.addView(sectionSpacer(8))
        buildQuickControlsSection(panel)
        panel.addView(sectionSpacer(4))
        panel.addView(divider())
        panel.addView(sectionSpacer(14))

        // ---- Frame Generation (expanded by default so drawer shows content on first open) ----
        val frameGenSection = collapsibleSection(panel, "FRAME GENERATION", initiallyExpanded = true)

        frameGenSection.addView(switchRow(
            label = "NovaFrame Frame Gen",
            initial = initial.lsfgEnabled,
        ) {
            Log.i(TAG, "live: lsfgEnabled=$it")
            prefs.setNovaEnabled(it)
            // Invert for the bypass plumbing: on = frame gen active, off = bypass raw capture.
            bypassListener?.onBypassChanged(!it)
        })

        // Performance mode / HDR / FP16 only apply to the LSFG_3_1/3_1P shader
        // chain — the AI (ncnn) backend never reads them (see the matching
        // gate + comment in ParamsScreen.kt's Frame generation & pacing screen
        // and lsfg_render_loop.cpp's initRenderLoop). Hidden here too so the
        // in-game drawer never shows a control that does nothing for the
        // backend that's actually running.
        if (initial.framegenBackend == FramegenBackend.LSFG_DLL) {
            frameGenSection.addView(switchRow(
                label = "Performance mode",
                initial = initial.performanceMode,
            ) {
                Log.i(TAG, "live: performance=$it")
                prefs.setPerformance(it)
                markReinitDirty()
            })
            frameGenSection.addView(switchRow(
                label = "HDR mode",
                initial = initial.hdrMode,
            ) {
                Log.i(TAG, "live: hdr=$it")
                prefs.setHdr(it)
                markReinitDirty()
            })

            // FP16 frame-gen shaders — only show when the GPU supports shaderFloat16
            // and the FP16 SPIR-V cache has been populated (same gate the in-app
            // Params screen uses). Toggling requires a context re-init because
            // shader modules are bound at LSFG_3_X::initialize time, hence
            // markParamsDirty() — the reinit itself waits for "Apply".
            val fp16CacheDir = File(ctx.filesDir, "spirv").absolutePath
            val fp16Available = runCatching {
                NativeBridge.isFramegenFp16Supported(fp16CacheDir)
            }.getOrDefault(false)
            if (fp16Available) {
                frameGenSection.addView(switchRow(
                    label = "FP16 frame-gen shaders",
                    initial = initial.framegenFp16,
                ) {
                    Log.i(TAG, "live: framegenFp16=$it")
                    prefs.setFramegenFp16(it)
                    markReinitDirty()
                })
            }
        }

        // ---- Frame generation -----------------------------------------------
        val multiplierValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${initial.multiplier}×"
        }
        frameGenSection.addView(
            sliderRow(
                labelText = "Frame multiplier",
                valueView = multiplierValue,
            ),
        )
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 6
            progress = (initial.multiplier - 2).coerceIn(0, 6)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            // Tap commits immediately; drag coalesces the prefs write + reinit to
            // release, avoiding ~60×/sec SharedPreferences writes during a drag.
            wireLiveDrag(
                label = "multiplier",
                toValue = { p -> (p + 2).coerceIn(2, 8) },
                display = { m -> multiplierValue.text = "${m}×" },
                commit = { m -> prefs.setMultiplier(m); reinitDirty = true },
            )
        })

        frameGenSection.addView(sectionSpacer(10))

        // Flow scale only affects the LSFG_DLL shader chain's motion-estimation
        // pass (see resourcepool.cpp). The AI (ncnn) backend's RIFE/IFRNet nets
        // are single-pass with no separate low-res flow stage to downscale —
        // flowScale is accepted by NcnnInterpolator::interpolate()/
        // IfrnetInterpolator::interpolate() only for call-site compatibility
        // and is explicitly unused. Hidden here too, matching the perf/HDR/FP16
        // gate above, so the in-game drawer never shows a control that does
        // nothing for the backend that's actually running.
        if (initial.framegenBackend == FramegenBackend.LSFG_DLL) {
        val flowValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "%.2f".format(initial.flowScale)
        }
        frameGenSection.addView(
            sliderRow(
                // Keep the original 0.1.3 representation: raw 0.25..1.0 value.
                labelText = "Flow scale",
                valueView = flowValue,
            ),
        )
        val flowSeekBar = SeekBar(ctx).apply {
            max = 15
            progress = ((initial.flowScale - 0.25f) / 0.05f).toInt().coerceIn(0, 15)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "flowScale",
                toValue = { p -> (0.25f + p * 0.05f).coerceIn(0.25f, 1.0f) },
                display = { f ->
                    flowValue.text = "%.2f".format(f)
                },
                commit = { f -> prefs.setFlowScale(f); reinitDirty = true },
            )
        }
        frameGenSection.addView(flowSeekBar)

        frameGenSection.addView(sectionSpacer(6))

        } // framegenBackend == LSFG_DLL (flow scale)

        frameGenSection.addView(sectionSpacer(10))

        val renderResValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initial.renderResolutionScale * 100f).toInt()}%"
        }
        frameGenSection.addView(
            sliderRow(
                labelText = "Render resolution",
                valueView = renderResValue,
            ),
        )
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 20
            progress = (initial.renderResolutionScale * 20f).toInt().coerceIn(0, 20)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "renderResolutionScale",
                toValue = { p -> (p / 20f).coerceIn(0f, 1f) },
                display = { f -> renderResValue.text = "${(f * 100f).toInt()}%" },
                commit = { f -> prefs.setRenderResolutionScale(f); reinitDirty = true },
            )
        })

        // ---- Frame scheduling: independent detailed toggles ---------------------
        // Replaces the old two-value NO_WAIT/WAIT_GENERATION mode picker: each
        // knob is set separately instead of being bundled into a fixed mode, so
        // new combinations don't need a new mode + new code path.
        frameGenSection.addView(sectionSpacer(8))
        frameGenSection.addView(miniHeader("Frame scheduling"))

        frameGenSection.addView(switchRow(
            label = ctx.getString(R.string.drawer_sched_wait_for_busy),
            initial = initial.waitForBusyGeneration,
        ) {
            prefs.setWaitForBusyGeneration(it)
            queueNativeAction("waitForBusyGeneration") {
                runCatching { NativeBridge.setWaitForBusyGeneration(it) }
                    .onFailure { e -> NovaLog.w(TAG, "setWaitForBusyGeneration failed", e) }
            }
        })

        frameGenSection.addView(switchRow(
            label = ctx.getString(R.string.drawer_sched_allow_when_busy),
            initial = initial.allowGenerationWhenBusy,
        ) {
            prefs.setAllowGenerationWhenBusy(it)
            queueNativeAction("allowGenerationWhenBusy") {
                runCatching { NativeBridge.setAllowGenerationWhenBusy(it) }
                    .onFailure { e -> NovaLog.w(TAG, "setAllowGenerationWhenBusy failed", e) }
            }
        })

        frameGenSection.addView(switchRow(
            label = ctx.getString(R.string.drawer_sched_lossless_queue),
            initial = initial.losslessQueue,
        ) {
            prefs.setLosslessQueue(it)
            queueNativeAction("losslessQueue") {
                runCatching { NativeBridge.setLosslessQueue(it) }
                    .onFailure { e -> NovaLog.w(TAG, "setLosslessQueue failed", e) }
            }
        })

        // ---- Queue depth -----------------------------------------------------------
        val initialMaxQueued = prefs.getMaxQueuedRealFrames()
        val maxQueuedValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "$initialMaxQueued"
        }
        frameGenSection.addView(sliderRow(ctx.getString(R.string.drawer_queue_depth_label), maxQueuedValue))
        val maxQueuedSeek = SeekBar(ctx).apply {
            max = 7
            progress = (initialMaxQueued - 1).coerceIn(0, 7)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "maxQueuedRealFrames",
                toValue = { p -> (p + 1).coerceIn(1, 8) },
                display = { v -> maxQueuedValue.text = "$v" },
                commit = { v ->
                    prefs.setMaxQueuedRealFrames(v)
                    queueNativeAction("maxQueuedRealFrames") {
                        runCatching { NativeBridge.setMaxQueuedRealFrames(v) }
                    }
                },
            )
        }
        frameGenSection.addView(maxQueuedSeek)

        // ---- Generation deadline ---------------------------------------------------
        // Absolute capture-to-generation budget. A late generated frame is discarded
        // and the real frame is presented immediately, preventing old GEN frames from
        // causing visible judder when the GPU is overloaded.
        val initialDeadlineMs = prefs.getGenerationDeadlineMs()
        val deadlineValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = if (initialDeadlineMs == 0) "Off" else "${initialDeadlineMs} ms"
        }
        frameGenSection.addView(sliderRow("Generation deadline", deadlineValue))
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 50
            progress = initialDeadlineMs.coerceIn(0, 50)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "generationDeadlineMs",
                toValue = { p -> p.coerceIn(0, 50) },
                display = { v -> deadlineValue.text = if (v == 0) "Off" else "$v ms" },
                commit = { v ->
                    prefs.setGenerationDeadlineMs(v)
                    queueNativeAction("generationDeadlineMs") {
                        runCatching { NativeBridge.setGenerationDeadlineMs(v) }
                    }
                },
            )
        })

        // ---- BypassGen -------------------------------------------------------------
        // Automatic GPU-saving bypass is separate from the manual Bypass switch.
        val initialBypassGenDeadline = prefs.getBypassGenDeadlineMs()
        val bypassGenDeadlineValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = if (initialBypassGenDeadline == 0) "Off" else "${initialBypassGenDeadline} ms"
        }
        frameGenSection.addView(sliderRow("BypassGen deadline", bypassGenDeadlineValue))
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 50
            progress = initialBypassGenDeadline.coerceIn(0, 50)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "bypassGenDeadlineMs",
                toValue = { p -> p.coerceIn(0, 50) },
                display = { v -> bypassGenDeadlineValue.text = if (v == 0) "Off" else "$v ms" },
                commit = { v ->
                    prefs.setBypassGenDeadlineMs(v)
                    queueNativeAction("bypassGenDeadlineMs") {
                        runCatching { NativeBridge.setBypassGenDeadlineMs(v) }
                    }
                },
            )
        })

        val initialBypassGenResume = prefs.getBypassGenResumeDelayMs()
        val bypassGenResumeValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${initialBypassGenResume} ms"
        }
        frameGenSection.addView(sliderRow("BypassGen resume delay", bypassGenResumeValue))
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 500
            progress = initialBypassGenResume.coerceIn(0, 500)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            wireLiveDrag(
                label = "bypassGenResumeDelayMs",
                toValue = { p -> p.coerceIn(0, 500) },
                display = { v -> bypassGenResumeValue.text = "$v ms" },
                commit = { v ->
                    prefs.setBypassGenResumeDelayMs(v)
                    queueNativeAction("bypassGenResumeDelayMs") {
                        runCatching { NativeBridge.setBypassGenResumeDelayMs(v) }
                    }
                },
            )
        })

        frameGenSection.addView(switchRow(
            label = ctx.getString(R.string.drawer_autodisable_device_lost),
            initial = prefs.isAutoDisableOnDeviceLostEnabled(),
        ) {
            Log.i(TAG, "live: autoDisableOnDeviceLostEnabled=$it")
            prefs.setAutoDisableOnDeviceLostEnabled(it)
            queueNativeAction("autoDisableOnDeviceLostEnabled") {
                runCatching { NativeBridge.setAutoDisableOnDeviceLostEnabled(it) }
            }
        })

        // resumeFramegenAfterAutoDisable() has been wired end-to-end (native ->
        // JNI -> NativeBridge) since the device-lost auto-disable feature was
        // added, but nothing ever called it: once a session latches into
        // passthrough after a VK_ERROR_DEVICE_LOST, the only way back was a
        // full session restart. This button clears the latch in place.
        val resumeFramegenBtn = Button(ctx).apply {
            text = ctx.getString(R.string.drawer_resume_framegen)
            isAllCaps = false
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_CHIP_BG)
                cornerRadius = rad(3)
            }
            setPadding(0, dp(10), 0, dp(10))
            stateListAnimator = null
            setOnClickListener {
                val wasLatched = runCatching { NativeBridge.resumeFramegenAfterAutoDisable() }
                    .onFailure { e -> NovaLog.w(TAG, "resumeFramegenAfterAutoDisable failed", e) }
                    .getOrDefault(false)
                Log.i(TAG, "resumeFramegenAfterAutoDisable: wasLatched=$wasLatched")
                val original = text
                text = ctx.getString(
                    if (wasLatched) R.string.drawer_resume_framegen_resumed
                    else R.string.drawer_resume_framegen_not_latched,
                )
                postDelayed({ text = original }, 1500)
            }
        }
        frameGenSection.addView(resumeFramegenBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6); bottomMargin = dp(4) })

        // ---- IMAGE ENHANCEMENT / POST PROCESS ----------------------------------------
        val imageEnhanceSection = collapsibleSection(enhancementPage, "IMAGE ENHANCEMENT / POST PROCESS")
        val iePrefs = NovaPreferences(ctx)
        var ieEnabled = iePrefs.load().imageEnhancementEnabled
        var ieContrast = iePrefs.load().imageEnhancementContrast
        var ieSaturation = iePrefs.load().imageEnhancementSaturation

        imageEnhanceSection.addView(switchRow(
            label = "Enable live image enhancement",
            initial = ieEnabled,
        ) {
            ieEnabled = it
            iePrefs.setImageEnhancementEnabled(it)
            queueNativeAction("imageEnhancement") {
                NativeBridge.setImageEnhancement(ieEnabled, 1, ieContrast, ieSaturation)
            }
        })

        val ieContrastValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = String.format("%.2f×", ieContrast)
        }
        imageEnhanceSection.addView(sliderRow("Contrast", ieContrastValue))
        imageEnhanceSection.addView(SeekBar(ctx).apply {
            max = 100
            progress = (((ieContrast - 0.5f) / 1.0f) * 100f).toInt()
            progressDrawable = buildSeekTrack(); thumb = buildSeekThumb(); splitTrack = false
            wireLiveDrag("imageEnhancementContrast", { 0.5f + it / 100f }, { v -> ieContrastValue.text = String.format("%.2f×", v) }) { v ->
                ieContrast = v
                iePrefs.setImageEnhancementContrast(v)
                queueNativeAction("imageEnhancement") {
                    NativeBridge.setImageEnhancement(ieEnabled, 1, ieContrast, ieSaturation)
                }
            }
        })
        val ieSatValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = String.format("%.2f×", ieSaturation)
        }
        imageEnhanceSection.addView(sliderRow("Saturation", ieSatValue))
        imageEnhanceSection.addView(SeekBar(ctx).apply {
            max = 100
            progress = (ieSaturation * 50f).toInt().coerceIn(0, 100)
            progressDrawable = buildSeekTrack(); thumb = buildSeekThumb(); splitTrack = false
            wireLiveDrag("imageEnhancementSaturation", { it / 50f }, { v -> ieSatValue.text = String.format("%.2f×", v) }) { v ->
                ieSaturation = v
                iePrefs.setImageEnhancementSaturation(v)
                queueNativeAction("imageEnhancement") {
                    NativeBridge.setImageEnhancement(ieEnabled, 1, ieContrast, ieSaturation)
                }
            }
        })
        imageEnhanceSection.addView(TextView(ctx).apply {
            text = "CPU = full pixel enhancement. GPU/Vulkan = zero-copy hardware presentation/scaling; CPU readback is never forced."
            setTextColor(COLOR_ON_SURFACE); alpha = 0.60f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(4), 0, dp(8))
        })

        enhancementPage.addView(divider())

        // ---- UPSCALE FILTER -----------------------------------------------------------
        // Controls the filter vkCmdBlitImage uses whenever the framegen output
        // resolution differs from the swapchain/display resolution (e.g. the game
        // renders below native res). At 1:1 sizes this has no effect — the render
        // loop always takes the vkCmdCopyImage fast-path in that case.
        val upscaleSection = collapsibleSection(enhancementPage, "UPSCALE FILTER")
        val upPrefs = NovaPreferences(ctx)
        var upEnabled = upPrefs.load().upscaleEnabled
        var upFilter = upPrefs.load().upscaleFilter.ordinal

        upscaleSection.addView(switchRow(
            label = "Enable upscale",
            initial = upEnabled,
        ) {
            upEnabled = it
            upPrefs.setUpscaleEnabled(it)
            queueNativeAction("upscale") {
                NativeBridge.setUpscaleEnabled(upEnabled)
                NativeBridge.setUpscaleFilter(upFilter)
            }
        })

        val upscaleButtons = mutableListOf<Button>()
        fun paintUpscaleButtons() {
            upscaleButtons.forEachIndexed { index, b ->
                val selected = index == upFilter
                b.setTextColor(if (selected) COLOR_ON_SELECTED else COLOR_ON_SURFACE)
                (b.background as? GradientDrawable)?.setColor(if (selected) COLOR_PRIMARY else COLOR_CHIP_BG)
            }
        }
        val upscaleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        listOf("Nearest", "Bilinear").forEachIndexed { index, label ->
            val b = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                minHeight = dp(36)
                minWidth = 0
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(COLOR_CHIP_BG) }
                setOnClickListener {
                    upFilter = index
                    upPrefs.setUpscaleFilter(if (index == 0) com.firstt175.novaframe.prefs.UpscaleFilter.NEAREST else com.firstt175.novaframe.prefs.UpscaleFilter.BILINEAR)
                    queueNativeAction("upscale") {
                        NativeBridge.setUpscaleEnabled(upEnabled)
                        NativeBridge.setUpscaleFilter(upFilter)
                    }
                    paintUpscaleButtons()
                }
            }
            upscaleButtons += b
            upscaleRow.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
        upscaleSection.addView(miniHeader("Scaling filter"))
        upscaleSection.addView(upscaleRow)
        paintUpscaleButtons()
        upscaleSection.addView(TextView(ctx).apply {
            text = "Upscale can be disabled. When disabled, presentation remains valid but uses the cheapest nearest filter. When enabled, the selected filter is used when render resolution differs from the display."
            setTextColor(COLOR_ON_SURFACE); alpha = 0.60f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(4), 0, dp(8))
        })

        enhancementPage.addView(divider())

        // ---- PRESENT MODE --------------------------------------------------------------
        // Controls the swapchain's VkPresentModeKHR. No full context re-init needed —
        // the render loop tears down and recreates just the swapchain with the new mode
        // on the next present — but the actual native call is still queued and only
        // fires on APPLY CHANGES, same as every other setting, so it can never race a
        // heavier reinit that's in flight. Falls back to FIFO natively if the surface
        // doesn't advertise the chosen mode.
        val presentSection = collapsibleSection(overlayPage, "PRESENT MODE")
        val pmPrefs = NovaPreferences(ctx)
        val presentModeValues = listOf(
            com.firstt175.novaframe.prefs.PresentMode.IMMEDIATE,
            com.firstt175.novaframe.prefs.PresentMode.MAILBOX,
            com.firstt175.novaframe.prefs.PresentMode.FIFO,
        )
        var presentModeIndex = presentModeValues.indexOf(pmPrefs.load().presentMode).let { if (it < 0) 1 else it }

        val presentButtons = mutableListOf<Button>()
        fun paintPresentButtons() {
            presentButtons.forEachIndexed { index, b ->
                val selected = index == presentModeIndex
                b.setTextColor(if (selected) COLOR_ON_SELECTED else COLOR_ON_SURFACE)
                (b.background as? GradientDrawable)?.setColor(if (selected) COLOR_PRIMARY else COLOR_CHIP_BG)
            }
        }
        val presentRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        listOf("Immediate", "Mailbox", "FIFO").forEachIndexed { index, label ->
            val b = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                minHeight = dp(36)
                minWidth = 0
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(COLOR_CHIP_BG) }
                setOnClickListener {
                    presentModeIndex = index
                    val chosen = presentModeValues[index]
                    pmPrefs.setPresentMode(chosen)
                    queueNativeAction("presentMode") { NativeBridge.setPresentMode(chosen.vkValue) }
                    paintPresentButtons()
                }
            }
            presentButtons += b
            presentRow.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
        presentSection.addView(miniHeader("Swapchain present mode"))
        presentSection.addView(presentRow)
        paintPresentButtons()
        presentSection.addView(TextView(ctx).apply {
            text = "Immediate = lowest latency, may tear. Mailbox = low-latency triple buffering, no tearing (default). FIFO = vsync'd, no tearing, highest latency."
            setTextColor(COLOR_ON_SURFACE); alpha = 0.60f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(4), 0, dp(8))
        })

        // ---- HUD (readout switch + separate frame graph switch) ---------------------
        val hudSection = collapsibleSection(overlayPage, "HUD & OVERLAY")
        hudSection.addView(switchRow(
            label = "HUD (CPU / GPU / RAM / FPS)",
            initial = initial.hudEnabled,
        ) {
            prefs.setHudEnabled(it)
            hudListener?.onHudChanged(it)
        })
        hudSection.addView(switchRow(
            label = "Frame graph",
            initial = initial.frameGraphEnabled,
        ) {
            prefs.setFrameGraphEnabled(it)
            frameGraphListener?.onFrameGraphChanged(it)
        })
        hudSection.addView(switchRow(
            label = "Unlock HUD position",
            initial = initial.hudPositionUnlocked,
        ) {
            prefs.setHudPositionUnlocked(it)
            hudPositionListener?.onHudPositionUnlocked(it)
        })
        hudSection.addView(miniHeader("Memory"))
        val ramResultText = TextView(ctx).apply {
            setTextColor(COLOR_ON_SURFACE)
            alpha = 0.60f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = "ปิดแอปพื้นหลังที่ไม่ได้ใช้เพื่อคืน RAM ให้เกม (ไม่กระทบเกมที่กำลังเล่นอยู่)"
            setPadding(0, dp(2), 0, dp(6))
        }
        hudSection.addView(ramResultText)
        ramCleanResultView = ramResultText
        val cleanRamBtn = Button(ctx).apply {
            text = "CLEAN RAM"
            isAllCaps = false
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_CHIP_BG)
                cornerRadius = rad(3)
            }
            setPadding(0, dp(10), 0, dp(10))
            stateListAnimator = null
            setOnClickListener {
                isEnabled = false
                ramResultText.text = "กำลังล้าง RAM…"
                ramCleanListener?.onCleanRam()
            }
        }
        ramCleanButtonView = cleanRamBtn
        hudSection.addView(cleanRamBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })

        hudSection.addView(miniHeader("Diagnostics"))
        val viewLogBtn = Button(ctx).apply {
            text = "VIEW LOG"
            isAllCaps = false
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_CHIP_BG)
                cornerRadius = rad(3)
            }
            setPadding(0, dp(10), 0, dp(10))
            stateListAnimator = null
            setOnClickListener { showLogViewer() }
        }
        hudSection.addView(viewLogBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })

        buildSoundTunerSection(audioPage, prefs)

        val startBtn = Button(ctx).apply {
            text = "START SESSION"
            setTextColor(COLOR_START_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_START_BG)
                cornerRadius = rad(3)
                setStroke(dp(1), COLOR_START_STROKE)
            }
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener { restartListener?.onRestartSession() }
        }
        val startLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
        sessionPage.addView(startBtn, startLp)

        val stopBtn = Button(ctx).apply {
            text = "END SESSION"
            setTextColor(COLOR_STOP_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_STOP_BG)
                cornerRadius = rad(3)
                setStroke(dp(1), COLOR_STOP_STROKE)
            }
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener { requestStop() }
        }
        val stopLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
        sessionPage.addView(stopBtn, stopLp)

        // -------------------------------------------------------------------------
        // TAB NAVIGATION
        // Keep the categories as independent pages. The content itself is not
        // duplicated; we simply switch which page is visible inside the same
        // ScrollView. This keeps the drawer lightweight while making each setting
        // group feel like its own screen.
        // -------------------------------------------------------------------------
        val pages = listOf(panel, enhancementPage, overlayPage, audioPage, sessionPage)
        val tabLabels = listOf("FRAME GEN", "ENHANCE", "OVERLAY", "AUDIO", "SESSION")
        val tabButtons = mutableListOf<Button>()

        val pageHost = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        pages.forEachIndexed { index, page ->
            page.visibility = if (index == 0) View.VISIBLE else View.GONE
            pageHost.addView(page, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        fun selectTab(index: Int) {
            pages.forEachIndexed { i, page ->
                page.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            tabButtons.forEachIndexed { i, button ->
                val selected = i == index
                button.setTextColor(if (selected) COLOR_ON_SELECTED else COLOR_ON_SURFACE)
                (button.background as? GradientDrawable)?.setColor(
                    if (selected) COLOR_PRIMARY else COLOR_CHIP_BG
                )
                button.alpha = if (selected) 1f else 0.72f
            }
            scroll.scrollTo(0, 0)
        }

        val tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PANEL_BG)
            }
        }
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabLabels.forEachIndexed { index, label ->
            val button = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                minHeight = dp(36)
                minWidth = dp(76)
                setPadding(dp(10), 0, dp(10), 0)
                stateListAnimator = null
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(if (index == 0) COLOR_PRIMARY else COLOR_CHIP_BG)
                }
                setTextColor(if (index == 0) COLOR_ON_SELECTED else COLOR_ON_SURFACE)
                alpha = if (index == 0) 1f else 0.72f
                setOnClickListener { selectTab(index) }
            }
            tabButtons += button
            tabRow.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36),
            ).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
        tabScroll.addView(tabRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        scroll.addView(pageHost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        container.addView(tabScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(48),
            Gravity.TOP,
        ))
        container.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            topMargin = dp(48)
        })

        container.addView(buildApplyBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))

        return container
    }

    /**
     * Floating "Apply" bar pinned to the bottom of the drawer, above the scrollable
     * content. EVERY control that touches the native session — frame-gen params
     * (multiplier, flow scale, render resolution, performance/HDR/FP16), frame
     * scheduling, image enhancement, upscale filter, present mode, frame transfer
     * mode, ... — is batched instead of firing immediately: the reinit-requiring
     * subset just flips [reinitDirty], everything else queues its native call into
     * [pendingNativeActions] (see markReinitDirty()/queueNativeAction()). This bar is
     * the single place anything actually reaches NativeBridge, so multiple tweaks in
     * a row cost one atomic apply instead of a pile of overlapping live calls that
     * could race a reinit and crash the app.
     *
     * On tap: the heavier reinit (if needed) runs first and is allowed to fully
     * tear down/recreate the native context, THEN the queued lightweight setters
     * run — never before or concurrently with the reinit, since those would be
     * lost (or race) if the context is being replaced underneath them.
     *
     * Disabled/dim until something changes; tapping it applies everything at once
     * and re-dims itself.
     */
    private fun buildApplyBar(): View {
        val bar = FrameLayout(ctx).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PANEL_BG)
            }
            alpha = 0.5f
        }
        val btn = Button(ctx).apply {
            text = "APPLY CHANGES"
            isEnabled = false
            setTextColor(COLOR_ON_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PRIMARY)
                cornerRadius = rad(3)
            }
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener {
                if (!paramsDirty) return@setOnClickListener
                val needsReinit = reinitDirty
                val actions = pendingNativeActions.values.toList()
                Log.i(TAG, "Apply changes tapped — reinit=$needsReinit, queuedActions=${actions.size}")
                reinitDirty = false
                pendingNativeActions.clear()
                clearParamsDirty()
                // Heavier reinit first — it destroys and recreates the native Vulkan
                // context, so it must fully finish before any of the lighter runtime
                // setters below touch that context, or they'd be lost or race the
                // teardown/recreate and risk the exact overlap/crash this queue exists
                // to prevent.
                if (needsReinit) {
                    liveParamsListener?.onParamsChanged()
                }
                // Each queued action gets its own fallback: a failure here must
                // never abort the batch (skipping every setter queued after it)
                // and must never crash the click handler — this runs on the main
                // thread. Individual settings still log so a failure is visible.
                actions.forEach { action ->
                    runCatching { action() }
                        .onFailure { e -> NovaLog.w(TAG, "queued Apply action failed", e) }
                }
            }
        }
        bar.addView(btn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        applyBar = bar
        applyButton = btn
        return bar
    }

    // --- drawer state / animation -------------------------------------------------------

    private fun attachEdgeSwipeBehavior(strip: View) {
        val slop = dp(8)
        strip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A bare touch on the strip is not enough to expand the window — we wait
                    // until ACTION_MOVE crosses the slop threshold. Keeping the window at
                    // `edgeStripWidthPx` while only a tap is in flight avoids forcing the
                    // compositor to blend a full-screen overlay on top of the running game.
                    dragStartX = ev.rawX
                    dragStartProgress = progress
                    dragActive = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaPx = dragDeltaTowardCenter(ev)
                    if (!dragActive && kotlin.math.abs(deltaPx) > slop) {
                        dragActive = true
                        settleAnimator?.cancel()
                        // Only now that the user is really dragging do we expand the window
                        // so the panel can be laid out across the screen.
                        if (!expanded) expandWindow()
                    }
                    if (dragActive) {
                        val delta = deltaPx / panelTravelPx().toFloat()
                        setProgress((dragStartProgress + delta).coerceIn(0f, 1f))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragActive) {
                        val target = if (progress > 0.45f) 1f else 0f
                        animateTo(target)
                    } else if (!expanded && progress == 0f) {
                        // Touch without drag on the collapsed strip — keep window narrow,
                        // no state change.
                        collapseWindowIfNeeded()
                    }
                    dragActive = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setProgress(p: Float) {
        val wasZero = progress == 0f
        progress = p
        val panelView = panelContainer ?: return
        panelView.visibility = View.VISIBLE
        applyPanelProgress(panelView, p)
        val scrimView = scrim
        if (scrimView != null) {
            scrimView.visibility = if (p > 0f) View.VISIBLE else View.GONE
            scrimView.alpha = p * 0.45f
        }
        // Keep the drawer icon/handle visible at all times, including while
        // the settings panel is expanded. It is the persistent entry point
        // for the in-game settings drawer.
        handleView?.alpha = 1f
        expanded = p >= 0.99f
        // Stop the pulse as soon as the drawer starts opening — no point burning invalidate
        // cycles on a handle the user has already grabbed. Resume when fully collapsed.
        {
            if (p > 0f && wasZero) {
                stopHandlePulse()
            } else if (p == 0f && !wasZero) {
                startHandlePulse()
            }
        }
    }

    private fun animateTo(target: Float) {
        // Snap instantly instead of animating the slide — the panel should pop
        // straight to its resting position the moment the drag/tap resolves,
        // no ValueAnimator/interpolator easing in between.
        settleAnimator?.cancel()
        settleAnimator = null
        setProgress(target)
        if (target == 0f) {
            collapseWindowIfNeeded()
        }
    }

    private fun expandWindow() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT) return
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.x = 0
        lp.y = 0
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "expandWindow failed", it) }
    }

    private fun collapseWindowIfNeeded() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        val collapsedW = collapsedWindowWidth()
        val collapsedH = collapsedWindowHeight()
        val needsResize = lp.width != collapsedW || lp.height != collapsedH
        if (!needsResize) return
        lp.width = collapsedW
        lp.height = collapsedH
        lp.x = 0
        lp.y = 0
        scrim?.visibility = View.GONE
        panelContainer?.visibility = View.GONE
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "collapseWindowIfNeeded failed", it) }
    }

    /** Re-reads display metrics and reapplies drawer layout after rotation/configuration changes. */
    fun onDisplayConfigurationChanged() {
        val r = root ?: return
        r.post {
            relayoutForCurrentDisplay()
        }
    }

    private fun relayoutForCurrentDisplay() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return

        // Stop any in-flight slide animation — the dimensions it was animating
        // toward are about to be invalidated.
        settleAnimator?.cancel()
        settleAnimator = null

        val dm = ctx.resources.displayMetrics
        val newW = dm.widthPixels
        val newH = dm.heightPixels
        if (newW <= 0 || newH <= 0) return
        screenW = newW
        screenH = newH

        // Recompute size-dependent dimensions. dp(...) is density-relative so
        // it's stable across rotations, but the screen-percentage clamps on
        // panel size are not.
        panelWidthPx = minOf(dp(430), (screenW * 0.92f).toInt())

        // Rebuild panel layout params (size + gravity) on the new orientation.
        panelContainer?.let { pv ->
            pv.layoutParams = panelLayoutParams()
            applyPanelProgress(pv, progress)
        }
        // Same for the drawer handle pill — its preferred size depends on
        // whether the active edge is vertical or horizontal.
        handleView?.layoutParams = handleLayoutParams()

        // Reset the WindowManager params to match the current collapsed/expanded state.
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT) {
            // Expanded: nothing to resize, the panel itself was rebuilt above.
        } else {
            lp.width = collapsedWindowWidth()
            lp.height = collapsedWindowHeight()
            lp.gravity = collapsedWindowGravity()
            lp.x = 0
            lp.y = 0
        }
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "relayoutForCurrentDisplay updateViewLayout failed", it) }
        Log.i(TAG, "Drawer relayout for ${newW}x${newH}")
    }

    private fun startHandlePulse() {
        // Was an infinite ValueAnimator "breathing" the handle glow to make it
        // findable while collapsed — decorative only, but it kept the compositor
        // re-blending an overlay layer every ~16ms for the entire time the drawer
        // sits collapsed (i.e. most of a gaming session). Set once instead of
        // animating forever: zero ongoing CPU/GPU cost, and a static minimal
        // handle reads cleaner than a pulsing one anyway.
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        handleView?.setGlow(1f)
    }

    private fun stopHandlePulse() {
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        handleView?.setGlow(1f)
    }

    // --- small helpers & drawables ------------------------------------------------------

    private fun brandMark(): View {
        val size = dp(26)
        val radius = dp(7).toFloat()
        // Wrap the bitmap icon in a RoundedBitmapDrawable-equivalent (manual clip via
        // ShapeAppearance) by drawing it into a GradientDrawable with a bitmap shader.
        val mark = ImageView(ctx).apply {
            setImageResource(com.firstt175.novaframe.R.drawable.nova_app_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        return mark
    }

    private fun crossDrawable(): android.graphics.drawable.Drawable {
        // Simple X drawn via a custom drawable (no vector asset needed).
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_ON_SURFACE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val inset = dp(5).toFloat()
                canvas.drawLine(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, paint)
                canvas.drawLine(b.right - inset, b.top + inset, b.left + inset, b.bottom - inset, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /** Corner radius: unchanged in Normal style, much rounder (iOS-like) in Liquid Glass. */
    private fun rad(n: Int): Float = if (glassMode) dp((n * 4).coerceIn(12, 22)).toFloat() else dp(n).toFloat()

    /**
     * iOS 27 Liquid Glass panel, built from static drawables only (no blur, so it adds no
     * per-frame GPU work on top of frame generation):
     *  - rim      : lit gradient edge (bright top-left / bottom-right) = the refraction edge;
     *  - body     : tint scrim whose strength follows the Clear <-> Tinted slider. It keeps a
     *               higher floor than the in-app cards because this panel floats over a game
     *               of unknown brightness and must always stay readable;
     *  - lift     : faint white gradient so the panel reads as glass, not a flat sheet;
     *  - specular : soft highlight fading down from the top edge, strongest on Clear.
     */
    private fun buildGlassPanelBackground(): android.graphics.drawable.Drawable {
        val t = glassIntensity.coerceIn(0f, 1f)
        val radius = dp(28).toFloat()
        val rimBright = if (glassDark) mixF(0.72f, 0.40f, t) else 1f
        val rimDim = if (glassDark) 0.06f else 0.30f
        val rim = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                argb(rimBright, 0xFFFFFF),
                argb(rimDim, 0xFFFFFF),
                argb(rimDim * 0.6f, 0xFFFFFF),
                argb(rimBright * 0.55f, 0xFFFFFF),
            ),
        ).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = radius }
        val body = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (glassDark) intArrayOf(
                argb(mixF(0.46f, 0.90f, t), 0x23253A),
                argb(mixF(0.40f, 0.86f, t), 0x13152A),
            ) else intArrayOf(
                argb(mixF(0.62f, 0.94f, t), 0xFFFFFF),
                argb(mixF(0.54f, 0.90f, t), 0xEEF1FA),
            ),
        ).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = radius - dp(1) }
        val lift = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(argb(if (glassDark) 0.10f else 0.20f, 0xFFFFFF), argb(0.02f, 0xFFFFFF)),
        ).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = radius - dp(1) }
        val specular = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(argb(if (glassDark) mixF(0.30f, 0.10f, t) else mixF(0.60f, 0.25f, t), 0xFFFFFF), 0x00FFFFFF),
        ).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = radius - dp(1) }
        return LayerDrawable(arrayOf(rim, body, lift, specular)).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
            setLayerInset(2, dp(1), dp(1), dp(1), dp(1))
            // Specular fades out toward the bottom, so it reads as a top-edge highlight.
            setLayerInset(3, dp(1), dp(1), dp(1), dp(1))
        }
    }

    private fun buildPanelBackground(): android.graphics.drawable.Drawable {
        if (glassMode) return buildGlassPanelBackground()
        // Flat, squared-off console shell. Keep the shadow/stroke subtle so the
        // panel reads like a dedicated in-game control console rather than a
        // Material quick-settings card.
        val shadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x55000000.toInt())
            cornerRadius = rad(4)
        }
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(COLOR_PANEL_BG)
            cornerRadius = rad(4)
            setStroke(dp(1), COLOR_PANEL_STROKE)
        }
        val layers = LayerDrawable(arrayOf(shadow, body))
        layers.setLayerInset(0, 0, 0, 0, 0)
        layers.setLayerInset(1, 0, 0, 0, dp(2))
        return layers
    }

    private fun buildSeekTrack(): android.graphics.drawable.Drawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(COLOR_TRACK_BG)
            cornerRadius = rad(3)
        }
        val bgInset = android.graphics.drawable.InsetDrawable(bg, 0, dp(10), 0, dp(10))
        val progress = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(COLOR_ACCENT_DEEP, COLOR_PRIMARY),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rad(3)
        }
        val progressScale = android.graphics.drawable.ScaleDrawable(
            progress, Gravity.START or Gravity.CENTER_VERTICAL, 1f, -1f,
        )
        progressScale.level = 0
        val progressInset = android.graphics.drawable.InsetDrawable(progressScale, 0, dp(10), 0, dp(10))
        val layers = LayerDrawable(arrayOf(bgInset, progressInset))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        return layers
    }

    private fun buildSeekThumb(): android.graphics.drawable.Drawable =
        LiquidThumbDrawable(ctx.resources.displayMetrics.density, COLOR_PRIMARY)

    /**
     * Builds a collapsible section with a tappable header (brand-primary eyebrow label + chevron)
     * and a body [LinearLayout] that callers populate. Returns the body so the rest of the panel
     * construction can `body.addView(...)` the section's children.
     *
     * The whole wrapper (header + body) is the view that gets added to the scrolling panel, so the
     * section dividers/spacers between sections remain the caller's responsibility — same visual
     * language as before, just with toggle affordances.
     */
    /**
     * ROG Ally Command Center–style quick panel: always-visible brightness + volume
     * sliders at the very top of the drawer, above the collapsible detail sections.
     * Brightness uses a per-window override (no WRITE_SETTINGS permission needed);
     * volume drives the media stream via AudioManager.
     */
    private fun buildQuickControlsSection(panel: LinearLayout) {
        panel.addView(miniHeader("QUICK CONTROLS"))
        panel.addView(sectionSpacer(6))

        // --- Brightness -----------------------------------------------------------
        val initialBrightness = runCatching {
            android.provider.Settings.System.getInt(
                ctx.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(128).coerceIn(0, 255)

        val brightnessValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initialBrightness * 100 / 255)}%"
        }
        panel.addView(sliderRow(labelText = "Screen brightness", valueView = brightnessValue))
        panel.addView(SeekBar(ctx).apply {
            max = 100
            progress = (initialBrightness * 100 / 255).coerceIn(1, 100)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                val pct = p.coerceIn(1, 100)
                brightnessValue.text = "$pct%"
                if (!fromUser) return@onProgress
                val lp = params
                val r = root
                val wm = hostWindowManager
                if (lp != null && r != null && wm != null && r.isAttachedToWindow) {
                    lp.screenBrightness = pct / 100f
                    runCatching { wm.updateViewLayout(r, lp) }
                        .onFailure { Log.w(TAG, "brightness updateViewLayout failed", it) }
                }
            }
        })

        panel.addView(sectionSpacer(10))

        // --- Volume -----------------------------------------------------------------
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = runCatching {
            audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        }.getOrDefault(15).coerceAtLeast(1)
        val initialVol = runCatching {
            audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        }.getOrDefault(0).coerceIn(0, maxVol)

        val volumeValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initialVol * 100 / maxVol)}%"
        }
        panel.addView(sliderRow(labelText = "Media volume", valueView = volumeValue))
        panel.addView(SeekBar(ctx).apply {
            max = maxVol
            progress = initialVol
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                volumeValue.text = "${(p * 100 / maxVol)}%"
                if (!fromUser) return@onProgress
                runCatching {
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, p, 0)
                }.onFailure { Log.w(TAG, "setStreamVolume failed", it) }
            }
        })
    }

    /**
     * "SOUND TUNER" section: global (session-0) Equalizer/BassBoost/Virtualizer/
     * LoudnessEnhancer, so it colors whatever the captured game is currently playing
     * without needing that app's own audio session. See [SoundTunerController] for the
     * attach mechanism and why it degrades gracefully instead of crashing on devices
     * that refuse session-0 effects.
     *
     * Bass boost / virtualizer / loudness sliders are shown as 0-100%, mapped onto the
     * effect's native units (0-1000 strength, 0-2000 millibel gain) — same "%" language
     * as the brightness/volume quick controls above. The per-band equalizer (if the
     * device's Equalizer engine is queryable) shows each band by its actual center
     * frequency and commits directly in millibels, matching [SoundTunerController]'s units.
     */
    private fun buildSoundTunerSection(panel: LinearLayout, prefs: NovaPreferences) {
        val section = collapsibleSection(panel, "SOUND TUNER")
        val bandInfo = SoundTunerController.queryBandInfo()
        val savedBands = prefs.getSoundTunerEqBands()

        fun applyAllToController(controller: SoundTunerController) {
            controller.setBassBoostStrength(prefs.getSoundTunerBass())
            controller.setVirtualizerStrength(prefs.getSoundTunerVirtualizer())
            controller.setLoudnessGain(prefs.getSoundTunerLoudness())
            if (bandInfo != null && savedBands.size == bandInfo.bandCount) {
                savedBands.forEachIndexed { i, lvl -> controller.setEqBandLevel(i, lvl) }
            }
        }

        // Re-attach immediately if the tuner was left on from a previous session,
        // same as how frame-gen / FPS counter restore their prior on/off state.
        if (prefs.isSoundTunerEnabled()) {
            val controller = soundTuner ?: SoundTunerController().also { soundTuner = it }
            controller.enable()
            applyAllToController(controller)
        }

        section.addView(switchRow(
            label = "Enable sound tuner",
            initial = prefs.isSoundTunerEnabled(),
        ) { enabled ->
            Log.i(TAG, "live: soundTunerEnabled=$enabled")
            prefs.setSoundTunerEnabled(enabled)
            if (enabled) {
                val controller = soundTuner ?: SoundTunerController().also { soundTuner = it }
                controller.enable()
                applyAllToController(controller)
            } else {
                soundTuner?.disable()
                soundTuner = null
            }
        })

        // ---- Presets: one-tap fill for the sliders below. Declared as lateinit
        // because the row is placed above the sliders it drives (same forward-
        // reference pattern as the frame-pacing preset row above), but every
        // reference below only actually runs from a button tap, by which point
        // buildSoundTunerSection has already finished assigning them.
        lateinit var bassSeek: SeekBar
        lateinit var virtSeek: SeekBar
        lateinit var loudSeek: SeekBar
        lateinit var bassValue: TextView
        lateinit var virtValue: TextView
        lateinit var loudValue: TextView
        val bandSeeks = mutableListOf<SeekBar>()
        val bandValues = mutableListOf<TextView>()

        fun applyPreset(preset: SoundTunerController.Preset) {
            Log.i(TAG, "live: soundTuner preset=${preset.label}")
            prefs.setSoundTunerBass(preset.bass)
            prefs.setSoundTunerVirtualizer(preset.virtualizer)
            prefs.setSoundTunerLoudness(preset.loudness)
            soundTuner?.setBassBoostStrength(preset.bass)
            soundTuner?.setVirtualizerStrength(preset.virtualizer)
            soundTuner?.setLoudnessGain(preset.loudness)

            bassSeek.progress = preset.bass / 10
            virtSeek.progress = preset.virtualizer / 10
            loudSeek.progress = preset.loudness / 20
            bassValue.text = "${preset.bass / 10}%"
            virtValue.text = "${preset.virtualizer / 10}%"
            loudValue.text = "${preset.loudness / 20}%"

            if (bandInfo != null && bandInfo.bandCount > 0 && bandSeeks.size == bandInfo.bandCount) {
                val range = bandInfo.levelRange
                val span = (range[1] - range[0]).coerceAtLeast(1)
                val newBands = bandInfo.centerFreqsHz.map { freq ->
                    SoundTunerController.presetLevelForBand(preset.eqShapeAt(freq), range)
                }
                prefs.setSoundTunerEqBands(newBands)
                newBands.forEachIndexed { i, level ->
                    soundTuner?.setEqBandLevel(i, level)
                    bandSeeks[i].progress = (level - range[0]).coerceIn(0, span)
                    bandValues[i].text = String.format("%.1f dB", level / 100f)
                }
            }
        }

        section.addView(miniHeader("Presets"))
        val soundPresetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        SoundTunerController.PRESETS.forEachIndexed { index, preset ->
            val button = Button(ctx).apply {
                text = preset.label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = rad(3)
                    setColor(COLOR_CHIP_BG)
                }
                setPadding(dp(3), dp(4), dp(3), dp(4))
                stateListAnimator = null
                setOnClickListener { applyPreset(preset) }
            }
            soundPresetRow.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = if (index == 0) 0 else dp(4)
            })
        }
        section.addView(soundPresetRow)

        section.addView(miniHeader("Bass & stereo"))

        bassValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${prefs.getSoundTunerBass() / 10}%"
        }
        section.addView(sliderRow(labelText = "Bass boost", valueView = bassValue))
        bassSeek = SeekBar(ctx).apply {
            max = 100
            progress = prefs.getSoundTunerBass() / 10
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                bassValue.text = "$p%"
                if (!fromUser) return@onProgress
                val strength = (p * 10).coerceIn(0, 1000)
                prefs.setSoundTunerBass(strength)
                soundTuner?.setBassBoostStrength(strength)
            }
        }
        section.addView(bassSeek)

        virtValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${prefs.getSoundTunerVirtualizer() / 10}%"
        }
        section.addView(sliderRow(labelText = "Virtualizer (surround)", valueView = virtValue))
        virtSeek = SeekBar(ctx).apply {
            max = 100
            progress = prefs.getSoundTunerVirtualizer() / 10
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                virtValue.text = "$p%"
                if (!fromUser) return@onProgress
                val strength = (p * 10).coerceIn(0, 1000)
                prefs.setSoundTunerVirtualizer(strength)
                soundTuner?.setVirtualizerStrength(strength)
            }
        }
        section.addView(virtSeek)

        loudValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${prefs.getSoundTunerLoudness() / 20}%"
        }
        section.addView(sliderRow(labelText = "Loudness boost", valueView = loudValue))
        loudSeek = SeekBar(ctx).apply {
            max = 100
            progress = prefs.getSoundTunerLoudness() / 20
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            onProgress { p, fromUser ->
                loudValue.text = "$p%"
                if (!fromUser) return@onProgress
                val gain = (p * 20).coerceIn(0, 2000)
                prefs.setSoundTunerLoudness(gain)
                soundTuner?.setLoudnessGain(gain)
            }
        }
        section.addView(loudSeek)

        // Per-band EQ — only shown if this device's Equalizer engine answered the
        // metadata query; some OEMs refuse session-0 Equalizer entirely.
        if (bandInfo != null && bandInfo.bandCount > 0) {
            section.addView(miniHeader("Equalizer"))
            val range = bandInfo.levelRange
            val span = (range[1] - range[0]).coerceAtLeast(1)
            val workingBands = (savedBands.takeIf { it.size == bandInfo.bandCount }
                ?: List(bandInfo.bandCount) { 0 }).toMutableList()

            for (band in 0 until bandInfo.bandCount) {
                val freqHz = bandInfo.centerFreqsHz[band]
                val freqLabel = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"
                val bandValue = TextView(ctx).apply {
                    setTextColor(COLOR_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                    text = String.format("%.1f dB", workingBands[band] / 100f)
                }
                section.addView(sliderRow(labelText = freqLabel, valueView = bandValue))
                val bandSeek = SeekBar(ctx).apply {
                    max = span
                    progress = (workingBands[band] - range[0]).coerceIn(0, span)
                    progressDrawable = buildSeekTrack()
                    thumb = buildSeekThumb()
                    splitTrack = false
                    onProgress { p, fromUser ->
                        val level = p + range[0]
                        bandValue.text = String.format("%.1f dB", level / 100f)
                        if (!fromUser) return@onProgress
                        workingBands[band] = level
                        prefs.setSoundTunerEqBands(workingBands)
                        soundTuner?.setEqBandLevel(band, level)
                    }
                }
                section.addView(bandSeek)
                bandSeeks.add(bandSeek)
                bandValues.add(bandValue)
            }
        }
    }

    private fun collapsibleSection(
        parent: LinearLayout,
        title: String,
        initiallyExpanded: Boolean = false,
    ): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val chevron = ImageView(ctx).apply {
            setImageDrawable(chevronDrawable())
            val sz = dp(14)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            rotation = if (initiallyExpanded) 180f else 0f
        }

        val label = TextView(ctx).apply {
            text = title
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.15f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(8), dp(9))
            isClickable = true
            isFocusable = true
            // Subtle ripple on tap to hint at interactivity; stays on-brand because the body will
            // just fade-slide via visibility toggle.
            val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            addView(label)
            addView(chevron)
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }

        header.setOnClickListener {
            val nowVisible = body.visibility != View.VISIBLE
            body.visibility = if (nowVisible) View.VISIBLE else View.GONE
            // Snap instantly — no rotate animation, matches the drawer's
            // instant open/close (see animateTo()).
            chevron.rotation = if (nowVisible) 180f else 0f
        }

        wrapper.addView(header)
        wrapper.addView(body)
        parent.addView(wrapper)
        return body
    }

    private fun chevronDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_PRIMARY
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
            }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val inset = dp(3).toFloat()
                val midX = (b.left + b.right) / 2f
                canvas.drawLine(b.left + inset, b.top + inset + dp(1), midX, b.bottom - inset - dp(1), paint)
                canvas.drawLine(b.right - inset, b.top + inset + dp(1), midX, b.bottom - inset - dp(1), paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(COLOR_DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        )
    }

    private fun sectionSpacer(height: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(height),
        )
    }

    /**
     * Small uppercase caption used inside a section to group related chip rows.
     * Styled to match a Quick-Settings-style group label (e.g. "OTHER",
     * "CONTROLLER") — dim grey, wide letter-spacing, extra top margin so it
     * visually separates from the group above it.
     */
    private fun miniHeader(text: String) = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextColor(COLOR_ON_SURFACE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        alpha = 0.55f
        letterSpacing = 0.14f
        typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun sliderRow(labelText: String, valueView: TextView): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        row.addView(
            TextView(ctx).apply {
                text = labelText
                setTextColor(COLOR_ON_SURFACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val chip = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_CHIP_BG)
                cornerRadius = dp(2).toFloat()
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            addView(valueView)
        }
        row.addView(chip)
        return row
    }

    /**
     * A toggle row plus its own bottom hairline divider, so a stack of
     * switchRow()s reads as a list of separated settings lines (like the
     * reference Quick Settings panel) instead of one dense block.
     */
    private fun switchRow(
        label: String,
        initial: Boolean,
        swRef: ((CompoundButton) -> Unit)? = null,
        onChange: (Boolean) -> Unit,
    ): View {
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(6), dp(9))
        }
        val lbl = TextView(ctx).apply {
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = LiquidSwitch(ctx, COLOR_PRIMARY, COLOR_TRACK_BG).apply {
            isChecked = initial
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> onChange(checked) }
            // Start in the right place without animating on first show.
            jumpDrawablesToCurrentState()
        }
        // Lets a caller hold onto this specific Switch (e.g. so a preset row
        // elsewhere can force its isChecked state later) without every other
        // switchRow() call site needing to care.
        swRef?.invoke(sw)
        row.addView(lbl)
        row.addView(sw)
        wrapper.addView(row)
        wrapper.addView(divider())
        return wrapper
    }

    private fun dp(v: Int): Int {
        return (v * ctx.resources.displayMetrics.density).toInt()
    }

    // The drawer handle and panel are locked to the right edge.

    private fun collapsedWindowWidth(): Int = edgeStripWidthPx

    private fun collapsedWindowHeight(): Int = WindowManager.LayoutParams.MATCH_PARENT

    private fun collapsedWindowGravity(): Int = Gravity.TOP or Gravity.END

    private fun panelTravelPx(): Int = panelWidthPx.coerceAtLeast(1)

    private fun panelLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            panelWidthPx,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END,
        )

    private fun handleLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            handleWidthPx + dp(8),
            handleHeightPx,
            Gravity.CENTER_VERTICAL or Gravity.END,
        )

    private fun dragDeltaTowardCenter(ev: MotionEvent): Float = dragStartX - ev.rawX

    private fun applyPanelProgress(panelView: View, p: Float) {
        panelView.translationX = (1f - p) * panelWidthPx
        panelView.translationY = 0f
    }

    // --- handle view -------------------------------------------------------------------

    private inner class HandleView(ctx: Context) : View(ctx) {
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var glow: Float = 1f
        private var shader: LinearGradient? = null

        fun setGlow(v: Float) {
            if (glow == v) return
            glow = v
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Build the gradient shader once per size change instead of every draw.
            val pillWidth = handleWidthPx.toFloat()
            val pillHeight = handleHeightPx.toFloat()
            val left = w.toFloat() - dp(4) - pillWidth
            val top = (h.toFloat() - pillHeight) / 2f
            val bottom = top + pillHeight
            shader = LinearGradient(
                left, top, left, bottom,
                COLOR_PRIMARY, COLOR_ACCENT_DEEP,
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            pillPaint.shader = shader
            pillPaint.alpha = (255 * glow).toInt().coerceIn(120, 255)
            val w = width.toFloat()
            val h = height.toFloat()
            val pillWidth = handleWidthPx.toFloat()
            val pillHeight = handleHeightPx.toFloat()
            val left = w - dp(4) - pillWidth
            val top = (h - pillHeight) / 2f
            val right = left + pillWidth
            val bottom = top + pillHeight
            val radius = minOf(pillWidth, pillHeight) / 2f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, pillPaint)
        }
    }

    companion object {
        private const val TAG = "SettingsDrawer"

        // Brand palette — mirrors ui/theme/Color.kt's amber/teal retro-terminal
        // scheme so the in-game overlay and the in-app screens read as the same
        // product instead of two different color languages.
        // Set from AppAppearancePrefs each time the drawer is shown (see show()).
        @Volatile private var glassMode = false
        @Volatile private var glassDark = true
        // iOS 27 Clear (0f) <-> Tinted (1f) slider, mirrored from Appearance settings.
        @Volatile private var glassIntensity = 0.45f

        private fun mixF(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun argb(alpha: Float, rgb: Int): Int =
            ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)

        private const val COLOR_PRIMARY = 0xFFE3A857.toInt()
        private const val COLOR_ON_PRIMARY = 0xFF35230A.toInt()
        private const val COLOR_ACCENT_DEEP = 0xFF9C6423.toInt()
        private val COLOR_ON_SURFACE: Int get() = when {
            !glassMode -> 0xFFEDE7DE.toInt()
            glassDark -> 0xFFF7F7FB.toInt()
            else -> 0xFF111318.toInt()
        }
        private val COLOR_PANEL_BG: Int get() = when {
            !glassMode -> 0xF01A1811.toInt()
            glassDark -> argb(mixF(0.62f, 0.94f, glassIntensity), 0x1B1D2E)
            else -> argb(mixF(0.78f, 0.96f, glassIntensity), 0xF4F6FC)
        }
        private val COLOR_PANEL_STROKE: Int get() = when {
            !glassMode -> 0x55E3A857
            glassDark -> 0x66FFFFFF
            else -> 0xAAFFFFFF.toInt()
        }
        private val COLOR_DIVIDER: Int get() = when {
            !glassMode -> 0x26FFFFFF
            glassDark -> 0x2EFFFFFF
            else -> 0x26000000
        }
        private val COLOR_TRACK_BG: Int get() = when {
            !glassMode -> 0xFF2A271D.toInt()
            glassDark -> 0x40FFFFFF
            else -> 0x33000000
        }
        private val COLOR_CHIP_BG: Int get() = when {
            !glassMode -> 0x22FFFFFF
            glassDark -> 0x33FFFFFF
            else -> 0x1F000000
        }
        // Text on a selected (amber) chip: the old dark panel colour, or dark brown in glass mode
        // (the glass panel colour is translucent / light, which would wash out on amber).
        private val COLOR_ON_SELECTED: Int get() = if (glassMode) COLOR_ON_PRIMARY else COLOR_PANEL_BG
        private const val COLOR_STOP_BG = 0xFF2B1613.toInt()
        private const val COLOR_STOP_STROKE = 0x66CF7D72.toInt()
        private const val COLOR_STOP_TEXT = 0xFFCF7D72.toInt()
        private const val COLOR_START_BG = 0xFF17261A.toInt()
        private const val COLOR_START_STROKE = 0x667DCF8A.toInt()
        private const val COLOR_START_TEXT = 0xFF7DCF8A.toInt()
    }
}
