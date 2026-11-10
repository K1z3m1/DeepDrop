package app.fluxa

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** About screen: logo, version, and four rows (terms, privacy, open source, contact) that open a sheet. */
class AboutActivity : Activity() {
    private lateinit var kit: Kit

    private enum class Page { TERMS, PRIVACY, SOURCE, CONSENT, CONTACT }

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

        // top bar: back arrow + title
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
        head.addView(kit.text("เกี่ยวกับแอพ", 19f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(-2, -2))
        page.addView(head, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(16), kit.dp(24), kit.dp(16), kit.dp(24))
        }
        body.addView(
            ImageView(kit.ctx).apply {
                setImageResource(R.drawable.fluxa_ic_launcher_foreground)
                scaleType = ImageView.ScaleType.FIT_CENTER
                scaleX = 1.45f
                scaleY = 1.45f
            },
            LinearLayout.LayoutParams(kit.dp(96), kit.dp(96)),
        )
        body.addView(
            kit.text("Deepdrop", 26f, Palette.TEXT, bold = true, spacing = -0.01f),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(16) },
        )
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0"
        body.addView(
            kit.text("v$version", 13f, Palette.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(6) },
        )

        val list = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.glass(22)
        }
        val rows = listOf(
            Triple(AboutIcon.Kind.DOC, "ข้อตกลงการใช้งาน", Page.TERMS),
            Triple(AboutIcon.Kind.SHIELD, "นโยบายความเป็นส่วนตัว", Page.PRIVACY),
            Triple(AboutIcon.Kind.CHECK, "ทบทวนการยินยอม", Page.CONSENT),
            Triple(AboutIcon.Kind.CODE, "แหล่งที่มา (Open Source)", Page.SOURCE),
            Triple(AboutIcon.Kind.MAIL, "ติดต่อผู้พัฒนา", Page.CONTACT),
        )
        rows.forEachIndexed { i, (kind, title, target) ->
            if (i > 0) list.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)).apply {
                marginStart = kit.dp(60); marginEnd = kit.dp(16)
            })
            val row = LinearLayout(kit.ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(18), kit.dp(18), kit.dp(16), kit.dp(18))
                isClickable = true
                setOnClickListener {
                    if (target == Page.CONSENT) {
                        startActivity(Intent(this@AboutActivity, ConsentActivity::class.java).putExtra(ConsentActivity.EXTRA_REVIEW, true))
                        AppSettings.transition(this@AboutActivity, true)
                    } else showPage(target)
                }
            }
            row.addView(AboutIcon(kit.ctx, kind), LinearLayout.LayoutParams(kit.dp(24), kit.dp(24)).apply { marginEnd = kit.dp(16) })
            row.addView(kit.text(title, 15f, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.icon(R.drawable.fluxa_ic_chevron_right, 20, Palette.DIM))
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(36) })

        body.addView(View(kit.ctx), LinearLayout.LayoutParams(1, kit.dp(40)))
        body.addView(kit.text("Small App · Big Performance", 12.5f, Palette.DIM))
        body.addView(
            kit.text("Made for Gamers", 12.5f, Palette.DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(10) },
        )

        page.addView(
            ScrollView(kit.ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(body)
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        setContentView(root)
    }

    private fun showPage(p: Page) {
        val (title, paragraphs) = when (p) {
            Page.TERMS -> "ข้อตกลงการใช้งาน" to LegalText.TERMS
            Page.PRIVACY -> "นโยบายความเป็นส่วนตัว" to LegalText.PRIVACY
            Page.SOURCE -> "แหล่งที่มา (Open Source)" to LegalText.SOURCE
            Page.CONSENT -> "ทบทวนการยินยอม" to emptyList<String>()
            Page.CONTACT -> "ติดต่อผู้พัฒนา" to emptyList<String>()
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(Palette.PANEL_CARD, 24, Palette.PANEL_STROKE, 1)
            setPadding(kit.dp(22), kit.dp(22), kit.dp(22), kit.dp(16))
        }
        card.addView(kit.text(title, 18f, Palette.TEXT, bold = true))
        val inner = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        paragraphs.forEachIndexed { i, t ->
            inner.addView(
                kit.text(t, 13.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.25f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(if (i == 0) 14 else 10) },
            )
        }
        if (p == Page.CONTACT) {
            inner.addView(
                kit.text("ช่องทางติดต่อและสนับสนุนผู้พัฒนา", 13.5f, Palette.MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(14) },
            )
            link(inner, "YouTube", "https://m.youtube.com/@firstT175")
            link(inner, "Ko-fi", "https://ko-fi.com/firstk1z3m1")
        }
        card.addView(
            ScrollView(kit.ctx).apply { isVerticalScrollBarEnabled = false; addView(inner) },
            LinearLayout.LayoutParams(-1, -2),
        )
        card.addView(
            kit.text("ปิดหน้า", 14f, Palette.PRIMARY, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(14), 0, kit.dp(14))
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) },
        )
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            val w = (resources.displayMetrics.widthPixels * 0.9f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun link(into: LinearLayout, label: String, url: String) {
        into.addView(
            kit.text("$label  ›", 15f, Palette.PRIMARY, bold = true).apply {
                setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(14))
                background = kit.glassBtn(14)
                isClickable = true
                setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) },
        )
    }

    override fun finish() {
        super.finish()
        AppSettings.transition(this, false)
    }
}

/** About-row icons: ready-made Material vectors. */
class AboutIcon(context: android.content.Context, kind: Kind) : android.widget.ImageView(context) {
    enum class Kind(val res: Int) {
        DOC(R.drawable.fluxa_ic_doc), SHIELD(R.drawable.fluxa_ic_shield), CODE(R.drawable.fluxa_ic_code),
        MAIL(R.drawable.fluxa_ic_mail), CHECK(R.drawable.fluxa_ic_check),
    }

    init {
        setImageResource(kind.res)
        scaleType = ScaleType.FIT_CENTER
        setColorFilter(Palette.TEXT)
    }
}
