package app.fluxa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Before / after upscale comparison: the picked image is rendered at "render %" (what the game
 * would draw), then upscaled to "output %" by the presenter's own upscaler (same shaders, run on a
 * still). Left of the divider = the low-res frame, right = the upscaled result. Every change is
 * saved to the same settings the session uses, so the choice made by eye carries over.
 *
 * Layout: an edge-to-edge viewer with floating controls (back, pick image, test pattern, reset
 * zoom, before / split / after switch, live info chip) and a tabbed settings panel (upscale /
 * effects / colour) under it (beside it in landscape), styled like the in-game drawer.
 */
class CompareActivity : Activity() {
    private lateinit var kit: Kit
    private lateinit var view: CompareView
    private lateinit var info: TextView
    private lateinit var modeBox: FrameLayout
    private lateinit var modeSeg: Segmented
    private lateinit var sharpSlider: Slider
    private lateinit var methodSeg: Segmented
    private lateinit var percentSlider: Slider
    private lateinit var outSlider: Slider
    private lateinit var denoiseSlider: Slider
    private lateinit var aaSeg: Segmented
    private lateinit var debandSlider: Slider
    private lateinit var ditherSeg: Segmented
    private lateinit var brightSlider: Slider
    private lateinit var contrastSlider: Slider
    private lateinit var satSlider: Slider
    private lateinit var vibSlider: Slider
    private lateinit var tempSlider: Slider
    private lateinit var tintSlider: Slider

    private val prefs by lazy { getSharedPreferences("fluxa", Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "fluxa-compare") }
    private val generation = AtomicInteger()

    private var base: Bitmap? = null           // the "full resolution" reference frame
    private var curMode = DisplayTuner.FILTER_LINEAR
    private var vulkanOk = true
    private var loading = false
    private var restoring = true
    private var tab = 0

    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = Kit(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val cfg = DisplayTuner.config(this)
        curMode = cfg.upscaleFilter

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val page = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        // ---- viewer with floating controls
        view = CompareView(kit.ctx, kit)
        val viewer = FrameLayout(kit.ctx)
        viewer.addView(view, FrameLayout.LayoutParams(-1, -1))

        fun pillBtn(label: String, color: Int = Palette.TEXT, onClick: () -> Unit) =
            kit.fitText(label, 13f, color, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8))
                background = kit.rounded(Palette.a(Palette.BG, 0xB3), 16, Palette.STROKE, 1)
                setOnClickListener { onClick() }
            }

        fun iconPill(res: Int, color: Int = Palette.TEXT, onClick: () -> Unit) =
            kit.icon(res, 36, color).apply {
                setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(8))
                background = kit.rounded(Palette.a(Palette.BG, 0xB3), 16, Palette.STROKE, 1)
                setOnClickListener { onClick() }
            }

        val top = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(10), kit.dp(10), kit.dp(10), 0)
        }
        val row1 = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        row1.addView(iconPill(R.drawable.fluxa_ic_chevron_left, Palette.PRIMARY) { finish() })
        row1.addView(View(kit.ctx), lp(0, 1).apply { weight = 1f })
        row1.addView(pillBtn("เลือกภาพ", Palette.PRIMARY) { pickImage() })
        row1.addView(pillBtn("ภาพทดสอบ") {
            prefs.edit().remove(KEY_URI).apply()
            loadSource()
        }, lp(-2, -2).apply { marginStart = kit.dp(6) })
        row1.addView(iconPill(R.drawable.fluxa_ic_fit) { view.resetZoom() }, lp(-2, -2).apply { marginStart = kit.dp(6) })
        top.addView(row1)
        // what is shown: only the "before", the split, or only the "after"
        val showSeg = Segmented(kit, listOf("ก่อน", "แบ่งครึ่ง", "หลัง"), 1, textSp = 12.5f) { i ->
            view.setSplit(when (i) { 0 -> 1f; 2 -> 0f; else -> 0.5f })
            true
        }
        showSeg.background = kit.rounded(Palette.a(Palette.BG, 0xB3), 14, Palette.STROKE, 1)
        showSeg.setPadding(kit.dp(4), kit.dp(4), kit.dp(4), kit.dp(4))
        top.addView(showSeg, lp(kit.dp(230), -2).apply { topMargin = kit.dp(8) })
        viewer.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        info = kit.fitText("", 12f, Palette.PRIMARY, bold = true).apply {
            setPadding(kit.dp(10), kit.dp(6), kit.dp(10), kit.dp(6))
            background = kit.rounded(Palette.a(Palette.BG, 0xB3), 10, Palette.a(Palette.PRIMARY, 0x55), 1)
            visibility = View.GONE
        }
        val infoBox = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(10), 0, kit.dp(10), kit.dp(10))
            addView(info, lp(-2, -2))
            addView(
                kit.text("ลากเส้นแบ่ง · บีบนิ้วซูม · แตะสองครั้งซูม/รีเซ็ต", 11f, Palette.MUTED)
                    .apply { setPadding(kit.dp(2), kit.dp(6), 0, 0) },
            )
        }
        infoBox.isClickable = false
        viewer.addView(infoBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        // ---- settings panel (tabs: upscale / effects / colour), all controls built once
        val pages = listOf(buildUpscalePage(cfg), buildEffectsPage(cfg), buildColourPage(cfg))
        val names = listOf("อัปสเกล", "เอฟเฟกต์", "สี")
        val scroll = CenteredScrollView(kit.ctx).apply { isVerticalScrollBarEnabled = false }
        val tabViews = ArrayList<Pair<TextView, View>>()
        fun paintTabs() = tabViews.forEachIndexed { i, (label, bar) ->
            val on = i == tab
            label.setTextColor(if (on) Palette.PRIMARY else Palette.MUTED)
            bar.setBackgroundColor(if (on) Palette.PRIMARY else 0)
        }
        fun openTab(i: Int) {
            tab = i
            paintTabs()
            scroll.removeAllViews()
            (pages[i].parent as? android.view.ViewGroup)?.removeView(pages[i])
            scroll.addView(pages[i])
            scroll.scrollTo(0, 0)
        }
        val tabRow = LinearLayout(kit.ctx).apply { setPadding(kit.dp(10), kit.dp(2), kit.dp(10), 0) }
        names.forEachIndexed { i, name ->
            val label = kit.fitText(name, 14f, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(12), 0, kit.dp(10))
            }
            val bar = View(kit.ctx)
            tabViews.add(label to bar)
            tabRow.addView(
                LinearLayout(kit.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setOnClickListener { openTab(i) }
                    addView(label, lp())
                    addView(bar, lp(-1, kit.dp(2)).apply { marginStart = kit.dp(14); marginEnd = kit.dp(14) })
                },
                lp(0, -2).apply { weight = 1f },
            )
        }
        val panel = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.PANEL)
            addView(View(kit.ctx).apply { setBackgroundColor(Palette.STROKE) }, lp(-1, kit.dp(1)))
            addView(tabRow)
            addView(View(kit.ctx).apply { setBackgroundColor(Palette.STROKE) }, lp(-1, kit.dp(1)))
            addView(scroll, lp(-1, 0).apply { weight = 1f })
        }
        openTab(tab)

        if (landscape) {
            val body = LinearLayout(kit.ctx)
            body.addView(viewer, lp(0, -1).apply { weight = 1.5f })
            body.addView(panel, lp(0, -1).apply { weight = 1f })
            page.addView(body, lp(-1, 0).apply { weight = 1f })
        } else {
            page.addView(viewer, lp(-1, 0).apply { weight = 5f })
            page.addView(panel, lp(-1, 0).apply { weight = 4f })
        }
        setContentView(page)
        restoring = false

        if (CaptureService.running) {
            view.message = "หยุดเซสชันก่อน ถึงจะเทียบภาพได้"
            return
        }
        view.message = "กำลังโหลด…"
        worker.execute {
            val caps = FluxaNative.ensureLoaded().let { if (it) FluxaNative.nativeStillInfo() else -1 }
            runOnUiThread {
                if (caps < 0) {
                    vulkanOk = false
                    view.message = "Vulkan ใช้ไม่ได้บนเครื่องนี้"
                    return@runOnUiThread
                }
                val cubic = (caps and 2) != 0
                if (cubic && !DisplayTuner.cubicSupported(this)) {
                    DisplayTuner.setCubicSupported(this, true)
                    buildModeSeg()
                }
                loadSource()
            }
        }
    }

    // ---- building blocks (same look as the in-game drawer)

    private fun newPage() = LinearLayout(kit.ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(14), kit.dp(2), kit.dp(14), kit.dp(18))
    }

    private fun group(page: LinearLayout, title: String): LinearLayout = LinearLayout(kit.ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.rounded(0xFF22252B.toInt(), 14, 0xFF2C3036.toInt(), 1)
        setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(16))
        addView(kit.fitText(title, 12f, Palette.MUTED, bold = true, spacing = 0.06f))
        page.addView(this, lp().apply { topMargin = kit.dp(12) })
    }

    private fun gap(g: LinearLayout, v: View, top: Int = 16) = g.addView(v, lp().apply { topMargin = kit.dp(top) })

    private fun label(g: LinearLayout, title: String, desc: String? = null) {
        val box = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(kit.fitText(title, 13.5f, Palette.TEXT, bold = true))
        if (desc != null) box.addView(kit.fitText(desc, 11.5f, Palette.DIM), lp().apply { topMargin = kit.dp(2) })
        gap(g, box)
    }

    /** Slider writing [key] while dragging (live re-render) and once more on release. */
    private fun liveSlider(
        g: LinearLayout, title: String, key: String, min: Int, max: Int, init: Int, step: Int = 5,
        format: (Int) -> String,
    ): Slider {
        val sl = Slider(
            kit, min, max, init, step = step, format = format, title = title, showRange = false,
            onPreview = { if (!restoring) { prefs.edit().putInt(key, it).apply(); schedule() } },
        ) { prefs.edit().putInt(key, it).apply() }
        gap(g, sl)
        return sl
    }

    private val strength: (Int) -> String = { if (it == 0) "ปิด" else "$it%" }
    private val signed: (Int) -> String = { if (it > 0) "+$it" else "$it" }

    private fun buildUpscalePage(cfg: DisplayTuner.Config): LinearLayout {
        val page = newPage()
        val up = group(page, "อัปสเกล")
        label(up, "ฟิลเตอร์")
        modeBox = FrameLayout(kit.ctx)
        buildModeSeg()
        up.addView(modeBox, lp().apply { topMargin = kit.dp(8) })

        sharpSlider = liveSlider(up, "ความคมหลังอัปสเกล", DisplayTuner.K_UPSCALE_SHARP, 0, DisplayTuner.STRENGTH_MAX, cfg.upscaleSharp, format = strength)
        label(up, "วิธีเพิ่มความคม")
        methodSeg = Segmented(
            kit, DisplayTuner.sharpMethods.map { it.second },
            DisplayTuner.sharpMethods.indexOfFirst { it.first == cfg.upscaleSharpMethod }.coerceAtLeast(0),
            textSp = 13f,
        ) { i ->
            prefs.edit().putInt(DisplayTuner.K_UPSCALE_SHARP_METHOD, DisplayTuner.sharpMethods[i].first).apply()
            schedule()
            true
        }
        up.addView(methodSeg, lp().apply { topMargin = kit.dp(8) })

        val res = group(page, "ความละเอียด (% ของภาพเต็ม)")
        percentSlider = liveSlider(res, "เรนเดอร์", DisplayTuner.K_PERCENT, DisplayTuner.MIN_PERCENT, DisplayTuner.MAX_PERCENT, cfg.percent,
            step = DisplayTuner.PERCENT_STEP, format = { "$it%" })
        outSlider = liveSlider(res, "อัปสเกล (เพิ่มจากเรนเดอร์)", DisplayTuner.K_UP_PERCENT, DisplayTuner.MIN_UP_PERCENT, DisplayTuner.MAX_UP_PERCENT, cfg.upPercent,
            step = DisplayTuner.UP_PERCENT_STEP, format = { if (it == 0) "0 · ไม่อัป" else "+$it%" })
        res.addView(
            kit.text(
                "ค่าที่เลือกบันทึกให้อัตโนมัติ · ใช้ค่าเดียวกับตอนรันจริง · " + I18n.f("ภาพเต็ม = ภาพที่เลือก (ย่อให้ด้านยาวไม่เกิน {0} px)", MAX_SIDE),
                11.5f, Palette.DIM,
            ), lp().apply { topMargin = kit.dp(12) },
        )
        return page
    }

    private fun buildEffectsPage(cfg: DisplayTuner.Config): LinearLayout {
        val page = newPage()
        val g = group(page, "เอฟเฟกต์ภาพ")
        denoiseSlider = liveSlider(g, "ลดสัญญาณรบกวน/บล็อก", DisplayTuner.K_ENH_DENOISE, 0, DisplayTuner.STRENGTH_MAX, cfg.denoise, format = strength)
        label(g, "ลดฟันปลา (Anti-aliasing)", "ก่อนอัปสเกล")
        aaSeg = Segmented(
            kit, DisplayTuner.aaModes.map { it.second },
            DisplayTuner.aaModes.indexOfFirst { it.first == cfg.aa }.coerceAtLeast(0), textSp = 13f,
        ) { i ->
            prefs.edit().putInt(DisplayTuner.K_ENH_AA, DisplayTuner.aaModes[i].first).apply()
            schedule()
            true
        }
        g.addView(aaSeg, lp().apply { topMargin = kit.dp(8) })
        debandSlider = liveSlider(g, "ลดแถบสี (Deband)", DisplayTuner.K_ENH_DEBAND, 0, DisplayTuner.STRENGTH_MAX, cfg.deband, format = strength)
        label(g, "Dither", "เติมนอยส์ละเอียดก่อนลงค่า 8-bit")
        ditherSeg = Segmented(kit, listOf("ปิด", "เปิด"), if (cfg.dither) 1 else 0, textSp = 13f) { i ->
            prefs.edit().putBoolean(DisplayTuner.K_ENH_DITHER, i == 1).apply()
            schedule()
            true
        }
        g.addView(ditherSeg, lp().apply { topMargin = kit.dp(8) })
        return page
    }

    private fun buildColourPage(cfg: DisplayTuner.Config): LinearLayout {
        val page = newPage()
        val g = DisplayTuner.GRADE_RANGE
        val color = group(page, "ปรับสี")
        brightSlider = liveSlider(color, "ความสว่าง", DisplayTuner.K_LOOK_BRIGHT, -g, g, cfg.brightness, format = signed)
        contrastSlider = liveSlider(color, "คอนทราสต์", DisplayTuner.K_LOOK_CONTRAST, -g, g, cfg.contrast, format = signed)
        satSlider = liveSlider(color, "ความอิ่มสี", DisplayTuner.K_LOOK_SAT, -g, g, cfg.saturation, format = signed)
        vibSlider = liveSlider(color, "Vibrance", DisplayTuner.K_LOOK_VIB, -g, g, cfg.vibrance, format = signed)
        tempSlider = liveSlider(color, "อุณหภูมิสี (เย็น − / อุ่น +)", DisplayTuner.K_LOOK_TEMP, -g, g, cfg.temperature, format = signed)
        tintSlider = liveSlider(color, "Tint (เขียว − / ม่วงแดง +)", DisplayTuner.K_LOOK_TINT, -g, g, cfg.tint, format = signed)
        page.addView(
            kit.fitText("รีเซ็ตเอฟเฟกต์/สีทั้งหมด", 14f, Palette.PRIMARY, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(12), 0, kit.dp(12))
                background = kit.glassBtn(16)
                setOnClickListener { resetFx() }
            },
            lp().apply { topMargin = kit.dp(14) },
        )
        return page
    }

    /** Zeroes every effect / colour control (sharpen + resolutions are left alone). */
    private fun resetFx() {
        restoring = true
        denoiseSlider.set(0); debandSlider.set(0)
        brightSlider.set(0); contrastSlider.set(0); satSlider.set(0)
        vibSlider.set(0); tempSlider.set(0); tintSlider.set(0)
        aaSeg.select(0); ditherSeg.select(0)
        restoring = false
        prefs.edit()
            .putInt(DisplayTuner.K_ENH_DENOISE, 0).putInt(DisplayTuner.K_ENH_DEBAND, 0)
            .putInt(DisplayTuner.K_ENH_AA, DisplayTuner.AA_OFF).putBoolean(DisplayTuner.K_ENH_DITHER, false)
            .putInt(DisplayTuner.K_LOOK_BRIGHT, 0).putInt(DisplayTuner.K_LOOK_CONTRAST, 0)
            .putInt(DisplayTuner.K_LOOK_SAT, 0).putInt(DisplayTuner.K_LOOK_VIB, 0)
            .putInt(DisplayTuner.K_LOOK_TEMP, 0).putInt(DisplayTuner.K_LOOK_TINT, 0)
            .apply()
        schedule()
    }

    private fun buildModeSeg() {
        val modes = DisplayTuner.upscaleModes(this)
        val at = modes.indexOfFirst { it.first == curMode }
        if (at < 0) curMode = DisplayTuner.FILTER_LINEAR
        modeSeg = Segmented(
            kit, modes.map { it.second }, modes.indexOfFirst { it.first == curMode }.coerceAtLeast(0),
            perRow = 3, textSp = 13f,
        ) { i ->
            curMode = modes[i].first
            prefs.edit().putInt(DisplayTuner.K_UPSCALE_FILTER, curMode).apply()
            schedule()
            true
        }
        modeBox.removeAllViews()
        modeBox.addView(modeSeg)
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
        loadSource()
    }

    /** Decodes the saved image (or builds the test pattern) off the UI thread, then renders. */
    private fun loadSource() {
        if (CaptureService.running || !vulkanOk) return
        loading = true
        view.message = "กำลังโหลดภาพ…"
        val uriString = prefs.getString(KEY_URI, null)
        worker.execute {
            var bmp: Bitmap? = null
            var failed = false
            if (uriString != null) {
                bmp = runCatching { decode(Uri.parse(uriString)) }
                    .getOrNull()
                if (bmp == null) failed = true
            }
            if (bmp == null) bmp = testPattern()
            val loaded = bmp
            runOnUiThread {
                base = loaded
                loading = false
                if (failed) I18n.toastMake(this, "เปิดภาพไม่ได้ · ใช้ภาพทดสอบแทน", Toast.LENGTH_SHORT).show()
                schedule()
            }
        }
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val longSide = max(raw.width, raw.height)
        if (longSide <= MAX_SIDE) return raw
        val f = MAX_SIDE.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(
            raw, (raw.width * f).roundToInt().coerceAtLeast(2), (raw.height * f).roundToInt().coerceAtLeast(2), true,
        )
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    /** Edges, hairlines, text, a zone plate and a gradient: things that show resampling differences. */
    private fun testPattern(): Bitmap {
        val w = 1920
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1B2A41.toInt(), 0xFF324A5F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // fine grid
        p.strokeWidth = 1f
        p.color = 0x55FFFFFF
        var x = 0
        while (x < w) { c.drawLine(x.toFloat(), 0f, x.toFloat(), 300f, p); x += 12 }
        var y = 0
        while (y < 300) { c.drawLine(0f, y.toFloat(), w.toFloat() * 0.5f, y.toFloat(), p); y += 12 }

        // diagonal + curved edges
        p.color = Color.WHITE
        p.strokeWidth = 2f
        for (i in 0 until 24) c.drawLine(980f + i * 14f, 20f, 1180f + i * 14f, 280f, p)
        p.style = Paint.Style.STROKE
        for (i in 1..10) c.drawCircle(1620f, 160f, i * 14f, p)
        p.style = Paint.Style.FILL

        // zone plate
        val zs = 380
        val zone = IntArray(zs * zs)
        val k = (2.0 * PI / zs).toFloat() // reaches Nyquist (0.5 cycle/px) at the plate's edge
        for (j in 0 until zs) for (i in 0 until zs) {
            val dx = (i - zs / 2).toFloat()
            val dy = (j - zs / 2).toFloat()
            val v = (0.5f + 0.5f * cos(k * (dx * dx + dy * dy) / 2f)).coerceIn(0f, 1f)
            val g = (v * 255f).toInt()
            zone[j * zs + i] = Color.rgb(g, g, g)
        }
        c.drawBitmap(Bitmap.createBitmap(zone, zs, zs, Bitmap.Config.ARGB_8888), 60f, 360f, null)

        // text at several sizes
        p.typeface = Typeface.DEFAULT_BOLD
        var ty = 400f
        for (sz in intArrayOf(64, 44, 32, 24, 18, 14, 11)) {
            p.textSize = sz.toFloat()
            p.color = Color.WHITE
            c.drawText("Deepdrop อัปสเกล ความคม 0123456789", 520f, ty, p)
            ty += sz * 1.35f
        }
        p.typeface = Typeface.DEFAULT

        // colour bars and hard edges
        val cols = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.BLACK)
        for ((i, col) in cols.withIndex()) {
            p.color = col
            c.drawRect(1100f + i * 90f, 380f, 1100f + i * 90f + 90f, 560f, p)
        }
        // checkerboard (1 px and 4 px cells)
        for (cell in intArrayOf(1, 4)) {
            val ox = if (cell == 1) 1100 else 1280
            for (j in 0 until 40) for (i in 0 until 40) {
                p.color = if ((i + j) % 2 == 0) Color.WHITE else Color.BLACK
                c.drawRect((ox + i * cell).toFloat(), (600 + j * cell).toFloat(), (ox + i * cell + cell).toFloat(), (600 + j * cell + cell).toFloat(), p)
            }
        }
        // soft gradient ramp + thin black text-like strokes on white
        p.shader = LinearGradient(60f, 0f, 1860f, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
        c.drawRect(60f, 800f, 1860f, 860f, p)
        p.shader = null
        p.color = Color.WHITE
        c.drawRect(60f, 900f, 1860f, 1040f, p)
        p.color = Color.BLACK
        p.strokeWidth = 1f
        for (i in 0 until 120) c.drawLine(80f + i * 15f, 910f, 80f + i * 15f + (i % 7) * 3f, 1030f, p)
        return bmp
    }

    private fun schedule() {
        val src = base ?: return
        if (CaptureService.running || !vulkanOk || loading) return
        val mode = curMode
        val sharp = sharpSlider.value
        val method = DisplayTuner.sharpMethods.getOrNull(methodSeg.selected)?.first ?: DisplayTuner.SHARP_RCAS
        val percent = percentSlider.value
        val upPercent = outSlider.value
        val enhance = DisplayTuner.packEnhance(
            debandSlider.value, denoiseSlider.value,
            DisplayTuner.aaModes.getOrNull(aaSeg.selected)?.first ?: DisplayTuner.AA_OFF,
            ditherSeg.selected == 1,
        )
        val look = DisplayTuner.packLook(
            brightSlider.value, contrastSlider.value,
            satSlider.value, vibSlider.value, tempSlider.value, tintSlider.value,
        )
        val my = generation.incrementAndGet()
        worker.execute {
            if (my != generation.get()) return@execute // a newer request is queued
            val bw = src.width
            val bh = src.height
            val rw = (bw * percent / 100f).roundToInt().coerceAtLeast(2)
            val rh = (bh * percent / 100f).roundToInt().coerceAtLeast(2)
            // upscale = render size x (1 + up %), so 0 keeps the render size
            var ow = (rw * (1f + upPercent / 100f)).roundToInt().coerceAtLeast(2)
            var oh = (rh * (1f + upPercent / 100f)).roundToInt().coerceAtLeast(2)
            // same output-area cap as the live service (200 % outputs are 4x the pixels)
            if (ow.toLong() * oh > DisplayTuner.MAX_OUT_PIXELS) {
                val k = Math.sqrt(DisplayTuner.MAX_OUT_PIXELS.toDouble() / (ow.toLong() * oh))
                ow = (ow * k).toInt().coerceAtLeast(2)
                oh = (oh * k).toInt().coerceAtLeast(2)
            }

            val low = if (rw == bw && rh == bh) src else Bitmap.createScaledBitmap(src, rw, rh, true)
            val buf = ByteBuffer.allocate(rw * rh * 4)
            low.copyPixelsToBuffer(buf)
            val t0 = SystemClock.elapsedRealtimeNanos()
            val out = FluxaNative.nativeStillUpscale(buf.array(), rw, rh, ow, oh, mode,
                sharp or (method shl 8) or enhance, look)
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            var result: Bitmap? = null
            if (out != null && out.size >= ow * oh * 4) {
                result = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                result.copyPixelsFromBuffer(ByteBuffer.wrap(out))
            }
            if (my != generation.get()) return@execute
            runOnUiThread {
                view.beforeLabel = "ก่อน · ${rw}×${rh}"
                view.afterLabel = "หลัง · ${ow}×${oh}"
                view.setImages(low, result, bw, bh)
                view.message = if (result == null) "อัปสเกลไม่สำเร็จ" else null
                val modeName = DisplayTuner.upscaleModes(this).firstOrNull { it.first == mode }?.second ?: "?"
                info.visibility = View.VISIBLE
                info.text = buildString {
                    append(modeName)
                    if (sharp > 0) {
                        append(" + ${DisplayTuner.sharpMethods.firstOrNull { it.first == method }?.second ?: "RCAS"} $sharp%")
                    }
                    if (enhance != 0 || look != 0L) append(" + เอฟเฟกต์")
                    append(" · ${rw}×${rh} {ic:arrow} ${ow}×${oh} · ${"%.1f".format(ms)} ms")
                    if (ow <= rw) append("\nเอาต์พุตไม่เกินความละเอียดที่เรนเดอร์ {ic:arrow} ตอนรันจริงจะไม่อัปสเกล")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            worker.execute { FluxaNative.nativeStillRelease() }
        }
        worker.shutdown()
    }

    companion object {
        private const val REQ_PICK = 11
        private const val KEY_URI = "cmp_uri"
        private const val MAX_SIDE = 2400
    }

    override fun finish() {
        super.finish()
        AppSettings.transition(this, false)
    }
}
