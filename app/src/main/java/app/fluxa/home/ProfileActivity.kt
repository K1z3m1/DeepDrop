package app.fluxa

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Name, about text, avatar and banner of the player profile (all stored on the device). */
object ProfileStore {
    private const val K_NAME = "profile_name"
    private const val K_ABOUT = "profile_about"
    private const val AVATAR_PX = 512
    private const val BANNER_PX = 1280

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fluxa", Context.MODE_PRIVATE)

    fun name(ctx: Context): String = prefs(ctx).getString(K_NAME, null)?.takeIf { it.isNotBlank() } ?: "ผู้เล่น"
    fun about(ctx: Context): String = prefs(ctx).getString(K_ABOUT, null)?.takeIf { it.isNotBlank() } ?: "พื้นที่เล่นเกมของฉัน"
    fun setText(ctx: Context, name: String, about: String) =
        prefs(ctx).edit().putString(K_NAME, name.trim()).putString(K_ABOUT, about.trim()).apply()

    private fun file(ctx: Context, avatar: Boolean) = File(ctx.filesDir, if (avatar) "profile_avatar.jpg" else "profile_banner.jpg")

    fun avatar(ctx: Context): Bitmap? = load(file(ctx, true))
    fun banner(ctx: Context): Bitmap? = load(file(ctx, false))

    private fun load(f: File): Bitmap? =
        if (!f.exists()) null else runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()

    /** Copies the picked picture into the app (avatar: centre-cropped square) so it survives the picker. Blocking. */
    fun import(ctx: Context, uri: android.net.Uri, avatar: Boolean): Boolean = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = if (avatar) AVATAR_PX else BANNER_PX
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val raw = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching false
        val out = if (avatar) {
            val s = min(raw.width, raw.height)
            val square = Bitmap.createBitmap(raw, (raw.width - s) / 2, (raw.height - s) / 2, s, s)
            if (s > AVATAR_PX) Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true) else square
        } else if (raw.width > BANNER_PX) {
            Bitmap.createScaledBitmap(raw, BANNER_PX, raw.height * BANNER_PX / raw.width, true)
        } else raw
        FileOutputStream(file(ctx, avatar)).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    }.getOrDefault(false)

    /** Rank from total hours played. */
    fun rank(totalSec: Long): String {
        val h = totalSec / 3600.0
        return when {
            h < 1 -> "มือใหม่"
            h < 10 -> "นักเล่น"
            h < 50 -> "เซียน"
            h < 100 -> "ระดับสูง"
            else -> "ตำนาน"
        }
    }
}

/** Round avatar: the picked picture, or a person glyph while none is set. Optional ring in the page colour. */
class AvatarView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val src = Rect()
    private val dst = RectF()
    var ringColor = 0
    var ringPx = 0f

    fun setBitmap(b: Bitmap?) {
        bmp = b
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val inner = r - ringPx
        c.save()
        clip.reset()
        clip.addCircle(cx, cy, inner, Path.Direction.CW)
        c.clipPath(clip)
        val b = bmp
        if (b != null) {
            val s = min(b.width, b.height)
            src.set((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
            dst.set(cx - inner, cy - inner, cx + inner, cy + inner)
            c.drawBitmap(b, src, dst.toRect(), paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Palette.SURFACE_HI
            c.drawCircle(cx, cy, inner, paint)
            paint.color = Palette.PRIMARY
            c.drawCircle(cx, cy - inner * 0.18f, inner * 0.28f, paint)
            c.drawCircle(cx, cy + inner * 0.95f, inner * 0.58f, paint)
        }
        c.restore()
        if (ringPx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ringPx
            paint.color = ringColor
            c.drawCircle(cx, cy, r - ringPx / 2f, paint)
        }
    }

    private fun RectF.toRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
}

/** Small column chart: one rounded bar per value, empty days drawn as a thin stub. */
class BarsView(ctx: Context, private val values: List<Float>, private val color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val n = values.size
        if (n == 0) return
        val d = resources.displayMetrics.density
        val gap = 3f * d
        val w = (width - gap * (n - 1)) / n
        val top = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val stub = 3f * d
        val rad = min(w / 2f, 3f * d)
        for (i in 0 until n) {
            val v = values[i]
            val h = if (v <= 0f) stub else stub + (height - stub) * (v / top)
            val l = i * (w + gap)
            r.set(l, height - h, l + w, height.toFloat())
            paint.color = if (v <= 0f) Palette.STROKE else color
            c.drawRoundRect(r, rad, rad, paint)
        }
    }
}

/**
 * Player profile: banner + avatar (both changeable), name / about, and four pages:
 * profile (days + playtime), statistics (fps min / avg / max), games (most played) and achievements.
 */
class ProfileActivity : Activity() {
    private lateinit var kit: Kit
    private lateinit var banner: ImageView
    private lateinit var avatar: AvatarView
    private lateinit var nameView: TextView
    private lateinit var chips: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var tabViews: List<Pair<TextView, View>>
    private lateinit var backBtn: View
    private lateinit var bannerEdit: View
    private lateinit var scroll: ScrollView
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = Kit(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = Palette.LIGHT
            isAppearanceLightNavigationBars = Palette.LIGHT
        }

        val root = FrameLayout(kit.ctx)
        root.addView(AuroraBackground(kit.ctx), FrameLayout.LayoutParams(-1, -1))
        scroll = CenteredScrollView(kit.ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // ---- header: banner, back, change banner, avatar, edit profile
        val bannerH = kit.dp(if (kit.landscape) 96 else 130) // the screen is short in landscape
        val avatarSize = kit.dp(if (kit.landscape) 68 else 76)
        val header = FrameLayout(kit.ctx)
        banner = ImageView(kit.ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Palette.a(Palette.PRIMARY, 0x99), Palette.a(Palette.SECONDARY, 0x55), Palette.SURFACE),
            )
        }
        header.addView(banner, FrameLayout.LayoutParams(-1, bannerH, Gravity.TOP))

        fun roundBtn(res: Int, size: Int, iconDp: Int, onClick: () -> Unit): View =
            kit.icon(res, iconDp, 0xFFFFFFFF.toInt()).apply {
                val pad = (size - kit.dp(iconDp)) / 2
                setPadding(pad, pad, pad, pad)
                layoutParams = android.view.ViewGroup.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        backBtn = roundBtn(R.drawable.fluxa_ic_chevron_left, kit.dp(40), 26) { finish() }
        header.addView(backBtn, FrameLayout.LayoutParams(kit.dp(40), kit.dp(40), Gravity.TOP or Gravity.START).apply {
            marginStart = kit.dp(12); topMargin = kit.dp(12)
        })
        bannerEdit = roundBtn(R.drawable.fluxa_ic_edit, kit.dp(40), 20) { pick(REQ_BANNER) }
        header.addView(bannerEdit, FrameLayout.LayoutParams(kit.dp(40), kit.dp(40), Gravity.TOP or Gravity.END).apply {
            marginEnd = kit.dp(12); topMargin = kit.dp(12)
        })

        avatar = AvatarView(kit.ctx).apply { ringColor = Palette.BG; ringPx = kit.dpf(4) }
        header.addView(avatar, FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = kit.dp(16)
        })
        val badge = roundBtn(R.drawable.fluxa_ic_edit, kit.dp(28), 14) { pick(REQ_AVATAR) }
        header.addView(badge, FrameLayout.LayoutParams(kit.dp(28), kit.dp(28), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = kit.dp(16) + avatarSize - kit.dp(26); bottomMargin = kit.dp(4)
        })
        avatar.isClickable = true
        avatar.setOnClickListener { pick(REQ_AVATAR) }

        val editBtn = kit.fitText("แก้ไขโปรไฟล์", 14f, Palette.ON_ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(14), kit.dp(8), kit.dp(14), kit.dp(8))
            background = kit.accentFill(20)
            isClickable = true
            isFocusable = true
            setOnClickListener { editProfile() }
        }
        header.addView(editBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = kit.dp(16); bottomMargin = kit.dp(2)
        })
        col.addView(header, LinearLayout.LayoutParams(-1, bannerH + avatarSize / 2))

        nameView = kit.fitText("", 22f, Palette.TEXT, bold = true, spacing = -0.01f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(nameView, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = kit.dp(20); marginEnd = kit.dp(20); topMargin = kit.dp(10)
        })
        chips = LinearLayout(kit.ctx)
        col.addView(
            HorizontalScrollView(kit.ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(12); marginStart = kit.dp(20); marginEnd = kit.dp(12) },
        )

        // ---- tabs
        val labels = listOf("โปรไฟล์", "สถิติ", "เกม", "ความสำเร็จ")
        val tabRow = LinearLayout(kit.ctx)
        tabViews = labels.mapIndexed { i, label ->
            val t = kit.fitText(label, 14f, Palette.MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(kit.dp(4), kit.dp(14), kit.dp(4), kit.dp(12))
            }
            val line = View(kit.ctx)
            val cell = LinearLayout(kit.ctx).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { showTab(i) }
                addView(t, LinearLayout.LayoutParams(-1, -2))
                addView(line, LinearLayout.LayoutParams(-1, kit.dp(2)))
            }
            tabRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            t to line
        }
        col.addView(tabRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = kit.dp(14); marginStart = kit.dp(16); marginEnd = kit.dp(16)
        })
        col.addView(View(kit.ctx).apply { setBackgroundColor(Palette.STROKE_SOFT) }, LinearLayout.LayoutParams(-1, kit.dp(1)).apply {
            marginStart = kit.dp(16); marginEnd = kit.dp(16)
        })

        content = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(16))
        }
        col.addView(content, LinearLayout.LayoutParams(-1, -2))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (backBtn.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + kit.dp(10)
            (bannerEdit.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + kit.dp(10)
            backBtn.requestLayout(); bannerEdit.requestLayout()
            scroll.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun finish() {
        super.finish()
        AppSettings.transition(this, false)
    }

    /** Re-reads pictures, text and statistics (also after returning from the picker). */
    private fun refresh() {
        val d = StatsStore.load(this)
        avatar.setBitmap(ProfileStore.avatar(this))
        ProfileStore.banner(this)?.let { banner.setImageBitmap(it) } ?: banner.setImageDrawable(null)
        nameView.text = ProfileStore.name(this)

        chips.removeAllViews()
        fun chip(label: String, fg: Int, bg: Int, line: Int) {
            chips.addView(
                kit.fitText(label, 12.5f, fg, bold = true).apply {
                    maxLines = 1
                    setPadding(kit.dp(12), kit.dp(7), kit.dp(12), kit.dp(7))
                    background = kit.rounded(bg, 14, line, 1)
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = kit.dp(8) },
            )
        }
        chip("{ic:star} " + I18n.tr(ProfileStore.rank(d.totalSec)), Palette.PRIMARY, Palette.PRIMARY_SOFT, Palette.PRIMARY_LINE)
        chip("Android ${Build.VERSION.RELEASE}", Palette.TEXT, Palette.CHIP_HI, Palette.STROKE)
        chip(Build.MODEL, Palette.TEXT, Palette.CHIP_HI, Palette.STROKE)
        showTab(tab, d)
    }

    private fun showTab(i: Int, data: StatsStore.Data = StatsStore.load(this)) {
        tab = i
        tabViews.forEachIndexed { k, (t, line) ->
            t.setTextColor(if (k == i) Palette.PRIMARY else Palette.MUTED)
            line.setBackgroundColor(if (k == i) Palette.PRIMARY else 0)
        }
        content.removeAllViews()
        val cards = when (i) {
            0 -> profileCards(data)
            1 -> statCards(data)
            2 -> gameCards(data)
            else -> achievementCards(data)
        }
        cards.forEachIndexed { k, v ->
            content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (k > 0) topMargin = kit.dp(AppSettings.cardGap) })
        }
    }

    // ------------------------------------------------------------------ pictures + editing

    private fun pick(req: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { startActivityForResult(intent, req) }
            .onFailure { I18n.toastMake(this, "เลือกรูปไม่ได้", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || (requestCode != REQ_AVATAR && requestCode != REQ_BANNER)) return
        thread(name = "profile-image") {
            val ok = ProfileStore.import(applicationContext, uri, requestCode == REQ_AVATAR)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) refresh() else I18n.toastMake(this, "บันทึกรูปไม่ได้", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun editProfile() {
        fun field(hint: String, value: String, maxLen: Int, multi: Boolean) = EditText(this).apply {
            setText(value)
            setSelection(text.length)
            this.hint = I18n.tr(hint)
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.DIM)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
            inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
            if (multi) maxLines = 3
        }
        val name = field("ชื่อ", ProfileStore.name(this).takeIf { it != "ผู้เล่น" } ?: "", 24, false)
        val about = field("เกี่ยวกับฉัน", ProfileStore.about(this).takeIf { it != "พื้นที่เล่นเกมของฉัน" } ?: "", 120, true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(6), kit.dp(16), 0)
            addView(name)
            addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.tr("แก้ไขโปรไฟล์"))
            .setView(box)
            .setNegativeButton(I18n.tr("ยกเลิก"), null)
            .setPositiveButton(I18n.tr("บันทึก")) { _, _ ->
                ProfileStore.setText(this, name.text.toString(), about.text.toString())
                refresh()
            }
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun dur(sec: Long): String {
        val m = sec / 60
        return if (m < 60) I18n.f("{0} นาที", m) else I18n.f("{0} ชม. {1} นาที", m / 60, m % 60)
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    private fun big(value: String, color: Int = Palette.TEXT, size: Float = 28f) =
        kit.fitText(value, size, color, bold = true, spacing = -0.01f).apply { fontFeatureSettings = "tnum" }

    private fun hint(text: String) = kit.text(text, 12f, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) }

    private fun LinearLayout.add(v: View, top: Int = 0) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(top) })

    private fun row(label: String, value: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, kit.dp(10), 0, kit.dp(10))
        addView(kit.fitText(label, 13.5f, Palette.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        addView(kit.fitText(value, 14f, Palette.TEXT, bold = true).apply { fontFeatureSettings = "tnum" })
    }

    private fun progress(fraction: Float, color: Int = Palette.PRIMARY): View {
        val f = fraction.coerceIn(0f, 1f)
        return LinearLayout(this).apply {
            background = kit.rounded(Palette.STROKE, 3)
            addView(View(context).apply { background = kit.rounded(color, 3) }, LinearLayout.LayoutParams(0, -1, max(f, 0.001f)))
            addView(View(context), LinearLayout.LayoutParams(0, -1, max(1f - f, 0.001f)))
        }
    }

    private fun gameName(pkg: String): String =
        if (pkg == "?") I18n.tr("ไม่ทราบแอพ")
        else runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun gameIcon(pkg: String, size: Int): View {
        val icon = if (pkg == "?") null else runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
        return if (icon != null) ImageView(this).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER }
        else FrameLayout(this).apply {
            background = kit.lit(14)
            addView(kit.fitText("?", 20f, Palette.PRIMARY, bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun fpsTrio(min: Int, avg: Int, max: Int): View = LinearLayout(this).apply {
        fun col(label: String, v: Int, color: Int) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.fitText(label, 12f, Palette.MUTED))
            addView(big(if (v > 0) "$v" else "--", color, 30f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(6) })
            addView(kit.fitText("fps", 11.5f, Palette.DIM), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(2) })
        }
        addView(col("ต่ำสุด", min, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        addView(col("เฉลี่ย", avg, Palette.PRIMARY), LinearLayout.LayoutParams(0, -2, 1f))
        addView(col("สูงสุด", max, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
    }

    // ------------------------------------------------------------------ pages

    private fun profileCards(d: StatsStore.Data): List<View> {
        val about = kit.card("เกี่ยวกับฉัน", Palette.PRIMARY)
        about.add(kit.text(ProfileStore.about(this), 14f, Palette.TEXT).apply { setLineSpacing(0f, 1.2f) }, 12)

        val days = kit.card("วันที่เล่น", Palette.TERTIARY)
        days.add(big("${d.activeDays(90)}"), 12)
        days.add(hint("ใน 90 วันที่ผ่านมา"), 4)
        val dayVals = (29 downTo 0).map { if (d.secOn(it) > 0) 1f else 0f }
        days.addView(BarsView(this, dayVals, Palette.PRIMARY), LinearLayout.LayoutParams(-1, kit.dp(34)).apply { topMargin = kit.dp(14) })

        val play = kit.card("เวลาเล่นรวม", Palette.SECONDARY)
        play.add(big(dur(d.secLast(90))), 12)
        play.add(hint("ใน 90 วันที่ผ่านมา"), 4)
        val playVals = (29 downTo 0).map { d.secOn(it).toFloat() }
        play.addView(BarsView(this, playVals, Palette.SECONDARY), LinearLayout.LayoutParams(-1, kit.dp(34)).apply { topMargin = kit.dp(14) })
        play.add(hint(I18n.f("ตลอดการใช้งาน {0}", dur(d.totalSec))), 12)

        fun tile(title: String, value: String): View = kit.card(title, Palette.PRIMARY).apply {
            add(big(value), 12)
        }
        val tiles = LinearLayout(this)
        tiles.addView(tile("จำนวนครั้งที่เล่น", "${d.sessions}"), LinearLayout.LayoutParams(0, -2, 1f))
        tiles.addView(tile("เฟรมที่เจนเพิ่ม", compact(d.generated)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) })
        return listOf(about, days, play, tiles)
    }

    private fun statCards(d: StatsStore.Data): List<View> {
        val fps = kit.card("เฟรมเรตที่แสดง", Palette.PRIMARY)
        if (!d.hasFps) {
            fps.add(hint("ยังไม่มีข้อมูล · เล่นสักครั้งแล้วสถิติจะขึ้นที่นี่"), 12)
        } else {
            fps.add(fpsTrio(d.minOut, Math.round(d.avgOut).toInt(), d.maxOut), 16)
            fps.add(hint(I18n.f("ต้นฉบับเฉลี่ย {0} {ic:arrow} หลังเจนเฟรม {1} fps", Math.round(d.avgReal), Math.round(d.avgOut))), 14)
        }

        val play = kit.card("การเล่น", Palette.SECONDARY)
        val avgSession = if (d.sessions > 0) d.totalSec / d.sessions else 0L
        play.add(row("จำนวนครั้งที่เล่น", "${d.sessions}"), 6)
        play.add(kit.divider(), 0)
        play.add(row("เวลาเฉลี่ยต่อครั้ง", dur(avgSession)))
        play.add(kit.divider(), 0)
        play.add(row("นานที่สุด", dur(d.longest)))
        play.add(kit.divider(), 0)
        play.add(row("เฟรมที่เจนเพิ่ม", compact(d.generated)))

        val week = kit.card("7 วันล่าสุด", Palette.TERTIARY)
        week.add(big(dur(d.secLast(7))), 12)
        val vals = (6 downTo 0).map { d.secOn(it).toFloat() }
        week.addView(BarsView(this, vals, Palette.PRIMARY), LinearLayout.LayoutParams(-1, kit.dp(80)).apply { topMargin = kit.dp(14) })
        val loc = Locale(arrayOf("th", "en", "zh", "es", "ja", "ko")[AppSettings.lang.coerceIn(0, 5)])
        val fmt = SimpleDateFormat("EEE", loc)
        val labels = LinearLayout(this)
        for (k in 6 downTo 0) {
            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -k) }
            labels.addView(
                kit.fitText(fmt.format(day.time), 11f, Palette.DIM).apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }
        week.add(labels, 8)
        return listOf(fps, play, week)
    }

    private fun gameCards(d: StatsStore.Data): List<View> {
        val list = d.mostPlayed()
        if (list.isEmpty()) {
            return listOf(kit.card("เล่นมากที่สุด", Palette.PRIMARY).apply {
                add(hint("ยังไม่มีข้อมูล · เล่นสักครั้งแล้วสถิติจะขึ้นที่นี่"), 12)
            })
        }
        val total = max(1L, list.sumOf { it.second.sec })
        val (topPkg, top) = list.first()
        val hero = kit.card("เล่นมากที่สุด", Palette.PRIMARY)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(gameIcon(topPkg, kit.dp(60)), LinearLayout.LayoutParams(kit.dp(60), kit.dp(60)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(kit.fitText(gameName(topPkg), 18f, Palette.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        info.addView(kit.fitText(dur(top.sec), 14f, Palette.PRIMARY, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(4) })
        info.addView(hint(I18n.f("{0}% ของเวลาทั้งหมด", top.sec * 100 / total)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(2) })
        head.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(14) })
        hero.add(head, 14)
        hero.addView(progress(top.sec.toFloat() / total), LinearLayout.LayoutParams(-1, kit.dp(6)).apply { topMargin = kit.dp(14) })
        hero.add(
            if (top.samples > 0) fpsTrio(top.min, Math.round(top.avgOut).toInt(), top.max) else hint("ยังไม่มีข้อมูล · เล่นสักครั้งแล้วสถิติจะขึ้นที่นี่"),
            18,
        )
        val out = arrayListOf<View>(hero)

        if (list.size > 1) {
            val all = kit.card("เกมทั้งหมด", Palette.SECONDARY)
            list.drop(1).forEachIndexed { i, (pkg, app) ->
                if (i > 0) all.add(kit.divider(), 0)
                val r = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, kit.dp(12), 0, kit.dp(12))
                }
                r.addView(gameIcon(pkg, kit.dp(42)), LinearLayout.LayoutParams(kit.dp(42), kit.dp(42)))
                val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                mid.addView(kit.fitText(gameName(pkg), 14.5f, Palette.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                mid.addView(progress(app.sec.toFloat() / total, Palette.SECONDARY), LinearLayout.LayoutParams(-1, kit.dp(4)).apply { topMargin = kit.dp(7) })
                r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(12); marginEnd = kit.dp(12) })
                val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                right.addView(kit.fitText(dur(app.sec), 13f, Palette.TEXT, bold = true))
                right.addView(
                    kit.fitText(if (app.samples > 0) "${Math.round(app.avgOut)} fps" else "--", 11.5f, Palette.MUTED),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(3) },
                )
                r.addView(right)
                all.add(r, if (i == 0) 6 else 0)
            }
            out.add(all)
        }
        return out
    }

    private fun num(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    private class Ach(val title: String, val desc: String, val cur: Double, val goal: Double)

    private fun achievementCards(d: StatsStore.Data): List<View> {
        val hours = d.totalSec / 3600.0
        val games = d.apps.keys.count { it != "?" }
        val items = listOf(
            Ach("ก้าวแรก", "เล่นกับ Fluxa ครบ 1 ครั้ง", d.sessions.toDouble(), 1.0),
            Ach("อุ่นเครื่อง", "เวลาเล่นรวมครบ 1 ชั่วโมง", hours, 1.0),
            Ach("นักเล่นตัวจริง", "เวลาเล่นรวมครบ 10 ชั่วโมง", hours, 10.0),
            Ach("ตำนาน", "เวลาเล่นรวมครบ 50 ชั่วโมง", hours, 50.0),
            Ach("มาราธอน", "เล่นต่อเนื่องในครั้งเดียวครบ 2 ชั่วโมง", d.longest / 3600.0, 2.0),
            Ach("ต่อเนื่อง", "เล่นครบ 7 วันใน 90 วันที่ผ่านมา", d.activeDays(90).toDouble(), 7.0),
            Ach("ลื่นระดับ 60", "เฟรมเรตเฉลี่ยถึง 60 fps", d.avgOut, 60.0),
            Ach("ชมรม 120", "เฟรมเรตสูงสุดถึง 120 fps", d.maxOut.toDouble(), 120.0),
            Ach("นักสำรวจ", "เล่นครบ 3 เกมที่ต่างกัน", games.toDouble(), 3.0),
        )
        val done = items.count { it.cur >= it.goal }
        val card = kit.card("ความสำเร็จ", Palette.TERTIARY)
        card.add(hint(I18n.f("ปลดล็อกแล้ว {0}/{1}", done, items.size)), 8)
        items.forEach { a ->
            val ok = a.cur >= a.goal
            card.add(kit.divider(), 14)
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(kit.fitText(a.title, 14.5f, if (ok) Palette.PRIMARY else Palette.TEXT, bold = true))
            txt.addView(hint(a.desc), LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(3) })
            r.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(
                if (ok) kit.icon(R.drawable.fluxa_ic_tick, 20, Palette.OK)
                else kit.text("${num(min(a.cur, a.goal))}/${a.goal.toInt()}", 13f, Palette.MUTED, bold = true),
            )
            card.add(r, 14)
            card.addView(progress((a.cur / a.goal).toFloat(), if (ok) Palette.OK else Palette.PRIMARY), LinearLayout.LayoutParams(-1, kit.dp(5)).apply { topMargin = kit.dp(10) })
        }
        return listOf(card)
    }

    private companion object {
        const val REQ_AVATAR = 21
        const val REQ_BANNER = 22
    }
}
