package app.fluxa

import app.fluxa.R
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.EditText
import android.app.NotificationManager
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.view.animation.DecelerateInterpolator

class MainActivity : Activity() {
    private lateinit var kit: Kit
    private lateinit var multiplierSlider: Slider
    private lateinit var presentSeg: Segmented
    private lateinit var qualitySlider: Slider
    private lateinit var genModeSeg: Segmented
    private lateinit var targetFpsSlider: Slider
    private lateinit var outModeSeg: Segmented
    private lateinit var multCaption: View
    private lateinit var fpsCaption: View
    private lateinit var dispEnableSeg: Segmented
    private lateinit var dispPctSlider: Slider
    private lateinit var dispDpiSeg: Segmented
    private lateinit var dispDpiSlider: Slider
    private lateinit var dispUpscaleSeg: Segmented
    private lateinit var dispFilterSeg: Segmented
    private lateinit var dispSharpSlider: Slider
    private lateinit var dispSharpMethodSeg: Segmented
    private lateinit var dispOutSlider: Slider
    private lateinit var dispDenoiseSlider: Slider
    private lateinit var dispAaSeg: Segmented
    private lateinit var dispDebandSlider: Slider
    private lateinit var dispDitherSeg: Segmented
    private lateinit var dispBrightSlider: Slider
    private lateinit var dispContrastSlider: Slider
    private lateinit var dispSatSlider: Slider
    private lateinit var dispVibSlider: Slider
    private lateinit var dispTempSlider: Slider
    private lateinit var dispTintSlider: Slider
    private var dispFilterModes: List<Pair<Int, String>> = emptyList()
    private lateinit var dispDpiBox: LinearLayout
    private lateinit var dispInfo: TextView
    private lateinit var dispSummary: TextView

    // ---- tabs (หลัก / เฟรม / จอ / ภาพ / ตั้งค่า)
    private class TabItem(val root: View, val icon: TabIcon, val label: TextView) {
        fun setActive(on: Boolean) {
            label.setTextColor(if (on) Palette.PRIMARY else Palette.MUTED)
            icon.setActive(on)
        }
    }
    private val tabs = ArrayList<TabItem>()
    private var tabPages: List<View> = emptyList()
    private var curTab = 0
    private lateinit var tabRow: LinearLayout
    /** Blurred snapshot of what scrolls behind the floating dock (used when Blur is on). */
    private val glass = GlassBackdrop()
    private val glassTick = object : Runnable {
        override fun run() {
            glass.request()
            tabRow.handler?.postDelayed(this, 2000)
        }
    }

    // ---- readiness checklist on the home tab
    private lateinit var headerAvatar: AvatarView
    private lateinit var headerDot: View
    private lateinit var tabPill: LiquidPill
    private lateinit var homeLauncher: HomeLauncher
    private lateinit var homeMonitor: HomeMonitor
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroChips: LinearLayout
    private lateinit var heroDot: View

    // Quality = optical-flow resolution (short side, px), passed as FluxaOptions.flowSide.
    private val prefs by lazy { getSharedPreferences("fluxa", Context.MODE_PRIVATE) }
    private var themeSignature = 0

    private fun saveInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun saveBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // nothing runs until the user has accepted the terms + privacy policy (ConsentActivity)
        if (!Consent.isAccepted(this)) {
            startActivity(Intent(this, ConsentActivity::class.java))
            finish()
            return
        }
        curTab = (savedInstanceState?.getInt(STATE_TAB) ?: 0).coerceIn(0, 2)
        // older versions kept the last crash in a file; this one keeps no logs at all, so remove it
        runCatching { java.io.File(filesDir, "last_crash.txt").delete() }
        // first start: remember the current resolution + DPI so they can always be restored
        DisplayTuner.captureOriginalIfNeeded(this)
        kit = Kit(this) // also syncs Palette with the app theme
        themeSignature = Palette.signature
        window.setBackgroundDrawable(ColorDrawable(Palette.BG)) // theme colour from the first frame
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = Palette.LIGHT
            isAppearanceLightNavigationBars = Palette.LIGHT
        }

        // landscape: two card columns, a one-row dock and notch-safe padding (the activity is rebuilt on rotation)
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // flat dark canvas behind everything; content is padded by the system bars
        val root = FrameLayout(kit.ctx)
        val aurora = AuroraBackground(kit.ctx)
        root.addView(aurora, FrameLayout.LayoutParams(-1, -1))
        val page = FrameLayout(kit.ctx).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val homeCards = ArrayList<View>()
        val screenCards = ArrayList<View>()
        homeCards.add(buildBrandHeader())
        // ---- launcher: RAM meter + clean button, then the pinned apps
        homeLauncher = HomeLauncher(this, kit)
        homeMonitor = HomeMonitor(this, kit) { done -> homeLauncher.cleanRam(done) }
        homeCards.add(homeMonitor.build())
        homeCards.add(homeLauncher.buildAppsCard())

        // ---- output
        multiplierSlider = Slider(
            kit, FluxaOptions.MIN_MULTIPLIER, FluxaOptions.MAX_MULTIPLIER, FluxaOptions.DEFAULT_MULTIPLIER,
            format = FluxaOptions::multiplierLabel,
        ) { saveInt(KEY_MULT_X, it); refreshStatus() }
        presentSeg = Segmented(kit, listOf("FIFO · vsync", "MAILBOX · latency ต่ำ"), 0) { i -> saveInt(KEY_PRESENT, i); refreshStatus(); true }
        qualitySlider = Slider(
            kit, FluxaOptions.MIN_FLOW_SIDE, FluxaOptions.MAX_FLOW_SIDE, FluxaOptions.DEFAULT_FLOW_SIDE,
            step = FluxaOptions.FLOW_SIDE_STEP, format = { "$it px" },
        ) { saveInt(KEY_FLOW_SIDE, it); refreshStatus() }

        genModeSeg = Segmented(kit, FluxaOptions.GEN_MODE_LABELS, FluxaOptions.GEN_MODE_LITE) { i ->
            saveInt(KEY_GEN_MODE, i); true
        }

        targetFpsSlider = Slider(
            kit, FluxaOptions.MIN_TARGET_FPS, FluxaOptions.MAX_TARGET_FPS, FluxaOptions.DEFAULT_TARGET_FPS,
            step = FluxaOptions.TARGET_FPS_STEP, format = FluxaOptions::targetFpsLabel,
        ) { saveInt(KEY_TARGET_FPS, it); refreshStatus() }
        outModeSeg = Segmented(kit, FluxaOptions.OUT_MODE_LABELS, FluxaOptions.OUT_MODE_MULTIPLIER) { i ->
            saveInt(KEY_OUT_MODE, i); updateOutModeViews(i); refreshStatus(); true
        }
        multCaption = kit.caption("ตัวคูณเฟรม · เจนจำนวนเฟรมคงที่")
        fpsCaption = kit.caption("เป้าหมาย fps · เจนเท่าที่จำเป็นให้ถึงเป้า")

        val outCard = kit.card("เอาต์พุต", Palette.SECONDARY)
        outCard.addView(kit.section("จำนวนเฟรม", first = true))
        outCard.addView(kit.caption("โหมดเอาต์พุต"))
        outCard.addView(outModeSeg)
        outCard.addView(multCaption)
        outCard.addView(multiplierSlider)
        outCard.addView(fpsCaption)
        outCard.addView(targetFpsSlider)
        updateOutModeViews(FluxaOptions.OUT_MODE_MULTIPLIER)
        outCard.addView(kit.section("คุณภาพและการแสดงผล"))
        outCard.addView(kit.caption("คุณภาพ"))
        outCard.addView(qualitySlider)
        outCard.addView(kit.caption("อัลกอริทึมเจนเฟรม · ยิ่งเบา GPU ยิ่งน้อย"))
        outCard.addView(genModeSeg)
        outCard.addView(kit.caption("โหมดแสดงผล"))
        outCard.addView(presentSeg)
        // the "เฟรม" tab was removed: outCard stays built (its controls are still referenced) but is not shown

        // ---- screen resolution + DPI (WRITE_SECURE_SETTINGS)
        screenCards.add(pageTitle("จอ", "ความละเอียดและ DPI ของหน้าจอ"))
        screenCards.add(buildCompareButton())
        screenCards.add(buildPermissionCard())
        screenCards.add(buildDisplayCard())
        screenCards.add(buildGameProfilesCard())
        // ---- "ภาพ" tab: upscale + sharpness + noise / colour, each in its own card
        // the "ภาพ" tab was removed: its cards are still built (controls are referenced elsewhere) but not shown;
        // the compare button now lives in the "จอ" tab
        buildUpscaleCards()
        buildColorCards()

        // ---- app settings: theme, look, effects, language
        // the settings tab is a hub: one row per category + a search box; each category opens its own cards
        catCards["look"] = listOf(buildThemeCard(), buildLookCard(), buildEffectsCard())
        catCards["panel"] = listOf(buildPanelCard())
        catCards["access"] = buildAccessCards()
        catCards["lang"] = listOf(buildLanguageCard())


        fun pageOf(cards: List<View>): ScrollView {
            val box = LinearLayout(kit.ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(16))
            }
            if (land && cards.size > 2) {
                // header full width, the rest alternate between a left and a right column
                val gap = kit.dp(AppSettings.cardGap)
                box.addView(cards[0], LinearLayout.LayoutParams(-1, -2))
                val cols = LinearLayout(kit.ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val left = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
                val right = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
                cards.drop(1).forEachIndexed { i, v ->
                    val col = if (i % 2 == 0) left else right
                    col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (col.childCount > 0) topMargin = gap })
                }
                cols.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                cols.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap })
                box.addView(cols, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
            } else {
                cards.forEachIndexed { i, v ->
                    box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = kit.dp(AppSettings.cardGap) })
                }
            }
            return ScrollView(kit.ctx).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false // content scrolls under the floating dock
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(box)
            }
        }
        val host = FrameLayout(kit.ctx)
        tabPages = listOf(pageOf(homeCards), pageOf(screenCards), settingsPage())
        pageHost = host
        tabPages.forEachIndexed { i, v ->
            v.visibility = if (i == curTab) View.VISIBLE else View.INVISIBLE
            host.addView(v, FrameLayout.LayoutParams(-1, -1))
        }
        page.addView(host, FrameLayout.LayoutParams(-1, -1))

        // ---- floating glass dock: primary action above the tab bar. Nothing solid sits behind it, so
        // the buttons really float; with Blur on they turn into translucent frosted glass (iOS style).
        fun iconButton(kind: GlyphView.Kind, color: Int, tone: Glass.Tone, desc: String, onClick: () -> Unit) =
            FrameLayout(kit.ctx).apply {
                background = Glass.make(kit, tone, 28, glass)
                isClickable = true
                isFocusable = true
                contentDescription = I18n.tr(desc)
                setOnClickListener { onClick() }
                glassPress()
                addView(GlyphView(kit.ctx, kind, color), FrameLayout.LayoutParams(kit.dp(22), kit.dp(22), Gravity.CENTER))
            }
        val start = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER
            background = Glass.make(kit, Glass.Tone.ACCENT, 28, glass)
            isClickable = true
            isFocusable = true
            setOnClickListener { requestStart() }
            glassPress(0.96f)
            addView(
                GlyphView(kit.ctx, GlyphView.Kind.PLAY, Palette.ON_ACCENT),
                LinearLayout.LayoutParams(kit.dp(20), kit.dp(20)).apply { marginEnd = kit.dp(8) },
            )
            addView(kit.text("เริ่มใช้งาน", 16f, Palette.ON_ACCENT, bold = true))
        }
        val stop = iconButton(GlyphView.Kind.STOP, Palette.DANGER, Glass.Tone.DANGER, "หยุด") {
            startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        val actions = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(start, LinearLayout.LayoutParams(0, kit.dp(56), 1f))
        actions.addView(stop, LinearLayout.LayoutParams(kit.dp(56), kit.dp(56)).apply { marginStart = kit.dp(10) })

        val dock = LinearLayout(kit.ctx).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), 0, kit.dp(16), kit.dp(10))
            // only a faint fade, so text scrolling past the gaps between the buttons stays calm
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.a(Palette.BG, 0x00), Palette.a(Palette.BG, if (AppSettings.blur) 0x66 else 0xE6)),
            )
        }
        if (land) {
            // one row: start / stop on the left, tabs on the right (landscape height is scarce)
            dock.addView(actions, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = kit.dp(12) })
            dock.addView(buildTabBar(), LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = kit.dp(12); marginStart = kit.dp(12) })
        } else {
            dock.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
            dock.addView(buildTabBar(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
        }
        page.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // the glass samples the page glow + the scrolling pages that sit behind the dock
        glass.bind(dock, listOf(aurora, host))
        tabPages.forEach { p ->
            (p as? ScrollView)?.setOnScrollChangeListener { _, _, _, _, _ -> glass.request() }
        }
        dock.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val h = b - t + kit.dp(4)
            tabPages.forEach { if (it.paddingBottom != h) it.setPadding(0, 0, 0, h) }
            glass.request()
        }

        setContentView(root)
        if (pendingCat != null) { openCat = pendingCat; pendingCat = null; renderSettings() }
        if (pendingScroll > 0) {
            // came back from a settings change: put the settings page back where it was
            val sv = tabPages[2] as ScrollView
            val y = pendingScroll
            pendingScroll = 0
            sv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (sv.getChildAt(0)?.height ?: 0 <= 0) return
                    sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    sv.scrollTo(0, y)
                }
            })
        }
        // the flat canvas covers the whole window: drop the theme background after the first frame
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                root.post { window.setBackgroundDrawable(null) }
                return true
            }
        })
        restoreSettings()
        refreshStatus()
    }

    /** Keep controls static and predictable; click listeners provide the action without scaling animations. */
    private fun View.pressable() {
        isClickable = true
        isFocusable = true
    }

    private fun currentDispConfig() = DisplayTuner.Config(
        enabled = dispEnableSeg.selected == 1,
        percent = dispPctSlider.value,
        dpiAuto = dispDpiSeg.selected == 0,
        dpi = dispDpiSlider.value,
        upscale = dispUpscaleSeg.selected == 0,
        upscaleFilter = dispFilterModes.getOrNull(dispFilterSeg.selected)?.first ?: DisplayTuner.FILTER_LINEAR,
        upscaleSharp = dispSharpSlider.value,
        upscaleSharpMethod = DisplayTuner.sharpMethods.getOrNull(dispSharpMethodSeg.selected)?.first
            ?: DisplayTuner.SHARP_RCAS,
        upPercent = dispOutSlider.value,
        deband = dispDebandSlider.value,
        denoise = dispDenoiseSlider.value,
        aa = DisplayTuner.aaModes.getOrNull(dispAaSeg.selected)?.first ?: DisplayTuner.AA_OFF,
        dither = dispDitherSeg.selected == 1,
    )

    private fun refreshDisplayInfo() {
        if (!::dispSummary.isInitialized || !::dispPctSlider.isInitialized || !::dispDpiSlider.isInitialized || !::dispOutSlider.isInitialized) return
        val orig = DisplayTuner.original(this)
        val perm = DisplayTuner.canWrite(this)
        dispInfo.text = buildString {
            append(if (orig != null) "จอจริง: ${orig.width}×${orig.height} · ${orig.dpi} dpi" else "อ่านค่าจอจริงไม่ได้")
            val cur = DisplayTuner.current(this@MainActivity)
            if (orig != null && cur != null && (cur.width != orig.width || cur.height != orig.height || cur.dpi != orig.dpi)) {
                append("\nตอนนี้: ${cur.width}×${cur.height} · ${cur.dpi} dpi (ถูกปรับอยู่ · กด \"คืนค่าจอเดิม\" เพื่อกลับเป็นจอจริง)")
            }
        }
        dispInfo.setTextColor(Palette.MUTED)
        if (::permState.isInitialized) {
            permState.text = if (perm) "{ic:tick} ให้สิทธิ์แล้ว" else "{ic:close} ยังไม่ได้ให้สิทธิ์"
            permState.setTextColor(if (perm) Palette.OK else Palette.WARN)
            permBtn.visibility = if (perm) View.GONE else View.VISIBLE
        }
        dispSummary.text =
            if (orig == null) ""
            else {
                val dc = currentDispConfig()
                val p = DisplayTuner.plan(orig, dc)
                val up = if (dc.upscale) DisplayTuner.upscaleTarget(p.width, p.height, dc.upPercent) else (0 to 0)
                "ตอนเริ่มจะตั้งเป็น ${p.width}×${p.height} · ${p.dpi} dpi" +
                    if (up.first > 0) "\nอัปสเกลเป็น ${up.first}×${up.second}" else "\nไม่อัปสเกล"
            }
    }

    private lateinit var permState: TextView
    private lateinit var permBtn: View

    /** Screen tab: one clear card for the WRITE_SECURE_SETTINGS permission; the button opens [PermissionActivity]. */
    private fun buildPermissionCard(): View {
        val card = kit.card("สิทธิ์ปรับหน้าจอ", Palette.WARN)
        card.addView(kit.caption("ต้องให้สิทธิ์ครั้งเดียวผ่าน adb จึงจะปรับความละเอียด/DPI ได้ (ไม่บังคับ ถ้าไม่ปรับจอ)"))
        permState = kit.text("", 13.5f, Palette.WARN, bold = true).apply { setPadding(0, kit.dp(10), 0, 0) }
        card.addView(permState)
        permBtn = kit.text("ขอสิทธิ์ตอนนี้", 14f, Palette.PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(8), kit.dp(13), kit.dp(8), kit.dp(13))
            background = kit.lit(16)
            setOnClickListener { startActivity(Intent(this@MainActivity, PermissionActivity::class.java)); AppSettings.transition(this@MainActivity, true) }
            pressable()
        }
        card.addView(permBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
        return card
    }

    private fun buildDisplayCard(): View {
        val card = kit.card("ความละเอียด · DPI", Palette.TERTIARY)

        dispInfo = kit.text("", 12f, Palette.MUTED)
        card.addView(dispInfo)

        dispEnableSeg = Segmented(kit, listOf("ปิด", "เปิดตอนกดเริ่ม"), 0) { i ->
            saveBool(DisplayTuner.K_ENABLED, i == 1)
            true
        }
        card.addView(kit.section("ความละเอียดจอ", first = true))
        card.addView(kit.caption("ปรับตอนกดเริ่ม · ไม่คืนค่าอัตโนมัติ ต้องกด \"คืนค่าจอเดิม\""))
        card.addView(dispEnableSeg)

        dispPctSlider = Slider(
            kit, DisplayTuner.MIN_PERCENT, DisplayTuner.MAX_PERCENT, DisplayTuner.DEFAULT_PERCENT,
            step = DisplayTuner.PERCENT_STEP, format = { "$it%" },
            onPreview = { refreshDisplayInfo() },
        ) { saveInt(DisplayTuner.K_PERCENT, it); refreshDisplayInfo() }
        card.addView(kit.caption("ความละเอียดจอจริง · % ของเดิม (10–200%) · เกิน 100% ภาพเนียนขึ้นแต่หนัก GPU/แรม"))
        card.addView(dispPctSlider)

        dispDpiSeg = Segmented(kit, listOf("อัตโนมัติ (ตามสัดส่วน)", "กำหนดเอง"), 0, textSp = 13f) { i ->
            saveBool(DisplayTuner.K_DPI_AUTO, i == 0)
            dispDpiBox.visibility = if (i == 1) View.VISIBLE else View.GONE
            refreshDisplayInfo()
            true
        }
        dispDpiSlider = Slider(
            kit, DisplayTuner.MIN_DPI, DisplayTuner.MAX_DPI, 400,
            step = DisplayTuner.DPI_STEP, format = { "$it dpi" },
            onPreview = { refreshDisplayInfo() },
        ) { saveInt(DisplayTuner.K_DPI, it); refreshDisplayInfo() }
        dispDpiBox = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        dispDpiBox.addView(dispDpiSlider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
        card.addView(kit.caption("DPI"))
        card.addView(dispDpiSeg)
        card.addView(dispDpiBox)

        dispSummary = kit.text("", 13f, Palette.PRIMARY, bold = true).apply { setPadding(0, kit.dp(14), 0, 0) }
        card.addView(dispSummary)

        val row = LinearLayout(kit.ctx).apply { setPadding(0, kit.dp(14), 0, 0) }
        fun action(label: String, color: Int, onClick: () -> Unit) = kit.text(label, 14f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(8), kit.dp(12), kit.dp(8), kit.dp(12))
            background = kit.glassBtn(16)
            setOnClickListener { onClick() }
            pressable()
        }
        row.addView(action("ทดลองใช้ตอนนี้", Palette.PRIMARY) {
            val orig = DisplayTuner.original(this)
            if (orig == null || !DisplayTuner.canWrite(this)) {
                I18n.toastMake(this, "ต้องมีสิทธิ์ WRITE_SECURE_SETTINGS ก่อน", Toast.LENGTH_LONG).show()
            } else {
                val plan = DisplayTuner.plan(orig, currentDispConfig())
                // restored only by the confirm dialog (timeout / "คืนค่าเดิม") or the "คืนค่าจอเดิม" button
                if (DisplayTuner.apply(this, plan)) {
                    // safety: 10 s countdown, the screen goes back by itself unless the user confirms
                    DisplayConfirm.show(this) { kept ->
                        if (!kept) I18n.toastMake(this, "คืนค่าจอเดิมแล้ว", Toast.LENGTH_LONG).show()
                    }
                } else {
                    I18n.toastMake(this, "ปรับไม่ได้", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(action("คืนค่าจอเดิม", Palette.OK) {
            val ok = DisplayTuner.restore(this)
            I18n.toastMake(this, if (ok) "คืนค่าจอเดิมแล้ว" else "คืนค่าจอเดิมไม่ได้", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) })
        card.addView(row)
        card.addView(
            kit.text(
                I18n.f("เกมเรนเดอร์ที่ความละเอียดต่ำลงแล้ว {0} ขยายกลับก่อนแสดง", getString(R.string.fluxa_app_name)) + " · หลังปรับจะมีนับถอยหลัง 10 วินาที ต้องกด \"ยืนยัน\" ไม่เช่นนั้นจะคืนค่าจอเดิมให้เอง",
                11.5f, Palette.DIM,
            ).apply { setPadding(0, kit.dp(12), 0, 0) },
        )
        return card
    }

    private lateinit var gameProfileList: LinearLayout

    /** "จอ" tab: games that have a saved profile (saved from the in-game panel), each with a delete button. */
    private fun buildGameProfilesCard(): View {
        val card = kit.card("โปรไฟล์เกม", Palette.SECONDARY)
        card.addView(
            kit.text(
                "บันทึกจากแผงควบคุมในเกม (ไทล์ \"โปรไฟล์เกม\") · ตอนเปิดเกมที่บันทึกไว้ ค่าเฟรม อัปสเกล และสีจะสลับให้เอง",
                11.5f, Palette.DIM,
            ).apply { setPadding(0, kit.dp(10), 0, kit.dp(4)) },
        )
        gameProfileList = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(gameProfileList, LinearLayout.LayoutParams(-1, -2))
        refreshGameProfiles()
        return card
    }

    private fun refreshGameProfiles() {
        if (!::gameProfileList.isInitialized) return
        gameProfileList.removeAllViews()
        val games = GameProfiles.all(this)
        if (games.isEmpty()) {
            gameProfileList.addView(kit.text("ยังไม่มีโปรไฟล์", 13f, Palette.MUTED).apply { setPadding(0, kit.dp(8), 0, kit.dp(4)) })
            return
        }
        games.forEach { pkg ->
            val row = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                kit.text(GameProfiles.label(this, pkg), 14f, Palette.TEXT, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            row.addView(
                kit.text("ลบ", 13.5f, Palette.DANGER, bold = true).apply {
                    setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
                    background = kit.lit(12)
                    pressable()
                    setOnClickListener { GameProfiles.remove(this@MainActivity, pkg); refreshGameProfiles() }
                },
            )
            gameProfileList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
        }
    }

    /** "ภาพ" tab: before/after compare button, pinned at the top of the page. */
    private fun buildCompareButton(): View {
        val box = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            kit.text("เทียบภาพ ก่อน / หลังอัปสเกล", 14.5f, Palette.PRIMARY, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(kit.dp(8), kit.dp(14), kit.dp(8), kit.dp(14))
                background = kit.lit(16)
                setOnClickListener { startActivity(Intent(this@MainActivity, CompareActivity::class.java)); AppSettings.transition(this@MainActivity, true) }
                pressable()
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        box.addView(
            kit.text("ซ้าย = ก่อนอัปสเกล · ขวา = หลัง · ใช้ได้ตอนยังไม่รันเซสชัน", 11.5f, Palette.DIM)
                .apply { setPadding(kit.dp(4), kit.dp(8), 0, 0) },
        )
        return box
    }

    /** "ภาพ" tab, part 1: upscaling + sharpening (moved here from the screen tab). */
    private fun buildUpscaleCards(): List<View> {
        val upCard = kit.card("อัปสเกล", Palette.PRIMARY)
        val sharpCard = kit.card("ความคม", Palette.TERTIARY)
        dispUpscaleSeg = Segmented(kit, listOf("เปิด", "ปิด"), 0, textSp = 13f) { i ->
            saveBool(DisplayTuner.K_UPSCALE, i == 0)
            true
        }
        upCard.addView(kit.caption("อัปสเกลเฟรมที่แสดง"))
        upCard.addView(dispUpscaleSeg)

        dispFilterModes = DisplayTuner.upscaleModes(this)
        val savedMode = prefs.getInt(DisplayTuner.K_UPSCALE_FILTER, DisplayTuner.FILTER_LINEAR)
        dispFilterSeg = Segmented(
            kit, dispFilterModes.map { it.second },
            dispFilterModes.indexOfFirst { it.first == savedMode }.coerceAtLeast(0),
            perRow = 3, textSp = 13f,
        ) { i ->
            saveInt(DisplayTuner.K_UPSCALE_FILTER, dispFilterModes[i].first)
            true
        }
        upCard.addView(kit.caption("ฟิลเตอร์อัปสเกล · Nearest/Linear = เร็วสุด · Mitchell/Catmull-Rom/Lanczos/Spline36/EWA = คมขึ้นตามลำดับ · SGSR/NIS/FSR1 เน้นขอบ · Anime เหมาะภาพอนิเมะ (เปลี่ยนสดได้ในแผง)"))
        upCard.addView(dispFilterSeg)

        dispOutSlider = Slider(
            kit, DisplayTuner.MIN_UP_PERCENT, DisplayTuner.MAX_UP_PERCENT, 0,
            step = DisplayTuner.UP_PERCENT_STEP, format = { if (it == 0) "0 · ไม่อัป" else "+$it%" },
            onPreview = { refreshDisplayInfo() },
        ) { saveInt(DisplayTuner.K_UP_PERCENT, it); refreshDisplayInfo() }
        upCard.addView(kit.caption("ระดับอัปสเกล", "0 = ไม่อัป · +100% = เอาต์พุตใหญ่ 2 เท่า · จำกัดรวมไม่เกิน 16 ล้านพิกเซล"))
        upCard.addView(dispOutSlider)


        dispSharpSlider = Slider(
            kit, 0, DisplayTuner.STRENGTH_MAX, 0, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { saveInt(DisplayTuner.K_UPSCALE_SHARP, it) }
        sharpCard.addView(kit.caption("ความคมหลังอัปสเกล · เกิน 100% ขอบอาจมีฮาโล"))
        sharpCard.addView(dispSharpSlider)

        val savedSharpMethod = prefs.getInt(DisplayTuner.K_UPSCALE_SHARP_METHOD, DisplayTuner.SHARP_RCAS)
        dispSharpMethodSeg = Segmented(
            kit, DisplayTuner.sharpMethods.map { it.second },
            DisplayTuner.sharpMethods.indexOfFirst { it.first == savedSharpMethod }.coerceAtLeast(0),
            textSp = 13f,
        ) { i ->
            saveInt(DisplayTuner.K_UPSCALE_SHARP_METHOD, DisplayTuner.sharpMethods[i].first)
            true
        }
        sharpCard.addView(kit.caption("วิธีเพิ่มความคม · RCAS · CAS · Adaptive (ลดฮาโล) · Luma (ไม่มีขอบสี) · Clarity (เน้นพื้นผิว)"))
        sharpCard.addView(dispSharpMethodSeg)

        return listOf(upCard, sharpCard)
    }

    /** "ภาพ" tab, part 2: cleanup (denoise / AA / deband / dither) and the colour grade. */
    private fun buildColorCards(): List<View> {
        val card = kit.card("ลดนอยส์และขอบ", Palette.SECONDARY)
        val gradeCard = kit.card("ปรับสี", Palette.PRIMARY)

        // ---- extra enhancement passes (also work without upscaling) ----
        dispDenoiseSlider = Slider(
            kit, 0, DisplayTuner.STRENGTH_MAX, 0, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { saveInt(DisplayTuner.K_ENH_DENOISE, it) }
        card.addView(kit.caption("ลดสัญญาณรบกวน/บล็อก (Denoise/Deblock) · ทำก่อนอัปสเกล รักษาขอบไว้ (เปลี่ยนสดได้ในแผง)"))
        card.addView(dispDenoiseSlider)

        val savedAa = prefs.getInt(DisplayTuner.K_ENH_AA, DisplayTuner.AA_OFF)
        dispAaSeg = Segmented(
            kit, DisplayTuner.aaModes.map { it.second },
            DisplayTuner.aaModes.indexOfFirst { it.first == savedAa }.coerceAtLeast(0),
            textSp = 13f,
        ) { i ->
            saveInt(DisplayTuner.K_ENH_AA, DisplayTuner.aaModes[i].first)
            true
        }
        card.addView(kit.caption("ลดฟันปลา (Anti-aliasing) · FXAA นุ่มทั้งภาพ · SMAA-lite แตะเฉพาะขอบ ภาพยังคม"))
        card.addView(dispAaSeg)

        dispDebandSlider = Slider(
            kit, 0, DisplayTuner.STRENGTH_MAX, 0, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { saveInt(DisplayTuner.K_ENH_DEBAND, it) }
        card.addView(kit.caption("ลดแถบสี (Deband) · ช่วยฉากไล่เฉดที่เป็นขั้น"))
        card.addView(dispDebandSlider)

        val savedDither = prefs.getBoolean(DisplayTuner.K_ENH_DITHER, false)
        dispDitherSeg = Segmented(
            kit, listOf("ปิด", "เปิด"), if (savedDither) 1 else 0, textSp = 13f,
        ) { i ->
            saveBool(DisplayTuner.K_ENH_DITHER, i == 1)
            true
        }
        card.addView(kit.caption("Dither · เติมนอยส์ละเอียดให้แถบสีกลืนไปกับภาพ"))
        card.addView(dispDitherSeg)

        // ---- colour grade (final pass at output size, also works without upscaling) ----
        fun signed(v: Int) = if (v > 0) "+$v" else "$v"
        fun gradeSlider(caption: String, key: String): Slider {
            val slider = Slider(
                kit, -DisplayTuner.GRADE_RANGE, DisplayTuner.GRADE_RANGE, 0, step = 5, format = ::signed,
            ) { saveInt(key, it) }
            gradeCard.addView(kit.caption(caption))
            gradeCard.addView(slider)
            return slider
        }
        dispBrightSlider = gradeSlider("ความสว่าง (Brightness) · ยกโทนกลาง ไม่ทำให้ขาวไหม้หรือดำจม", DisplayTuner.K_LOOK_BRIGHT)
        dispContrastSlider = gradeSlider("คอนทราสต์ (Contrast) · + = ภาพคมชัดขึ้น · − = นุ่มเรียบขึ้น", DisplayTuner.K_LOOK_CONTRAST)
        dispSatSlider = gradeSlider("ความอิ่มสี (Saturation) · −100 = ขาวดำ · +100 = สีจัดเป็นสองเท่า", DisplayTuner.K_LOOK_SAT)
        dispVibSlider = gradeSlider("Vibrance · เร่งเฉพาะสีที่จืด", DisplayTuner.K_LOOK_VIB)
        dispTempSlider = gradeSlider("อุณหภูมิสี (White balance) · − = เย็น/ฟ้า · + = อุ่น/ส้ม", DisplayTuner.K_LOOK_TEMP)
        dispTintSlider = gradeSlider("Tint · − = เขียว · + = ม่วงแดง", DisplayTuner.K_LOOK_TINT)
        gradeCard.addView(
            kit.text("รีเซ็ตสี", 14f, Palette.PRIMARY, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(kit.dp(8), kit.dp(12), kit.dp(8), kit.dp(12))
                background = kit.glassBtn(16)
                pressable()
                setOnClickListener {
                    prefs.edit()
                        .putInt(DisplayTuner.K_LOOK_BRIGHT, 0)
                        .putInt(DisplayTuner.K_LOOK_CONTRAST, 0)
                        .putInt(DisplayTuner.K_LOOK_SAT, 0)
                        .putInt(DisplayTuner.K_LOOK_VIB, 0)
                        .putInt(DisplayTuner.K_LOOK_TEMP, 0)
                        .putInt(DisplayTuner.K_LOOK_TINT, 0)
                        .apply()
                    restoreDisplaySettings()
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) },
        )

        return listOf(card, gradeCard)
    }

    /** Large title + one-line subtitle at the top of the frame / screen / colour tabs. */
    private fun pageTitle(title: String, subtitle: String): View = LinearLayout(kit.ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(4), kit.dp(6), 0, kit.dp(2))
        val row = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        if (kit.rm) kit.addMark(row, 26, 10)
        else row.addView(
            View(kit.ctx).apply { background = kit.rounded(Palette.PRIMARY, 2) },
            LinearLayout.LayoutParams(kit.dp(4), kit.dp(24)).apply { marginEnd = kit.dp(10) },
        )
        row.addView(kit.display(title, 27f * (if (kit.rm) 1.08f else 1f), Palette.TEXT).apply { if (!kit.rm) letterSpacing = -0.01f })
        addView(row)
        addView(
            kit.text(subtitle, 12.5f, Palette.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(6); marginStart = kit.dp(14) },
        )
    }

    /** Home header: round glass logo, app name, and a chip that says whether the system is ready to start. */
    private fun buildBrandHeader(): View {
        val row = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(4), kit.dp(6), 0, kit.dp(2))
        }
        val logo = FrameLayout(kit.ctx).apply {
            if (kit.rm) {
                background = kit.glassBtn(14)
                setPadding(kit.dp(4), kit.dp(4), kit.dp(4), kit.dp(4))
            } else {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.PRIMARY_SOFT) }
                clipToOutline = true
            }
            addView(
                ImageView(kit.ctx).apply {
                    setImageResource(R.drawable.fluxa_ic_launcher_foreground)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    scaleX = 1.45f
                    scaleY = 1.45f
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        }
        row.addView(logo, LinearLayout.LayoutParams(kit.dp(46), kit.dp(46)))
        val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            if (kit.rm) kit.display(getString(R.string.fluxa_app_name).uppercase(), 26f, Palette.TEXT).apply { letterSpacing = 0.06f }
            else kit.text(getString(R.string.fluxa_app_name), 23f, Palette.TEXT, bold = true, spacing = -0.01f),
        )
        col.addView(
            kit.text("เพิ่มเฟรมเรต · ปรับจอ · ปรับภาพ", 12f, Palette.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(3) },
        )
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(14) })
        // profile button: avatar (set in the profile page) + a dot that says whether the system is ready
        headerAvatar = AvatarView(kit.ctx).apply { ringColor = Palette.PRIMARY_LINE; ringPx = kit.dpf(2) }
        headerDot = View(kit.ctx)
        val profileBtn = FrameLayout(kit.ctx).apply {
            isClickable = true
            isFocusable = true
            contentDescription = I18n.tr("โปรไฟล์")
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ProfileActivity::class.java))
                AppSettings.transition(this@MainActivity, true)
            }
            addView(headerAvatar, FrameLayout.LayoutParams(kit.dp(46), kit.dp(46)))
            addView(headerDot, FrameLayout.LayoutParams(kit.dp(14), kit.dp(14), Gravity.BOTTOM or Gravity.END))
        }
        row.addView(profileBtn, LinearLayout.LayoutParams(kit.dp(46), kit.dp(46)).apply { marginEnd = kit.dp(2) })
        return row
    }

    /** Re-reads the current choices into the chips of the hero card. */
    private fun refreshHero(ready: Boolean, acc: Boolean) {
        if (!::heroTitle.isInitialized || !::multiplierSlider.isInitialized) return
        heroDot.background = kit.rounded(if (ready) Palette.OK else Palette.WARN, 5)
        heroTitle.text = if (ready) "พร้อมเจนเฟรม" else "ต้องตั้งค่าก่อนเริ่ม"
        heroSub.text = when {
            ready -> "กด เริ่ม ด้านล่าง แล้วเลือก \"แอพเดียว\" เป็นเกมที่ต้องการ"
            !acc -> "แตะการ์ดนี้เพื่อไปหน้าตั้งค่าเริ่มต้น แล้วเปิดบริการ Accessibility"
            else -> "ต้องใช้ Android 14 ขึ้นไปสำหรับจับภาพแอพเดียว"
        }
        val target = outModeSeg.selected == FluxaOptions.OUT_MODE_TARGET
        val chips = listOf(
            if (target) FluxaOptions.targetFpsLabel(targetFpsSlider.value) else FluxaOptions.multiplierLabel(multiplierSlider.value),
            "${qualitySlider.value} px",
            if (presentSeg.selected == 1) "MAILBOX" else "FIFO",
        )
        heroChips.removeAllViews()
        chips.forEachIndexed { i, label ->
            heroChips.addView(
                kit.text(label, 12.5f, Palette.TEXT, bold = true).apply {
                    fontFeatureSettings = "tnum"
                    setPadding(kit.dp(12), kit.dp(7), kit.dp(12), kit.dp(7))
                    background = kit.rounded(Palette.CHIP_HI, 12, Palette.STROKE, 1)
                },
                LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = kit.dp(8) },
            )
        }
    }

    /** Dots + state text of the checklist, and the ready / needs-setup chip in the header. */
    private fun refreshStatus() {
        if (!::headerAvatar.isInitialized) return
        val acc = OverlayAccessibilityService.instance != null
        val sdk = Build.VERSION.SDK_INT >= 34
        val ready = acc && sdk
        headerDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (ready) Palette.OK else Palette.WARN)
            setStroke(kit.dp(2), Palette.BG)
        }
        refreshHero(ready, acc)
    }

    /** Frosted glass dock with a liquid pill that glides to the tapped tab. */
    private fun buildTabBar(): View {
        val specs = listOf(
            "หลัก" to TabIcon.Kind.HOME,
            "จอ" to TabIcon.Kind.SCREEN,
            "ตั้งค่า" to TabIcon.Kind.SETTINGS,
        )
        val bar = FrameLayout(kit.ctx).apply {
            background = Glass.make(kit, Glass.Tone.NEUTRAL, 30, glass)
        }
        tabPill = LiquidPill(kit.ctx, kit.dpf(24), kit.dpf(6))
        bar.addView(tabPill, FrameLayout.LayoutParams(-1, -1))
        val row = LinearLayout(kit.ctx).apply { setPadding(kit.dp(6), 0, kit.dp(6), 0) }
        specs.forEachIndexed { i, (name, kind) ->
            val icon = TabIcon(kit.ctx, kind)
            val label = kit.text(name, 11f, Palette.MUTED, bold = true)
            if (!AppSettings.tabLabels) label.visibility = View.GONE
            val item = LinearLayout(kit.ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, kit.dp(11), 0, kit.dp(10))
                addView(icon, LinearLayout.LayoutParams(kit.dp(24), kit.dp(24)))
                addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(3) })
                if (!AppSettings.tabLabels) setPadding(0, kit.dp(14), 0, kit.dp(14))
                setOnClickListener { showTab(i) }
                pressable()
                glassPress(0.92f)
            }
            tabs.add(TabItem(item, icon, label))
            row.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(row, FrameLayout.LayoutParams(-1, -2))
        tabRow = row
        row.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> if (r - l > 0) moveIndicator() }
        tabs.forEachIndexed { k, t -> t.setActive(k == curTab) }
        return bar
    }

    private fun moveIndicator(animate: Boolean = false) {
        val item = tabs.getOrNull(curTab)?.root ?: return
        if (item.width <= 0) return
        val l = tabRow.left + item.left.toFloat()
        if (animate) tabPill.flowTo(l, l + item.width) else tabPill.moveTo(l, l + item.width)
    }

    private lateinit var pageHost: FrameLayout

    private fun resetPage(v: View) {
        v.animate().cancel()
        v.alpha = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null)
    }

    /** Animates a blur radius on [v] (Android 12+, only when Blur is on in Settings). */
    private fun blurPage(v: View, from: Float, to: Float, ms: Long) {
        if (Build.VERSION.SDK_INT < 31 || !AppSettings.blur) return
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            addUpdateListener {
                val r = it.animatedValue as Float
                v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
            start()
        }
    }

    /** Switches tab with the page animation chosen in Settings (none / fade / slide / zoom / rise). */
    private fun showTab(i: Int) {
        if (i !in tabPages.indices) return
        if (i == curTab) {
            tabs[i].icon.play()
            return
        }
        val from = curTab
        curTab = i
        syncBack()
        tabs.forEachIndexed { k, t -> t.setActive(k == i) }
        moveIndicator(animate = true)
        glass.pump(AppSettings.animMs + 120L)
        tabPages.forEach { resetPage(it) }
        val old = tabPages[from]
        val new = tabPages[i]
        val mode = AppSettings.pageAnim
        if (mode == AppSettings.ANIM_NONE || pageHost.width <= 0) {
            tabPages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.INVISIBLE }
            return
        }
        tabPages.forEachIndexed { k, v -> v.visibility = if (k == i || k == from) View.VISIBLE else View.INVISIBLE }
        new.bringToFront()
        // keep the dock above the incoming page
        val dur = AppSettings.animMs.toLong()
        val dir = if (i > from) 1 else -1
        val shift = pageHost.width * 0.3f
        val ease = DecelerateInterpolator(1.6f)
        when (mode) {
            AppSettings.ANIM_FADE -> {
                new.alpha = 0f
                new.animate().alpha(1f).setDuration(dur).setInterpolator(ease).withLayer().start()
                old.animate().alpha(0f).setDuration(dur).setInterpolator(ease).withLayer().start()
            }
            AppSettings.ANIM_SLIDE -> {
                new.translationX = dir * shift; new.alpha = 0f
                new.animate().translationX(0f).alpha(1f).setDuration(dur).setInterpolator(ease).withLayer().start()
                old.animate().translationX(-dir * shift).alpha(0f).setDuration(dur).setInterpolator(ease).withLayer().start()
            }
            AppSettings.ANIM_ZOOM -> {
                new.scaleX = 0.92f; new.scaleY = 0.92f; new.alpha = 0f
                new.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(dur).setInterpolator(ease).withLayer().start()
                old.animate().scaleX(1.06f).scaleY(1.06f).alpha(0f).setDuration(dur).setInterpolator(ease).withLayer().start()
            }
            else -> { // rise
                new.translationY = kit.dpf(36); new.alpha = 0f
                new.animate().translationY(0f).alpha(1f).setDuration(dur).setInterpolator(ease).withLayer().start()
                old.animate().alpha(0f).setDuration(dur).setInterpolator(ease).withLayer().start()
            }
        }
        blurPage(new, 22f, 0f, dur)
        blurPage(old, 0f, 22f, dur)
        old.animate().setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                old.animate().setListener(null)
                if (curTab != from) {
                    resetPage(old)
                    old.visibility = View.INVISIBLE
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, curTab)
    }

    /** Shows the slider that belongs to the selected output mode. */
    private fun updateOutModeViews(mode: Int) {
        val target = mode == FluxaOptions.OUT_MODE_TARGET
        multCaption.visibility = if (target) View.GONE else View.VISIBLE
        multiplierSlider.visibility = if (target) View.GONE else View.VISIBLE
        fpsCaption.visibility = if (target) View.VISIBLE else View.GONE
        targetFpsSlider.visibility = if (target) View.VISIBLE else View.GONE
    }

    /** Re-applies the choices saved on the last run (programmatic select never fires listeners). */
    private fun restoreSettings() {
        multiplierSlider.set(FluxaOptions.clampMultiplier(prefs.getInt(KEY_MULT_X, FluxaOptions.DEFAULT_MULTIPLIER)))
        presentSeg.select(if (prefs.getInt(KEY_PRESENT, 0) == 1) 1 else 0)
        qualitySlider.set(FluxaOptions.clampFlowSide(prefs.getInt(KEY_FLOW_SIDE, FluxaOptions.DEFAULT_FLOW_SIDE)))
        genModeSeg.select(FluxaOptions.clampGenMode(prefs.getInt(KEY_GEN_MODE, FluxaOptions.GEN_MODE_LITE)))
        targetFpsSlider.set(FluxaOptions.clampTargetFps(prefs.getInt(KEY_TARGET_FPS, FluxaOptions.DEFAULT_TARGET_FPS)))
        val outMode = if (prefs.getInt(KEY_OUT_MODE, 0) == FluxaOptions.OUT_MODE_TARGET) FluxaOptions.OUT_MODE_TARGET else FluxaOptions.OUT_MODE_MULTIPLIER
        outModeSeg.select(outMode)
        updateOutModeViews(outMode)

        restoreDisplaySettings()
    }

    /** Display-card controls from the saved prefs (also re-run on resume: the compare screen edits them). */
    private fun restoreDisplaySettings() {
        val dc = DisplayTuner.config(this)
        dispEnableSeg.select(if (dc.enabled) 1 else 0)
        dispPctSlider.set(dc.percent)
        dispDpiSeg.select(if (dc.dpiAuto) 0 else 1)
        dispDpiSlider.set(dc.dpi)
        dispDpiBox.visibility = if (dc.dpiAuto) View.GONE else View.VISIBLE
        dispUpscaleSeg.select(if (dc.upscale) 0 else 1)
        dispFilterSeg.select(dispFilterModes.indexOfFirst { it.first == dc.upscaleFilter }.coerceAtLeast(0))
        dispSharpSlider.set(dc.upscaleSharp)
        dispSharpMethodSeg.select(
            DisplayTuner.sharpMethods.indexOfFirst { it.first == dc.upscaleSharpMethod }.coerceAtLeast(0),
        )
        dispOutSlider.set(dc.upPercent)
        dispDenoiseSlider.set(dc.denoise)
        dispAaSeg.select(DisplayTuner.aaModes.indexOfFirst { it.first == dc.aa }.coerceAtLeast(0))
        dispDebandSlider.set(dc.deband)
        dispDitherSeg.select(if (dc.dither) 1 else 0)
        dispBrightSlider.set(dc.brightness)
        dispContrastSlider.set(dc.contrast)
        dispSatSlider.set(dc.saturation)
        dispVibSlider.set(dc.vibrance)
        dispTempSlider.set(dc.temperature)
        dispTintSlider.set(dc.tint)
        refreshDisplayInfo()
    }

    override fun onResume() {
        super.onResume()
        // the app theme may have been changed in Settings while this screen was in the background
        Palette.sync(this)
        if (::kit.isInitialized && Palette.signature != themeSignature) {
            recreate()
            return
        }
        if (::dispOutSlider.isInitialized) {
            // the compare screen may have learned cubic support (new chip) and changed the saved choices
            if (DisplayTuner.upscaleModes(this).size != dispFilterModes.size) {
                recreate()
                return
            }
            restoreDisplaySettings()
        }
        refreshDisplayInfo() // permission may have been granted meanwhile
        refreshAccess() // permissions may have been granted in the system settings
        refreshGameProfiles() // profiles are saved from the in-game panel while the app is in the background
        if (::headerAvatar.isInitialized) headerAvatar.setBitmap(ProfileStore.avatar(this))
        if (::homeLauncher.isInitialized) homeLauncher.start()
        if (::homeMonitor.isInitialized) homeMonitor.start()
        refreshStatus()
        if (::tabRow.isInitialized) {
            glass.request()
            tabRow.handler?.removeCallbacks(glassTick)
            if (AppSettings.blur) tabRow.postDelayed(glassTick, 2000)
        }
    }

    override fun onPause() {
        if (::tabRow.isInitialized) tabRow.handler?.removeCallbacks(glassTick)
        if (::homeLauncher.isInitialized) homeLauncher.stop()
        if (::homeMonitor.isInitialized) homeMonitor.stop()
        super.onPause()
    }

    override fun onDestroy() {
        glass.release()
        super.onDestroy()
    }

    private fun requestStart() {
        if (Build.VERSION.SDK_INT < 34) {
            I18n.toastMake(
                this,
                I18n.f("ต้องใช้ Android 14 ขึ้นไป (เครื่องนี้ Android {0}) จึงยังเริ่มไม่ได้", Build.VERSION.RELEASE),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (OverlayAccessibilityService.instance == null) {
            I18n.toastMake(this, I18n.f("เปิด {0} overlay ในการช่วยเหลือพิเศษ (Accessibility) ก่อน", getString(R.string.fluxa_app_name)), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(RecordConsentActivity.KEY_AUDIO_ASKED, false)
        ) {
            // the record button in the panel needs it (app sound / microphone); asked once, before the share dialog
            prefs.edit().putBoolean(RecordConsentActivity.KEY_AUDIO_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        openCaptureDialog()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_ACC_AUDIO -> refreshAccess()
            REQ_AUDIO -> {
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    I18n.toastMake(this, "ไม่อนุญาตเสียง · จะอัดแค่ภาพ", Toast.LENGTH_LONG).show()
                }
                openCaptureDialog()
            }
        }
    }

    private fun openCaptureDialog() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            I18n.toastMake(this, "ยกเลิกการแชร์หน้าจอ", Toast.LENGTH_SHORT).show()
            return
        }

        val multiplier = multiplierSlider.value
        val presentMode =
            if (presentSeg.selected == 1) FluxaOptions.PRESENT_MAILBOX
            else FluxaOptions.PRESENT_FIFO
        val options = FluxaOptions(
            enabled = true,
            multiplier = multiplier,
            flowSide = qualitySlider.value,
            driverName = null,
            presentMode = presentMode,
            genMode = genModeSeg.selected,
            targetFps = FluxaOptions.clampTargetFps(targetFpsSlider.value),
            targetMode = outModeSeg.selected == FluxaOptions.OUT_MODE_TARGET,
        )
        Fluxa.install(this, options)
        // Picks the display mode that fits multiplier x source rate, stores the refresh rate in Fluxa.
        Fluxa.applyDisplayMode(this)
        val modeId = window.attributes.preferredDisplayModeId

        val service = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_DISPLAY_MODE_ID, modeId)
        startForegroundService(service)
        moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ settings tab

    /** Saves a UI setting and reloads the palette; [rebuild] recreates the screen so it picks the change up. */
    private fun setUi(key: String, value: Any, rebuild: Boolean) {
        val e = prefs.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
        }
        e.apply()
        Palette.sync(this)
        if (rebuild) rebuildUi()
    }

    private fun rebuildUi() {
        pendingScroll = (tabPages.getOrNull(2) as? ScrollView)?.scrollY ?: 0
        pendingCat = openCat
        recreate()
    }

    /** Setting with a one-tap switch on the right (the label keeps its own line for the description). */
    private fun toggleRow(card: LinearLayout, caption: String, key: String, on: Boolean, rebuild: Boolean, anchor: String? = null, after: () -> Unit = {}) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val sw = FlatSwitch(this, on) { v ->
            setUi(key, v, rebuild)
            after()
        }
        row.addView(
            kit.caption(caption).also { if (anchor != null) anchors[anchor] = it },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = kit.dp(12) },
        )
        row.addView(sw, LinearLayout.LayoutParams(kit.dp(48), kit.dp(28)).apply { topMargin = kit.dp(8) })
        row.setOnClickListener { sw.toggle() }
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildThemeCard(): View {
        val card = kit.card("ธีม", Palette.PRIMARY)
        card.addView(kit.caption("โหมดสี · พื้นหลังและพื้นผิวของแอพ").also { anchors["theme_mode"] = it })
        card.addView(Segmented(kit, listOf("มืด", "AMOLED", "สว่าง", "Game"), AppSettings.base) { i ->
            setUi(AppSettings.K_BASE, i, true); true
        })
        card.addView(kit.caption("สีเน้น · ปุ่ม ตัวเลือกที่เลือก และสไลเดอร์").also { anchors["theme_accent"] = it })
        val row = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        for (i in 0..6) {
            val dot = View(kit.ctx).apply {
                val on = AppSettings.accent == i
                background = if (i < 6) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Palette.ACCENTS[i][0])
                        setStroke(kit.dp(if (on) 3 else 1), if (on) Palette.TEXT else Palette.STROKE)
                    }
                } else {
                    GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(0xFFFF4D4D.toInt(), 0xFFFFD84D.toInt(), 0xFF4DFF88.toInt(), 0xFF4DB8FF.toInt(), 0xFFB04DFF.toInt(), 0xFFFF4D4D.toInt()),
                    ).apply {
                        shape = GradientDrawable.OVAL
                        gradientType = GradientDrawable.SWEEP_GRADIENT
                        setStroke(kit.dp(if (on) 3 else 1), if (on) Palette.TEXT else Palette.STROKE)
                    }
                }
                setOnClickListener { setUi(AppSettings.K_ACCENT, i, true) }
                pressable()
            }
            row.addView(dot, LinearLayout.LayoutParams(0, kit.dp(38), 1f).apply {
                marginStart = kit.dp(if (i == 0) 0 else 6)
                marginEnd = kit.dp(if (i == 6) 0 else 6)
            })
        }
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(4) })
        if (AppSettings.accent == 6) {
            card.addView(kit.caption("เฉดสีกำหนดเอง · ลากเลือกเฉดสีที่ชอบ"))
            card.addView(Slider(kit, 0, 360, AppSettings.hue, step = 5, format = { "$it°" }) {
                setUi(AppSettings.K_HUE, it, true)
            })
        }
        return card
    }

    private fun buildLookCard(): View {
        val card = kit.card("ลักษณะ UI", Palette.SECONDARY)
        card.addView(kit.caption("ขนาดตัวอักษร · ปรับทั้งแอพ").also { anchors["look_text"] = it })
        card.addView(Slider(kit, 80, 140, (AppSettings.textScale * 100).toInt(), step = 5, format = { "$it%" }) {
            setUi(AppSettings.K_TEXT_SCALE, it, true)
        })
        card.addView(kit.caption("ความโค้งมุม · การ์ด ปุ่ม และช่องต่าง ๆ").also { anchors["look_radius"] = it })
        card.addView(Slider(kit, 20, 160, (AppSettings.radiusScale * 100).toInt(), step = 20, format = { "$it%" }) {
            setUi(AppSettings.K_RADIUS, it, true)
        })
        card.addView(kit.caption("ระยะห่างการ์ด").also { anchors["look_gap"] = it })
        card.addView(Slider(kit, 6, 28, AppSettings.cardGap, step = 2, format = { "$it dp" }) {
            setUi(AppSettings.K_GAP, it, true)
        })
        toggleRow(card, "ชื่อแท็บด้านล่าง · แสดงข้อความใต้ไอคอนของแท็บ", AppSettings.K_TAB_LABELS, AppSettings.tabLabels, true, "look_tabs")
        return card
    }

    /** In-game panel: which side it opens from + the CPU / GPU dials (look, size, colour). */
    private fun buildPanelCard(): View {
        val card = kit.card("แผงควบคุม (Panel)", Palette.SECONDARY)
        val preview = DialTile(kit, "CPU", "GHz", 2)
        fun refresh() {
            preview.applyLook(this)
            preview.update(2.4, 3.3)
        }
        card.addView(kit.caption("แผงแสดงและเปิดจาก · ปัดจากขอบด้านนี้ระหว่างเล่นเกม").also { anchors["panel_side"] = it })
        card.addView(Segmented(kit, listOf("ขวา", "ซ้าย"), if (PanelPrefs.left(this)) 1 else 0) { i ->
            setUi(PanelPrefs.K_SIDE, i, false); true
        })

        card.addView(kit.section("หน้าปัดบนแผง").also { anchors["panel_dial"] = it })
        card.addView(
            preview,
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = kit.dp(12) },
        )
        toggleRow(card, "แสดง CPU (GHz)", PanelPrefs.K_CPU, PanelPrefs.showCpu(this), false, "panel_cpu") { refresh() }
        toggleRow(card, "แสดง GPU (MHz)", PanelPrefs.K_GPU, PanelPrefs.showGpu(this), false, "panel_gpu") { refresh() }
        card.addView(kit.caption("รูปแบบหน้าปัด · ตามธีม = ขีดในโหมด Game และเส้นโค้งในธีมอื่น").also { anchors["panel_style"] = it })
        card.addView(Segmented(kit, listOf("ตามธีม", "เส้นโค้ง", "ขีด"), PanelPrefs.style(this), perRow = 3, textSp = 13f) { i ->
            setUi(PanelPrefs.K_STYLE, i, false); refresh(); true
        })
        card.addView(kit.caption("ขนาดหน้าปัด").also { anchors["panel_size"] = it })
        card.addView(Slider(kit, PanelPrefs.MIN_SIZE, PanelPrefs.MAX_SIZE, PanelPrefs.size(this), step = 4, format = { "$it dp" }) {
            setUi(PanelPrefs.K_SIZE, it, false); refresh()
        })
        card.addView(kit.caption("สีหน้าปัด · ตามโหลด = เขียว เหลือง แดงตามความเร็ว").also { anchors["panel_color"] = it })
        card.addView(Segmented(kit, listOf("สีเน้น", "ตามโหลด", "สีรอง"), PanelPrefs.color(this), perRow = 3, textSp = 13f) { i ->
            setUi(PanelPrefs.K_COLOR, i, false); refresh(); true
        })
        toggleRow(card, "แสดงค่าสูงสุดและ % ใต้หน้าปัด", PanelPrefs.K_PCT, PanelPrefs.showPct(this), false, "panel_pct") { refresh() }
        card.addView(
            kit.text("GPU อ่านจากไฟล์ระบบของเครื่อง · บางรุ่นไม่อนุญาตให้แอพอ่านจะขึ้น --", 11.5f, Palette.DIM)
                .apply { setPadding(0, kit.dp(10), 0, 0); setLineSpacing(0f, 1.15f) },
        )
        refresh()
        return card
    }

    private fun buildEffectsCard(): View {
        val card = kit.card("เอฟเฟกต์และอนิเมชัน", Palette.TERTIARY)
        val blurNote = if (AppSettings.canBlurViews) "" else " · เบลอตอนสลับหน้าต้อง Android 12 ขึ้นไป"
        toggleRow(
            card,
            "เบลอ (Blur) · พื้นหลังเรืองแสงเบลอ การ์ดโปร่งแสง และเบลอตอนสลับหน้า · ใช้ GPU เพิ่มเล็กน้อย$blurNote",
            AppSettings.K_BLUR, AppSettings.blur, true, "fx_blur",
        )
        card.addView(kit.caption("อนิเมชันสลับหน้า · ใช้กับการสลับแท็บและเปิดหน้าเทียบภาพ").also { anchors["fx_anim"] = it })
        card.addView(
            Segmented(kit, listOf("ไม่มี", "เฟด", "สไลด์", "ซูม", "ลอยขึ้น"), AppSettings.pageAnim, perRow = 3, textSp = 13f) { i ->
                setUi(AppSettings.K_PAGE_ANIM, i, false)
                true
            },
        )
        card.addView(kit.caption("ความเร็วอนิเมชัน").also { anchors["fx_speed"] = it })
        card.addView(Slider(kit, 100, 600, AppSettings.animMs, step = 50, format = { "$it ms" }) {
            setUi(AppSettings.K_ANIM_MS, it, false)
        })
        return card
    }

    private fun buildLanguageCard(): View {
        val card = kit.card("ภาษา", Palette.PRIMARY)
        card.addView(kit.caption("ภาษา · เปลี่ยนภาษาทั้งแอพ แผงควบคุมในเกม และการแจ้งเตือน").also { anchors["lang"] = it })
        card.addView(Segmented(kit, I18n.LANG_NAMES, AppSettings.lang, perRow = 2) { i ->
            setUi(AppSettings.K_LANG, i, true); true
        })
        card.addView(
            kit.text("รีเซ็ตการตั้งค่าหน้าตา", 13.5f, Palette.DANGER, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(14), 0, kit.dp(14))
                background = kit.rounded(Palette.DANGER_SOFT, 16, Palette.DANGER_LINE, 1)
                anchors["lang_reset"] = this
                pressable()
                setOnClickListener {
                    AppSettings.reset(this@MainActivity)
                    Palette.sync(this@MainActivity)
                    I18n.toastMake(this@MainActivity, "รีเซ็ตแล้ว", Toast.LENGTH_SHORT).show()
                    rebuildUi()
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(18) },
        )
        return card
    }



    // ------------------------------------------------------------------ permissions & access category

    private class AccRow(val dot: View, val state: TextView, val ok: () -> Boolean, val required: Boolean, val good: String, val bad: () -> String)

    private val accRows = ArrayList<AccRow>()
    private lateinit var accDot: View
    private lateinit var accTitle: TextView
    private lateinit var accSub: TextView
    private val appControl by lazy { AppControl(this) }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun buildAccessCards(): List<View> {
        // summary card: are the required permissions in place?
        val sum = kit.card()
        val top = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        accDot = View(kit.ctx)
        top.addView(accDot, LinearLayout.LayoutParams(kit.dp(10), kit.dp(10)).apply { marginEnd = kit.dp(10) })
        accTitle = kit.text("", 17f, Palette.TEXT, bold = true)
        top.addView(accTitle, LinearLayout.LayoutParams(0, -2, 1f))
        sum.addView(top)
        accSub = kit.text("", 12.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) }
        sum.addView(accSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(6) })

        fun row(
            card: LinearLayout, anchor: String, title: String, sub: String, required: Boolean,
            good: String, bad: () -> String, ok: () -> Boolean, onClick: (() -> Unit)?,
        ) {
            val first = card.childCount <= 1 // the card title is child 0
            if (!first) card.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)))
            val r = LinearLayout(kit.ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(2), kit.dp(13), kit.dp(2), kit.dp(13))
                if (onClick != null) { pressable(); setOnClickListener { onClick() } }
            }
            val dot = View(kit.ctx)
            r.addView(dot, LinearLayout.LayoutParams(kit.dp(8), kit.dp(8)).apply { marginEnd = kit.dp(12) })
            val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(kit.text(title, 14.5f, Palette.TEXT, bold = true))
            col.addView(
                kit.text(sub, 12f, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(3) },
            )
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val state = kit.text("", 12.5f, Palette.MUTED, bold = true)
            r.addView(state, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(10) })
            if (onClick != null) {
                r.addView(kit.icon(R.drawable.fluxa_ic_chevron_right, 20, Palette.DIM), LinearLayout.LayoutParams(kit.dp(20), kit.dp(20)).apply { marginStart = kit.dp(6) })
            }
            card.addView(r, LinearLayout.LayoutParams(-1, -2).apply { if (first) topMargin = kit.dp(6) })
            anchors[anchor] = r
            accRows.add(AccRow(dot, state, ok, required, good, bad))
        }

        accRows.clear()
        val must = kit.card("จำเป็นต่อการเจนเฟรม", Palette.WARN)
        row(must, "acc_a11y", "บริการ Accessibility", "จำเป็น · ใช้แสดงภาพและแผงควบคุมเหนือเกม (overlay)", true,
            "เปิดแล้ว", { "ยังไม่เปิด" }, { OverlayAccessibilityService.instance != null }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        row(must, "acc_sdk", "Android 14 ขึ้นไป", "จำเป็น · สำหรับจับภาพแอปเดียว", true,
            "รองรับ", { "ไม่รองรับ" }, { Build.VERSION.SDK_INT >= 34 }, null)

        val opt = kit.card("ไม่บังคับ", Palette.TERTIARY)
        row(opt, "acc_mic", "ไมโครโฟน / เสียงแอพ", "ไม่บังคับ · ใช้เมื่อเลือกโหมดอัดจอ", false,
            "ให้แล้ว", { "ยังไม่ให้" }, { hasPerm(Manifest.permission.RECORD_AUDIO) }) {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_ACC_AUDIO)
            } else {
                I18n.toastMake(this, "ให้สิทธิ์แล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        row(opt, "acc_notif", "สิทธิ์เข้าถึงการแจ้งเตือน", "ไม่บังคับ · แสดงแจ้งเตือนในแผงควบคุมระหว่างเล่นเกม", false,
            "เปิดแล้ว", { "ยังไม่เปิด" }, { FluxaNotificationListener.enabled(this) }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row(opt, "acc_dnd", "ห้ามรบกวน (DND)", "ไม่บังคับ · เปิดห้ามรบกวนอัตโนมัติในโหมดเกม", false,
            "ให้แล้ว", { "ยังไม่ให้" }, { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        row(opt, "acc_sys", "แก้ไขการตั้งค่าระบบ", "ไม่บังคับ · ล็อกความสว่างหน้าจอในโหมดเกม", false,
            "ให้แล้ว", { "ยังไม่ให้" }, { Settings.System.canWrite(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        row(opt, "acc_secure", "สิทธิ์ WRITE_SECURE_SETTINGS", "ไม่บังคับ · ใช้ปรับความละเอียด/DPI · แตะเพื่อดูวิธีขอสิทธิ์", false,
            "ให้แล้ว", { "ยังไม่ให้" }, { DisplayTuner.canWrite(this) }) {
            if (!DisplayTuner.canWrite(this)) {
                startActivity(Intent(this, PermissionActivity::class.java))
                AppSettings.transition(this, true)
            } else {
                I18n.toastMake(this, "ให้สิทธิ์ปรับความละเอียดแล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        row(opt, "acc_usage", "การเข้าถึงข้อมูลการใช้งาน", "ไม่บังคับ · แสดงแอพที่เปิดล่าสุดในหน้าหลัก", false,
            "เปิดแล้ว", { "ยังไม่เปิด" }, { appControl.hasUsageAccess() }) {
            appControl.openUsageAccess()
        }
        row(opt, "acc_appinfo", "ตั้งค่าแอพในระบบ", "เปิดหน้าข้อมูลแอพของ Android เพื่อดูสิทธิ์ทั้งหมดและแบตเตอรี่", false,
            "", { "" }, { true }) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        refreshAccess()
        return listOf(sum, must, opt)
    }

    /** Re-reads every permission (they change in the system settings while the app is in the background). */
    private fun refreshAccess() {
        if (!::accTitle.isInitialized) return
        accRows.forEach { r ->
            val ok = r.ok()
            val badColor = if (r.required) Palette.WARN else Palette.DIM
            r.dot.background = kit.rounded(if (ok) Palette.OK else badColor, 4)
            r.state.text = if (ok) r.good else r.bad()
            r.state.setTextColor(if (ok) Palette.OK else Palette.MUTED)
        }
        val required = accRows.filter { it.required }
        val missing = required.count { !it.ok() }
        val given = accRows.count { it.ok() && it.good.isNotEmpty() }
        val total = accRows.count { it.good.isNotEmpty() }
        accDot.background = kit.rounded(if (missing == 0) Palette.OK else Palette.WARN, 5)
        accTitle.text = if (missing == 0) "สิทธิ์ที่จำเป็นครบแล้ว" else "ยังขาดสิทธิ์ที่จำเป็น"
        accSub.text = I18n.f("ให้สิทธิ์แล้ว {0} จาก {1} รายการ", given, total) + " · " +
            I18n.tr(if (missing == 0) "ข้อที่ไม่บังคับเปิดเมื่ออยากใช้ฟีเจอร์นั้น" else "แตะข้อที่ขึ้นสีเหลืองเพื่อไปเปิด")
    }

    // ------------------------------------------------------------------ settings hub (categories + search)

    private class SetCat(val id: String, val icon: Int, val title: String, val sub: String)
    private class SetItem(val cat: String, val title: String, val desc: String, val anchor: String?, val keys: String)

    private val anchors = HashMap<String, View>()
    private val catCards = HashMap<String, List<View>>()
    private var openCat: String? = null
    private lateinit var setBox: LinearLayout
    private lateinit var setScroll: ScrollView
    private lateinit var searchEdit: EditText
    private lateinit var searchBar: View
    private lateinit var hubBody: LinearLayout
    private var backCb: Any? = null

    private val setCats by lazy {
        listOf(
            SetCat("look", R.drawable.fluxa_ic_palette, "รูปลักษณ์และการแสดงผล", "ธีม สี ขนาดตัวอักษร เอฟเฟกต์และอนิเมชัน"),
            SetCat("panel", R.drawable.fluxa_ic_dashboard, "แผงควบคุมในเกม", "ด้านที่เปิด และหน้าปัด CPU / GPU"),
            SetCat("access", R.drawable.fluxa_ic_shield, "สิทธิ์และการเข้าถึง", "Accessibility, การแจ้งเตือน, ห้ามรบกวน และอื่น ๆ"),
            SetCat("lang", R.drawable.fluxa_ic_language, "ภาษาและรีเซ็ต", "ภาษาของแอพ และรีเซ็ตการตั้งค่าหน้าตา"),
            SetCat("about", R.drawable.fluxa_ic_info, "เกี่ยวกับ", "เวอร์ชัน นโยบาย และข้อมูลแอพ"),
        )
    }

    /** Every single setting, so the search box can jump straight to it. [keys] are extra words people may type. */
    private val setItems by lazy {
        listOf(
            SetItem("look", "โหมดสี", "ธีม มืด AMOLED สว่าง Game", "theme_mode", "theme dark light amoled color mode พื้นหลัง ธีม"),
            SetItem("look", "สีเน้น", "สีปุ่มและสไลเดอร์ เฉดสีกำหนดเอง", "theme_accent", "accent color hue สี เฉด"),
            SetItem("look", "ขนาดตัวอักษร", "ปรับทั้งแอพ", "look_text", "font text size ตัวหนังสือ ฟอนต์ ใหญ่ เล็ก"),
            SetItem("look", "ความโค้งมุม", "การ์ด ปุ่ม และช่องต่าง ๆ", "look_radius", "corner radius round มุม โค้ง"),
            SetItem("look", "ระยะห่างการ์ด", "ช่องว่างระหว่างการ์ด", "look_gap", "gap spacing margin ช่องไฟ"),
            SetItem("look", "ชื่อแท็บด้านล่าง", "แสดงข้อความใต้ไอคอนของแท็บ", "look_tabs", "tab label name bar แถบ เมนู"),
            SetItem("look", "เบลอ (Blur)", "พื้นหลังเรืองแสง การ์ดโปร่งแสง", "fx_blur", "blur glass transparent เบลอ โปร่งแสง กระจก เรืองแสง"),
            SetItem("look", "อนิเมชันสลับหน้า", "เฟด สไลด์ ซูม ลอยขึ้น", "fx_anim", "animation transition fade slide zoom rise แอนิเมชัน เปลี่ยนหน้า"),
            SetItem("look", "ความเร็วอนิเมชัน", "ระยะเวลาเป็น ms", "fx_speed", "animation speed duration ms เร็ว ช้า"),
            SetItem("panel", "แผงแสดงและเปิดจาก", "ซ้าย / ขวา ปัดจากขอบระหว่างเล่นเกม", "panel_side", "panel drawer side left right edge swipe ซ้าย ขวา ขอบ ปัด ลิ้นชัก"),
            SetItem("panel", "หน้าปัดบนแผง", "ตัวอย่างหน้าปัด CPU / GPU", "panel_dial", "dial gauge preview เกจ ตัวอย่าง"),
            SetItem("panel", "แสดง CPU (GHz)", "เปิด/ปิดหน้าปัด CPU", "panel_cpu", "cpu ghz processor ความเร็ว ซีพียู"),
            SetItem("panel", "แสดง GPU (MHz)", "เปิด/ปิดหน้าปัด GPU", "panel_gpu", "gpu mhz graphics การ์ดจอ จีพียู"),
            SetItem("panel", "รูปแบบหน้าปัด", "ตามธีม เส้นโค้ง ขีด", "panel_style", "style gauge arc tick เกจ รูปแบบ"),
            SetItem("panel", "ขนาดหน้าปัด", "ขนาดเป็น dp", "panel_size", "size dial gauge ใหญ่ เล็ก"),
            SetItem("panel", "สีหน้าปัด", "สีเน้น ตามโหลด สีรอง", "panel_color", "color load green yellow red สี โหลด เขียว เหลือง แดง"),
            SetItem("panel", "แสดงค่าสูงสุดและ %", "ตัวเลขใต้หน้าปัด", "panel_pct", "max percent สูงสุด เปอร์เซ็นต์"),
            SetItem("access", "บริการ Accessibility", "ใช้แสดงภาพและแผงควบคุมเหนือเกม (overlay)", "acc_a11y", "accessibility overlay permission สิทธิ์ เปิดบริการ ผู้ช่วยเหลือพิเศษ"),
            SetItem("access", "ไมโครโฟน / เสียงแอพ", "ใช้เมื่อเลือกโหมดอัดจอ", "acc_mic", "microphone audio record mic ไมค์ เสียง อัดจอ"),
            SetItem("access", "สิทธิ์เข้าถึงการแจ้งเตือน", "แสดงแจ้งเตือนในแผงควบคุมระหว่างเล่นเกม", "acc_notif", "notification listener access แจ้งเตือน"),
            SetItem("access", "ห้ามรบกวน (DND)", "เปิดห้ามรบกวนอัตโนมัติในโหมดเกม", "acc_dnd", "do not disturb dnd policy ห้ามรบกวน"),
            SetItem("access", "แก้ไขการตั้งค่าระบบ", "ล็อกความสว่างหน้าจอในโหมดเกม", "acc_sys", "write settings brightness ความสว่าง ตั้งค่าระบบ"),
            SetItem("access", "สิทธิ์ WRITE_SECURE_SETTINGS", "ใช้ปรับความละเอียด/DPI", "acc_secure", "secure settings adb resolution dpi ความละเอียด grant"),
            SetItem("access", "การเข้าถึงข้อมูลการใช้งาน", "แสดงแอพที่เปิดล่าสุดในหน้าหลัก", "acc_usage", "usage access usage stats แอพล่าสุด task manager"),
            SetItem("access", "ตั้งค่าแอพในระบบ", "เปิดหน้าข้อมูลแอพของ Android เพื่อดูสิทธิ์ทั้งหมดและแบตเตอรี่", "acc_appinfo", "app info system settings permissions ข้อมูลแอพ สิทธิ์ทั้งหมด"),
            SetItem("lang", "ภาษา", "เปลี่ยนภาษาทั้งแอพ", "lang", "language thai english chinese spanish japanese korean ไทย อังกฤษ จีน ญี่ปุ่น เกาหลี"),
            SetItem("lang", "รีเซ็ตการตั้งค่าหน้าตา", "คืนค่าธีมและรูปลักษณ์เป็นค่าเริ่มต้น", "lang_reset", "reset default restore รีเซ็ต คืนค่า ค่าเริ่มต้น"),
            SetItem("about", "เกี่ยวกับแอพ", "เวอร์ชัน นโยบาย การยินยอม", null, "about version privacy policy consent เวอร์ชัน ความเป็นส่วนตัว นโยบาย ผู้พัฒนา"),
        )
    }

    private fun settingsPage(): ScrollView {
        setBox = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            val side = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                maxOf(kit.dp(16), (resources.displayMetrics.widthPixels - kit.dp(640)) / 2) else kit.dp(16)
            setPadding(side, kit.dp(14), side, kit.dp(16))
        }
        searchBar = buildSearchBar()
        hubBody = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        setScroll = ScrollView(kit.ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(setBox)
        }
        renderSettings()
        return setScroll
    }

    private fun renderSettings() {
        setBox.removeAllViews()
        hubBody.removeAllViews()
        val cat = openCat
        if (cat == null) {
            setBox.addView(pageTitle("ตั้งค่า", "ค้นหา หรือเลือกหมวดที่ต้องการปรับ"))
            setBox.addView(searchBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(14) })
            setBox.addView(hubBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(14) })
            fillHubBody()
        } else {
            val c = setCats.first { it.id == cat }
            setBox.addView(detailHeader(c))
            catCards[cat].orEmpty().forEachIndexed { i, v ->
                (v.parent as? ViewGroup)?.removeView(v)
                setBox.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(if (i == 0) 12 else AppSettings.cardGap) })
            }
        }
        syncBack()
    }

    private fun fillHubBody() {
        hubBody.removeAllViews()
        val q = searchEdit.text.toString().trim()
        if (q.isEmpty()) {
            val list = listCard()
            setCats.forEachIndexed { i, c ->
                if (i > 0) list.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)).apply { marginStart = kit.dp(68); marginEnd = kit.dp(10) })
                list.addView(hubRow(c.icon, Palette.PRIMARY, c.title, c.sub) { openCategory(c.id, null) })
            }
            hubBody.addView(list, LinearLayout.LayoutParams(-1, -2))
            return
        }
        val tokens = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hits = setItems.filter { it2 ->
            val cat = setCats.first { it.id == it2.cat }
            val hay = (it2.title + " " + it2.desc + " " + it2.keys + " " + cat.title + " " +
                I18n.tr(it2.title) + " " + I18n.tr(it2.desc) + " " + I18n.tr(cat.title)).lowercase()
            tokens.all { t -> hay.contains(t) }
        }
        if (hits.isEmpty()) {
            val box = listCard()
            box.setPadding(kit.dp(18), kit.dp(22), kit.dp(18), kit.dp(22))
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.addView(kit.icon(R.drawable.fluxa_ic_search, 28, Palette.DIM))
            box.addView(
                kit.text("ไม่พบการตั้งค่าที่ตรงกับ \"$q\"", 14.5f, Palette.TEXT, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(10) },
            )
            box.addView(
                kit.text("ลองใช้คำอื่น เช่น สี ภาษา แผง", 12.5f, Palette.MUTED).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(4) },
            )
            hubBody.addView(box, LinearLayout.LayoutParams(-1, -2))
            return
        }
        val list = listCard()
        hits.forEachIndexed { i, h ->
            val c = setCats.first { it.id == h.cat }
            if (i > 0) list.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)).apply { marginStart = kit.dp(68); marginEnd = kit.dp(10) })
            list.addView(hubRow(c.icon, Palette.PRIMARY, h.title, c.title + " · " + h.desc) { openCategory(h.cat, h.anchor) })
        }
        hubBody.addView(list, LinearLayout.LayoutParams(-1, -2))
    }

    private fun listCard() = LinearLayout(kit.ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.glass(24)
        setPadding(kit.dp(6), kit.dp(6), kit.dp(6), kit.dp(6))
    }

    /** One tappable row: tinted icon chip, title + quiet subtitle, chevron. */
    private fun hubRow(icon: Int, tint: Int, title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(10), kit.dp(13), kit.dp(8), kit.dp(13))
            pressable()
            setOnClickListener { hideKeyboard(); onClick() }
        }
        val chip = FrameLayout(kit.ctx).apply { background = kit.rounded(Palette.a(tint, 0x26), 14) }
        chip.addView(kit.icon(icon, 22, tint), FrameLayout.LayoutParams(kit.dp(22), kit.dp(22), Gravity.CENTER))
        row.addView(chip, LinearLayout.LayoutParams(kit.dp(44), kit.dp(44)).apply { marginEnd = kit.dp(14) })
        val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.text(title, 15.5f, Palette.TEXT, bold = true))
        col.addView(
            kit.text(sub, 12.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(3) },
        )
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(kit.icon(R.drawable.fluxa_ic_chevron_right, 20, Palette.DIM), LinearLayout.LayoutParams(kit.dp(20), kit.dp(20)).apply { marginStart = kit.dp(8) })
        return row
    }

    private fun buildSearchBar(): View {
        val box = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = kit.glass(20)
            setPadding(kit.dp(16), 0, kit.dp(6), 0)
        }
        box.addView(kit.icon(R.drawable.fluxa_ic_search, 20, Palette.MUTED), LinearLayout.LayoutParams(kit.dp(20), kit.dp(20)))
        val clear = kit.icon(R.drawable.fluxa_ic_close, 18, Palette.MUTED).apply {
            visibility = View.GONE
            setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8))
            pressable()
            contentDescription = I18n.tr(this@MainActivity, "ล้าง")
        }
        searchEdit = EditText(kit.ctx).apply {
            hint = I18n.tr(this@MainActivity, "ค้นหาการตั้งค่า เช่น สี ภาษา แผง")
            setHintTextColor(Palette.DIM)
            setTextColor(Palette.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f * AppSettings.textScale)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(kit.dp(12), kit.dp(15), kit.dp(6), kit.dp(15))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    if (openCat == null) fillHubBody()
                }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
            }
        }
        clear.setOnClickListener { searchEdit.setText("") }
        box.addView(searchEdit, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(clear, LinearLayout.LayoutParams(kit.dp(34), kit.dp(34)))
        return box
    }

    private fun detailHeader(c: SetCat): View {
        val row = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, kit.dp(6), 0, kit.dp(2))
        }
        val back = FrameLayout(kit.ctx).apply {
            background = kit.lit(16)
            pressable()
            contentDescription = I18n.tr(this@MainActivity, "กลับ")
            setOnClickListener { closeCat() }
        }
        back.addView(kit.icon(R.drawable.fluxa_ic_chevron_left, 22, Palette.PRIMARY), FrameLayout.LayoutParams(kit.dp(22), kit.dp(22), Gravity.CENTER))
        row.addView(back, LinearLayout.LayoutParams(kit.dp(44), kit.dp(44)).apply { marginEnd = kit.dp(14) })
        val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.display(c.title, 21f, Palette.TEXT))
        col.addView(kit.text(c.sub, 12.5f, Palette.MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(3) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun openCategory(id: String, anchor: String?) {
        if (id == "about") {
            startActivity(Intent(this, AboutActivity::class.java))
            AppSettings.transition(this, true)
            return
        }
        openCat = id
        renderSettings()
        setScroll.scrollTo(0, 0)
        val target = anchor?.let { anchors[it] } ?: return
        // wait for the cards to be laid out, then scroll to the setting and blink it twice
        setBox.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (openCat != id || !target.isAttachedToWindow) { setBox.viewTreeObserver.removeOnGlobalLayoutListener(this); return }
                if (target.height <= 0) return
                setBox.viewTreeObserver.removeOnGlobalLayoutListener(this)
                var y = 0
                var v: View = target
                while (v !== setBox) {
                    y += v.top
                    v = (v.parent as? View) ?: break
                }
                setScroll.scrollTo(0, (y - kit.dp(90)).coerceAtLeast(0))
                target.animate().alpha(0.2f).setDuration(260).withEndAction {
                    target.animate().alpha(1f).setDuration(260).withEndAction {
                        target.animate().alpha(0.2f).setDuration(260).withEndAction {
                            target.animate().alpha(1f).setDuration(260).start()
                        }.start()
                    }.start()
                }.start()
            }
        })
    }

    private fun closeCat() {
        openCat = null
        renderSettings()
        setScroll.scrollTo(0, 0)
    }

    private fun hideKeyboard() {
        if (!::searchEdit.isInitialized) return
        searchEdit.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchEdit.windowToken, 0)
    }

    /** Back inside a category returns to the hub instead of leaving the app. */
    private fun syncBack() {
        val need = curTab == 2 && openCat != null
        if (Build.VERSION.SDK_INT >= 33) {
            val disp = onBackInvokedDispatcher
            if (need && backCb == null) {
                val cb = android.window.OnBackInvokedCallback { closeCat() }
                backCb = cb
                disp.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            } else if (!need && backCb != null) {
                disp.unregisterOnBackInvokedCallback(backCb as android.window.OnBackInvokedCallback)
                backCb = null
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (curTab == 2 && openCat != null) closeCat() else super.onBackPressed()
    }

    companion object {
        private const val STATE_TAB = "tab"
        /** Scroll position of the settings page, carried over a recreate() triggered by a setting. */
        private var pendingScroll = 0
        private var pendingCat: String? = null
        private const val REQ_CAPTURE = 1
        private const val REQ_AUDIO = 3
        private const val REQ_ACC_AUDIO = 7

        private const val KEY_MULT_X = "ui_mult_x"
        private const val KEY_PRESENT = "ui_present"
        private const val KEY_GEN_MODE = "ui_gen_mode"
        private const val KEY_TARGET_FPS = "ui_target_fps"
        private const val KEY_OUT_MODE = "ui_out_mode"
        private const val KEY_FLOW_SIDE = "ui_flow_side"
    }
}
