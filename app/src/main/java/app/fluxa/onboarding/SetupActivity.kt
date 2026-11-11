package app.fluxa

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Setup page. Opened right after the user accepts the terms ([EXTRA_FIRST]) and again from the
 * status card on the home tab. Holds the readiness checklist and the short how-to that used to sit
 * on the home tab.
 */
class SetupActivity : Activity() {
    private lateinit var kit: Kit
    private val first by lazy { intent?.getBooleanExtra(EXTRA_FIRST, false) == true }

    private class Row(val dot: View, val state: TextView, val ok: () -> Boolean, val required: Boolean, val good: String, val bad: String)
    private val rows = ArrayList<Row>()

    /** Tap-jacking guard: touches are dropped while another app's window is drawn over this page (it grants permissions). */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = Kit(this)
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = Palette.LIGHT
            isAppearanceLightNavigationBars = Palette.LIGHT
        }

        val root = FrameLayout(kit.ctx)
        root.addView(AuroraBackground(kit.ctx), FrameLayout.LayoutParams(-1, -1))
        val page = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val body = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(20), kit.dp(16), kit.dp(12))
        }
        body.addView(kit.text("ตั้งค่าเริ่มต้น", 24f, Palette.TEXT, bold = true, spacing = -0.01f))
        body.addView(
            kit.text("ทำตามรายการด้านล่างให้พร้อมก่อนกด เริ่ม · ข้อที่ไม่บังคับข้ามได้", 13f, Palette.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(4) },
        )
        body.addView(buildStatusCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(18) })
        body.addView(buildHelpCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(AppSettings.cardGap) })

        page.addView(
            ScrollView(kit.ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(body)
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )

        val bar = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(10))
            background = kit.rounded(Palette.a(Palette.BG, 0xF2), 0)
        }
        bar.addView(
            kit.text(if (first) "ต่อไป" else "ปิดหน้า", 16f, Palette.ON_ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = kit.accentFill(28)
                isClickable = true
                isFocusable = true
                setOnClickListener { done() }
            },
            LinearLayout.LayoutParams(-1, kit.dp(56)),
        )
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun buildStatusCard(): View {
        val card = kit.card("สิทธิ์ที่ต้องขอ")
        fun row(title: String, sub: String, required: Boolean, good: String, bad: String, ok: () -> Boolean, onClick: (() -> Unit)?) {
            if (rows.isNotEmpty()) card.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)))
            val r = LinearLayout(kit.ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(10), kit.dp(12), kit.dp(10), kit.dp(12))
                if (onClick != null) {
                    background = kit.rounded(Palette.ROW, 12, Palette.STROKE_SOFT, 1)
                    setOnClickListener { onClick() }
                }
            }
            val dot = View(kit.ctx)
            r.addView(dot, LinearLayout.LayoutParams(kit.dp(8), kit.dp(8)).apply { marginEnd = kit.dp(12) })
            val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(kit.text(title, 14f, Palette.TEXT, bold = true))
            col.addView(kit.text(sub, 12f, Palette.MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(2) })
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val state = kit.text("", 12.5f, Palette.MUTED, bold = true)
            r.addView(state, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(10) })
            if (onClick != null) {
                r.addView(kit.icon(R.drawable.fluxa_ic_chevron_right, 20, Palette.DIM), LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(6) })
            }
            card.addView(r, LinearLayout.LayoutParams(-1, -2).apply { if (rows.isEmpty()) topMargin = kit.dp(4) })
            rows.add(Row(dot, state, ok, required, good, bad))
        }
        val sdk34 = Build.VERSION.SDK_INT >= 34
        row("บริการ Accessibility", "จำเป็น · ใช้แสดงภาพและแผงควบคุมเหนือเกม (overlay)", true, "เปิดแล้ว", "ยังไม่เปิด",
            { OverlayAccessibilityService.instance != null }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        row("Android 14 ขึ้นไป", "จำเป็น · สำหรับจับภาพแอปเดียว", true, "รองรับ", "ไม่รองรับ", { sdk34 }, null)
        row("ไมโครโฟน / เสียงแอพ", "ไม่บังคับ · ใช้เมื่อเลือกโหมดอัดจอ", false, "ให้แล้ว", "ยังไม่ให้",
            { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            } else {
                I18n.toastMake(this, "ให้สิทธิ์แล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        row("สิทธิ์เข้าถึงการแจ้งเตือน", "ไม่บังคับ · แสดงแจ้งเตือนในแผงควบคุมระหว่างเล่นเกม", false, "เปิดแล้ว", "ยังไม่เปิด",
            { FluxaNotificationListener.enabled(this) }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row("ห้ามรบกวน (DND)", "ไม่บังคับ · เปิดห้ามรบกวนอัตโนมัติในโหมดเกม", false, "ให้แล้ว", "ยังไม่ให้",
            { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        row("แก้ไขการตั้งค่าระบบ", "ไม่บังคับ · ล็อกความสว่างหน้าจอในโหมดเกม", false, "ให้แล้ว", "ยังไม่ให้",
            { Settings.System.canWrite(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        row("สิทธิ์ WRITE_SECURE_SETTINGS", "ไม่บังคับ · ใช้ปรับความละเอียด/DPI · แตะเพื่อดูวิธีขอสิทธิ์", false, "ให้แล้ว", "ยังไม่ให้",
            { DisplayTuner.canWrite(this) }) {
            if (!DisplayTuner.canWrite(this)) {
                startActivity(Intent(this, PermissionActivity::class.java))
                AppSettings.transition(this, true)
            } else {
                I18n.toastMake(this, "ให้สิทธิ์ปรับความละเอียดแล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        return card
    }

    private fun buildHelpCard(): View {
        val help = kit.card("วิธีใช้", Palette.TERTIARY)
        listOf(
            I18n.f("เปิดบริการ Accessibility ของ {0} (ครั้งแรกครั้งเดียว)", getString(R.string.fluxa_app_name)),
            "กด เริ่ม แล้วในหน้าต่างแชร์หน้าจอเลือก \"แอพเดียว\" {ic:arrow} เลือกเกม",
            "สลับไปเล่นเกม · ปัดจากขอบขวาเพื่อเปิดแผงควบคุม",
        ).forEachIndexed { i, step ->
            val row = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                kit.text("${i + 1}", 12f, Palette.PRIMARY, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = kit.rounded(Palette.PRIMARY_SOFT, 11)
                },
                LinearLayout.LayoutParams(kit.dp(22), kit.dp(22)).apply { marginEnd = kit.dp(12) },
            )
            row.addView(kit.text(step, 13.5f, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            help.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(if (i == 0) 14 else 12) })
        }
        help.addView(
            kit.text("ห้ามเลือกทั้งหน้าจอ ไม่งั้น overlay จะถูกจับซ้ำ ต้องใช้ Android 14 ขึ้นไป", 12f, Palette.MUTED)
                .apply { setPadding(0, kit.dp(14), 0, 0) },
        )
        return help
    }

    private fun refresh() {
        rows.forEach { r ->
            val ok = r.ok()
            val badColor = if (r.required) Palette.WARN else Palette.DIM
            r.dot.background = kit.rounded(if (ok) Palette.OK else badColor, 4)
            r.state.text = if (ok) r.good else r.bad
            r.state.setTextColor(if (ok) Palette.OK else Palette.MUTED)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun done() {
        if (first) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = done()

    override fun finish() {
        super.finish()
        if (!first) AppSettings.transition(this, false)
    }

    companion object {
        const val EXTRA_FIRST = "first"
        private const val REQ_AUDIO = 21
    }
}
