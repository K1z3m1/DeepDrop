package com.firstt175.novaframe.session.overlay

import com.firstt175.novaframe.session.diagnostics.AdbDisplayController
import com.firstt175.novaframe.session.service.NovaAccessibilityService
import com.firstt175.novaframe.session.service.NovaForegroundService

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.firstt175.novaframe.prefs.NovaPreferences

/**
 * Full-screen overlay that hosts a [TextureView] for the mirrored / LSFG-processed
 * frames.
 *
 * **Why TextureView instead of SurfaceView**: a SurfaceView creates a child BLAST
 * SurfaceControl that lives outside the parent window's surface hierarchy. On strict
 * AOSP builds (e.g. Rockchip Orange Pi 5 Ultra, Android 13) InputDispatcher's
 * BLOCK_UNTRUSTED_TOUCHES filter evaluates that BLAST surface independently, sees
 * an opaque (alpha=1.0) untrusted overlay sitting over the target app, and drops
 * every tap with `Dropping untrusted touch event due to /<uid>` — even when the
 * parent window has an empty touchable region and is itself trusted. The
 * "trusted" bit does NOT propagate to the child SurfaceControl, and the only API
 * to mark it trusted (`SurfaceControl.Transaction.setTrustedOverlay`) is hidden /
 * blocklisted on user builds.
 *
 * TextureView draws into the parent View's hardware layer instead of creating a
 * separate SurfaceControl, so InputDispatcher only sees one window — the parent
 * one, whose touchable region we already publish as empty. The cost is one extra
 * GPU presentation path relative to SurfaceView; acceptable here because the
 * native render loop already CPU-blits each generated frame to ANativeWindow.
 *
 * Surface lifecycle is event-driven: consumers get [onSurfaceReady] every time a
 * new Surface becomes valid (initial show and every recreation after orientation
 * / immersive-mode changes) and [onSurfaceLost] every time the Surface is torn
 * down. We pin the producer-side buffer to the physical screen size so the
 * Surface dimensions match the VirtualDisplay dimensions exactly — no scaling,
 * no letter-boxing, no off-screen positioning.
 */
class OverlayManager(private val ctx: Context) {

    /**
     * Converts a dp value to px using the CURRENT live display density. Deliberately
     * re-reads [Context.resources] every call (not cached) so callers made after a
     * resolution/DPI change (rotation, or our own per-app `wm density` override via
     * [AdbDisplayController]) automatically pick up the new density — mirrors the
     * `dp()` helpers in [SettingsDrawerOverlay].
     */
    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Appends one icon+value pair to the fps/stats HUD line: an inline
     * [ImageSpan] icon, a thin space, then the value text. The span anchors
     * to a single placeholder character (standard Android pattern for inline
     * icons in a TextView) — the placeholder itself is invisible, only the
     * drawable is painted.
     */
    private fun SpannableStringBuilder.appendIconValue(icon: Drawable, value: String, valueColor: Int? = null) {
        val start = length
        append(" ")
        setSpan(ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" ")
        val valueStart = length
        append(value)
        if (valueColor != null) {
            setSpan(
                android.text.style.ForegroundColorSpan(valueColor),
                valueStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Green → amber → red so a throttling phone is obvious at a glance without reading the number. */
    private fun tempColor(c: Float): Int = when {
        c >= 46f -> 0xFFFF453A.toInt()
        c >= 41f -> 0xFFFFB340.toInt()
        else -> 0xFF30D158.toInt()
    }

    /** Square icon canvas size for the fps/stats HUD glyphs — sits close to the 14sp HUD text's cap height. */
    private fun hudIconSizePx(): Int = dp(HUD_ICON_SIZE_DP)

    /** Wraps a draw callback (bounds-relative w/h already resolved) into a ready-to-use, bounds-set [Drawable]. */
    private fun hudIcon(draw: (Canvas, Paint, Float, Float) -> Unit): Drawable {
        val size = hudIconSizePx()
        return HudIconDrawable(draw).apply { setBounds(0, 0, size, size) }
    }

    // fps: stopwatch — circle + top stem + a hand, reads as "rate over time".
    private fun buildFpsIcon(): Drawable = hudIcon { c, p, w, h ->
        val cx = w / 2f
        val cy = h * 0.56f
        val r = w * 0.36f
        c.drawCircle(cx, cy, r, p)
        c.drawLine(cx, h * 0.08f, cx, h * 0.20f, p)
        c.drawLine(cx, cy, cx + r * 0.55f, cy - r * 0.55f, p)
    }

    // cpu: chip outline with pin ticks top/bottom.
    private fun buildCpuIcon(): Drawable = hudIcon { c, p, w, h ->
        val inset = w * 0.26f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), w * 0.10f, w * 0.10f, p)
        val pinLen = h * 0.16f
        for (x in floatArrayOf(w * 0.38f, w * 0.62f)) {
            c.drawLine(x, inset, x, inset - pinLen, p)
            c.drawLine(x, h - inset, x, h - inset + pinLen, p)
        }
    }

    // gpu: monitor — screen rect + stem + base, reads as "display/graphics".
    private fun buildGpuIcon(): Drawable = hudIcon { c, p, w, h ->
        val screenBottom = h * 0.62f
        c.drawRoundRect(RectF(w * 0.08f, h * 0.10f, w * 0.92f, screenBottom), w * 0.08f, w * 0.08f, p)
        val cx = w / 2f
        c.drawLine(cx, screenBottom, cx, screenBottom + h * 0.14f, p)
        c.drawLine(w * 0.30f, h * 0.92f, w * 0.70f, h * 0.92f, p)
    }

    // temp: thermometer — tube + bulb.
    private fun buildTempIcon(): Drawable = hudIcon { c, p, w, h ->
        val cx = w / 2f
        val tubeW = w * 0.22f
        c.drawRoundRect(RectF(cx - tubeW / 2f, h * 0.08f, cx + tubeW / 2f, h * 0.66f), tubeW / 2f, tubeW / 2f, p)
        c.drawCircle(cx, h * 0.76f, w * 0.17f, p)
        c.drawLine(cx, h * 0.30f, cx, h * 0.66f, p)
    }

    // ram: memory-stick outline with two chip divider lines.
    private fun buildRamIcon(): Drawable = hudIcon { c, p, w, h ->
        val rect = RectF(w * 0.10f, h * 0.28f, w * 0.90f, h * 0.72f)
        c.drawRoundRect(rect, h * 0.06f, h * 0.06f, p)
        for (x in floatArrayOf(w * 0.37f, w * 0.63f)) {
            c.drawLine(x, rect.top, x, rect.bottom, p)
        }
    }

    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var producerSurface: Surface? = null
    private var hostWindowManager: WindowManager? = null
    // One text view for the whole HUD readout: CPU GPU RAM FPS on a single line.
    private var hudTextView: TextView? = null
    private var graphView: FrameGraphView? = null
    private var recordingView: TextView? = null
    // Single container binding the readout line + graph into one HUD unit — see show().
    private var hudClusterView: LinearLayout? = null
    private var statusView: TextView? = null
    @Volatile private var firstFrameDisplayed = false
    private var insetsListener: Any? = null
    private var internalInsetsListener: Any? = null
    private var frameLoopCallback: Choreographer.FrameCallback? = null
    private var surfaceTextureUpdateCount = 0

    @Volatile
    private var surfaceReadyListener: ((Surface, Int, Int) -> Unit)? = null

    @Volatile
    private var surfaceLostListener: (() -> Unit)? = null
    private var overlayWidth: Int = 0
    private var overlayHeight: Int = 0
    // Density last used to size the fps/graph/status HUD views. Tracked
    // separately from overlayWidth/Height because a `wm density` override
    // can change density without necessarily changing the reported pixel
    // dimensions in the same tick — see syncOverlayGeometry().
    private var lastSyncedDensity: Float = 0f
    // IME-aware window shrinking (see installImeAwareness()/applyImeWindowHeight()).
    private var imeGlobalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var imeKeyboardVisible: Boolean = false
    // True when this session is hosted as TYPE_ACCESSIBILITY_OVERLAY (see show()'s
    // useTrusted). Only in that mode does our window out-rank the status bar /
    // notification shade / heads-up banners in system z-order, so only then do we
    // need to reserve a strip at the top the same way applyImeWindowHeight already
    // reserves one at the bottom for the keyboard — see applyImeWindowHeight().
    private var isTrustedHost: Boolean = false
    private var lastFpsUpdateAtMs: Long = 0L
    private var lastGraphSampleAtMs: Long = 0L
    private var lastStatsUpdateAtMs: Long = 0L
    @Volatile private var hudPositionUnlocked: Boolean = false
    private var hudDragDownX = 0f
    private var hudDragDownY = 0f
    private var hudDragStartLeft = 0
    private var hudDragStartTop = 0

    // Small canvas-drawn icons swapped in for the fps/stats HUD's old bare
    // unicode glyphs (◉ ⚡ ◌ ≋), so both lines read as icon+value pairs in a
    // matching style — same "no asset needed" Drawable pattern SettingsDrawerOverlay
    // already uses for crossDrawable()/chevronDrawable(). Built lazily (first
    // updateFps()/updateStats() call) since they need dp() → display density,
    // and cached because the same Drawable instance is reusable across every
    // ImageSpan (they're stateless — draw() only reads bounds already fixed
    // at construction time in hudIcon()).
    private val fpsIconDrawable: Drawable by lazy { buildFpsIcon() }
    private val cpuIconDrawable: Drawable by lazy { buildCpuIcon() }
    private val gpuIconDrawable: Drawable by lazy { buildGpuIcon() }
    private val ramIconDrawable: Drawable by lazy { buildRamIcon() }
    private val tempIconDrawable: Drawable by lazy { buildTempIcon() }

    /** Callback invoked every time the overlay Surface becomes valid for writing. */
    fun onSurfaceReady(cb: (Surface, Int, Int) -> Unit) {
        surfaceReadyListener = cb
    }

    /** Callback invoked every time the Surface is torn down. */
    fun onSurfaceLost(cb: () -> Unit) {
        surfaceLostListener = cb
    }

    fun show(outputWidth: Int? = null, outputHeight: Int? = null) {
        if (root != null) return

        // Two host modes, chosen by whether the accessibility service is bound
        // (trusted overlay is always requested; there is no user setting):
        //
        //  - TYPE_APPLICATION_OVERLAY (default): the overlay sits below the
        //    system bars (status bar, navigation bar, notification shade) so
        //    the user can still pull down notifications and tap nav buttons.
        //    Works on most devices because BLOCK_UNTRUSTED_TOUCHES is
        //    permissive on Pixel/Samsung/Xiaomi/etc.
        //
        //  - TYPE_ACCESSIBILITY_OVERLAY (used whenever the accessibility service is bound; requires the
        //    NovaAccessibilityService to be bound): the overlay becomes a
        //    trusted overlay so InputDispatcher's BLOCK_UNTRUSTED_TOUCHES
        //    filter does not drop tap pass-through on strict AOSP builds (e.g.
        //    Rockchip Orange Pi 5 Ultra, Android 13). Trade-off: a11y overlays
        //    are forced into a system layer family ABOVE the status / nav
        //    bars, so the user loses access to those bars while the session is
        //    running. Combined with TextureView (which avoids the child BLAST
        //    SurfaceControl that would re-introduce an untrusted occluder),
        //    this is the only path that makes pass-through actually work on
        //    those devices.
        val prefs = NovaPreferences(ctx).load()
        hudPositionUnlocked = prefs.hudPositionUnlocked
        val a11y = NovaAccessibilityService.instance
        val useTrusted = a11y != null
        isTrustedHost = useTrusted
        val hostCtx: Context = if (useTrusted) a11y!! else ctx
        val wm = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hostWindowManager = wm

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Present mode is the single global presentation policy for the native
        // render loop. It is not derived from, or overridden by, display refresh.

        val screenW = outputWidth?.takeIf { it > 0 } ?: metrics.widthPixels
        val screenH = outputHeight?.takeIf { it > 0 } ?: metrics.heightPixels
        overlayWidth = screenW
        overlayHeight = screenH
        lastSyncedDensity = metrics.density
        Log.i(TAG, "Showing overlay at ${screenW}x${screenH} presentMode=${NovaPreferences(ctx).load().presentMode}")

        val layoutType = when {
            useTrusted -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        Log.i(TAG, "Overlay host=${if (useTrusted) "a11y/TRUSTED" else "app/UNTRUSTED"}")

        // No FLAG_NOT_TOUCHABLE: that flag, combined with TYPE_APPLICATION_OVERLAY, is
        // what triggers the Android 12+ 0.8-alpha clamp. Pass-through is handled by an
        // empty touchable region (installed right after addView).
        // FLAG_SECURE is intentionally absent: on MediaTek/OEM ROMs it composites the
        // window as opaque black on VirtualDisplays (MediaProjection), making every
        // captured frame black. Exclusion from capture is handled by installSkipScreenshot
        // via the eSkipScreenshot SurfaceFlinger layer flag instead.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            @Suppress("DEPRECATION")
            systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            // PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_CAPTURE (0x00080000, API 31+): instructs
            // WMS to tell SurfaceFlinger to skip this window in VirtualDisplay/screenshot
            // composition at window-creation time — no SurfaceControl timing race.
            runCatching {
                val f = javaClass.getDeclaredField("privateFlags")
                f.isAccessible = true
                f.setInt(this, f.getInt(this) or 0x00080000)
                Log.i("lsfg-vk-loop", "privateFlags EXCLUDE_FROM_SCREEN_CAPTURE applied")
            }.onFailure {
                Log.d("lsfg-vk-loop", "privateFlags field unavailable: ${it.message}")
            }
        }

        // FrameLayout background stays transparent — TextureView composites into
        // its parent's hardware layer, but we still want no opaque fill behind it
        // before the first frame arrives so the status text remains
        // visible against the underlying app instead of a black slab.
        val layout = FrameLayout(ctx)
        val status = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val padH = dp(28)
            val padV = dp(16)
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        firstFrameDisplayed = false

        val tex = TextureView(ctx)
        // Must be false: isOpaque=true signals the hardware composer that the
        // layer is always opaque, causing HWC to skip compositing underlying
        // layers (the game) for VirtualDisplay/MediaProjection output.  With
        // opaque=true, even an alpha=0 cleared surface appears as opaque black
        // in the capture feed, seeding the dark feedback loop.  false lets the
        // compositor see through transparent pixels to the game content.
        tex.isOpaque = false
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                // Re-apply skip-screenshot now that the window's SurfaceControl is
                // guaranteed to have a valid native handle. The call in show() fires
                // immediately after wm.addView(), where isAttachedToWindow is already
                // true (ViewRootImpl.setView dispatches attachment synchronously) but
                // the SurfaceControl native handle is only assigned later during
                // relayoutWindow() — which completes before the first onSurfaceTextureAvailable.
                installSkipScreenshot(layout)

                // Pin the producer-side buffer size to the physical screen so the
                // VirtualDisplay's frames don't get scaled by the consumer.
                st.setDefaultBufferSize(screenW, screenH)
                val s = Surface(st)
                producerSurface = s
                
                syncOverlayGeometry()
                Log.i(TAG, "TextureView surface available ${width}x${height} valid=${s.isValid} hwAccel=${tex.isHardwareAccelerated}")
                if (s.isValid) {
                    surfaceReadyListener?.invoke(s, overlayWidth, overlayHeight)
                }
                // Drive TextureView redraws explicitly via Choreographer. On some
                // devices (MediaTek/Mali) the TextureView-internal onFrameAvailable
                // → scheduleTraversals path silently stops after the first buffer,
                // leaving the overlay frozen on frame 1. Calling invalidate() on
                // every VSYNC ensures updateTexImage() is called whenever a new
                // buffer is available, regardless of the internal mechanism.
                surfaceTextureUpdateCount = 0
                startFrameLoop(tex)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(screenW, screenH)
                val s = producerSurface
                if (s != null) 
                syncOverlayGeometry()
                if (s != null) {
                        Log.i(TAG, "TextureView size changed ${width}x${height}")
                    if (s.isValid) {
                        surfaceReadyListener?.invoke(s, overlayWidth, overlayHeight)
                    }
                }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                Log.i(TAG, "TextureView surface destroyed")
                stopFrameLoop()
                surfaceLostListener?.invoke()
                runCatching { producerSurface?.release() }
                producerSurface = null
                // Returning true tells TextureView it can release the SurfaceTexture
                // immediately; we have no off-thread producer holding a reference
                // beyond the native render loop, which has already been notified
                // via surfaceLostListener and detaches before this returns.
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                val n = ++surfaceTextureUpdateCount
                if (!firstFrameDisplayed) {
                    firstFrameDisplayed = true
                    statusView?.post { statusView?.visibility = View.GONE }
                    Log.i(TAG, "First overlay frame displayed — hiding status indicator")
                }
                if (n <= 30 || n % 60 == 0) {
                    Log.d(TAG, "onSurfaceTextureUpdated #$n")
                }
            }
        }
        // readout line (CPU GPU RAM FPS) + frame graph (graph last, at the bottom) are bound into ONE vertically-
        // stacked cluster (hudCluster) instead of three independently
        // positioned views. The cluster is transparent (no background panel —
        // just text over the game, each line legible via its own shadow) and
        // is the only thing given a position on the root FrameLayout;
        // the readout line and graph are just its children with LinearLayout.LayoutParams.
        // Two consequences, both intentional:
        //  - the HUD has a single on/off switch, so the three children are
        //    always shown/hidden together (see setHudVisible()).
        //  - the whole cluster moves/resizes as a single unit any time we
        //    reposition the HUD (relayoutHud()), rather than three views
        //    that each need their own margins kept in sync.
        val hudCluster = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Transparent — no background panel, just the text/graph directly
            // over the game. Each text line gets its own shadow (below) for
            // legibility instead of a dark box behind the whole cluster.
            background = null
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            // Starts hidden — setHudVisible(true) reveals it once the HUD switch
            // is on, so no empty view shows up before NovaForegroundService
            // restores the saved HUD state.
            visibility = View.GONE
        }

        val hudText = TextView(ctx).apply {
            text = ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            visibility = View.GONE
        }
        val graph = FrameGraphView(ctx).apply {
            visibility = View.GONE
        }

        val recording = TextView(ctx).apply {
            text = "● REC 00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
            visibility = View.GONE
        }
        recordingView = recording
        layout.addView(recording, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(14)
            rightMargin = dp(14)
        })

        val savedHudX = prefs.hudPositionX
        val savedHudY = prefs.hudPositionY
        hudCluster.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0) {
                val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@addOnLayoutChangeListener
                if (lp.leftMargin == 0 && lp.topMargin == 0 && (savedHudX != 0.02f || savedHudY != 0.02f)) {
                    lp.leftMargin = ((overlayWidth - v.width).coerceAtLeast(0) * savedHudX).toInt()
                    lp.topMargin = ((overlayHeight - v.height).coerceAtLeast(0) * savedHudY).toInt()
                    v.layoutParams = lp
                }
                updateHudTouchableRegion()
            }
        }
        hudCluster.setOnTouchListener { v, event ->
            if (!hudPositionUnlocked) return@setOnTouchListener false
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hudDragDownX = event.rawX
                    hudDragDownY = event.rawY
                    hudDragStartLeft = lp.leftMargin
                    hudDragStartTop = lp.topMargin
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (overlayWidth - v.width).coerceAtLeast(0)
                    val maxY = (overlayHeight - v.height).coerceAtLeast(0)
                    lp.leftMargin = (hudDragStartLeft + (event.rawX - hudDragDownX).toInt()).coerceIn(0, maxX)
                    lp.topMargin = (hudDragStartTop + (event.rawY - hudDragDownY).toInt()).coerceIn(0, maxY)
                    v.layoutParams = lp
                    updateHudTouchableRegion()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val maxX = (overlayWidth - v.width).coerceAtLeast(1)
                    val maxY = (overlayHeight - v.height).coerceAtLeast(1)
                    NovaPreferences(ctx).setHudPosition(
                        lp.leftMargin.toFloat() / maxX.toFloat(),
                        lp.topMargin.toFloat() / maxY.toFloat(),
                    )
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }

        // Order: one readout line (stream info on its own line above it when set,
        // then CPU GPU RAM FPS side by side), then the frame graph last. Each has its
        // own on/off switch (setHudVisible / setFrameGraphVisible).
        hudCluster.addView(
            hudText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        hudCluster.addView(
            graph,
            LinearLayout.LayoutParams(dp(HUD_GRAPH_WIDTH_DP), dp(HUD_GRAPH_HEIGHT_DP)).apply {
                topMargin = dp(HUD_CLUSTER_GAP_DP)
            },
        )

        layout.addView(
            tex,
            // Fixed pixel size (not MATCH_PARENT) pinned to the top-left: this is
            // what lets applyImeWindowHeight() below shrink the WINDOW to make
            // room for the keyboard without squishing/rescaling the rendered
            // frame — a texture bigger than a smaller window just gets clipped
            // at the window's Surface boundary instead of being scaled down.
            FrameLayout.LayoutParams(overlayWidth, overlayHeight, Gravity.TOP or Gravity.START),
        )
        layout.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        layout.addView(
            hudCluster,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                leftMargin = ((overlayWidth - dp(HUD_GRAPH_WIDTH_DP)).coerceAtLeast(0) * savedHudX).toInt().coerceAtLeast(0)
                topMargin = ((overlayHeight - dp(HUD_GRAPH_HEIGHT_DP)).coerceAtLeast(0) * savedHudY).toInt().coerceAtLeast(0)
            },
        )

        hudTextView = hudText
        graphView = graph
        hudClusterView = hudCluster
        statusView = status
        textureView = tex

        wm.addView(layout, params)

        // Mark the overlay's SurfaceControl with skipScreenshot so SurfaceFlinger
        // excludes it from virtual-display composition (MediaProjection) while still
        // rendering it normally on the physical display.  This prevents the feedback
        // loop where MediaProjection captures our overlay output instead of the live
        // game when the overlay is opaque.
        installSkipScreenshot(layout)

        // Two complementary pass-through mechanisms — we install both because each
        // is needed on a different subset of devices:
        //   1) AttachedSurfaceControl.setTouchableRegion(empty) — the modern API
        //      (public on API 33+, reflective on 29–32). Most reliable on Pixel /
        //      AOSP-like ROMs.
        //   2) ViewTreeObserver.OnComputeInternalInsetsListener with
        //      TOUCHABLE_INSETS_REGION — the legacy SystemUI pattern that some
        //      OEMs (Samsung One UI, Xiaomi HyperOS, OPPO ColorOS) honour even
        //      when (1) is silently rejected.
        // We do NOT use FLAG_NOT_TOUCHABLE, which would re-enable the Android 12+
        // 0.8-alpha clamp.
        insetsListener = installEmptyTouchableRegion(layout)
        internalInsetsListener = installEmptyInternalInsets(layout)
        // Must be set before installImeAwareness(): its initial call applies the
        // top-reserve immediately via applyImeWindowHeight(), which reads `root`.
        root = layout
        installImeAwareness(layout)
    }

    /**
     * Re-asserts the overlay as the topmost window. Call this after the target app is
     * launched so the new foreground activity doesn't leave a stale z-order with our
     * overlay stuck behind it.
     */
    fun bringToFront() {
        val r = root ?: return
        val wm = hostWindowManager ?: return
        if (!r.isAttachedToWindow) return
        val lp = r.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "bringToFront updateViewLayout failed", it) }
    }

    /**
     * Re-reads display metrics and resizes the overlay window to match a new
     * orientation / display configuration. Idempotent; safe to call multiple
     * times for a single rotation. The actual `wm.updateViewLayout` is posted
     * to the root view's main-thread looper to avoid racing with WindowManager
     * while it is still distributing the configuration change.
     */
    fun onDisplayConfigurationChanged() {
        val r = root ?: return
        r.post { syncOverlayGeometry() }
        // Safety net: normally NovaForegroundService calls
        // endGeometryTransition() once the native reinit for the new size
        // actually posts a frame. If that signal never arrives for some
        // reason (context reinit skipped, capture never re-attaches, etc.)
        // this guarantees the content doesn't stay hidden forever — it's a
        // no-op if endGeometryTransition() already ran.
        r.postDelayed({ endGeometryTransition() }, 2500L)
    }

    fun updateStatus(line: String) {
        if (firstFrameDisplayed) return
        val v = statusView ?: return
        v.post {
            if (!firstFrameDisplayed) {
                v.text = line
                v.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    // "<backend> · POST · Input: WxH → Output: WxH" — set once per (re)init by
    // NovaForegroundService.pushStreamInfo(), not on every fps tick, since
    // backend/resolution only change on a context reinit. Kept separate from
    // the fps line so updateFps()'s 250 ms throttle doesn't also gate this.
    @Volatile
    private var streamInfoLine: String = ""

    /**
     * Shows/hides the HUD readout line (CPU GPU RAM FPS). The frame graph has its own
     * switch — see [setFrameGraphVisible].
     */
    fun setHudVisible(visible: Boolean) {
        val cluster = hudClusterView ?: return
        cluster.post {
            hudTextView?.visibility = if (visible) View.VISIBLE else View.GONE
            updateHudClusterVisibility()
        }
    }

    /** Shows/hides the frame pacing graph (independent of the readout line). */
    fun setFrameGraphVisible(visible: Boolean) {
        val cluster = hudClusterView ?: return
        cluster.post {
            graphView?.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) graphView?.reset()
            updateHudClusterVisibility()
        }
    }

    /**
     * The cluster only takes up space while the readout line and/or the graph is on,
     * and the graph's top gap only applies when there's a readout line above it.
     * Must run on the main thread.
     */
    private fun updateHudClusterVisibility() {
        val cluster = hudClusterView ?: return
        val textOn = hudTextView?.visibility == View.VISIBLE
        val graphOn = graphView?.visibility == View.VISIBLE
        cluster.visibility = if (textOn || graphOn) View.VISIBLE else View.GONE
        graphView?.let { g ->
            (g.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                val gap = if (textOn) dp(HUD_CLUSTER_GAP_DP) else 0
                if (lp.topMargin != gap) {
                    lp.topMargin = gap
                    g.layoutParams = lp
                }
            }
        }
    }

    /**
     * Sets the static backend + pipeline input/output line shown above the HUD
     * readout line. Post-processing/upscale resolution is intentionally not
     * displayed. Safe to call even when the HUD isn't visible yet — it just
     * updates what the next refresh builds on.
     */
    fun setStreamInfo(line: String) {
        streamInfoLine = line
        refreshHudText()
    }

    // Latest CPU/GPU/RAM segment and FPS segment. They arrive from different threads on
    // different schedules (stats poll timer vs. the fps counter), so each is cached and
    // the single HUD line is rebuilt from both whenever either changes.
    @Volatile private var hudStatsSegment: CharSequence = ""
    @Volatile private var hudFpsSegment: CharSequence = ""

    /** Rebuilds the one-line readout: [stream info \n] CPU GPU RAM TEMP FPS. */
    private fun refreshHudText() {
        val v = hudTextView ?: return
        val text = SpannableStringBuilder()
        if (streamInfoLine.isNotEmpty()) text.append(streamInfoLine).append("\n")
        val stats = hudStatsSegment
        val fps = hudFpsSegment
        text.append(stats)
        if (stats.isNotEmpty() && fps.isNotEmpty()) text.append("   ")
        text.append(fps)
        v.post { v.text = text }
    }

    fun setRecordingHudVisible(visible: Boolean) {
        recordingView?.post { recordingView?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    fun updateRecordingHud(elapsedMs: Long) {
        val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
        val text = String.format(java.util.Locale.US, "● REC %02d:%02d", totalSec / 60L, totalSec % 60L)
        recordingView?.post { recordingView?.text = text }
    }

    fun updateFps(capturedFps: Float, postedFps: Float) {
        val v = hudTextView ?: return
        if (v.visibility != View.VISIBLE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFpsUpdateAtMs < 250L) return
        lastFpsUpdateAtMs = now
        // Real/generated fps as one "00/00" pair — compact single-number-pair style.
        hudFpsSegment = SpannableStringBuilder().apply {
            appendIconValue(fpsIconDrawable, "%.0f/%.0f".format(capturedFps, postedFps))
        }
        refreshHudText()
    }

    fun pushFrameGraphSample(realFps: Float, generatedFps: Float) {
        val g = graphView ?: return
        if (g.visibility != View.VISIBLE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastGraphSampleAtMs < 250L) return
        lastGraphSampleAtMs = now
        g.post { g.pushSample(realFps, generatedFps, realFps + generatedFps) }
    }

    /**
     * Updates the CPU/GPU/RAM readout line. Pass null for any metric the user
     * hasn't enabled in preferences — only non-null metrics are rendered, so
     * the line reflects exactly what's turned on. Throttled the same as
     * [updateFps] so a fast polling loop doesn't spam layout passes.
     */
    fun updateStats(cpuPercent: Float?, gpuPercent: Float?, ramUsedMb: Long?, ramTotalMb: Long?, tempC: Float? = null) {
        val v = hudTextView ?: return
        if (v.visibility != View.VISIBLE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsUpdateAtMs < 250L) return
        lastStatsUpdateAtMs = now
        val line = SpannableStringBuilder()
        var any = false
        cpuPercent?.let {
            line.appendIconValue(cpuIconDrawable, "%.0f%%".format(it))
            any = true
        }
        gpuPercent?.let {
            if (any) line.append("   ")
            line.appendIconValue(gpuIconDrawable, "%.0f%%".format(it))
            any = true
        }
        if (ramUsedMb != null && ramTotalMb != null && ramTotalMb > 0) {
            if (any) line.append("   ")
            line.appendIconValue(ramIconDrawable, "%.0f%%".format(ramUsedMb * 100f / ramTotalMb))
            any = true
        }
        tempC?.let {
            if (any) line.append("   ")
            line.appendIconValue(tempIconDrawable, "%.0f°".format(it), tempColor(it))
        }
        hudStatsSegment = line
        refreshHudText()
    }

    fun setHudPositionUnlocked(unlocked: Boolean) {
        hudPositionUnlocked = unlocked
        hudClusterView?.post {
            updateHudTouchableRegion()
            root?.requestLayout()
            root?.invalidate()
        }
    }

    fun hide() {
        val r = root ?: return
        insetsListener?.let { removeEmptyTouchableRegion(r, it) }
        insetsListener = null
        internalInsetsListener?.let { removeEmptyInternalInsets(r, it) }
        internalInsetsListener = null
        uninstallImeAwareness(r)
        val wm = hostWindowManager
        if (wm != null) {
            runCatching { wm.removeView(r) }
        }
        runCatching { producerSurface?.release() }
        producerSurface = null
        root = null
        textureView = null
        hudTextView = null
        graphView = null
        hudClusterView = null
        recordingView = null
        statusView = null
        firstFrameDisplayed = false
        hostWindowManager = null
    }

    /**
     * Publishes an empty touchable region on the overlay's root surface so
     * InputDispatcher skips the window and events fall through to the game below.
     *
     * Primary path (API 33+): public `View.getRootSurfaceControl().setTouchableRegion()`.
     * Legacy path (API 29-32): `getRootSurfaceControl()` is `@hide` but callable via
     *   reflection (not in the blocklist). `AttachedSurfaceControl.setTouchableRegion`
     *   has been present since API 29.
     * The call must happen after the view is attached to a window, so we hook it
     * into `onAttachedToWindow` / `OnAttachStateChangeListener`.
     *
     * Returns the attach listener (so we can detach it in hide()) or null if the
     * reflective lookup failed on a very old device.
     */
    private fun installEmptyTouchableRegion(host: View): Any? {
        val applyEmptyRegion: () -> Unit = {
            runCatching {
                val rootSc = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> host.rootSurfaceControl
                    else -> host.javaClass
                        .getMethod("getRootSurfaceControl")
                        .invoke(host)
                }
                if (rootSc == null) {
                    Log.w(TAG, "rootSurfaceControl is null; window may not be attached yet")
                } else {
                    val setTouchableRegion = rootSc.javaClass
                        .getMethod("setTouchableRegion", Region::class.java)
                    val region = Region()
                    if (hudPositionUnlocked) {
                        val rect = Rect()
                        hudClusterView?.getHitRect(rect)
                        if (!rect.isEmpty) region.set(rect)
                    }
                    setTouchableRegion.invoke(rootSc, region)
                    Log.i(TAG, "HUD touchable region ${if (hudPositionUnlocked) "enabled" else "empty"} (api=${Build.VERSION.SDK_INT})")
                }
            }.onFailure { Log.w(TAG, "setTouchableRegion(empty) failed", it) }
        }

        if (host.isAttachedToWindow) {
            applyEmptyRegion()
            return Unit
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                applyEmptyRegion()
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        host.addOnAttachStateChangeListener(attachListener)
        return attachListener
    }

    private fun removeEmptyTouchableRegion(host: View, listener: Any) {
        if (listener is View.OnAttachStateChangeListener) {
            runCatching { host.removeOnAttachStateChangeListener(listener) }
                .onFailure { Log.w(TAG, "removeOnAttachStateChangeListener failed", it) }
        }
    }

    private fun updateHudTouchableRegion() {
        val host = root ?: return
        if (!host.isAttachedToWindow) return
        runCatching {
            val rootSc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                host.rootSurfaceControl
            } else {
                host.javaClass.getMethod("getRootSurfaceControl").invoke(host)
            }
            val setTouchableRegion = rootSc?.javaClass?.getMethod("setTouchableRegion", Region::class.java) ?: return
            val region = Region()
            if (hudPositionUnlocked) {
                val rect = Rect()
                hudClusterView?.getHitRect(rect)
                if (!rect.isEmpty) region.set(rect)
            }
            setTouchableRegion.invoke(rootSc, region)
            host.invalidate()
        }.onFailure { Log.w(TAG, "update HUD touchable region failed", it) }
    }

    /**
     * Belt-and-braces touch pass-through using the SystemUI-canonical
     * `ViewTreeObserver.OnComputeInternalInsetsListener` + `TOUCHABLE_INSETS_REGION`.
     * Both the listener interface and `InternalInsetsInfo` are `@hide` in the public
     * SDK (stable since API 1), so the implementation is built reflectively. On
     * devices where this succeeds, InputDispatcher excludes our window from the
     * touchable region and events fall through to the app underneath even if
     * setTouchableRegion(empty) was silently rejected.
     *
     * Returns the proxy listener (so it can be detached in [hide]) or null on failure.
     */
    private fun installEmptyInternalInsets(host: View): Any? {
        return runCatching {
            val internalInsetsInfoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val touchableInsetsRegion = internalInsetsInfoCls
                .getField("TOUCHABLE_INSETS_REGION")
                .getInt(null)
            val setTouchableInsets = internalInsetsInfoCls
                .getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            val touchableRegionField = internalInsetsInfoCls.getField("touchableRegion")

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerCls.classLoader,
                arrayOf(listenerCls),
            ) { _, method, args ->
                if (method.name == "onComputeInternalInsets" && args != null && args.isNotEmpty()) {
                    val info = args[0]
                    runCatching {
                        setTouchableInsets.invoke(info, touchableInsetsRegion)
                        val region = touchableRegionField.get(info) as? Region
                        if (hudPositionUnlocked) {
                            val rect = Rect()
                            hudClusterView?.getHitRect(rect)
                            region?.set(rect)
                        } else {
                            region?.setEmpty()
                        }
                    }
                }
                null
            }
            val addMethod = host.viewTreeObserver.javaClass
                .getMethod("addOnComputeInternalInsetsListener", listenerCls)
            addMethod.invoke(host.viewTreeObserver, proxy)
            Log.i(TAG, "OnComputeInternalInsetsListener pass-through installed")
            proxy
        }.onFailure { Log.w(TAG, "installEmptyInternalInsets failed", it) }.getOrNull()
    }

    private fun removeEmptyInternalInsets(host: View, listener: Any) {
        runCatching {
            val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val removeMethod = host.viewTreeObserver.javaClass
                .getMethod("removeOnComputeInternalInsetsListener", listenerCls)
            removeMethod.invoke(host.viewTreeObserver, listener)
        }.onFailure { Log.w(TAG, "removeOnComputeInternalInsetsListener failed", it) }
    }

    private fun syncOverlayGeometry() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) {
            // The window has already been torn down (or hasn't finished attaching).
            // updateViewLayout in either state throws on some OEMs.
            return
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val newW = metrics.widthPixels
        val newH = metrics.heightPixels
        val densityChanged = metrics.density != lastSyncedDensity
        if (newW == overlayWidth && newH == overlayHeight && !densityChanged) return

        overlayWidth = newW
        overlayHeight = newH
        lastSyncedDensity = metrics.density
        relayoutHud()

        // Hide the content BEFORE resizing, not after. The window (lp.width/
        // height) and the TextureView's buffer both jump to the new
        // dimensions here, but the native LSFG context reinit that produces
        // a frame actually sized for the new orientation hasn't started yet
        // — it's kicked off downstream by onSurfaceReady once the
        // SurfaceTexture size-change lands, and can take ~100-300ms (up to a
        // few seconds on a slow device). Without hiding first, Android
        // stretches the *old*, wrong-aspect buffer to fill the *new* bounds
        // for that whole window, which is the visible "shrinks then pops
        // back" glitch. Hiding here means that stretch frame is never
        // painted; endGeometryTransition() fades the real thing back in once
        // the reinitialized context has actually posted at the new size.
        beginGeometryTransition()

        val lp = r.layoutParams as? WindowManager.LayoutParams
        if (lp != null) {
            lp.width = newW
            runCatching { wm.updateViewLayout(r, lp) }
                .onFailure { Log.w(TAG, "updateViewLayout(${newW}x${newH}) failed", it) }
        }
        // Re-derive y/height (and the matching TextureView.translationY) at the
        // new dimensions/density instead of just setting lp.height = newH here —
        // otherwise a rotation would silently wipe out the top reserve applied
        // by applyImeWindowHeight() for status bar / notification visibility,
        // and TOP_RESERVE_DP needs recomputing anyway if density changed.
        applyImeWindowHeight(imeKeyboardVisible, newH)
        // TextureView is a FIXED-size child now (see show()), not MATCH_PARENT —
        // needed so applyImeWindowHeight() can shrink just the window without
        // rescaling the frame. That means a genuine resolution/rotation change
        // has to resize it explicitly here too, or it would keep rendering at
        // the stale old size after rotating.
        (textureView?.layoutParams as? FrameLayout.LayoutParams)?.let { texLp ->
            texLp.width = newW
            texLp.height = newH
            textureView?.layoutParams = texLp
        }
        runCatching { textureView?.surfaceTexture?.setDefaultBufferSize(newW, newH) }
            .onFailure { Log.w(TAG, "setDefaultBufferSize(${newW}x${newH}) failed", it) }
        Log.i(TAG, "Overlay geometry synced to ${newW}x${newH}")
    }

    /**
     * Makes the on-screen keyboard, status bar, and notifications (heads-up
     * banners included) actually visible while a frame-gen session is running.
     *
     * The overlay window normally covers the full screen — in trusted-overlay
     * mode (TYPE_ACCESSIBILITY_OVERLAY) it sits in a system layer *above* the
     * IME window AND above the status bar / notification shade, so even though
     * the real keyboard opens underneath, and even though a notification really
     * is drawn underneath, our window is what's actually being composited on
     * top of those regions, and it has nothing there to draw beyond old/blank
     * frame content — neither ever becomes visible. (In the default, untrusted
     * TYPE_APPLICATION_OVERLAY mode this isn't needed: those system windows
     * already out-rank us in z-order on their own.)
     *
     * Fix: shrink the WINDOW itself (not the rendered frame — see the
     * fixed-size TextureView in [show]) so our surface simply doesn't extend
     * into those regions any more. With nothing of ours covering them, whatever
     * the system composites there next (the real IME, status bar, or a
     * heads-up banner) shows through normally, regardless of window-type
     * z-order:
     *  - Bottom: watched dynamically via the classic visible-display-frame
     *    heuristic, same as before — only shrinks while the keyboard is open.
     *  - Top: reserved permanently for the lifetime of a trusted-host session
     *    (TOP_RESERVE_DP), since heads-up banners are transient and don't shift
     *    the visible-frame the way a persistent IME inset does, so there's no
     *    reliable "notification is showing" signal to react to dynamically.
     * The window's y-origin moves down by the top reserve, and the TextureView
     * is translated up by the same amount so the visible portion of the frame
     * still lines up 1:1 with its true screen position — only the reserved
     * strip itself goes undrawn by us.
     */
    private fun installImeAwareness(view: FrameLayout) {
        // Establish the top reserve (if any) immediately, without waiting for
        // the first layout pass / keyboard toggle.
        applyImeWindowHeight(imeKeyboardVisible, overlayHeight)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            view.getWindowVisibleDisplayFrame(r)
            // Anything system bars / cutouts eat is normally a small fraction of
            // the screen; a keyboard is typically 25-45% of it. 100dp comfortably
            // separates the two without false-triggering on a tall cutout/gesture
            // nav inset.
            val hiddenBelow = overlayHeight - r.bottom
            val nowVisible = hiddenBelow > dp(100)
            if (nowVisible != imeKeyboardVisible) {
                imeKeyboardVisible = nowVisible
                applyImeWindowHeight(nowVisible, r.bottom)
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        imeGlobalLayoutListener = listener
    }

    private fun uninstallImeAwareness(view: FrameLayout) {
        val listener = imeGlobalLayoutListener ?: return
        imeGlobalLayoutListener = null
        imeKeyboardVisible = false
        runCatching { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    private fun applyImeWindowHeight(keyboardVisible: Boolean, visibleBottom: Int) {
        val r = root ?: return
        val wm = hostWindowManager ?: return
        if (!r.isAttachedToWindow) return
        val lp = r.layoutParams as? WindowManager.LayoutParams ?: return
        // No top reserve: the overlay always covers the full screen, edge to
        // edge, in both host modes. In trusted-host mode this means the
        // status bar / notification shade are covered by our window while a
        // session is running (they no longer show through), trading that
        // visibility away for a seamless fullscreen frame with no reserved
        // strip at the top.
        val topPx = 0
        val bottomPx = (if (keyboardVisible) visibleBottom.coerceIn(1, overlayHeight) else overlayHeight)
            .coerceAtLeast(topPx + 1)
        lp.y = topPx
        lp.height = bottomPx - topPx
        textureView?.translationY = -topPx.toFloat()
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "IME-aware updateViewLayout failed", it) }
        Log.i(
            TAG,
            "Overlay window y=$topPx height=${lp.height} (topReserve=none, keyboardVisible=$keyboardVisible)",
        )
    }

    /**
     * Re-applies dp/sp-based sizing to the fps text, frame graph, stats line,
     * and status indicator after a resolution or DPI change (rotation into/
     * out of landscape included). [syncOverlayGeometry] resizes the
     * game-frame TextureView itself; this is the HUD-chrome counterpart —
     * without it these views keep whatever pixel size they were built with and
     * end up too small/large relative to the newly-resized frame underneath.
     */
    private fun relayoutHud() {
        val margin = dp(HUD_MARGIN_DP)
        statusView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val padH = dp(28)
            val padV = dp(16)
            setPadding(padH, padV, padH, padV)
        }
        // The cluster is one bound unit now: re-apply its own position/padding
        // once, then just re-apply text size + the graph's fixed dp size on
        // its children — no more per-child margins to keep in sync.
        hudClusterView?.apply {
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val maxX = (overlayWidth - width).coerceAtLeast(0)
                val maxY = (overlayHeight - height).coerceAtLeast(0)
                val pos = NovaPreferences(ctx).load()
                lp.leftMargin = (maxX * pos.hudPositionX).toInt().coerceIn(0, maxX)
                lp.topMargin = (maxY * pos.hudPositionY).toInt().coerceIn(0, maxY)
                layoutParams = lp
            }
        }
        hudTextView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }
        graphView?.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = dp(HUD_GRAPH_WIDTH_DP)
                lp.height = dp(HUD_GRAPH_HEIGHT_DP)
                lp.topMargin = if (hudTextView?.visibility == View.VISIBLE) dp(HUD_CLUSTER_GAP_DP) else 0
                layoutParams = lp
            }
        }
    }

    /**
     * Instantly hides the overlay's content view ahead of a resize so the
     * stale/wrong-aspect buffer is never visibly stretched into the new
     * window bounds. No fade-out on purpose — a fade would itself show the
     * stretched frame for its duration. Pair with [endGeometryTransition].
     */
    fun beginGeometryTransition() {
        val tex = textureView ?: return
        tex.post {
            tex.animate().cancel()
            tex.alpha = 0f
        }
    }

    /**
     * Fades the overlay's content back in after a rotation/geometry change,
     * once the caller has confirmed a frame at the new size has actually
     * been posted (or after a bounded fallback delay). Safe to call more
     * than once; a no-op if content is already visible.
     */
    fun endGeometryTransition() {
        val tex = textureView ?: return
        tex.post {
            if (tex.alpha >= 0.99f) return@post
            tex.animate().cancel()
            tex.animate().alpha(1f).setDuration(150L).start()
        }
    }

    /**
     * Sets the overlay window's `eSkipScreenshot` SurfaceFlinger flag so SurfaceFlinger
     * excludes it from VirtualDisplay composition (MediaProjection capture) while rendering
     * it normally on the physical display.
     *
     * Reflection path:
     *   View.getViewRootImpl() (@hide, API 11+) → ViewRootImpl
     *   ViewRootImpl.getSurfaceControl() (@hide, API 29+) → window root SurfaceControl
     *   SurfaceControl.Transaction.setSkipScreenshot(@hide, API 12+)
     *
     * Note: rootSurfaceControl/getRootSurfaceControl() returns AttachedSurfaceControl, not
     * ViewRootImpl, so getSurfaceControl() would throw NoSuchMethodException on that path.
     */
    private fun installSkipScreenshot(host: View) {
        val apply: () -> Unit = {
            runCatching {
                // getViewRootImpl() is @hide but stable since API 11 and returns ViewRootImpl
                // directly — the object that owns the window's root SurfaceControl.
                val viewRootImpl = host.javaClass.getMethod("getViewRootImpl").invoke(host)
                    ?: error("getViewRootImpl() returned null (view not attached?)")

                // Get the window's SurfaceControl.
                // Primary path:   ViewRootImpl.getSurfaceControl() — @hide, API 29+.
                // Fallback path:  direct ViewRootImpl.mSurfaceControl field — some OEM
                //   ROMs (MediaTek Android 13 confirmed) strip the accessor method while
                //   leaving the underlying field intact in the class hierarchy.
                val sc: SurfaceControl = run {
                    runCatching {
                        viewRootImpl.javaClass.getMethod("getSurfaceControl")
                            .invoke(viewRootImpl) as? SurfaceControl
                    }.getOrNull()?.let { return@run it }

                    var cls: Class<*>? = viewRootImpl.javaClass
                    while (cls != null) {
                        runCatching {
                            cls!!.getDeclaredField("mSurfaceControl")
                                .also { it.isAccessible = true }
                                .get(viewRootImpl) as? SurfaceControl
                        }.getOrNull()?.let {
                            Log.d("lsfg-vk-loop", "installSkipScreenshot: SurfaceControl via ${cls!!.simpleName}.mSurfaceControl")
                            return@run it
                        }
                        cls = cls.superclass
                    }

                    // Third fallback: enumerate all fields by type — catches OEM renames.
                    val scFieldsFound = mutableListOf<String>()
                    var typeCls: Class<*>? = viewRootImpl.javaClass
                    while (typeCls != null) {
                        for (field in typeCls!!.declaredFields) {
                            if (SurfaceControl::class.java.isAssignableFrom(field.type)) {
                                val fieldId = "${typeCls.simpleName}.${field.name}"
                                scFieldsFound.add(fieldId)
                                runCatching {
                                    field.isAccessible = true
                                    field.get(viewRootImpl) as? SurfaceControl
                                }.getOrNull()?.let { found ->
                                    Log.i("lsfg-vk-loop", "installSkipScreenshot: SurfaceControl via type-search: $fieldId")
                                    return@run found
                                }
                            }
                        }
                        typeCls = typeCls.superclass
                    }
                    Log.i("lsfg-vk-loop", "installSkipScreenshot: type-search SC-typed fields: $scFieldsFound")

                    // Fourth fallback: Android 12+ BLAST pipeline — ViewRootImpl stores a
                    // BLASTBufferQueue which owns the window's SurfaceControl internally.
                    // Some OEM ROMs strip all direct SC fields from ViewRootImpl but leave
                    // the BLASTBufferQueue field intact.
                    val bbqClass = runCatching {
                        Class.forName("android.graphics.BLASTBufferQueue")
                    }.getOrNull()
                    if (bbqClass != null) {
                        var bbqSearchCls: Class<*>? = viewRootImpl.javaClass
                        outer@ while (bbqSearchCls != null) {
                            for (field in bbqSearchCls!!.declaredFields) {
                                if (bbqClass.isAssignableFrom(field.type)) {
                                    runCatching {
                                        field.isAccessible = true
                                        val bbq = field.get(viewRootImpl) ?: return@runCatching
                                        for (methodName in listOf(
                                            "getSyncedSurfaceControl",
                                            "getSurfaceControl",
                                        )) {
                                            runCatching {
                                                bbq.javaClass.getMethod(methodName)
                                                    .invoke(bbq) as? SurfaceControl
                                            }.getOrNull()?.let { found ->
                                                Log.i("lsfg-vk-loop",
                                                    "installSkipScreenshot: SurfaceControl via " +
                                                    "BLASTBufferQueue.${field.name}.$methodName()")
                                                return@run found
                                            }
                                        }
                                    }
                                }
                            }
                            bbqSearchCls = bbqSearchCls.superclass
                        }
                    }

                    // All paths exhausted — dump surface/blast/buffer related field names from
                    // the ViewRootImpl hierarchy so we can see what the OEM actually has.
                    val relatedFields = mutableListOf<String>()
                    var dumpCls: Class<*>? = viewRootImpl.javaClass
                    while (dumpCls != null) {
                        for (field in dumpCls!!.declaredFields) {
                            val n = field.name.lowercase()
                            if (n.contains("surface") || n.contains("blast") ||
                                n.contains("buffer") || n.contains("mbbq") ||
                                n == "msc" || n == "mwrl") {
                                relatedFields.add(
                                    "${dumpCls.simpleName}.${field.name}:${field.type.simpleName}"
                                )
                            }
                        }
                        dumpCls = dumpCls.superclass
                    }
                    Log.i("lsfg-vk-loop", "installSkipScreenshot: related fields dump: $relatedFields")
                    error("getSurfaceControl() missing and no SurfaceControl field found in class hierarchy")
                }

                // Verify the native handle is non-zero — it is 0 if relayoutWindow()
                // hasn't run yet. The onSurfaceTextureAvailable retry guarantees it is set.
                val nativeHandle = runCatching {
                    sc.javaClass.getDeclaredField("mNativeObject")
                        .also { it.isAccessible = true }.getLong(sc)
                }.getOrDefault(-1L)
                Log.i("lsfg-vk-loop", "installSkipScreenshot: SurfaceControl nativeHandle=0x${nativeHandle.toString(16)}")
                if (nativeHandle == 0L) {
                    error("SurfaceControl native handle is 0 — relayoutWindow not yet run; onSurfaceTextureAvailable will retry")
                }

                // setSkipScreenshot (@hide) — layer skipped during VirtualDisplay composition
                // (MediaProjection) but rendered normally on physical display.
                val txn = SurfaceControl.Transaction()
                val method = txn.javaClass.getMethod(
                    "setSkipScreenshot",
                    SurfaceControl::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                method.invoke(txn, sc, true)
                txn.apply()
                Log.i("lsfg-vk-loop", "installSkipScreenshot: OK — overlay excluded from MediaProjection")
            }.onFailure {
                Log.w("lsfg-vk-loop", "installSkipScreenshot FAILED (${it.javaClass.simpleName}): ${it.message}" +
                           " — feedback loop may occur")
            }
        }

        if (host.isAttachedToWindow) {
            apply()
        } else {
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { apply() }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private fun startFrameLoop(tex: TextureView) {
        stopFrameLoop()
        // GPU-load fix: this loop used to call tex.invalidate() on EVERY display VSYNC
        // (60/90/120 Hz) for the whole session, even when no new frame had been posted.
        // Each invalidate makes RenderThread re-record the full-screen window and
        // re-composite the TextureView layer, which is GPU time that competes directly
        // with the frame-generation compute work. Now we only invalidate when the native
        // side has actually posted a new frame (getPostedFrameCount changed), plus a slow
        // watchdog so the MediaTek/Mali "stuck on frame 1" recovery still works.
        var lastPosted = -1L
        var lastForceNs = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (frameLoopCallback !== this) return
                val posted = try {
                    com.firstt175.novaframe.session.NativeBridge.getPostedFrameCount()
                } catch (_: Throwable) {
                    -1L // native not ready -> behave like the old always-invalidate path
                }
                val watchdogDue = frameTimeNanos - lastForceNs >= FRAME_LOOP_WATCHDOG_NS
                if (posted < 0L || posted != lastPosted || watchdogDue) {
                    lastPosted = posted
                    lastForceNs = frameTimeNanos
                    tex.invalidate()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameLoopCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopFrameLoop() {
        val cb = frameLoopCallback
        frameLoopCallback = null
        if (cb != null) {
            Choreographer.getInstance().removeFrameCallback(cb)
        }
    }

    companion object {
        private const val TAG = "OverlayManager"
        // Max gap between forced TextureView invalidates when no new frame was posted.
        private const val FRAME_LOOP_WATCHDOG_NS = 250_000_000L
        // Dp-space constants for the HUD cluster (fps text + frame graph +
        // stats, bound together as one panel — see show()), so they scale
        // with resolution/DPI the same way the rest of the app's overlays do.
        const val HUD_MARGIN_DP = 8
        const val HUD_GRAPH_WIDTH_DP = 220
        const val HUD_GRAPH_HEIGHT_DP = 80
        // Gap between the readout line and the graph inside the cluster.
        const val HUD_CLUSTER_GAP_DP = 6
        // Square size of each inline fps/stats icon glyph (see buildFpsIcon() etc.) —
        // close to the 14sp HUD text's cap height so icon and value sit on one line.
        const val HUD_ICON_SIZE_DP = 14
        // How much of the top of the screen to permanently leave undrawn by our
        // overlay while trusted-host (TYPE_ACCESSIBILITY_OVERLAY) is active, so
        // the status bar and heads-up notification banners remain visible —
        // see applyImeWindowHeight(). Sized generously past just the status bar
        // (~24-32dp on most devices) to also clear a compact heads-up banner.
        const val TOP_RESERVE_DP = 90
    }
}

/**
 * Minimal monochrome canvas-drawn icon for the fps/stats HUD line (see
 * [OverlayManager.hudIcon]). Same "no drawable asset needed" approach as
 * [SettingsDrawerOverlay]'s crossDrawable()/chevronDrawable() — a stroke
 * [Paint] plus a draw callback that receives the already-resolved bounds
 * width/height so each icon's shape is expressed as simple fractions of its
 * box rather than hardcoded pixels.
 */
private class HudIconDrawable(
    private val draw: (Canvas, Paint, Float, Float) -> Unit,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        paint.strokeWidth = (w * 0.09f).coerceAtLeast(1.5f)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        draw(canvas, paint, w, h)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(cf: android.graphics.ColorFilter?) {
        paint.colorFilter = cf
    }

    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
