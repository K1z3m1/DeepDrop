package app.fluxa

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import java.io.File

/**
 * Settings for the in-game panel (side it opens from + the CPU / GPU dials). Same "fluxa"
 * SharedPreferences as everything else; read fresh each time the panel is built.
 */
object PanelPrefs {
    const val K_SIDE = "panel_side"            // 0 right, 1 left
    const val K_CPU = "panel_dial_cpu"
    const val K_GPU = "panel_dial_gpu"
    const val K_STYLE = "panel_dial_style"     // 0 follow theme, 1 arc, 2 ticks
    const val K_SIZE = "panel_dial_size"       // dp
    const val K_COLOR = "panel_dial_color"     // 0 accent, 1 by load, 2 secondary
    const val K_PCT = "panel_dial_pct"

    const val MIN_SIZE = 64
    const val MAX_SIZE = 140
    const val DEFAULT_SIZE = 96

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("fluxa", Context.MODE_PRIVATE)

    fun left(ctx: Context) = p(ctx).getInt(K_SIDE, 0) == 1
    fun showCpu(ctx: Context) = p(ctx).getBoolean(K_CPU, true)
    fun showGpu(ctx: Context) = p(ctx).getBoolean(K_GPU, true)
    fun style(ctx: Context) = p(ctx).getInt(K_STYLE, ClockDial.STYLE_AUTO).coerceIn(0, 2)
    fun size(ctx: Context) = p(ctx).getInt(K_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
    fun color(ctx: Context) = p(ctx).getInt(K_COLOR, 0).coerceIn(0, 2)
    fun showPct(ctx: Context) = p(ctx).getBoolean(K_PCT, true)
}

/** Reads CPU / GPU clocks from sysfs. Phones differ a lot, and some block these files for apps (then null). */
object ClockReader {
    private fun readLong(path: String): Long =
        runCatching { File(path).readText().trim().toLong() }.getOrDefault(0L)

    private val cpuCount = Runtime.getRuntime().availableProcessors()
    private val cpuCurPaths = Array(cpuCount) { "/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq" }
    private var cpuMaxKhz = 0L // cpuinfo_max_freq never changes at run time: read it once, not every second

    private fun cpuMaxOnce(): Long {
        if (cpuMaxKhz > 0) return cpuMaxKhz
        var max = 0L
        for (i in 0 until cpuCount) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
            max = maxOf(max, readLong(base + "cpuinfo_max_freq"), readLong(base + "scaling_max_freq"))
        }
        cpuMaxKhz = max
        return max
    }

    /** Highest current clock and highest possible clock over all cores, in kHz; null when sysfs is not readable. */
    fun cpuKhz(): Pair<Long, Long>? {
        var cur = 0L
        for (path in cpuCurPaths) cur = maxOf(cur, readLong(path))
        val max = cpuMaxOnce()
        if (cur <= 0 || max <= 0) return null
        return cur to maxOf(cur, max)
    }

    // ---- GPU
    private val gpuCurFiles = ArrayList<String>()
    private val gpuMaxFiles = ArrayList<String>()
    private val gpuListFiles = ArrayList<String>()
    private var gpuScanned = false
    private var gpuSeenMax = 0.0

    private fun scanGpu() {
        gpuScanned = true
        val cur = arrayListOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz",
            "/sys/kernel/gpu/gpu_clock",
            "/sys/class/misc/mali0/device/clock",
            "/sys/devices/platform/gpusysfs/gpu_clock",
        )
        val max = arrayListOf(
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/kernel/gpu/gpu_max_clock",
        )
        val lists = arrayListOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",
        )
        // Mali / other SoCs expose the GPU as a devfreq device
        runCatching {
            File("/sys/class/devfreq").listFiles()?.forEach { d ->
                val n = d.name.lowercase()
                if (GPU_WORDS.any { n.contains(it) }) {
                    cur.add(d.path + "/cur_freq")
                    max.add(d.path + "/max_freq")
                    lists.add(d.path + "/available_frequencies")
                }
            }
        }
        gpuCurFiles.addAll(cur)
        gpuMaxFiles.addAll(max)
        gpuListFiles.addAll(lists)
    }

    private val GPU_WORDS = listOf("gpu", "mali", "kgsl", "adreno", "g3d", "pvr", "img")

    /** The kernel reports Hz, kHz or MHz depending on the driver; bring everything to MHz. */
    private fun toMhz(v: Long): Double = when {
        v >= 1_000_000L -> v / 1_000_000.0
        v >= 10_000L -> v / 1_000.0
        else -> v.toDouble()
    }

    private fun readFirst(files: List<String>): Double {
        for (f in files) {
            val v = readLong(f)
            if (v > 0) return toMhz(v)
        }
        return 0.0
    }

    private fun readListMax(files: List<String>): Double {
        var best = 0.0
        for (f in files) {
            val s = runCatching { File(f).readText() }.getOrNull() ?: continue
            for (tok in s.split(' ', '\n', '\t')) {
                val v = tok.trim().toLongOrNull() ?: continue
                best = maxOf(best, toMhz(v))
            }
            if (best > 0) return best
        }
        return best
    }

    /** Current and highest GPU clock in MHz; null when the phone does not let apps read it. */
    @Synchronized
    fun gpuMhz(): Pair<Double, Double>? {
        if (!gpuScanned) scanGpu()
        val cur = readFirst(gpuCurFiles)
        if (cur <= 0.0) return null
        var max = maxOf(readFirst(gpuMaxFiles), readListMax(gpuListFiles))
        gpuSeenMax = maxOf(gpuSeenMax, cur, max)
        max = maxOf(max, gpuSeenMax)
        return cur to max
    }

    /** Every clock step the GPU can use, in MHz ascending; empty when the phone does not list them. */
    @Synchronized
    fun gpuSteps(): List<Double> {
        if (!gpuScanned) scanGpu()
        for (f in gpuListFiles) {
            val s = runCatching { File(f).readText() }.getOrNull() ?: continue
            val steps = s.split(' ', '\n', '\t').mapNotNull { it.trim().toLongOrNull() }.map { toMhz(it) }
                .filter { it > 0 }.distinct().sorted()
            if (steps.isNotEmpty()) return steps
        }
        return emptyList()
    }

    /** GPU load in percent when the driver exposes it (Adreno / some Mali); null otherwise. */
    fun gpuBusy(): Int? {
        readLong("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").takeIf { it in 1..100 }?.let { return it.toInt() }
        runCatching { File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim() }.getOrNull()?.let { s ->
            val p = s.split(' ').filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }
            if (p.size >= 2 && p[1] > 0) return (p[0] * 100 / p[1]).toInt().coerceIn(0, 100)
        }
        runCatching {
            File("/sys/class/devfreq").listFiles()?.forEach { d ->
                if (GPU_WORDS.any { d.name.lowercase().contains(it) }) {
                    val l = runCatching { File(d.path + "/load").readText().trim() }.getOrNull()
                    val v = l?.substringBefore('@')?.trim()?.toIntOrNull()
                    if (v != null) return v.coerceIn(0, 100)
                }
            }
        }
        return null
    }
}

/**
 * Round clock dial: a 270° arc (or a ring of ticks in the game look) with the value and its unit
 * drawn in the exact centre of the ring. Text is sized from the ring itself, so it stays inside the
 * dial whatever the system font scale or the app text scale is.
 */
class ClockDial(private val kit: Kit) : View(kit.ctx) {
    companion object {
        const val STYLE_AUTO = 0
        const val STYLE_ARC = 1
        const val STYLE_TICKS = 2
    }

    /** Preferred side in px; the dial shrinks to fit when the parent is smaller. */
    var prefSizePx = kit.dp(112)
        set(v) { field = v; requestLayout() }
    var style = STYLE_AUTO
        set(v) { field = v; invalidate() }

    /** Optional small title drawn inside the ring above the value (e.g. "CPU"); empty = none. */
    var label = ""
        set(v) { field = v; invalidate() }

    private var shown = 0f
    private var color = Palette.PRIMARY
    private var valueText = "--"
    private var unitText = ""
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private val bounds = Rect()

    fun set(fraction: Float, c: Int, value: String, unit: String) {
        color = c
        valueText = value
        unitText = unit
        val target = fraction.coerceIn(0f, 1f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        var side = prefSizePx
        if (wMode != MeasureSpec.UNSPECIFIED) side = minOf(side, MeasureSpec.getSize(widthMeasureSpec))
        if (hMode != MeasureSpec.UNSPECIFIED) side = minOf(side, MeasureSpec.getSize(heightMeasureSpec))
        side = side.coerceAtLeast(kit.dp(40))
        setMeasuredDimension(side, side)
    }

    private fun useTicks() = style == STYLE_TICKS || (style == STYLE_AUTO && kit.rm)

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val ticks = useTicks()
        val stroke = s * 0.075f
        // radius of the free space inside the ring
        val innerEdge: Float
        if (ticks) {
            val outer = s / 2f - s * 0.03f
            val len = s * 0.09f
            innerEdge = outer - len - s * 0.03f
            drawTicks(c, cx, cy, outer, len, s)
        } else {
            val inset = stroke / 2f + s * 0.02f
            box.set(cx - s / 2f + inset, cy - s / 2f + inset, cx + s / 2f - inset, cy + s / 2f - inset)
            track.strokeWidth = stroke
            arc.strokeWidth = stroke
            track.color = Palette.STROKE
            arc.color = color
            c.drawArc(box, 135f, 270f, false, track)
            if (shown > 0.005f) c.drawArc(box, 135f, 270f * shown, false, arc)
            innerEdge = s / 2f - inset - stroke / 2f
        }
        drawCentered(c, cx, cy, innerEdge, s)
    }

    private fun drawTicks(c: Canvas, cx: Float, cy: Float, outer: Float, len: Float, s: Float) {
        val n = 41
        tick.strokeWidth = (s * 0.018f).coerceAtLeast(1.5f)
        val inner = outer - len
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val a = Math.toRadians(135.0 + 270.0 * f)
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            val lit = shown > 0.005f && f <= shown + 0.001f
            val rr = if (i % 5 == 0) inner - s * 0.03f else inner
            tick.color = if (lit) color else Palette.STROKE
            c.drawLine(cx + cs * rr, cy + sn * rr, cx + cs * outer, cy + sn * outer, tick)
        }
    }

    /** Value + unit as one block, centred on the ring both ways, shrunk until it fits inside it. */
    private fun drawCentered(c: Canvas, cx: Float, cy: Float, innerEdge: Float, s: Float) {
        val maxW = (innerEdge - s * 0.05f) * 2f * 0.84f
        if (maxW <= 0f) return
        valuePaint.color = color
        valuePaint.typeface =
            if (kit.rm) Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 700, false)
            else Typeface.create(Typeface.DEFAULT, 700, false)
        valuePaint.textSkewX = if (kit.rm) -0.2f else 0f
        valuePaint.textSize = s * 0.27f
        val w = valuePaint.measureText(valueText)
        if (w > maxW) valuePaint.textSize *= maxW / w

        unitPaint.color = Palette.MUTED
        unitPaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        unitPaint.textSize = (valuePaint.textSize * 0.42f).coerceAtLeast(s * 0.09f)

        valuePaint.getTextBounds("0", 0, 1, bounds)
        val vh = bounds.height().toFloat()
        val hasUnit = unitText.isNotEmpty()
        var uh = 0f
        if (hasUnit) {
            unitPaint.getTextBounds("Hz", 0, 2, bounds)
            uh = bounds.height().toFloat()
        }
        val hasLabel = label.isNotEmpty()
        var lh = 0f
        if (hasLabel) {
            labelPaint.color = Palette.TEXT
            labelPaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            labelPaint.textSize = unitPaint.textSize * 1.1f
            labelPaint.getTextBounds("CPU", 0, 3, bounds)
            lh = bounds.height().toFloat()
        }
        val gap = if (hasUnit || hasLabel) s * 0.05f else 0f
        val total = (if (hasLabel) lh + gap else 0f) + vh + (if (hasUnit) gap + uh else 0f)
        var y = cy - total / 2f
        if (hasLabel) {
            c.drawText(label, cx, y + lh, labelPaint)
            y += lh + gap
        }
        // italic lean moves the glyphs right on average by about a tenth of their height
        val lean = if (kit.rm) vh * 0.1f else 0f
        c.drawText(valueText, cx - lean, y + vh, valuePaint)
        if (hasUnit) c.drawText(unitText, cx, y + vh + gap + uh, unitPaint)
    }
}

/**
 * One titled dial with a "max · %" line under it. Used by the in-game panel (CPU / GPU) and by the
 * settings preview. [update] takes values in the dial's own unit (GHz or MHz).
 */
class DialTile(
    private val kit: Kit,
    title: String,
    private val unit: String,
    private val decimals: Int,
    /** Header look: no card, no title / foot line; the title is drawn inside the ring instead. */
    private val bare: Boolean = false,
) : LinearLayout(kit.ctx) {
    val dial = ClockDial(kit)
    private val foot = kit.text("", 11.5f, Palette.MUTED).apply { gravity = Gravity.CENTER }
    private var colorMode = 0
    private var showPct = true
    private var lastCur: Double? = null
    private var lastMax: Double? = null
    private var updated = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        if (bare) {
            dial.label = title
            addView(dial, LayoutParams(-2, -2))
            foot.visibility = View.GONE
        } else {
            background = kit.panelCard(18)
            setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10))
            addView(kit.text(title, 12.5f, Palette.TEXT, bold = true))
            addView(dial, LayoutParams(-2, -2).apply { topMargin = kit.dp(8) })
            addView(foot, LayoutParams(-2, -2).apply { topMargin = kit.dp(8) })
        }
    }

    /** Re-reads the look chosen in panel settings. */
    fun applyLook(ctx: Context) {
        dial.style = PanelPrefs.style(ctx)
        dial.prefSizePx = kit.dp(PanelPrefs.size(ctx))
        colorMode = PanelPrefs.color(ctx)
        showPct = PanelPrefs.showPct(ctx)
        foot.visibility = if (showPct && !bare) View.VISIBLE else View.GONE
        if (updated) update(lastCur, lastMax)
    }

    private fun levelColor(pct: Int) = when (colorMode) {
        1 -> if (pct >= 90) Palette.DANGER else if (pct >= 70) Palette.WARN else Palette.OK
        2 -> Palette.SECONDARY
        else -> if (pct >= 90) Palette.DANGER else Palette.PRIMARY
    }

    private fun fmt(v: Double) = String.format("%.${decimals}f", v)

    fun update(cur: Double?, max: Double?) {
        updated = true
        lastCur = cur
        lastMax = max
        if (cur == null || max == null || max <= 0.0) {
            dial.set(0f, levelColor(0), "--", unit)
            foot.text = I18n.tr("อ่านค่าไม่ได้")
            return
        }
        val pct = (cur * 100 / max).toInt().coerceIn(0, 100)
        dial.set(pct / 100f, levelColor(pct), fmt(cur), unit)
        foot.text = I18n.tr("สูงสุด") + " ${fmt(max)} $unit · $pct%"
    }
}
