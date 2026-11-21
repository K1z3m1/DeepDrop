package app.fluxa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date

/**
 * Consent screen. Shown before the main screen on first launch (and again whenever [Consent.VERSION]
 * is raised). It explains what the app does, which permissions it asks for, what happens to the
 * user's data and what the risks are, and links to the full terms and privacy policy.
 *
 * With [EXTRA_REVIEW] it opens from About as a read-only review where consent can be withdrawn.
 */
class ConsentActivity : Activity() {
    private lateinit var kit: Kit
    private val review by lazy { intent?.getBooleanExtra(EXTRA_REVIEW, false) == true }

    private var agreeTerms = false
    private var agreeUnderstand = false
    private lateinit var acceptBtn: View

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

        // ---- scrolling content
        val body = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(20), kit.dp(16), kit.dp(12))
        }
        body.addView(buildHeader(), LinearLayout.LayoutParams(-1, -2))
        if (!review) body.addView(buildLanguage(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(18) })

        val cards = listOf(buildAboutCard(), buildPermissionCard(), buildDataCard(), buildRiskCard(), buildDocsCard())
        cards.forEach { c ->
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(AppSettings.cardGap) })
        }
        if (!review) {
            body.addView(buildAgreeCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(AppSettings.cardGap) })
        } else {
            val at = Consent.acceptedAt(this)
            if (at > 0L) {
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
                body.addView(
                    kit.text(I18n.f("ยินยอมไว้เมื่อ {0}", date), 12.5f, Palette.MUTED).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(18) },
                )
            }
            body.addView(
                kit.text("ถอนความยินยอมได้ทุกเมื่อ และจะต้องยินยอมใหม่เมื่อเปิดแอพครั้งถัดไป", 12.5f, Palette.DIM).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.2f)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) },
            )
        }
        page.addView(
            ScrollView(kit.ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(body)
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )

        // ---- bottom action bar
        val bar = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(10))
            background = kit.rounded(Palette.a(Palette.BG, 0xF2), 0)
        }
        if (review) {
            bar.addView(
                kit.text("ถอนความยินยอม", 15f, Palette.DANGER, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = kit.rounded(Palette.DANGER_SOFT, 28, Palette.DANGER_LINE, 1)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { withdraw() }
                },
                LinearLayout.LayoutParams(-1, kit.dp(52)),
            )
            bar.addView(
                kit.text("ปิดหน้า", 14f, Palette.MUTED, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, kit.dp(12), 0, kit.dp(6))
                    isClickable = true
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        } else {
            acceptBtn = kit.text("ยอมรับและเริ่มใช้งาน", 16f, Palette.ON_ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = kit.accentFill(28)
                isClickable = true
                isFocusable = true
                setOnClickListener { if (agreeTerms && agreeUnderstand) accept() }
            }
            bar.addView(acceptBtn, LinearLayout.LayoutParams(-1, kit.dp(56)))
            bar.addView(
                kit.text("ไม่ยอมรับ", 14f, Palette.MUTED, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, kit.dp(12), 0, kit.dp(6))
                    isClickable = true
                    setOnClickListener { finishAffinity() }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            refreshAccept()
        }
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    // ------------------------------------------------------------------ sections

    private fun buildHeader(): View {
        val col = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(
            ImageView(kit.ctx).apply {
                setImageResource(R.drawable.fluxa_ic_launcher_foreground)
                scaleType = ImageView.ScaleType.FIT_CENTER
                scaleX = 1.45f
                scaleY = 1.45f
            },
            LinearLayout.LayoutParams(kit.dp(72), kit.dp(72)),
        )
        col.addView(
            kit.text(if (review) "การยินยอม" else "ก่อนเริ่มใช้งาน", 24f, Palette.TEXT, bold = true, spacing = -0.01f),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(18) },
        )
        col.addView(
            kit.text("โปรดอ่านและยินยอมเงื่อนไขด้านล่างก่อนใช้ Deepdrop", 13.5f, Palette.MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(8) },
        )
        return col
    }

    /** The user must be able to read this in their own language before agreeing to anything. */
    private fun buildLanguage(): View {
        val prefs = getSharedPreferences("fluxa", Context.MODE_PRIVATE)
        return Segmented(kit, I18n.LANG_NAMES, AppSettings.lang, perRow = 3) { i ->
            prefs.edit().putInt(AppSettings.K_LANG, i).apply()
            AppSettings.load(this)
            recreate()
            true
        }
    }

    private fun buildAboutCard(): View {
        val card = kit.card("แอพนี้ทำอะไร", Palette.PRIMARY)
        card.addView(
            kit.text(
                "Deepdrop สร้างเฟรมเพิ่มให้เกมลื่นขึ้น โดยจับภาพจากเกมที่คุณเลือก คำนวณเฟรมระหว่างกลางด้วย GPU " +
                    "แล้วแสดงผลซ้อนบนหน้าจอ พร้อมตัวช่วยอัปสเกล ปรับสี และอัดวิดีโอ",
                13.5f, Palette.MUTED,
            ).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12) },
        )
        return card
    }

    private fun buildPermissionCard(): View {
        val card = kit.card("สิ่งที่แอพขอใช้", Palette.SECONDARY)
        val items = listOf(
            Triple("จับภาพหน้าจอ", true, "ใช้ระบบแชร์หน้าจอของ Android เพื่อดึงภาพเกมมาสร้างเฟรม แนะนำให้เลือก \"แอพเดียว\" ภาพถูกประมวลผลในเครื่องเท่านั้น"),
            Triple("บริการ Accessibility", true, "ใช้แสดง overlay ให้แตะทะลุไปที่เกมได้ แอพไม่อ่านและไม่เก็บเนื้อหาบนหน้าจอ"),
            Triple("การแจ้งเตือนขณะทำงาน", true, "Android กำหนดให้แสดงการแจ้งเตือนตลอดเวลาที่กำลังจับภาพหน้าจอ"),
            Triple("ไมโครโฟนและเสียงแอพ", false, "ขอเฉพาะเมื่อใช้โหมดอัดจอ เพื่ออัดเสียงแอพ และเสียงจากไมโครโฟนถ้าคุณเลือก"),
            Triple("ปรับความละเอียด/DPI", false, "ใช้สิทธิ์ WRITE_SECURE_SETTINGS ที่คุณต้องให้เองผ่าน adb ถ้าไม่ให้ ฟีเจอร์นี้จะไม่ทำงาน"),
            Triple("บันทึกวิดีโอ", false, "วิดีโอที่อัดจะถูกบันทึกในโฟลเดอร์ Movies ของเครื่อง"),
        )
        items.forEachIndexed { i, (title, required, desc) ->
            if (i > 0) card.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)).apply { topMargin = kit.dp(12) })
            val head = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(kit.text(title, 14f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(
                kit.text(
                    if (required) "จำเป็น" else "ไม่บังคับ", 11f,
                    if (required) Palette.PRIMARY else Palette.MUTED, bold = true,
                ).apply {
                    setPadding(kit.dp(9), kit.dp(4), kit.dp(9), kit.dp(4))
                    background = if (required) kit.rounded(Palette.PRIMARY_SOFT, 10)
                    else kit.rounded(Palette.TRACK, 10, Palette.STROKE, 1)
                },
                LinearLayout.LayoutParams(-2, -2),
            )
            card.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(if (i == 0) 14 else 12) })
            card.addView(
                kit.text(desc, 12.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.22f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(5) },
            )
        }
        return card
    }

    private fun buildDataCard(): View = bulletCard(
        "ข้อมูลของคุณ", Palette.OK,
        listOf(LegalText.PRIVACY[0], LegalText.PRIVACY[1], LegalText.PRIVACY[5], LegalText.PRIVACY[6]),
    )

    private fun buildRiskCard(): View = bulletCard(
        "ข้อควรรู้", Palette.WARN,
        listOf(
            LegalText.TERMS[3],
            "เกมออนไลน์บางเกมอาจห้ามใช้โปรแกรมจับหน้าจอหรือ overlay การใช้อาจเสี่ยงถูกลงโทษ เช่น ถูกแบนบัญชี ผู้ใช้รับความเสี่ยงเอง",
            LegalText.TERMS[1],
            "เฟรมที่สร้างขึ้นเป็นภาพที่คำนวณได้ อาจมีภาพเพี้ยนหรือเบลอบริเวณที่เคลื่อนไหวเร็ว",
        ),
    )

    private fun bulletCard(title: String, accent: Int, lines: List<String>): View {
        val card = kit.card(title, accent)
        lines.forEachIndexed { i, t ->
            val row = LinearLayout(kit.ctx)
            row.addView(
                View(kit.ctx).apply { background = kit.rounded(accent, 3) },
                LinearLayout.LayoutParams(kit.dp(6), kit.dp(6)).apply { topMargin = kit.dp(6); marginEnd = kit.dp(12) },
            )
            row.addView(
                kit.text(t, 13f, Palette.MUTED).apply { setLineSpacing(0f, 1.22f) },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(if (i == 0) 14 else 10) })
        }
        return card
    }

    private fun buildDocsCard(): View {
        val card = kit.card("เอกสารฉบับเต็ม", Palette.TERTIARY)
        val docs = listOf(
            Triple("ข้อตกลงการใช้งาน", LegalText.TERMS, "ข้อตกลงการใช้งาน"),
            Triple("นโยบายความเป็นส่วนตัว", LegalText.PRIVACY, "นโยบายความเป็นส่วนตัว"),
            Triple("แหล่งที่มา (Open Source)", LegalText.SOURCE, "แหล่งที่มา (Open Source)"),
        )
        docs.forEachIndexed { i, (label, paragraphs, title) ->
            if (i > 0) card.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)))
            val row = LinearLayout(kit.ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, kit.dp(14), 0, kit.dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { showLegalSheet(this@ConsentActivity, kit, title, paragraphs) }
            }
            row.addView(kit.text(label, 14.5f, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.icon(R.drawable.fluxa_ic_chevron_right, 20, Palette.DIM))
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i == 0) topMargin = kit.dp(6) })
        }
        return card
    }

    private fun buildAgreeCard(): View {
        val card = kit.card(null)
        card.addView(
            checkRow("ฉันอ่านและยอมรับข้อตกลงการใช้งานและนโยบายความเป็นส่วนตัว") { agreeTerms = it; refreshAccept() },
        )
        card.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)))
        card.addView(
            checkRow("ฉันเข้าใจสิทธิ์ที่แอพขอใช้และความเสี่ยงข้างต้น และยินยอมให้แอพทำงานตามที่อธิบาย") {
                agreeUnderstand = it; refreshAccept()
            },
        )
        return card
    }

    private fun checkRow(text: String, onChange: (Boolean) -> Unit): LinearLayout {
        var on = false
        val box = FrameLayout(kit.ctx)
        val tick = kit.icon(R.drawable.fluxa_ic_tick, 18, Palette.ON_ACCENT).apply {
            setPadding(kit.dp(3), kit.dp(3), kit.dp(3), kit.dp(3))
            visibility = View.INVISIBLE
        }
        box.addView(tick, FrameLayout.LayoutParams(-1, -1))
        fun paint() {
            box.background = if (on) kit.rounded(Palette.PRIMARY, 8) else kit.rounded(Palette.TRACK, 8, Palette.STROKE, 2)
            tick.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        paint()
        val row = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, kit.dp(12), 0, kit.dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { on = !on; paint(); onChange(on) }
        }
        row.addView(box, LinearLayout.LayoutParams(kit.dp(26), kit.dp(26)).apply { marginEnd = kit.dp(14) })
        row.addView(
            kit.text(text, 13.5f, Palette.TEXT).apply { setLineSpacing(0f, 1.22f) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        return row
    }

    // ------------------------------------------------------------------ actions

    private fun refreshAccept() {
        val ok = agreeTerms && agreeUnderstand
        acceptBtn.alpha = if (ok) 1f else 0.4f
    }

    private fun accept() {
        Consent.accept(this)
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FIRST, true))
        finish()
    }

    private fun withdraw() {
        Consent.withdraw(this)
        // stop anything that is running; the main screen asks again on the next launch
        runCatching { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }
        I18n.toastMake(this, "ถอนความยินยอมแล้ว แอพจะขอความยินยอมใหม่เมื่อเปิดครั้งหน้า", Toast.LENGTH_LONG).show()
        finishAffinity()
    }

    override fun finish() {
        super.finish()
        if (review) AppSettings.transition(this, false)
    }

    companion object {
        const val EXTRA_REVIEW = "review"
    }
}
