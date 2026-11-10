package app.fluxa

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Home "Real-time Monitoring" section: CPU and GPU clock gauges, RAM and storage usage, the
 * network latency and the clear-RAM button. Every card opens its own detail page ([MonitorDetailActivity]).
 * Just sitting on the home tab, the only network traffic is a tiny TCP handshake to 8.8.8.8 to time the ping.
 */
class HomeMonitor(
    private val act: Activity,
    private val kit: Kit,
    /** The clear-RAM button asks the launcher to stop background apps; [done] re-enables the button. */
    private val onCleanRam: (done: () -> Unit) -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var running = false
    private var reading = false
    private var readingGpu = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refreshCpu()
            refreshGpu()
            refreshRam()
            refreshStorage()
            if (tickCount % 2 == 0) refreshBattery()
            if (tickCount++ % 3 == 0) refreshPing()
            ui.postDelayed(this, 1000)
        }
    }

    // ---- views
    private lateinit var cpuDial: ClockDial
    private lateinit var cpuMax: TextView
    private lateinit var cpuPct: TextView
    private lateinit var gpuDial: ClockDial
    private lateinit var gpuMax: TextView
    private lateinit var gpuPct: TextView
    private lateinit var ramValue: TextView
    private lateinit var ramBar: SegmentBar
    private lateinit var ramTotal: TextView
    private lateinit var stoValue: TextView
    private lateinit var stoUsed: View
    private lateinit var stoFree: View
    private lateinit var stoTotal: TextView
    private lateinit var netValue: TextView
    private lateinit var netUnit: TextView
    private lateinit var netChip: TextView
    private lateinit var netBar: SegmentBar
    private lateinit var batValue: TextView
    private lateinit var batSub: TextView
    private lateinit var batBar: SegmentBar
    private var readingBat = false
    private lateinit var cleanBtn: TextView
    private var pinging = false
    private var tickCount = 0
    private var cleaning = false

    fun start() {
        running = true
        ui.removeCallbacks(tick)
        tick.run()
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
    }

    // ------------------------------------------------------------------ layout

    fun build(): View {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(4), 0, 0, 0)
        }
        kit.addMark(head, 16, 8)
        head.addView(kit.fitText("การตรวจสอบแบบเรียลไทม์", 15f, Palette.TEXT, bold = true))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // row 1: the two clock dials; row 2: memory and storage
        val dials = LinearLayout(act)
        dials.addView(buildCpuTile(), LinearLayout.LayoutParams(0, -1, 1f))
        dials.addView(buildGpuTile(), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = kit.dp(10) })
        root.addView(dials, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })

        val mem = LinearLayout(act)
        mem.addView(buildRamTile(), LinearLayout.LayoutParams(0, -1, 1f))
        mem.addView(buildStorageTile(), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = kit.dp(10) })
        root.addView(mem, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })

        root.addView(buildBatteryTile(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
        root.addView(buildNetworkTile(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
        root.addView(buildCleanButton(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })

        refreshRam(); refreshStorage()
        return root
    }

    /** A glass card that opens the detail page [kind] when tapped. */
    private fun tile(kind: String): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.glass(20)
        setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(14))
        isClickable = true
        isFocusable = true
        val d = act.resources.displayMetrics.density
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = (if (kit.rm) 6f else 20f) * d }
        foreground = RippleDrawable(ColorStateList.valueOf(Palette.a(Palette.PRIMARY, 0x30)), null, mask)
        setOnClickListener { MonitorDetailActivity.open(act, kind) }
    }

    /** Card title with a small chevron on the right that says "tap for details". */
    private fun titleRow(label: String): View = LinearLayout(act).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(title(label), LinearLayout.LayoutParams(0, -2, 1f))
        addView(kit.icon(R.drawable.fluxa_ic_chevron_right, 16, Palette.DIM))
    }

    private fun title(label: String) = kit.fitText(label, 13f, Palette.TEXT, bold = true)

    /** Number + muted "/ 100" on one baseline. */
    private fun scoreRow(valueView: TextView): LinearLayout = LinearLayout(act).apply {
        gravity = Gravity.BOTTOM
        addView(valueView)
        addView(
            kit.fitText("/ 100", 13f, Palette.DIM),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(4); bottomMargin = kit.dp(3) },
        )
    }

    private fun num(size: Float = 28f) =
        kit.fitDisplay("--", size * (if (kit.rm) 1.12f else 1f), Palette.PRIMARY).apply { fontFeatureSettings = "tnum" }

    private fun buildCpuTile(): View {
        val t = tile(MonitorDetailActivity.CPU)
        t.addView(titleRow("ความเร็ว CPU"))

        // the dial draws its own value + unit in the exact centre of the ring and sizes the text from the ring
        cpuDial = ClockDial(kit)
        cpuDial.prefSizePx = kit.dp(124)
        val gaugeBox = FrameLayout(act).apply { minimumHeight = kit.dp(96) }
        gaugeBox.addView(cpuDial, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        t.addView(gaugeBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10); bottomMargin = kit.dp(10) })

        val foot = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        cpuMax = kit.fitText("สูงสุด --", 11.5f, Palette.MUTED)
        cpuPct = kit.fitText("--", 12f, Palette.PRIMARY, bold = true).apply { fontFeatureSettings = "tnum" }
        foot.addView(cpuMax, LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(cpuPct)
        t.addView(foot, LinearLayout.LayoutParams(-1, -2))
        return t
    }

    private fun buildGpuTile(): View {
        val t = tile(MonitorDetailActivity.GPU)
        t.addView(titleRow("ความเร็ว GPU"))

        gpuDial = ClockDial(kit)
        gpuDial.prefSizePx = kit.dp(124)
        val gaugeBox = FrameLayout(act).apply { minimumHeight = kit.dp(96) }
        gaugeBox.addView(gpuDial, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        t.addView(gaugeBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10); bottomMargin = kit.dp(10) })

        val foot = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        gpuMax = kit.fitText("สูงสุด --", 11.5f, Palette.MUTED)
        gpuPct = kit.fitText("--", 12f, Palette.PRIMARY, bold = true).apply { fontFeatureSettings = "tnum" }
        foot.addView(gpuMax, LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(gpuPct)
        t.addView(foot, LinearLayout.LayoutParams(-1, -2))
        return t
    }

    private fun buildRamTile(): View {
        val t = tile(MonitorDetailActivity.RAM)
        t.addView(titleRow("การใช้ RAM"))
        ramValue = num()
        t.addView(scoreRow(ramValue), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(8) })
        ramBar = SegmentBar(act, 10, kit.dpf(2))
        t.addView(ramBar, LinearLayout.LayoutParams(-1, kit.dp(5)).apply { topMargin = kit.dp(10) })
        ramTotal = kit.fitText("", 11.5f, Palette.MUTED)
        t.addView(ramTotal, LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(10) })
        return t
    }

    private fun buildStorageTile(): View {
        val t = tile(MonitorDetailActivity.STORAGE)
        t.addView(titleRow("พื้นที่เก็บข้อมูล"))
        stoValue = num()
        t.addView(scoreRow(stoValue), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(8) })
        val track = LinearLayout(act).apply { background = kit.rounded(Palette.STROKE, 3) }
        stoUsed = View(act).apply { background = kit.rounded(Palette.PRIMARY, 3) }
        stoFree = View(act)
        track.addView(stoUsed, LinearLayout.LayoutParams(0, -1, 1f))
        track.addView(stoFree, LinearLayout.LayoutParams(0, -1, 1f))
        t.addView(track, LinearLayout.LayoutParams(-1, kit.dp(5)).apply { topMargin = kit.dp(10) })
        stoTotal = kit.fitText("", 11.5f, Palette.MUTED)
        t.addView(stoTotal, LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(10) })
        return t
    }

    private fun buildBatteryTile(): View {
        val t = tile(MonitorDetailActivity.BATTERY)
        t.addView(titleRow("แบตเตอรี่"))
        val mid = LinearLayout(act).apply { gravity = Gravity.BOTTOM }
        batValue = num(26f)
        mid.addView(batValue)
        mid.addView(kit.fitText("%", 13f, Palette.MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(5); bottomMargin = kit.dp(3) })
        batSub = kit.fitText("", 11.5f, Palette.MUTED).apply { gravity = Gravity.END }
        mid.addView(batSub, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = kit.dp(3) })
        t.addView(mid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
        batBar = SegmentBar(act, 10, kit.dpf(2))
        t.addView(batBar, LinearLayout.LayoutParams(-1, kit.dp(5)).apply { topMargin = kit.dp(10) })
        return t
    }

    /** Big number: battery level. Sub line: charge state and the power in watts right now. */
    private fun refreshBattery() {
        if (!::batValue.isInitialized || readingBat) return
        readingBat = true
        Bg.run {
            val b = SocInfo.battery(act)
            ui.post {
                readingBat = false
                if (!running || !::batValue.isInitialized) return@post
                if (b == null) {
                    batValue.text = "--"
                    batSub.text = I18n.tr("อ่านค่าไม่ได้")
                    batBar.set(0f, Palette.PRIMARY)
                    return@post
                }
                val color = if (b.percent <= 15 && !b.charging) Palette.DANGER else Palette.PRIMARY
                batValue.text = "${b.percent}"
                batValue.setTextColor(color)
                batBar.set(b.percent / 100f, color)
                val state = when {
                    b.full -> I18n.tr("เต็มแล้ว")
                    b.charging -> I18n.tr("กำลังชาร์จ")
                    else -> I18n.tr("ใช้งานอยู่")
                }
                batSub.text = if (b.watts != null && !b.full) state + String.format(" · %.1f W", b.watts) else state
            }
        }
    }

    private fun buildNetworkTile(): View {
        val t = tile(MonitorDetailActivity.NET)
        val top = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(title("เครือข่าย"), LinearLayout.LayoutParams(0, -2, 1f))
        netChip = kit.fitText("--", 11.5f, Palette.MUTED, bold = true).apply {
            setPadding(kit.dp(10), kit.dp(4), kit.dp(10), kit.dp(4))
            background = kit.rounded(Palette.CHIP, 12, Palette.STROKE, 1)
        }
        top.addView(netChip)
        top.addView(kit.icon(R.drawable.fluxa_ic_chevron_right, 16, Palette.DIM), LinearLayout.LayoutParams(kit.dp(16), kit.dp(16)).apply { marginStart = kit.dp(6) })
        t.addView(top)

        val mid = LinearLayout(act).apply { gravity = Gravity.BOTTOM }
        netValue = num(26f)
        netUnit = kit.fitText("ms", 13f, Palette.MUTED)
        mid.addView(netValue)
        mid.addView(netUnit, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(5); bottomMargin = kit.dp(3) })
        mid.addView(
            kit.fitText(PING_HOST, 12.5f, Palette.MUTED).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = kit.dp(3) },
        )
        t.addView(mid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })

        netBar = SegmentBar(act, 5, kit.dpf(3))
        t.addView(netBar, LinearLayout.LayoutParams(-1, kit.dp(5)).apply { topMargin = kit.dp(12) })
        return t
    }

    private fun buildCleanButton(): View {
        cleanBtn = kit.fitText("ทำความสะอาด RAM", 14.5f, kit.onHot, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(8), kit.dp(15), kit.dp(8), kit.dp(15))
            background = kit.hot(16)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (cleaning) return@setOnClickListener
                cleaning = true
                text = I18n.tr("กำลังทำความสะอาด…")
                onCleanRam {
                    cleaning = false
                    text = I18n.tr("ทำความสะอาด RAM")
                }
            }
        }
        return cleanBtn
    }

    // ------------------------------------------------------------------ data

    private fun levelColor(pct: Int) = if (pct >= 90) Palette.DANGER else Palette.PRIMARY

    private fun refreshCpu() {
        if (!::cpuDial.isInitialized || reading) return
        reading = true
        Bg.run {
            val r = ClockReader.cpuKhz()
            ui.post {
                reading = false
                if (!running || !::cpuDial.isInitialized) return@post
                if (r == null) {
                    cpuDial.set(0f, Palette.PRIMARY, "--", "GHz")
                    cpuMax.text = I18n.tr("สูงสุด") + " --"
                    cpuPct.text = "--"
                } else {
                    val (cur, max) = r
                    val pct = (cur * 100 / max).toInt().coerceIn(0, 100)
                    cpuDial.set(pct / 100f, levelColor(pct), String.format("%.2f", cur / 1_000_000.0), "GHz")
                    cpuMax.text = I18n.tr("สูงสุด") + String.format(" %.2f GHz", max / 1_000_000.0)
                    cpuPct.text = "$pct%"
                }
            }
        }
    }

    private fun refreshGpu() {
        if (!::gpuDial.isInitialized || readingGpu) return
        readingGpu = true
        Bg.run {
            val r = ClockReader.gpuMhz()
            ui.post {
                readingGpu = false
                if (!running || !::gpuDial.isInitialized) return@post
                if (r == null) {
                    gpuDial.set(0f, Palette.PRIMARY, "--", "MHz")
                    gpuMax.text = I18n.tr("อ่านค่าไม่ได้")
                    gpuPct.text = "--"
                } else {
                    val (cur, max) = r
                    val pct = (cur * 100 / max).toInt().coerceIn(0, 100)
                    gpuDial.set(pct / 100f, levelColor(pct), String.format("%.0f", cur), "MHz")
                    gpuMax.text = I18n.tr("สูงสุด") + String.format(" %.0f MHz", max)
                    gpuPct.text = "$pct%"
                }
            }
        }
    }

    private fun refreshRam() {
        if (!::ramValue.isInitialized) return
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = (mi.totalMem - mi.availMem).coerceAtLeast(0)
        val pct = if (mi.totalMem > 0) (used * 100 / mi.totalMem).toInt().coerceIn(0, 100) else 0
        val color = levelColor(pct)
        ramValue.text = "$pct"
        ramValue.setTextColor(color)
        ramBar.set(pct / 100f, color)
        // the system reports a bit less than the marketed size, so round up to whole GB
        val gb = Math.ceil(mi.totalMem / 1073741824.0).toInt().coerceAtLeast(1)
        ramTotal.text = I18n.tr("ความจุรวม") + " $gb GB"
    }

    private fun refreshStorage() {
        if (!::stoValue.isInitialized) return
        val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() ?: return
        val total = fs.totalBytes
        val used = (total - fs.availableBytes).coerceAtLeast(0)
        val pct = if (total > 0) (used * 100 / total).toInt().coerceIn(0, 100) else 0
        val color = levelColor(pct)
        stoValue.text = "$pct"
        stoValue.setTextColor(color)
        stoUsed.background = kit.rounded(color, 3)
        (stoUsed.layoutParams as LinearLayout.LayoutParams).weight = pct.coerceAtLeast(1).toFloat()
        (stoFree.layoutParams as LinearLayout.LayoutParams).weight =
            (100 - pct).toFloat().let { if (it <= 0f) 0.001f else it }
        stoUsed.requestLayout()
        stoTotal.text = I18n.tr("ความจุรวม") + " ${Math.round(total / 1073741824.0)} GB"
    }

    private data class Chip(val label: String, val color: Int, val soft: Int, val line: Int)

    /** Times a TCP handshake to [PING_HOST]; -1 when unreachable. Runs off the main thread. */
    private fun refreshPing() {
        if (!::netValue.isInitialized || pinging) return
        pinging = true
        Bg.run {
            val ms = runCatching {
                Socket().use { sk ->
                    val t0 = System.nanoTime()
                    sk.connect(InetSocketAddress(PING_HOST, 53), 1500)
                    ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                }
            }.getOrDefault(-1)
            ui.post {
                pinging = false
                if (running && ::netValue.isInitialized) showPing(ms)
            }
        }
    }

    private fun showPing(ms: Int) {
        val (level, chip) = when {
            ms < 0 -> 0 to Chip("ออฟไลน์", Palette.DANGER, Palette.DANGER_SOFT, Palette.DANGER_LINE)
            ms < 30 -> 5 to Chip("ดี", Palette.OK, Palette.OK_SOFT, Palette.OK_LINE)
            ms < 60 -> 4 to Chip("ดี", Palette.OK, Palette.OK_SOFT, Palette.OK_LINE)
            ms < 100 -> 3 to Chip("พอใช้", Palette.WARN, Palette.WARN_SOFT, Palette.WARN_LINE)
            ms < 200 -> 2 to Chip("อ่อน", Palette.DANGER, Palette.DANGER_SOFT, Palette.DANGER_LINE)
            else -> 1 to Chip("อ่อน", Palette.DANGER, Palette.DANGER_SOFT, Palette.DANGER_LINE)
        }
        netChip.text = I18n.tr(chip.label)
        netChip.setTextColor(chip.color)
        netChip.background = kit.rounded(chip.soft, 12, chip.line, 1)
        netValue.text = if (ms < 0) "--" else "$ms"
        netUnit.visibility = if (ms < 0) View.GONE else View.VISIBLE
        netBar.setLevel(level, Palette.PRIMARY)
    }

    private companion object {
        const val PING_HOST = "8.8.8.8"
    }

    // ------------------------------------------------------------------ custom views

    /** A row of [count] rounded dashes; the first ones are lit according to the level. */
    private inner class SegmentBar(ctx: Context, private val count: Int, private val radius: Float) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val slant = Path()
        private var lit = 0
        private var color = Palette.PRIMARY

        fun set(fraction: Float, c: Int) {
            lit = Math.round(fraction.coerceIn(0f, 1f) * count)
            color = c
            invalidate()
        }

        fun setLevel(level: Int, c: Int) {
            lit = level.coerceIn(0, count)
            color = c
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val gap = kit.dpf(4)
            val w = (width - gap * (count - 1)) / count
            for (i in 0 until count) {
                val l = i * (w + gap)
                r.set(l, 0f, l + w, height.toFloat())
                paint.color = if (i < lit) color else Palette.STROKE
                if (kit.rm) {
                    val sk = height * 0.6f
                    slant.reset()
                    slant.moveTo(l + sk, 0f); slant.lineTo(l + w, 0f)
                    slant.lineTo(l + w - sk, height.toFloat()); slant.lineTo(l, height.toFloat()); slant.close()
                    c.drawPath(slant, paint)
                } else {
                    c.drawRoundRect(r, radius, radius, paint)
                }
            }
        }
    }
}
