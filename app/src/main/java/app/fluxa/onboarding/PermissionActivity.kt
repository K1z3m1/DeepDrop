package app.fluxa

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
 * Permission page (dialog style). Explains the one-time WRITE_SECURE_SETTINGS grant that screen
 * resolution / DPI tuning needs, with the adb command, a shortcut to Developer options, and a live
 * check that closes the page by itself as soon as the permission is granted.
 */
class PermissionActivity : Activity() {
    private lateinit var kit: Kit
    private lateinit var state: TextView

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
        val page = FrameLayout(kit.ctx).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        // dialog-style card, centred
        val dialog = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(Palette.PANEL_CARD, 28, Palette.PANEL_STROKE, 1)
            setPadding(kit.dp(22), kit.dp(22), kit.dp(22), kit.dp(8))
        }
        dialog.addView(kit.text("อนุญาตให้แก้ไขการตั้งค่าหน้าจอหรือไม่", 18f, Palette.TEXT, bold = true))
        dialog.addView(
            kit.text(
                I18n.f("{0} ต้องได้รับสิทธิ์ WRITE_SECURE_SETTINGS เพื่อปรับความละเอียดและ DPI ของจอ · ให้สิทธิ์ครั้งเดียวผ่าน adb จากคอมพิวเตอร์", getString(R.string.fluxa_app_name)),
                13.5f, Palette.MUTED,
            ).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) },
        )

        val steps = listOf(
            "ตั้งค่า {ic:arrow} เกี่ยวกับโทรศัพท์ {ic:arrow} แตะ \"หมายเลขบิลด์\" 7 ครั้ง",
            "เปิด \"ตัวเลือกนักพัฒนา\" {ic:arrow} เปิด \"การแก้ไขข้อบกพร่อง USB\" แล้วต่อสายกับคอมพิวเตอร์",
            "กด \"อนุญาต\" ในหน้าต่างลายนิ้วมือ RSA ที่เด้งขึ้นบนโทรศัพท์",
            "รันคำสั่งด้านล่างบนคอมพิวเตอร์ (หรือกดคัดลอก)",
        )
        steps.forEachIndexed { i, step ->
            val row = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                kit.text("${i + 1}", 12f, Palette.PRIMARY, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = kit.rounded(Palette.PRIMARY_SOFT, 11)
                },
                LinearLayout.LayoutParams(kit.dp(22), kit.dp(22)).apply { marginEnd = kit.dp(12) },
            )
            row.addView(kit.text(step, 13f, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            dialog.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) })
        }

        dialog.addView(
            kit.text(DisplayTuner.GRANT_COMMAND, 12.5f, Palette.PRIMARY).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
                background = kit.lit(14)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) },
        )

        state = kit.text("", 13f, Palette.WARN, bold = true).apply { setPadding(0, kit.dp(14), 0, 0) }
        dialog.addView(state)

        fun action(label: String, color: Int, onClick: () -> Unit) = kit.text(label, 14.5f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(8), kit.dp(16), kit.dp(8), kit.dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        dialog.addView(
            action("เปิดตัวเลือกนักพัฒนา", Palette.PRIMARY) {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { I18n.toastMake(this, "เปิดไม่ได้ · ไปที่ ตั้งค่า > ตัวเลือกนักพัฒนา เอง", Toast.LENGTH_LONG).show() }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(6) },
        )
        dialog.addView(View(kit.ctx).apply { setBackgroundColor(Palette.STROKE_SOFT) }, LinearLayout.LayoutParams(-1, 1))

        val buttons = LinearLayout(kit.ctx)
        buttons.addView(action("ยกเลิก", Palette.TEXT) { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(View(kit.ctx).apply { setBackgroundColor(Palette.STROKE_SOFT) }, LinearLayout.LayoutParams(1, kit.dp(28)).apply { gravity = Gravity.CENTER_VERTICAL })
        buttons.addView(
            action("คัดลอกคำสั่ง", Palette.PRIMARY) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", DisplayTuner.GRANT_COMMAND))
                I18n.toastMake(this, "คัดลอกคำสั่ง adb แล้ว", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        dialog.addView(buttons)

        page.addView(
            ScrollView(kit.ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = true
                addView(
                    FrameLayout(kit.ctx).apply {
                        setPadding(kit.dp(20), kit.dp(20), kit.dp(20), kit.dp(20))
                        addView(dialog, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    },
                    FrameLayout.LayoutParams(-1, -2),
                )
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (DisplayTuner.canWrite(this)) {
            I18n.toastMake(this, "ให้สิทธิ์ปรับความละเอียดแล้ว", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        state.text = "{ic:close} ยังไม่ได้ให้สิทธิ์ · หน้านี้จะปิดเองเมื่อได้สิทธิ์แล้ว"
    }

    override fun finish() {
        super.finish()
        AppSettings.transition(this, false)
    }
}
