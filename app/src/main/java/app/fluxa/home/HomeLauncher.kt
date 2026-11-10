package app.fluxa

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Home tab: a small launcher (pin apps, tap to open, long-press to remove) plus a live RAM meter
 * with a "clean" button that asks Android to stop the background processes of the installed apps,
 * and game-mode buttons (do not disturb, brightness lock).
 */
class HomeLauncher(private val act: Activity, private val kit: Kit) {
    private val prefs = act.getSharedPreferences("fluxa", Context.MODE_PRIVATE)
    private val pm = act.packageManager
    private val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val ui = Handler(Looper.getMainLooper())

    // ---- RAM
    private lateinit var ramPercent: TextView
    private lateinit var ramDetail: TextView
    private lateinit var ramUsedBar: View
    private lateinit var ramFreeBar: View
    private lateinit var cleanBtn: TextView
    private var cleaning = false
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refreshRam()
            ui.postDelayed(this, 2000)
        }
    }

    // ---- apps
    private lateinit var appsBox: LinearLayout

    fun start() {
        running = true
        ui.removeCallbacks(tick)
        tick.run()
        if (::appsBox.isInitialized) refreshApps()
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
    }

    // ------------------------------------------------------------------ RAM card

    private fun memInfo() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    private fun fmt(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }
    private fun refreshRam() {
        if (!::ramPercent.isInitialized) return
        val mi = memInfo()
        val used = (mi.totalMem - mi.availMem).coerceAtLeast(0)
        val pct = if (mi.totalMem > 0) (used * 100 / mi.totalMem).toInt().coerceIn(0, 100) else 0
        val color = when {
            pct >= 90 -> Palette.DANGER
            pct >= 75 -> Palette.WARN
            else -> Palette.OK
        }
        ramPercent.text = "$pct%"
        ramPercent.setTextColor(color)
        ramDetail.text = "ใช้ ${fmt(used)} / ${fmt(mi.totalMem)}\nว่าง ${fmt(mi.availMem)}"
        ramUsedBar.background = kit.rounded(color, 6)
        (ramUsedBar.layoutParams as LinearLayout.LayoutParams).weight = pct.coerceAtLeast(1).toFloat()
        (ramFreeBar.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).coerceAtLeast(0).toFloat().let { if (it == 0f) 0.001f else it }
        ramUsedBar.requestLayout()
    }

    private fun launchable(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != act.packageName && it != home }
    }

    /** Used by the RAM tile of the home monitor. */
    fun cleanRam(done: () -> Unit = {}) = clean(done)

    private fun clean(done: () -> Unit = {}) {
        if (cleaning) { done(); return }
        cleaning = true
        if (::cleanBtn.isInitialized) cleanBtn.text = "กำลังทำความสะอาด…"
        tools.clean { freed ->
            cleaning = false
            if (::cleanBtn.isInitialized) cleanBtn.text = "ทำความสะอาด RAM"
            refreshRam()
            done()
            I18n.toastMake(act, tools.cleanMessage(freed), Toast.LENGTH_LONG).show()
        }
    }

    private val tools = GameTools(act)

    // ------------------------------------------------------------------ apps card

    private fun saved(): MutableList<String> =
        (prefs.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableList()

    private fun save(list: List<String>) = prefs.edit().putString(KEY, list.joinToString("\n")).apply()

    private fun iconOf(pkg: String): Drawable? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
    private fun labelOf(pkg: String): String? =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()

    fun buildAppsCard(): View {
        val card = kit.card("แอพของฉัน", Palette.PRIMARY)
        card.addView(kit.caption("แตะเพื่อเปิดแอพ · กดค้างเพื่อนำออกจากรายการ"))
        appsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        card.addView(appsBox, LinearLayout.LayoutParams(-1, -2))
        refreshApps()
        return card
    }

    private fun cell(icon: View, label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null): View =
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(2), kit.dp(8), kit.dp(2), kit.dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
            addView(icon, LinearLayout.LayoutParams(kit.dp(54), kit.dp(54)))
            addView(
                kit.text(label, 11.5f, Palette.TEXT).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(6) },
            )
        }

    /** Game look: every app icon sits on its own chamfered plate, like a game card. */
    private fun framed(icon: View): View =
        if (!kit.rm) icon else FrameLayout(act).apply {
            background = kit.glassBtn(12)
            setPadding(kit.dp(7), kit.dp(7), kit.dp(7), kit.dp(7))
            addView(icon, FrameLayout.LayoutParams(-1, -1))
        }

    private fun refreshApps() {
        appsBox.removeAllViews()
        val pkgs = saved().filter { labelOf(it) != null }
        if (pkgs.size != saved().size) save(pkgs) // drop uninstalled apps
        val addTile = {
            cell(
                FrameLayout(act).apply {
                    background = kit.lit(16)
                    addView(
                        kit.icon(R.drawable.fluxa_ic_add, 28, Palette.PRIMARY),
                        FrameLayout.LayoutParams(-1, -1).apply { setMargins(kit.dp(13), kit.dp(13), kit.dp(13), kit.dp(13)) },
                    )
                },
                "เพิ่มแอพ", { showPicker() },
            )
        }
        val cells = pkgs.map { pkg ->
            cell(
                framed(ImageView(act).apply { setImageDrawable(iconOf(pkg)); scaleType = ImageView.ScaleType.FIT_CENTER }),
                labelOf(pkg) ?: pkg,
                { open(pkg) },
                { confirmRemove(pkg) },
            )
        } + addTile()
        cells.chunked(COLUMNS).forEach { rowCells ->
            val row = LinearLayout(act)
            rowCells.forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            repeat(COLUMNS - rowCells.size) { row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f)) }
            appsBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun open(pkg: String) {
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            I18n.toastMake(act, "เปิดแอพนี้ไม่ได้", Toast.LENGTH_SHORT).show()
            return
        }
        // opened from here: no frame generation, just the panel (with the record button) over the app
        val panel = startPanel()
        runCatching { act.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                I18n.toastMake(act, "เปิดแอพนี้ไม่ได้", Toast.LENGTH_SHORT).show()
                if (panel && !CaptureService.running) {
                    act.startService(Intent(act, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                }
            }
    }

    /** Starts the panel-only session. False when it cannot / need not start (the app still opens). */
    private fun startPanel(): Boolean {
        if (CaptureService.running || CaptureService.panelRunning) return false // a panel is already up
        if (OverlayAccessibilityService.instance == null) {
            I18n.toastMake(
                act,
                I18n.f("เปิด {0} overlay ในการช่วยเหลือพิเศษ (Accessibility) ก่อน", act.getString(R.string.fluxa_app_name)),
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        val started = runCatching {
            act.startForegroundService(Intent(act, CaptureService::class.java).setAction(CaptureService.ACTION_PANEL))
        }.isSuccess
        if (started) I18n.toastMake(act, "ปัดจากขอบขวาเพื่อเปิดแผงควบคุม", Toast.LENGTH_SHORT).show()
        return started
    }

    private fun confirmRemove(pkg: String) {
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.tr(act, "นำออกจากรายการ"))
            .setMessage(labelOf(pkg) ?: pkg)
            .setNegativeButton(I18n.tr(act, "ยกเลิก"), null)
            .setPositiveButton(I18n.tr(act, "นำออก")) { _, _ ->
                save(saved().apply { remove(pkg) })
                refreshApps()
            }
            .show()
    }

    private class Entry(val pkg: String, val label: String, val icon: Drawable?)

    private fun showPicker() {
        I18n.toastMake(act, "กำลังโหลดรายชื่อแอพ…", Toast.LENGTH_SHORT).show()
        thread(name = "app-list") {
            val list = launchable()
                .mapNotNull { p -> labelOf(p)?.let { Entry(p, it, iconOf(p)) } }
                .sortedBy { it.label.lowercase() }
            ui.post { if (!act.isFinishing) buildPicker(list) }
        }
    }

    private fun buildPicker(list: List<Entry>) {
        val dialog = Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.panelCard(28)
            setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(6))
        }
        box.addView(kit.text("เลือกแอพที่จะแสดง", 17f, Palette.TEXT, bold = true))
        val chosen = saved().toMutableSet()
        val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        list.forEach { e ->
            val mark = kit.icon(R.drawable.fluxa_ic_tick, 22, Palette.PRIMARY).apply {
                visibility = if (e.pkg in chosen) View.VISIBLE else View.INVISIBLE
            }
            val row = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, kit.dp(8), 0, kit.dp(8))
                isClickable = true
                isFocusable = true
                addView(ImageView(act).apply { setImageDrawable(e.icon) }, LinearLayout.LayoutParams(kit.dp(38), kit.dp(38)))
                addView(
                    kit.text(e.label, 14f, Palette.TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(14) },
                )
                addView(mark, LinearLayout.LayoutParams(kit.dp(28), kit.dp(28)))
                setOnClickListener {
                    val order = saved()
                    if (e.pkg in chosen) { chosen.remove(e.pkg); order.remove(e.pkg); mark.visibility = View.INVISIBLE }
                    else { chosen.add(e.pkg); if (e.pkg !in order) order.add(e.pkg); mark.visibility = View.VISIBLE }
                    save(order)
                    refreshApps()
                }
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        box.addView(
            ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(rows) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = kit.dp(10) },
        )
        box.addView(
            kit.text("เสร็จ", 15f, Palette.PRIMARY, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(14), 0, kit.dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(act.dialogWidth(maxDp = 520), act.dialogHeight(if (act.isLandscapeWindow()) 0.92f else 0.78f))
        }
        dialog.show()
    }

    private companion object {
        const val KEY = "launcher_apps"
        const val COLUMNS = 4
    }
}
