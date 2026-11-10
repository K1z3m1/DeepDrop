package app.fluxa

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

/**
 * Detail page behind each card of the home monitor. One activity, one page class per card:
 * CPU (big / mid / little cores), GPU, RAM (apps you can stop / force-stop / freeze),
 * storage, and the network page with the speed test.
 */
class MonitorDetailActivity : Activity() {
    companion object {
        private const val EXTRA_KIND = "kind"
        const val CPU = "cpu"
        const val GPU = "gpu"
        const val RAM = "ram"
        const val STORAGE = "storage"
        const val NET = "net"
        const val BATTERY = "battery"

        fun open(from: Activity, kind: String) {
            from.startActivity(Intent(from, MonitorDetailActivity::class.java).putExtra(EXTRA_KIND, kind))
            AppSettings.transition(from, true)
        }
    }

    private lateinit var kit: Kit
    private lateinit var page: DetailPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = Kit(this)
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = Palette.LIGHT
            isAppearanceLightNavigationBars = Palette.LIGHT
        }

        val kind = intent.getStringExtra(EXTRA_KIND) ?: CPU
        page = when (kind) {
            GPU -> GpuPage(this, kit)
            RAM -> RamPage(this, kit)
            STORAGE -> StoragePage(this, kit)
            NET -> NetworkPage(this, kit)
            BATTERY -> BatteryPage(this, kit)
            else -> CpuPage(this, kit)
        }
        val title = when (kind) {
            GPU -> "GPU"
            RAM -> "การใช้ RAM"
            STORAGE -> "พื้นที่เก็บข้อมูล"
            NET -> "เครือข่าย"
            BATTERY -> "แบตเตอรี่"
            else -> "CPU"
        }

        val root = FrameLayout(kit.ctx)
        root.addView(AuroraBackground(kit.ctx), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val head = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(8), kit.dp(10), kit.dp(16), kit.dp(6))
        }
        head.addView(
            kit.icon(R.drawable.fluxa_ic_chevron_left, 30, Palette.TEXT).apply {
                setPadding(kit.dp(9), kit.dp(9), kit.dp(9), kit.dp(9))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)),
        )
        head.addView(kit.text(title, 19f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(-2, -2))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(kit.ctx).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(8), kit.dp(16), kit.dp(28))
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        page.build(body)
    }

    override fun onResume() {
        super.onResume()
        page.start()
    }

    override fun onPause() {
        super.onPause()
        page.stop()
    }

    override fun finish() {
        super.finish()
        AppSettings.transition(this, false)
    }
}

// ============================================================================================ base

abstract class DetailPage(protected val act: Activity, protected val kit: Kit) {
    protected val ui = Handler(Looper.getMainLooper())
    @Volatile protected var alive = false
    /** Bumped on every start so a worker loop left over from an earlier start ends by itself. */
    @Volatile protected var gen = 0

    abstract fun build(body: LinearLayout)
    open fun onStart() {}
    open fun onStop() {}

    fun start() { gen++; alive = true; onStart() }
    fun stop() { alive = false; onStop() }

    protected fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    protected fun addCard(body: LinearLayout, title: String?, accent: Int = Palette.PRIMARY): LinearLayout {
        val c = kit.card(title, accent)
        body.addView(c, lp(-1, -2).apply { topMargin = kit.dp(12) })
        return c
    }

    /** One "label ... value" row; the value is returned so it can be updated live. */
    protected fun kv(card: LinearLayout, label: String, value: String = "--", copyable: Boolean = false): TextView {
        if (card.childCount > 0) {
            card.addView(kit.divider(), lp(-1, kit.dp(1)).apply { topMargin = kit.dp(6) })
        }
        val row = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, kit.dp(9), 0, kit.dp(3))
        }
        row.addView(kit.text(label, 12.5f, Palette.MUTED), lp(0, -2, 0.9f))
        val v = kit.text(value, 12.5f, Palette.TEXT, bold = true).apply {
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
        }
        row.addView(v, lp(0, -2, 1.3f))
        if (copyable) {
            row.isClickable = true
            row.setOnClickListener { copy(v.text.toString()) }
        }
        card.addView(row, lp(-1, -2))
        return v
    }

    protected fun copy(text: String) {
        if (text == "--" || text.isBlank()) return
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Fluxa", text))
        I18n.toastMake(act, "คัดลอกแล้ว", Toast.LENGTH_SHORT).show()
    }

    protected fun toast(msg: String) = I18n.toastMake(act, msg, Toast.LENGTH_SHORT).show()

    protected fun note(body: LinearLayout, text: String) {
        body.addView(
            kit.text(text, 11.5f, Palette.DIM).apply { setLineSpacing(0f, 1.15f) },
            lp(-2, -2).apply { topMargin = kit.dp(12); marginStart = kit.dp(4); marginEnd = kit.dp(4) },
        )
    }

    protected fun button(text: String, hot: Boolean = false, onClick: () -> Unit): TextView =
        kit.text(text, 14f, if (hot) kit.onHot else Palette.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(8), kit.dp(13), kit.dp(8), kit.dp(13))
            background = if (hot) kit.hot(16) else kit.glass(16)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    protected fun fmtGhz(khz: Long) = String.format("%.2f GHz", khz / 1_000_000.0)

    protected fun fmtBytes(b: Long): String {
        val mb = b / 1048576.0
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    protected fun levelColor(pct: Int) = if (pct >= 90) Palette.DANGER else Palette.PRIMARY

    /** Thin progress bar. */
    protected inner class Bar(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var f = 0f
        private var c = Palette.PRIMARY
        fun set(fraction: Float, color: Int) { f = fraction.coerceIn(0f, 1f); c = color; invalidate() }
        override fun onDraw(cv: Canvas) {
            val rad = height / 2f
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            p.color = Palette.STROKE
            cv.drawRoundRect(r, rad, rad, p)
            if (f > 0f) {
                r.set(0f, 0f, maxOf(width * f, height.toFloat()), height.toFloat())
                p.color = c
                cv.drawRoundRect(r, rad, rad, p)
            }
        }
    }
}

// ============================================================================================ CPU

private class CpuPage(act: Activity, kit: Kit) : DetailPage(act, kit) {
    private lateinit var body: LinearLayout
    private lateinit var dial: ClockDial
    private lateinit var dialFoot: TextView
    private lateinit var tempV: TextView
    private lateinit var govV: TextView
    private var coreBars = HashMap<Int, Pair<TextView, Bar>>()
    private var built = false

    override fun build(body: LinearLayout) {
        this.body = body
        val soc = addCard(body, "ชิปประมวลผล")
        kv(soc, "ชิป", SocInfo.socName())
        kv(soc, "จำนวนคอร์", Runtime.getRuntime().availableProcessors().toString())
        kv(soc, "สถาปัตยกรรม", Build.SUPPORTED_ABIS.firstOrNull() ?: "--")
        govV = kv(soc, "โหมดควบคุมความเร็ว (Governor)")
        tempV = kv(soc, "อุณหภูมิ")

        val live = addCard(body, "ความเร็วตอนนี้")
        dial = ClockDial(kit).apply { prefSizePx = kit.dp(150) }
        live.addView(dial, lp(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = kit.dp(10) })
        dialFoot = kit.text("--", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }
        live.addView(dialFoot, lp(-1, -2).apply { topMargin = kit.dp(8) })
        note(body, "ค่าที่แสดงคือความเร็วของคอร์ที่เร็วที่สุดในขณะนั้น · คอร์ที่ว่างจะถูกลดความเร็วลงหรือปิดชั่วคราวเพื่อประหยัดแบต")
    }

    override fun onStart() {
        val g = gen
        thread(name = "cpu-detail") {
            while (alive && g == gen) {
                val cores = SocInfo.cores()
                val t = SocInfo.cpuTempC() ?: SocInfo.batteryTempC(act)
                val tIsBattery = SocInfo.cpuTempC() == null
                ui.post { if (alive && g == gen) render(cores, t, tIsBattery) }
                Thread.sleep(2000)
            }
        }
    }

    private fun render(cores: List<SocInfo.Core>, temp: Float?, battery: Boolean) {
        val cur = cores.filter { it.online }.maxOfOrNull { it.curKhz } ?: 0L
        val max = cores.maxOfOrNull { it.maxKhz } ?: 0L
        if (cur > 0 && max > 0) {
            val pct = (cur * 100 / max).toInt().coerceIn(0, 100)
            dial.set(pct / 100f, levelColor(pct), String.format("%.2f", cur / 1_000_000.0), "GHz")
            dialFoot.text = I18n.tr("สูงสุด") + " ${fmtGhz(max)} · $pct%"
        } else {
            dial.set(0f, Palette.PRIMARY, "--", "GHz")
            dialFoot.text = I18n.tr("เครื่องนี้ไม่ให้แอพอ่านความเร็ว CPU")
        }
        govV.text = cores.firstNotNullOfOrNull { it.governor } ?: "--"
        tempV.text = if (temp == null) "--"
        else String.format("%.1f °C", temp) + if (battery) " (" + I18n.tr("แบตเตอรี่") + ")" else ""

        if (!built) {
            built = true
            buildClusters(cores)
        }
        for (c in cores) {
            val (txt, bar) = coreBars[c.index] ?: continue
            if (!c.online) {
                txt.text = I18n.tr("พักอยู่")
                txt.setTextColor(Palette.DIM)
                bar.set(0f, Palette.PRIMARY)
            } else if (c.curKhz <= 0) {
                txt.text = "--"
                txt.setTextColor(Palette.DIM)
                bar.set(0f, Palette.PRIMARY)
            } else {
                val f = if (c.maxKhz > 0) c.curKhz.toFloat() / c.maxKhz else 0f
                txt.text = fmtGhz(c.curKhz)
                txt.setTextColor(Palette.TEXT)
                bar.set(f, levelColor((f * 100).toInt()))
            }
        }
    }

    private fun buildClusters(cores: List<SocInfo.Core>) {
        val clusters = SocInfo.clusters(cores)
        for (cl in clusters) {
            val card = addCard(body, null)
            val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            kit.addMark(head, 16, 9)
            head.addView(kit.text(cl.label, 15.5f, Palette.TEXT, bold = true), lp(0, -2, 1f))
            head.addView(kit.text("×${cl.cores.size}", 14f, Palette.PRIMARY, bold = true))
            card.addView(head, lp(-1, -2))
            card.addView(
                kit.text(
                    (if (cl.name != "--") cl.name + " · " else "") +
                        if (cl.maxKhz > 0) "${fmtGhz(cl.minKhz)} – ${fmtGhz(cl.maxKhz)}" else I18n.tr("ไม่ทราบความเร็ว"),
                    12f, Palette.MUTED,
                ),
                lp(-2, -2).apply { topMargin = kit.dp(6) },
            )
            for (c in cl.cores) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                val top = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(kit.text("คอร์ ${c.index}", 12.5f, Palette.MUTED), lp(0, -2, 1f))
                val v = kit.text("--", 12.5f, Palette.TEXT, bold = true).apply { fontFeatureSettings = "tnum" }
                top.addView(v)
                row.addView(top, lp(-1, -2))
                val bar = Bar(act)
                row.addView(bar, lp(-1, kit.dp(5)).apply { topMargin = kit.dp(6) })
                card.addView(row, lp(-1, -2).apply { topMargin = kit.dp(12) })
                coreBars[c.index] = v to bar
            }
        }
    }
}

// ============================================================================================ GPU

private class GpuPage(act: Activity, kit: Kit) : DetailPage(act, kit) {
    private lateinit var dial: ClockDial
    private lateinit var dialFoot: TextView
    private lateinit var nameV: TextView
    private lateinit var vendorV: TextView
    private lateinit var glV: TextView
    private lateinit var busyV: TextView
    private lateinit var tempV: TextView
    private lateinit var stepsV: TextView
    private var identityLoaded = false

    override fun build(body: LinearLayout) {
        val live = addCard(body, "ความเร็วตอนนี้")
        dial = ClockDial(kit).apply { prefSizePx = kit.dp(150) }
        live.addView(dial, lp(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = kit.dp(10) })
        dialFoot = kit.text("--", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }
        live.addView(dialFoot, lp(-1, -2).apply { topMargin = kit.dp(8) })

        val info = addCard(body, "ข้อมูล GPU")
        nameV = kv(info, "รุ่น")
        vendorV = kv(info, "ผู้ผลิต")
        glV = kv(info, "OpenGL ES")
        kv(info, "Vulkan", SocInfo.vulkanVersion(act) ?: I18n.tr("ไม่รองรับ"))
        busyV = kv(info, "โหลด GPU")
        tempV = kv(info, "อุณหภูมิ")
        stepsV = kv(info, "ขั้นความเร็ว")
        note(body, "บางเครื่องไม่เปิดให้แอพอ่านค่า GPU (ความเร็ว โหลด อุณหภูมิ) จะแสดงเป็น \"--\" ซึ่งเป็นข้อจำกัดของระบบ")
    }

    override fun onStart() {
        val g = gen
        thread(name = "gpu-detail") {
            if (!identityLoaded) {
                val id = SocInfo.gpuIdentity()
                val steps = ClockReader.gpuSteps()
                identityLoaded = true
                ui.post {
                    if (id != null) { nameV.text = id.renderer; vendorV.text = id.vendor; glV.text = id.glVersion.removePrefix("OpenGL ES ") }
                    stepsV.text = if (steps.isEmpty()) "--" else steps.joinToString(" · ") { String.format("%.0f", it) } + " MHz"
                }
            }
            while (alive && g == gen) {
                val clk = ClockReader.gpuMhz()
                val busy = ClockReader.gpuBusy()
                val t = SocInfo.gpuTempC()
                ui.post { if (alive && g == gen) render(clk, busy, t) }
                Thread.sleep(2000)
            }
        }
    }

    private fun render(g: Pair<Double, Double>?, busy: Int?, temp: Float?) {
        if (g == null) {
            dial.set(0f, Palette.PRIMARY, "--", "MHz")
            dialFoot.text = I18n.tr("เครื่องนี้ไม่ให้แอพอ่านความเร็ว GPU")
        } else {
            val pct = (g.first * 100 / g.second).toInt().coerceIn(0, 100)
            dial.set(pct / 100f, levelColor(pct), String.format("%.0f", g.first), "MHz")
            dialFoot.text = I18n.tr("สูงสุด") + String.format(" %.0f MHz · %d%%", g.second, pct)
        }
        busyV.text = if (busy == null) "--" else "$busy%"
        tempV.text = if (temp == null) "--" else String.format("%.1f °C", temp)
    }
}

// ============================================================================================ battery

private class BatteryPage(act: Activity, kit: Kit) : DetailPage(act, kit) {
    private lateinit var levelV: TextView
    private lateinit var levelBar: Bar
    private lateinit var stateV: TextView
    private lateinit var powerV: TextView
    private lateinit var currentV: TextView
    private lateinit var voltV: TextView
    private lateinit var tempV: TextView
    private lateinit var plugV: TextView
    private lateinit var healthV: TextView
    private lateinit var sohV: TextView
    private lateinit var cyclesV: TextView
    private lateinit var chargeV: TextView
    private lateinit var techV: TextView

    override fun build(body: LinearLayout) {
        val top = addCard(body, "ระดับแบตเตอรี่")
        levelV = kv(top, "ระดับ")
        levelBar = Bar(act)
        top.addView(levelBar, lp(-1, kit.dp(8)).apply { topMargin = kit.dp(12) })
        stateV = kv(top, "สถานะการชาร์จ")
        plugV = kv(top, "แหล่งพลังงาน")

        val power = addCard(body, "กำลังไฟตอนนี้")
        powerV = kv(power, "กำลังไฟ (Watt)")
        currentV = kv(power, "กระแส")
        voltV = kv(power, "แรงดัน")

        val info = addCard(body, "รายละเอียด")
        tempV = kv(info, "อุณหภูมิแบตเตอรี่")
        healthV = kv(info, "สุขภาพแบตเตอรี่")
        sohV = kv(info, "ความจุที่เหลือ (SoH)")
        cyclesV = kv(info, "จำนวนรอบชาร์จ")
        chargeV = kv(info, "ประจุคงเหลือ")
        techV = kv(info, "เทคโนโลยี")

        note(body, "ค่ากระแสและวัตต์เป็นค่าประมาณจากที่ระบบรายงาน อาจต่างจากเครื่องวัดจริงเล็กน้อย และบางเครื่องไม่เปิดให้แอพอ่านบางค่า (แสดงเป็น \"--\") · อัปเดตทุก 2 วินาที")
    }

    override fun onStart() {
        val g = gen
        thread(name = "battery-detail") {
            while (alive && g == gen) {
                val b = SocInfo.battery(act)
                ui.post { if (alive && g == gen) render(b) }
                Thread.sleep(4000)
            }
        }
    }

    private fun healthName(h: Int) = when (h) {
        android.os.BatteryManager.BATTERY_HEALTH_GOOD -> I18n.tr("ดี")
        android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> I18n.tr("ร้อนเกินไป")
        android.os.BatteryManager.BATTERY_HEALTH_DEAD -> I18n.tr("เสื่อมสภาพ")
        android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> I18n.tr("แรงดันสูงเกินไป")
        android.os.BatteryManager.BATTERY_HEALTH_COLD -> I18n.tr("เย็นเกินไป")
        android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> I18n.tr("ผิดปกติ")
        else -> "--"
    }

    private fun plugName(p: Int): String {
        return when {
            p and android.os.BatteryManager.BATTERY_PLUGGED_AC != 0 -> I18n.tr("ที่ชาร์จ (AC)")
            p and android.os.BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            p and android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> I18n.tr("ชาร์จไร้สาย")
            else -> I18n.tr("แบตเตอรี่")
        }
    }

    private fun render(b: SocInfo.Battery?) {
        if (b == null) {
            levelV.text = "--"; stateV.text = I18n.tr("อ่านค่าไม่ได้")
            return
        }
        levelV.text = "${b.percent}%"
        levelBar.set(b.percent / 100f, if (b.percent <= 15 && !b.charging) Palette.DANGER else Palette.PRIMARY)
        stateV.text = when {
            b.full -> I18n.tr("เต็มแล้ว")
            b.charging -> I18n.tr("กำลังชาร์จ")
            else -> I18n.tr("กำลังใช้แบตเตอรี่")
        }
        plugV.text = plugName(b.plugged)

        val dir = if (b.charging) "+" else if (b.full) "" else "−"
        powerV.text = if (b.watts == null) "--" else String.format("%s%.2f W", dir, b.watts)
        currentV.text = if (b.currentMa == null) "--" else String.format("%s%d mA", dir, b.currentMa)
        voltV.text = if (b.voltV == null) "--" else String.format("%.2f V", b.voltV)

        tempV.text = if (b.tempC == null) "--" else String.format("%.1f °C", b.tempC)
        healthV.text = healthName(b.health)
        sohV.text = if (b.soh == null) "--" else "${b.soh}%"
        cyclesV.text = if (b.cycles == null) "--" else "${b.cycles}"
        chargeV.text = if (b.chargeMah == null) "--" else "${b.chargeMah} mAh"
        techV.text = b.technology ?: "--"
    }
}

// ============================================================================================ storage

private class StoragePage(act: Activity, kit: Kit) : DetailPage(act, kit) {
    override fun build(body: LinearLayout) {
        val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val c = addCard(body, "พื้นที่ภายในเครื่อง")
        if (fs != null) {
            val total = fs.totalBytes
            val free = fs.availableBytes
            val used = (total - free).coerceAtLeast(0)
            val pct = if (total > 0) (used * 100 / total).toInt() else 0
            val bar = Bar(act).apply { set(pct / 100f, levelColor(pct)) }
            c.addView(bar, lp(-1, kit.dp(8)).apply { topMargin = kit.dp(12) })
            kv(c, "ใช้ไปแล้ว", "${fmtBytes(used)} ($pct%)")
            kv(c, "ว่าง", fmtBytes(free))
            kv(c, "ความจุที่ระบบเห็น", fmtBytes(total))
            kv(c, "ความจุโดยประมาณ", "${marketed(total)} GB")
        } else {
            kv(c, "สถานะ", I18n.tr("อ่านค่าไม่ได้"))
        }

        // SD card / other volumes the app can see
        val extra = runCatching { act.getExternalFilesDirs(null).drop(1).filterNotNull() }.getOrDefault(emptyList())
        extra.forEachIndexed { i, dir ->
            runCatching {
                val s = StatFs(dir.path)
                val card = addCard(body, I18n.tr("การ์ดหน่วยความจำ") + " ${i + 1}")
                val used = (s.totalBytes - s.availableBytes).coerceAtLeast(0)
                kv(card, "ใช้ไปแล้ว", fmtBytes(used))
                kv(card, "ว่าง", fmtBytes(s.availableBytes))
                kv(card, "ความจุ", fmtBytes(s.totalBytes))
            }
        }

        body.addView(
            button("เปิดหน้าที่เก็บข้อมูลของระบบ") {
                runCatching { act.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                    .onFailure { runCatching { act.startActivity(Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)) } }
            },
            lp(-1, -2).apply { topMargin = kit.dp(14) },
        )
        note(body, "การดูว่าแอพหรือไฟล์ไหนกินพื้นที่เท่าไรต้องใช้หน้าที่เก็บข้อมูลของระบบ เพราะแอพทั่วไปอ่านขนาดของแอพอื่นไม่ได้")
    }

    /** The phone reports a bit less than the marketed size (e.g. 108 GB for a 128 GB phone). */
    private fun marketed(bytes: Long): Int {
        val gb = bytes / 1_000_000_000.0
        var s = 16
        while (s < gb) s *= 2
        return s
    }
}

// ============================================================================================ RAM

private class RamPage(act: Activity, kit: Kit) : DetailPage(act, kit) {
    private val tools = GameTools(act)
    private val ctl = AppControl(act)
    private lateinit var usedV: TextView
    private lateinit var availV: TextView
    private lateinit var cachedV: TextView
    private lateinit var swapV: TextView
    private lateinit var lowV: TextView
    private lateinit var bar: Bar
    private lateinit var pctText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var frozenBox: LinearLayout
    private var loading = false
    private var cleaning = false
    private var expanded: View? = null

    override fun build(body: LinearLayout) {
        val c = addCard(body, "หน่วยความจำ")
        pctText = kit.display("--", 30f, Palette.PRIMARY).apply { fontFeatureSettings = "tnum" }
        c.addView(pctText, lp(-2, -2).apply { topMargin = kit.dp(10) })
        bar = Bar(act)
        c.addView(bar, lp(-1, kit.dp(8)).apply { topMargin = kit.dp(10) })
        usedV = kv(c, "ใช้ไปแล้ว")
        availV = kv(c, "ว่าง")
        cachedV = kv(c, "แคช")
        swapV = kv(c, "หน่วยความจำสำรอง (zram/swap)")
        lowV = kv(c, "สถานะ")

        val clean = button("ทำความสะอาด RAM", hot = true) {}
        clean.setOnClickListener {
            if (cleaning) return@setOnClickListener
            cleaning = true
            clean.text = I18n.tr("กำลังทำความสะอาด…")
            tools.clean { freed ->
                cleaning = false
                clean.text = I18n.tr("ทำความสะอาด RAM")
                toast(tools.cleanMessage(freed))
                refreshRam(); reload()
            }
        }
        body.addView(clean, lp(-1, -2).apply { topMargin = kit.dp(12) })

        body.addView(
            kit.text("แอพที่เปิดอยู่ / ใช้งานล่าสุด", 13f, Palette.MUTED, bold = true),
            lp(-2, -2).apply { topMargin = kit.dp(22); marginStart = kit.dp(4) },
        )
        listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listBox, lp(-1, -2).apply { topMargin = kit.dp(10) })

        frozenBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(frozenBox, lp(-1, -2))

        note(
            body,
            "แตะแอพเพื่อเลือกคำสั่ง · หยุด = ปิดงานเบื้องหลังของแอพ · บังคับปิด = หยุดแอพทั้งหมด · แช่แข็ง = ปิดใช้งานแอพจนกว่าจะปลดแช่แข็ง\n" +
                "Android ไม่ให้แอพทั่วไปดูว่าแอพอื่นกิน RAM เท่าไร รายการนี้จึงมาจากแอพที่เพิ่งใช้งาน (แอพที่อยู่หน้าจอจะมีป้าย \"กำลังใช้งาน\")",
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            refreshRam()
            ui.postDelayed(this, 4000)
        }
    }

    override fun onStart() {
        ui.post(tick)
        reload()
        renderFrozen()
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
    }

    private fun refreshRam() {
        if (!::usedV.isInitialized) return
        thread(name = "ram-detail") {
            val m = tools.ram()
            val mi = SocInfo.meminfo()
            val low = runCatching {
                android.app.ActivityManager.MemoryInfo().also {
                    (act.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
                }.lowMemory
            }.getOrDefault(false)
            ui.post {
                if (!alive) return@post
                pctText.text = "${m.percent}%"
                pctText.setTextColor(levelColor(m.percent))
                bar.set(m.percent / 100f, levelColor(m.percent))
                usedV.text = "${tools.fmt(m.used)} / ${tools.fmt(m.total)}"
                availV.text = tools.fmt(m.avail)
                cachedV.text = if (mi == null) "--" else tools.fmt((mi.cachedKb + mi.buffersKb) * 1024)
                swapV.text = if (mi == null || mi.swapTotalKb <= 0) I18n.tr("ไม่มี")
                else "${tools.fmt((mi.swapTotalKb - mi.swapFreeKb) * 1024)} / ${tools.fmt(mi.swapTotalKb * 1024)}"
                lowV.text = if (low) I18n.tr("RAM เหลือน้อย") else I18n.tr("ปกติ")
                lowV.setTextColor(if (low) Palette.DANGER else Palette.OK)
            }
        }
    }

    // ---- app list

    private fun showMessage(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        listBox.removeAllViews()
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.glass(22)
            setPadding(kit.dp(18), kit.dp(18), kit.dp(18), kit.dp(18))
        }
        card.addView(kit.text(msg, 13.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) })
        if (action != null && onAction != null) {
            card.addView(button(action, hot = true) { onAction() }, lp(-1, -2).apply { topMargin = kit.dp(14) })
        }
        listBox.addView(card, lp(-1, -2))
    }

    private fun reload() {
        if (!::listBox.isInitialized || loading) return
        if (!ctl.hasUsageAccess()) {
            showMessage("ต้องเปิดสิทธิ์ \"การเข้าถึงข้อมูลการใช้งาน\" ให้แอพนี้ก่อน จึงจะเห็นรายชื่อแอพที่เปิดอยู่", "ให้สิทธิ์") { ctl.openUsageAccess() }
            return
        }
        loading = true
        if (listBox.childCount == 0) showMessage("กำลังโหลด…")
        thread(name = "ram-apps") {
            val items = runCatching { ctl.recent() }.getOrDefault(emptyList())
            ui.post {
                loading = false
                if (alive) renderList(items)
            }
        }
    }

    private fun ago(t: Long): String {
        val min = ((System.currentTimeMillis() - t) / 60_000L).toInt()
        return when {
            min < 1 -> I18n.tr("ใช้เมื่อสักครู่")
            min < 60 -> I18n.f("ใช้เมื่อ {0} นาทีที่แล้ว", min)
            else -> I18n.f("ใช้เมื่อ {0} ชม. ที่แล้ว", min / 60)
        }
    }

    private fun renderList(items: List<AppControl.AppItem>) {
        listBox.removeAllViews()
        expanded = null
        if (items.isEmpty()) {
            showMessage("ไม่มีแอพที่เพิ่งใช้งาน")
            return
        }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.glass(22)
        }
        items.forEachIndexed { i, item ->
            if (i > 0) card.addView(kit.divider(), lp(-1, kit.dp(1)).apply { marginStart = kit.dp(70); marginEnd = kit.dp(16) })
            card.addView(appRow(item), lp(-1, -2))
        }
        listBox.addView(card, lp(-1, -2))
    }

    private fun appRow(item: AppControl.AppItem): View {
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            isClickable = true
        }
        row.addView(
            ImageView(act).apply { setImageDrawable(item.icon); scaleType = ImageView.ScaleType.FIT_CENTER },
            lp(kit.dp(40), kit.dp(40)).apply { marginEnd = kit.dp(14) },
        )
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.text(item.label, 14.5f, Palette.TEXT, bold = true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(
            kit.text(
                if (item.foreground) "กำลังใช้งาน" else ago(item.last), 11.5f,
                if (item.foreground) Palette.OK else Palette.MUTED,
            ),
            lp(-2, -2).apply { topMargin = kit.dp(2) },
        )
        row.addView(col, lp(0, -2, 1f))
        wrap.addView(row, lp(-1, -2))

        val actions = LinearLayout(act).apply {
            visibility = View.GONE
            setPadding(kit.dp(16), 0, kit.dp(16), kit.dp(14))
        }
        actions.addView(small("หยุด") {
            ctl.stop(item.pkg)
            toast(I18n.f("ปิดงานเบื้องหลังของ {0} แล้ว", item.label))
            ui.postDelayed({ refreshRam() }, 800)
        }, lp(0, -2, 1f))
        actions.addView(small("บังคับปิด") {
            ctl.forceStop(item.pkg)
            toast("เปิดหน้าข้อมูลแอพให้ · กด \"บังคับหยุด\" ที่นั่น")
            ui.postDelayed({ refreshRam() }, 800)
        }, lp(0, -2, 1f).apply { marginStart = kit.dp(8) })
        actions.addView(small("แช่แข็ง", hot = true) { freeze(item) }, lp(0, -2, 1f).apply { marginStart = kit.dp(8) })
        wrap.addView(actions, lp(-1, -2))

        row.setOnClickListener {
            val open = actions.visibility == View.VISIBLE
            expanded?.visibility = View.GONE
            actions.visibility = if (open) View.GONE else View.VISIBLE
            expanded = if (open) null else actions
        }
        row.setOnLongClickListener { ctl.openAppInfo(item.pkg); true }
        return wrap
    }

    private fun small(text: String, hot: Boolean = false, onClick: () -> Unit) =
        kit.text(text, 12.5f, if (hot) kit.onHot else Palette.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(6), kit.dp(11), kit.dp(6), kit.dp(11))
            background = if (hot) kit.hot(14) else kit.glassBtn(14)
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ---- freeze

    private fun freeze(item: AppControl.AppItem) {
        adbDialog(
            I18n.f("แช่แข็ง {0}", item.label),
            ctl.adbFreeze(item.pkg),
        ) { ctl.markFrozen(item.pkg, item.label, true); renderFrozen(); reload() }
    }

    private fun unfreeze(pkg: String, label: String) {
        adbDialog(I18n.f("ปลดแช่แข็ง {0}", label), ctl.adbUnfreeze(pkg)) {
            ctl.markFrozen(pkg, "", false); renderFrozen()
        }
    }

    /** Android refuses to disable another app from an app, so hand over the one-line adb command. */
    private fun adbDialog(title: String, cmd: String, onDone: () -> Unit) {
        val msg = I18n.tr("Android ไม่ให้แอพทั่วไปปิดใช้งานแอพอื่นเอง\n\nรันคำสั่งนี้ผ่าน adb (ต่อสาย USB หรือ Wireless debugging):") +
            "\n\n$cmd\n\n" + I18n.tr("รันเสร็จแล้วกด \"ทำแล้ว\" เพื่อให้ Fluxa จำสถานะไว้")
        AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(I18n.tr("ทำแล้ว")) { _, _ -> onDone() }
            .setNeutralButton(I18n.tr("คัดลอกคำสั่ง")) { _, _ -> copy(cmd) }
            .setNegativeButton(I18n.tr("ยกเลิก"), null)
            .show()
    }

    private fun renderFrozen() {
        if (!::frozenBox.isInitialized) return
        frozenBox.removeAllViews()
        val list = ctl.frozen()
        if (list.isEmpty()) return
        frozenBox.addView(
            kit.text("แอพที่แช่แข็งไว้", 13f, Palette.MUTED, bold = true),
            lp(-2, -2).apply { topMargin = kit.dp(22); marginStart = kit.dp(4) },
        )
        val card = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; background = kit.glass(22) }
        list.forEachIndexed { i, (pkg, label) ->
            if (i > 0) card.addView(kit.divider(), lp(-1, kit.dp(1)).apply { marginStart = kit.dp(16); marginEnd = kit.dp(16) })
            val row = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(16), kit.dp(10), kit.dp(12), kit.dp(10))
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(kit.text(label, 14.5f, Palette.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(kit.text(pkg, 11f, Palette.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(-2, -2).apply { topMargin = kit.dp(2) })
            row.addView(col, lp(0, -2, 1f))
            row.addView(small("ปลดแช่แข็ง") { unfreeze(pkg, label) }, lp(-2, -2).apply { marginStart = kit.dp(8) })
            card.addView(row, lp(-1, -2))
        }
        frozenBox.addView(card, lp(-1, -2).apply { topMargin = kit.dp(10) })
    }
}

// ============================================================================================ network

private class NetworkPage(act: Activity, kit: Kit) : DetailPage(act, kit) {
    private val prefs = act.getSharedPreferences("fluxa", Context.MODE_PRIVATE)
    private lateinit var dial: ClockDial
    private lateinit var phase: TextView
    private lateinit var progress: Bar
    private lateinit var startBtn: TextView
    private lateinit var downV: TextView
    private lateinit var upV: TextView
    private lateinit var pingV: TextView
    private lateinit var jitterV: TextView
    private lateinit var usageNote: TextView
    private lateinit var linkBox: LinearLayout
    private lateinit var ipBox: LinearLayout
    private var handle: NetTools.Handle? = null
    private var running = false
    private var scale = 10.0
    private var cellular = false

    override fun build(body: LinearLayout) {
        val t = addCard(body, "ทดสอบความเร็วอินเทอร์เน็ต", Palette.PRIMARY)
        dial = ClockDial(kit).apply { prefSizePx = kit.dp(170) }
        t.addView(dial, lp(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = kit.dp(12) })
        phase = kit.text("พร้อมทดสอบ", 13f, Palette.MUTED, bold = true).apply { gravity = Gravity.CENTER }
        t.addView(phase, lp(-1, -2).apply { topMargin = kit.dp(10) })
        progress = Bar(act)
        t.addView(progress, lp(-1, kit.dp(4)).apply { topMargin = kit.dp(10) })

        val grid = LinearLayout(act)
        downV = stat(grid, "ดาวน์โหลด", Palette.PRIMARY)
        upV = stat(grid, "อัปโหลด", Palette.SECONDARY)
        t.addView(grid, lp(-1, -2).apply { topMargin = kit.dp(14) })
        val grid2 = LinearLayout(act)
        pingV = stat(grid2, "Ping", Palette.TEXT)
        jitterV = stat(grid2, "Jitter", Palette.TEXT)
        t.addView(grid2, lp(-1, -2).apply { topMargin = kit.dp(8) })

        startBtn = button("เริ่มทดสอบ", hot = true) { toggle() }
        t.addView(startBtn, lp(-1, -2).apply { topMargin = kit.dp(14) })
        usageNote = kit.text("การทดสอบใช้ข้อมูลราว 50–300 MB ขึ้นกับความเร็วของเน็ต", 11.5f, Palette.DIM).apply { gravity = Gravity.CENTER }
        t.addView(usageNote, lp(-1, -2).apply { topMargin = kit.dp(10) })

        showLast()
        dial.set(0f, Palette.PRIMARY, "--", "Mbps")

        val l = addCard(body, "การเชื่อมต่อ")
        linkBox = l
        val ip = addCard(body, "IP และรายละเอียด")
        ipBox = ip
        note(body, "แตะที่แถวเพื่อคัดลอกค่า · IP สาธารณะ/ผู้ให้บริการ ถามจาก Cloudflare และ ipinfo.io ผ่าน HTTPS ตอนเปิดหน้านี้")
    }

    private fun stat(parent: LinearLayout, label: String, color: Int): TextView {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.glassBtn(14)
            setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10))
        }
        box.addView(kit.text(label, 11.5f, Palette.MUTED))
        val v = kit.display("--", 17f, color).apply { fontFeatureSettings = "tnum" }
        box.addView(v, lp(-2, -2).apply { topMargin = kit.dp(4) })
        parent.addView(box, lp(0, -2, 1f).apply { if (parent.childCount > 0) marginStart = kit.dp(8) })
        return v
    }

    private fun showLast() {
        if (!prefs.contains("net_last_down")) return
        downV.text = fmtMbps(prefs.getFloat("net_last_down", 0f).toDouble())
        upV.text = fmtMbps(prefs.getFloat("net_last_up", 0f).toDouble())
        pingV.text = prefs.getInt("net_last_ping", -1).let { if (it < 0) "--" else "$it ms" }
        jitterV.text = prefs.getInt("net_last_jitter", -1).let { if (it < 0) "--" else "$it ms" }
        val at = prefs.getLong("net_last_time", 0L)
        if (at > 0) phase.text = I18n.tr("ผลล่าสุด") + " · " +
            android.text.format.DateFormat.format("d MMM HH:mm", at)
    }

    private fun fmtMbps(v: Double) = when {
        v <= 0 -> "--"
        v >= 100 -> String.format("%.0f Mbps", v)
        else -> String.format("%.1f Mbps", v)
    }

    override fun onStart() {
        loadLink()
        loadPublic()
    }

    override fun onStop() {
        handle?.cancel()
        if (running) finishUi(null)
    }

    // ---- test

    private fun toggle() {
        if (running) { handle?.cancel(); return }
        running = true
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = I18n.tr("หยุดทดสอบ")
        downV.text = "--"; upV.text = "--"; pingV.text = "--"; jitterV.text = "--"
        scale = 10.0
        dial.set(0f, Palette.PRIMARY, "--", "Mbps")
        handle = NetTools.runTest(object : NetTools.Listener {
            private var lastPhase = -1
            override fun onProgress(phase: Int, mbps: Double, progress: Float) {
                ui.post {
                    if (!running) return@post
                    if (phase != lastPhase) {
                        lastPhase = phase
                        scale = 10.0
                        this@NetworkPage.phase.text = I18n.tr(
                            when (phase) { 0 -> "กำลังวัด Ping…"; 1 -> "กำลังทดสอบดาวน์โหลด…"; else -> "กำลังทดสอบอัปโหลด…" },
                        )
                    }
                    this@NetworkPage.progress.set(progress, if (phase == 2) Palette.SECONDARY else Palette.PRIMARY)
                    if (phase == 0) return@post
                    while (mbps * 1.25 > scale && scale < 5000) scale = nextScale(scale)
                    val c = if (phase == 2) Palette.SECONDARY else Palette.PRIMARY
                    dial.set((mbps / scale).toFloat(), c, if (mbps >= 100) String.format("%.0f", mbps) else String.format("%.1f", mbps), "Mbps")
                    if (phase == 1) downV.text = fmtMbps(mbps) else upV.text = fmtMbps(mbps)
                }
            }

            override fun onDone(r: NetTools.Result) {
                ui.post { finishUi(r) }
            }
        })
    }

    private fun nextScale(s: Double) = when {
        s < 25 -> 25.0; s < 50 -> 50.0; s < 100 -> 100.0; s < 250 -> 250.0
        s < 500 -> 500.0; s < 1000 -> 1000.0; else -> s * 2
    }

    private fun finishUi(r: NetTools.Result?) {
        running = false
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = I18n.tr("เริ่มทดสอบ")
        progress.set(0f, Palette.PRIMARY)
        dial.set(0f, Palette.PRIMARY, "--", "Mbps")
        if (r == null) return
        if (r.cancelled) {
            phase.text = I18n.tr("ยกเลิกการทดสอบแล้ว")
            downV.text = "--"; upV.text = "--"; pingV.text = "--"; jitterV.text = "--"
            return
        }
        pingV.text = r.pingMs?.let { "$it ms" } ?: "--"
        jitterV.text = r.jitterMs?.let { "$it ms" } ?: "--"
        downV.text = r.downMbps?.let { fmtMbps(it) } ?: "--"
        upV.text = r.upMbps?.let { fmtMbps(it) } ?: "--"
        val best = r.downMbps ?: r.upMbps ?: 0.0
        if (r.downMbps == null && r.upMbps == null) {
            phase.text = I18n.tr("ทดสอบไม่สำเร็จ · ตรวจสอบการเชื่อมต่ออินเทอร์เน็ต")
            return
        }
        phase.text = I18n.tr("เสร็จแล้ว")
        usageNote.text = I18n.f("ใช้ข้อมูลไปราว {0}", fmtBytes(r.bytes))
        if (best > 0) {
            prefs.edit()
                .putFloat("net_last_down", (r.downMbps ?: 0.0).toFloat())
                .putFloat("net_last_up", (r.upMbps ?: 0.0).toFloat())
                .putInt("net_last_ping", r.pingMs ?: -1)
                .putInt("net_last_jitter", r.jitterMs ?: -1)
                .putLong("net_last_time", System.currentTimeMillis())
                .apply()
        }
    }

    // ---- info

    private fun clearRows(card: LinearLayout) {
        while (card.childCount > 1) card.removeViewAt(1)
    }

    private fun loadLink() {
        thread(name = "net-link") {
            val link = runCatching { NetTools.link(act) }.getOrNull()
            ui.post {
                if (!alive || link == null) return@post
                cellular = link.kind == "มือถือ"
                clearRows(linkBox)
                kv(linkBox, "ชนิดเครือข่าย", link.kind)
                for ((k, v) in link.rows) kv(linkBox, k, v, copyable = true)
                if (link.metered || cellular) {
                    usageNote.text = I18n.tr("กำลังใช้ข้อมูลมือถือ/เครือข่ายที่คิดตามปริมาณ · การทดสอบอาจใช้ข้อมูลหลายร้อย MB")
                    usageNote.setTextColor(Palette.WARN)
                }
            }
        }
    }

    private fun loadPublic() {
        clearRows(ipBox)
        kv(ipBox, "สถานะ", I18n.tr("กำลังโหลด…"))
        thread(name = "net-public") {
            val info = runCatching { NetTools.publicInfo() }.getOrNull()
            ui.post {
                if (!alive) return@post
                clearRows(ipBox)
                if (info == null) {
                    kv(ipBox, "สถานะ", I18n.tr("ดึงข้อมูลไม่ได้ · ตรวจสอบอินเทอร์เน็ต"))
                } else {
                    for ((k, v) in info.rows) kv(ipBox, k, v, copyable = true)
                }
                ipBox.addView(
                    button("รีเฟรช") { loadLink(); loadPublic() },
                    lp(-1, -2).apply { topMargin = kit.dp(12) },
                )
            }
        }
    }
}
