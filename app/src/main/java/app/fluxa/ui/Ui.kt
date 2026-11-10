package app.fluxa

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Shared design tokens for the main app, settings, and in-game drawer. Neutral high-contrast
 * surfaces keep controls readable while the orange accent marks selected options and key actions.
 */
object Palette {
    // Every value below is rebuilt by [apply] from the user's theme choices (AppSettings).
    var BG = 0xFF0B0807.toInt()
    var GLOW_A = 0xFFFF8A1F.toInt()
    var GLOW_B = 0xFFFF5A1F.toInt()
    /** Strength of the decorative glow behind the pages (0 unless Blur is on). */
    var GLOW_SCALE = 0f

    var SURFACE = 0xFF15100D.toInt()
    var SURFACE_HI = 0xFF1B2330.toInt()
    var STROKE = 0xFF2B3543.toInt()
    var STROKE_SOFT = 0xFF252E3A.toInt()
    /** Recessed track behind segmented controls. */
    var TRACK = 0xFF0F141B.toInt()
    /** Unselected chip in a wrapped grid. */
    var CHIP = 0xFF111720.toInt()
    var CHIP_HI = 0xFF222B37.toInt()
    var ROW = 0xFF171E28.toInt()
    var HERO = 0xFF191F29.toInt()
    var SLIDER_TRACK = 0x26FFFFFF
    var DIVIDER = 0x22FFFFFF
    var LIGHT = false

    /** Opaque equivalents for panels drawn over a game. */
    var PANEL = 0xF20A0D12.toInt()
    var PANEL_CARD = 0xFF15100D.toInt()
    var PANEL_STROKE = 0xFF2B3543.toInt()

    /** Floating tab bar. */
    var TAB_TOP = 0xFF121821.toInt()
    var TAB_BOTTOM = 0xFF121821.toInt()

    var TEXT = 0xFFF3F6FB.toInt()
    var MUTED = 0xFFAAB4C2.toInt()
    var DIM = 0xFF7F8A9B.toInt()

    var PRIMARY = 0xFFFF9638.toInt()
    var SECONDARY = 0xFFFF713D.toInt()
    var TERTIARY = 0xFFFFC05A.toInt()
    var DANGER = 0xFFFF5964.toInt()
    var OK = 0xFF5CF2B0.toInt()
    var WARN = 0xFFFFC857.toInt()
    var OK_SOFT = 0x225CF2B0
    var OK_LINE = 0x555CF2B0
    var WARN_SOFT = 0x22FFC857
    var WARN_LINE = 0x55FFC857

    /** Text colour on top of the solid accent. */
    var ON_ACCENT = 0xFF201307.toInt()

    var PRIMARY_SOFT = 0x24FF9638
    var PRIMARY_FAINT = 0x33FF9638
    var PRIMARY_GLOW = 0x66FF9638
    var PRIMARY_LINE = 0x88FF9638.toInt()
    var DANGER_SOFT = 0x22FF5964
    var DANGER_LINE = 0x66FF5964
    var ACCENT = intArrayOf(0xFFFF9638.toInt(), 0xFFFF713D.toInt())

    /** Changes whenever the palette or a UI setting changes; screens compare it on resume and recreate themselves. */
    var signature = 0
        private set

    fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** (primary, secondary, tertiary) for each accent preset; index 6 = custom hue. */
    val ACCENTS = listOf(
        intArrayOf(0xFFFF7A21.toInt(), 0xFFE8551A.toInt(), 0xFFFFA64D.toInt()), // orange
        intArrayOf(0xFF4DA3FF.toInt(), 0xFF3B7BFF.toInt(), 0xFF7CC4FF.toInt()), // blue
        intArrayOf(0xFFA78BFA.toInt(), 0xFF8B5CF6.toInt(), 0xFFC4B5FD.toInt()), // violet
        intArrayOf(0xFF3DDC97.toInt(), 0xFF22C37A.toInt(), 0xFF8CF0C0.toInt()), // green
        intArrayOf(0xFFFF6FAE.toInt(), 0xFFF0488E.toInt(), 0xFFFFA3CB.toInt()), // pink
        intArrayOf(0xFFFF1F3D.toInt(), 0xFFD1001F.toInt(), 0xFFFF5C70.toInt()), // red (RedMagic)
    )

    fun accentFor(index: Int, hue: Int): IntArray {
        if (index in ACCENTS.indices) return ACCENTS[index]
        val h = hue.coerceIn(0, 360).toFloat()
        fun hsv(hh: Float, s: Float) = Color.HSVToColor(floatArrayOf(((hh % 360f) + 360f) % 360f, s, 1f))
        return intArrayOf(hsv(h, 0.62f), hsv(h - 12f, 0.75f), hsv(h + 14f, 0.5f))
    }

    /** Rebuilds the whole palette from the chosen base (0 dark, 1 AMOLED, 2 light) and accent. */
    fun applyTheme(base: Int, accent: Int, hue: Int, blur: Boolean) {
        LIGHT = base == 2
        when (base) {
            1 -> { // AMOLED: true black
                BG = 0xFF000000.toInt(); SURFACE = 0xFF0D0907.toInt(); SURFACE_HI = 0xFF17100C.toInt()
                STROKE = 0xFF2B1D14.toInt(); STROKE_SOFT = 0xFF1D140E.toInt()
                TRACK = 0xFF070504.toInt(); CHIP = 0xFF0D0907.toInt(); CHIP_HI = 0xFF1D140E.toInt()
                ROW = 0xFF120D0A.toInt(); HERO = 0xFF100B08.toInt()
                SLIDER_TRACK = 0x26FFFFFF; DIVIDER = 0x22FFFFFF
                PANEL = 0xF2000000.toInt(); PANEL_CARD = 0xFF0D0907.toInt(); PANEL_STROKE = 0xFF2B1D14.toInt()
                TAB_TOP = 0xFF0B0806.toInt(); TAB_BOTTOM = TAB_TOP
                TEXT = 0xFFF6F1EC.toInt(); MUTED = 0xFFB8ACA2.toInt(); DIM = 0xFF857A71.toInt()
            }
            3 -> { // RedMagic "Game Space": carbon black, hard edges, red accent
                BG = 0xFF050507.toInt(); SURFACE = 0xFF0C0C0F.toInt(); SURFACE_HI = 0xFF17171C.toInt()
                STROKE = 0xFF2C2C34.toInt(); STROKE_SOFT = 0xFF1E1E25.toInt()
                TRACK = 0xFF08080A.toInt(); CHIP = 0xFF0F0F13.toInt(); CHIP_HI = 0xFF202028.toInt()
                ROW = 0xFF121217.toInt(); HERO = 0xFF15151B.toInt()
                SLIDER_TRACK = 0x26FFFFFF; DIVIDER = 0x22FFFFFF
                PANEL = 0xF2050507.toInt(); PANEL_CARD = 0xFF0C0C0F.toInt(); PANEL_STROKE = 0xFF2C2C34.toInt()
                TAB_TOP = 0xFF09090C.toInt(); TAB_BOTTOM = TAB_TOP
                TEXT = 0xFFFFFFFF.toInt(); MUTED = 0xFFA4A4AE.toInt(); DIM = 0xFF71717B.toInt()
            }
            2 -> { // light
                BG = 0xFFF2F4F8.toInt(); SURFACE = 0xFFFFFFFF.toInt(); SURFACE_HI = 0xFFEDF0F5.toInt()
                STROKE = 0xFFD3D9E3.toInt(); STROKE_SOFT = 0xFFE2E7EF.toInt()
                TRACK = 0xFFE6EAF1.toInt(); CHIP = 0xFFF0F3F8.toInt(); CHIP_HI = 0xFFE9EDF4.toInt()
                ROW = 0xFFF4F6FA.toInt(); HERO = 0xFFFFFFFF.toInt()
                SLIDER_TRACK = 0x22000000; DIVIDER = 0x1A000000
                PANEL = 0xF2F2F4F8.toInt(); PANEL_CARD = 0xFFFFFFFF.toInt(); PANEL_STROKE = 0xFFD3D9E3.toInt()
                TAB_TOP = 0xFFFFFFFF.toInt(); TAB_BOTTOM = TAB_TOP
                TEXT = 0xFF11151B.toInt(); MUTED = 0xFF4A5565.toInt(); DIM = 0xFF768091.toInt()
            }
            else -> { // dark (default)
                BG = 0xFF0B0807.toInt(); SURFACE = 0xFF15100D.toInt(); SURFACE_HI = 0xFF211914.toInt()
                STROKE = 0xFF3A2619.toInt(); STROKE_SOFT = 0xFF2A1C13.toInt()
                TRACK = 0xFF0F0B09.toInt(); CHIP = 0xFF120D0A.toInt(); CHIP_HI = 0xFF271D16.toInt()
                ROW = 0xFF19130F.toInt(); HERO = 0xFF1B140F.toInt()
                SLIDER_TRACK = 0x26FFFFFF; DIVIDER = 0x22FFFFFF
                PANEL = 0xF20B0807.toInt(); PANEL_CARD = 0xFF15100D.toInt(); PANEL_STROKE = 0xFF3A2619.toInt()
                TAB_TOP = 0xFF16110E.toInt(); TAB_BOTTOM = TAB_TOP
                TEXT = 0xFFF6F1EC.toInt(); MUTED = 0xFFB8ACA2.toInt(); DIM = 0xFF857A71.toInt()
            }
        }
        val c = accentFor(accent, hue)
        PRIMARY = c[0]; SECONDARY = c[1]; TERTIARY = c[2]
        GLOW_A = c[0]; GLOW_B = c[1]
        GLOW_SCALE = if (blur) 1f else 0f
        PRIMARY_SOFT = a(c[0], 0x24); PRIMARY_FAINT = a(c[0], 0x33)
        PRIMARY_GLOW = a(c[0], 0x66); PRIMARY_LINE = a(c[0], 0x88)
        ACCENT = intArrayOf(c[0], c[1])
        ON_ACCENT = if (base == 3) 0xFFFFFFFF.toInt() else 0xFF14100A.toInt()
        // status colours: slightly deeper on a light background so they stay readable
        if (LIGHT) {
            OK = 0xFF0E9F6E.toInt(); WARN = 0xFFB7791F.toInt(); DANGER = 0xFFD92D3A.toInt()
        } else {
            OK = 0xFF5CF2B0.toInt(); WARN = 0xFFFFC857.toInt(); DANGER = 0xFFFF5964.toInt()
            // the accent is already red, so "hot" readings switch to amber to stay distinguishable
            if (base == 3) DANGER = 0xFFFF9F1C.toInt()
        }
        OK_SOFT = a(OK, 0x22); OK_LINE = a(OK, 0x55)
        WARN_SOFT = a(WARN, 0x22); WARN_LINE = a(WARN, 0x55)
        DANGER_SOFT = a(DANGER, 0x22); DANGER_LINE = a(DANGER, 0x66)
    }

    /** Reads the saved UI settings, rebuilds the palette and refreshes [signature]. Every Kit does this first. */
    fun sync(ctx: Context) {
        AppSettings.load(ctx)
        signature = AppSettings.signature()
    }
}

/** True when the window is wider than it is tall (phone turned sideways, or a wide tablet / foldable window). */
fun Context.isLandscapeWindow(): Boolean {
    val c = resources.configuration
    return c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
        c.screenWidthDp > c.screenHeightDp
}

/** Width in px for a dialog: [fraction] of the screen but never wider than [maxDp] (keeps dialogs readable in landscape). */
fun Context.dialogWidth(fraction: Float = 0.9f, maxDp: Int = 480): Int {
    val d = resources.displayMetrics
    return minOf((d.widthPixels * fraction).toInt(), (maxDp * d.density).toInt())
}

/** Height in px for a dialog that fills part of the screen, kept inside the (short) landscape window. */
fun Context.dialogHeight(fraction: Float = 0.78f): Int =
    (resources.displayMetrics.heightPixels * fraction).toInt()

/**
 * ScrollView whose content is at most [maxDp] wide and centred. On a portrait phone nothing changes;
 * in landscape / on tablets the content no longer stretches edge to edge.
 */
class CenteredScrollView(ctx: Context, private val maxDp: Int = 680) : ScrollView(ctx) {
    private val maxPx by lazy(LazyThreadSafetyMode.NONE) { (maxDp * resources.displayMetrics.density).toInt() }

    private fun capped(spec: Int): Int {
        val w = View.MeasureSpec.getSize(spec)
        return if (w > maxPx) View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.EXACTLY) else spec
    }

    private fun center(child: View) {
        val lp = child.layoutParams as? FrameLayout.LayoutParams ?: return
        val g = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        if (lp.gravity != g) lp.gravity = g // only the first pass changes anything; later measures are no-ops
    }

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        center(child)
        super.measureChild(child, capped(parentWidthMeasureSpec), parentHeightMeasureSpec)
    }

    override fun measureChildWithMargins(
        child: View, parentWidthMeasureSpec: Int, widthUsed: Int, parentHeightMeasureSpec: Int, heightUsed: Int,
    ) {
        center(child)
        super.measureChildWithMargins(child, capped(parentWidthMeasureSpec), widthUsed, parentHeightMeasureSpec, heightUsed)
    }
}

/**
 * ScrollView that wraps its content but never grows taller than [maxPx]: dialogs keep their title and
 * close button on screen in a short landscape window instead of running off the bottom.
 */
class MaxHeightScrollView(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = View.MeasureSpec.makeMeasureSpec(maxPx.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, limit)
    }
}

/** Height in px that a dialog's scrolling body may use: the window minus room for its title, buttons and the screen gap. */
fun Context.dialogBodyMax(reserveDp: Int = 150): Int {
    val d = resources.displayMetrics
    return (d.heightPixels * 0.92f - reserveDp * d.density).toInt().coerceAtLeast((120 * d.density).toInt())
}

/**
 * Vertical stack of cards that becomes two balanced columns when the window is wide enough (landscape / tablet)
 * and stays a plain single column otherwise. Children marked with [spanFull] (titles, search bars, the agree
 * card ...) always take the whole width; the rest go into whichever column is currently shorter, and are
 * measured at the real column width. Margins work like in a vertical LinearLayout.
 */
class ResponsiveColumns(
    ctx: Context,
    private val minColPx: Int = (340 * ctx.resources.displayMetrics.density).toInt(),
    private val gapPx: Int = (12 * ctx.resources.displayMetrics.density).toInt(),
) : ViewGroup(ctx) {
    private var xs = IntArray(0)
    private var ys = IntArray(0)
    private var ws = IntArray(0)
    private var hs = IntArray(0)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is ViewGroup.MarginLayoutParams
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams =
        if (p == null) generateDefaultLayoutParams() else ViewGroup.MarginLayoutParams(p)
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): ViewGroup.LayoutParams = ViewGroup.MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val inner = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        // 1 column on phones held upright, 2 on a landscape phone / small tablet, 3 on a big tablet or foldable
        val cols = when {
            inner >= 3 * minColPx + 2 * gapPx -> 3
            inner >= 2 * minColPx + gapPx -> 2
            else -> 1
        }
        val colW = if (cols > 1) (inner - (cols - 1) * gapPx) / cols else inner
        val n = childCount
        if (xs.size != n) { xs = IntArray(n); ys = IntArray(n); ws = IntArray(n); hs = IntArray(n) }
        val colBottom = IntArray(cols) { paddingTop }
        for (i in 0 until n) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) { ws[i] = 0; hs[i] = 0; continue }
            val lp = c.layoutParams as ViewGroup.MarginLayoutParams
            val full = cols == 1 || c.tag == FULL
            val slotW = if (full) inner else colW
            val avail = (slotW - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
            val wSpec = when {
                lp.width >= 0 -> View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY)
                lp.width == ViewGroup.LayoutParams.WRAP_CONTENT -> View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.AT_MOST)
                else -> View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.EXACTLY)
            }
            val hSpec = if (lp.height >= 0) View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
                else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            c.measure(wSpec, hSpec)
            val mw = c.measuredWidth
            val mh = c.measuredHeight
            if (full) {
                val y = (colBottom.maxOrNull() ?: paddingTop) + lp.topMargin
                xs[i] = paddingLeft + lp.leftMargin; ys[i] = y; ws[i] = mw; hs[i] = mh
                val b = y + mh + lp.bottomMargin
                for (k in 0 until cols) colBottom[k] = b
            } else {
                var col = 0
                for (k in 1 until cols) if (colBottom[k] < colBottom[col]) col = k
                val y = colBottom[col] + lp.topMargin
                xs[i] = paddingLeft + col * (colW + gapPx) + lp.leftMargin; ys[i] = y; ws[i] = mw; hs[i] = mh
                colBottom[col] = y + mh + lp.bottomMargin
            }
        }
        val h = (colBottom.maxOrNull() ?: paddingTop) + paddingBottom
        setMeasuredDimension(w, View.resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE || i >= xs.size) continue
            c.layout(xs[i], ys[i], xs[i] + ws[i], ys[i] + hs[i])
        }
    }

    companion object {
        const val FULL = "span_full"
    }
}

/** Marks a child of [ResponsiveColumns] as always full width. */
fun <T : View> T.spanFull(): T { tag = ResponsiveColumns.FULL; return this }

/** Width a page's scrolling content may use: wider in landscape so [ResponsiveColumns] can split into two columns. */
fun Context.pageMaxDp(): Int = when (widthClass()) {
    WidthClass.COMPACT -> 680
    WidthClass.MEDIUM -> 840
    WidthClass.EXPANDED -> 1240
}

/** Window width buckets (same breakpoints as Material): phone upright / small tablet / landscape phone, tablet, foldable. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

fun Context.widthClass(): WidthClass {
    val w = resources.configuration.screenWidthDp
    return if (w < 600) WidthClass.COMPACT else if (w < 840) WidthClass.MEDIUM else WidthClass.EXPANDED
}

/** Short window (a phone held sideways): vertical space is the scarce thing, so screens use tighter headers and docks. */
fun Context.isShortWindow(): Boolean = resources.configuration.screenHeightDp < 480

/** Small view-building kit shared by the launcher screen, the compare screen and the in-game drawer. */
class Kit(val ctx: Context) {
    init {
        Palette.sync(ctx) // every screen / overlay builds a Kit first, so they all pick up the current app theme
    }

    private val density = ctx.resources.displayMetrics.density
    /** Window is wider than tall: screens switch to compact / multi-column layouts. */
    val landscape: Boolean by lazy(LazyThreadSafetyMode.NONE) { ctx.isLandscapeWindow() }
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = v * density

    /**
     * Keeps a label on one line inside whatever width its parent gives it: the text shrinks (down to [minSp])
     * instead of wrapping, being cut off or pushing its neighbours when the language, font size or value changes.
     */
    fun <T : TextView> fit(tv: T, maxSp: Float, minSp: Float = maxSp * 0.5f, lines: Int = 1): T = tv.apply {
        maxLines = lines
        ellipsize = android.text.TextUtils.TruncateAt.END
        val sc = AppSettings.textScale
        setAutoSizeTextTypeUniformWithConfiguration(
            (minSp * sc).toInt().coerceAtLeast(6), (maxSp * sc).toInt().coerceAtLeast(7), 1,
            TypedValue.COMPLEX_UNIT_SP,
        )
    }
    /** Corner radius in px, scaled by the "corner roundness" setting. */
    private fun rad(v: Int) =
        if (rm) (if (v >= 16) 4 else 2) * density * AppSettings.radiusScale else v * density * AppSettings.radiusScale

    /** RedMagic "Game Space" look: hard corners, chamfered cards, red hairlines. */
    val rm: Boolean get() = AppSettings.game

    /** Chamfer size in px for a card of nominal radius [radiusDp]. */
    private fun cutOf(radiusDp: Int) =
        (radiusDp * 0.42f).coerceIn(5f, 13f) * density * AppSettings.radiusScale.coerceAtMost(1.3f)

    fun text(
        value: String,
        sizeSp: Float,
        color: Int = Palette.TEXT,
        bold: Boolean = false,
        spacing: Float = 0f,
    ) = TrTextView(ctx).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * AppSettings.textScale)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, 600, false) // semibold, not heavy
        if (spacing != 0f) letterSpacing = spacing
        includeFontPadding = false
    }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = rad(radiusDp)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    fun gradient(
        colors: IntArray,
        radiusDp: Int,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT,
        strokeColor: Int = 0,
        strokeDp: Int = 0,
    ) = GradientDrawable(orientation, colors).apply {
        cornerRadius = rad(radiusDp)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    /** Card surface: soft top-left highlight into the base colour, with an accent-tinted hairline (dark themes). */
    fun glass(radiusDp: Int = 22, hi: Boolean = false): Drawable {
        // minimal: one flat fill (no highlight gradient) and a quiet hairline
        val fill = if (hi) Palette.CHIP_HI else Palette.SURFACE
        if (rm) {
            // chamfered plate; short corner ticks instead of long brackets
            return CutDrawable(fill, fill, Palette.STROKE, Palette.PRIMARY, dpf(1), minOf(cutOf(radiusDp), dpf(7)), dpf(6))
        }
        // with Blur on the cards turn translucent so the blurred glow shows through them
        val alpha = if (AppSettings.blur) 0xCC else 0xFF
        return gradient(
            intArrayOf(Palette.a(fill, alpha), Palette.a(fill, alpha)),
            minOf(radiusDp, 14), GradientDrawable.Orientation.TL_BR, Palette.STROKE, 1,
        )
    }

    /** Selected / "on" state: tinted accent plate (chamfered in the game look). */
    fun lit(radiusDp: Int = 16): Drawable =
        if (rm) CutDrawable(Palette.a(Palette.PRIMARY, 0x40), Palette.a(Palette.SECONDARY, 0x1A), Palette.PRIMARY_LINE, Palette.PRIMARY, dpf(1), cutOf(radiusDp), dpf(10))
        else rounded(Palette.PRIMARY_SOFT, radiusDp, Palette.PRIMARY_LINE, 1)

    /** Solid red call-to-action plate in the game look; the normal tinted plate otherwise. */
    fun hot(radiusDp: Int = 16): Drawable =
        if (rm) CutDrawable(Palette.PRIMARY, Palette.SECONDARY, Palette.PRIMARY, 0xFFFFFFFF.toInt(), dpf(1), cutOf(radiusDp), dpf(10))
        else rounded(Palette.PRIMARY_SOFT, radiusDp, Palette.PRIMARY_LINE, 1)

    /** Text colour that reads on [hot]. */
    val onHot: Int get() = if (rm) Palette.ON_ACCENT else Palette.PRIMARY

    /** Opaque card for panels shown over a game. */
    fun panelCard(radiusDp: Int = 18): Drawable =
        if (rm) CutDrawable(Palette.PANEL_CARD, Palette.PANEL_CARD, Palette.PANEL_STROKE, Palette.PRIMARY, dpf(1), cutOf(radiusDp), dpf(10))
        else rounded(Palette.PANEL_CARD, radiusDp, Palette.PANEL_STROKE, 1)

    /** Ready-made vector icon (res/drawable fluxa_ic_*) tinted with [color]. */
    fun icon(res: Int, sizeDp: Int, color: Int = Palette.TEXT) = android.widget.ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        layoutParams = android.view.ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Status text (values, labels, chips): same as [text] but shrinks to stay inside its spot instead of overflowing. */
    fun fitText(value: String, sizeSp: Float, color: Int = Palette.TEXT, bold: Boolean = false, spacing: Float = 0f, lines: Int = 1) =
        fit(text(value, sizeSp, color, bold, spacing), sizeSp, sizeSp * 0.5f, lines)

    /** [display] that shrinks to fit its spot. */
    fun fitDisplay(value: String, sizeSp: Float, color: Int = Palette.TEXT) =
        fit(display(value, sizeSp, color), sizeSp, sizeSp * 0.5f)

    /** Number / headline text: condensed and slanted in the game look. */
    fun display(value: String, sizeSp: Float, color: Int = Palette.TEXT) =
        text(value, sizeSp, color, bold = true).apply {
            if (rm) {
                typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 700, false)
                paint.textSkewX = -0.2f
            }
        }

    /** Adds the section marker to a title row: a red bar normally, a red double slash in the game look. */
    fun addMark(row: LinearLayout, heightDp: Int, endMarginDp: Int, color: Int = Palette.PRIMARY) {
        if (rm) {
            row.addView(SlashMark(ctx, color), LinearLayout.LayoutParams(dp(14), dp(heightDp)).apply { marginEnd = dp(endMarginDp + 2) })
        } else {
            row.addView(View(ctx).apply { background = rounded(color, 2) }, LinearLayout.LayoutParams(dp(3), dp(heightDp)).apply { marginEnd = dp(endMarginDp) })
        }
    }

    /** Quiet secondary button background on a raised solid surface. */
    fun glassBtn(radiusDp: Int = 16) = glass(radiusDp, hi = true)

    /** Solid accent fill for the main call to action. */
    fun accentFill(radiusDp: Int = 18) = rounded(Palette.PRIMARY, radiusDp)

    /**
     * Surface card with a title and a small accent pip; children go into the returned box.
     * [accent] colours the pip so each card reads as its own section.
     */
    fun card(title: String? = null, accent: Int = Palette.PRIMARY): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(24)
            setPadding(dp(14), dp(11), dp(14), dp(12))
            if (title != null) {
                val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(text(title, 14f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(head, LinearLayout.LayoutParams(-1, -2))
            }
        }

    /** 1 dp hairline between rows. */
    fun divider() = View(ctx).apply { setBackgroundColor(Palette.STROKE_SOFT) }

    /**
     * Setting label. "Title · explanation" is split into a primary line and a quieter description
     * (a " · " inside brackets is not a split point).
     */
    fun caption(value: String): View {
        val i = value.indexOf(" · ")
        if (i > 0) {
            val head = value.substring(0, i)
            if (head.count { it == '(' } == head.count { it == ')' }) {
                return caption(head, value.substring(i + 3))
            }
        }
        return caption(value, null)
    }

    /** Group header inside a card: small accent label + hairline, so long cards read as clear sections. */
    fun section(label: String, first: Boolean = false): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, if (first) dp(8) else dp(14), 0, 0)
        if (!first) addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(8) })
        addView(text(label, 11f, Palette.PRIMARY, bold = true, spacing = 0.06f))
    }


    fun caption(title: String, desc: String?): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        addView(text(title, 13.5f, Palette.TEXT, bold = true))
        if (!desc.isNullOrEmpty()) {
            addView(
                text(desc, 11.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.12f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
            )
        }
    }
}

/**
 * One-tap on/off switch used by every settings page: accent track + round thumb. [animate] = false for the
 * in-game panel, which must not spend any GPU time over a running game.
 */
class FlatSwitch(
    context: Context,
    on: Boolean,
    private val animate: Boolean = true,
    private val onChange: (Boolean) -> Unit,
) : View(context) {
    var checked = on
        private set
    private var pos = if (on) 1f else 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mix = android.animation.ArgbEvaluator()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    /** Sets the state without calling [onChange]. */
    fun set(value: Boolean) {
        if (value == checked) return
        checked = value
        pos = if (value) 1f else 0f
        invalidate()
    }

    fun toggle() {
        checked = !checked
        val to = if (checked) 1f else 0f
        if (animate) {
            ValueAnimator.ofFloat(pos, to).apply {
                duration = 140
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            pos = to
            invalidate()
        }
        onChange(checked)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = resources.displayMetrics.density
        paint.style = Paint.Style.FILL
        paint.color = mix.evaluate(pos, Palette.CHIP_HI, Palette.PRIMARY_SOFT) as Int
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s
        paint.color = mix.evaluate(pos, Palette.STROKE, Palette.PRIMARY_LINE) as Int
        c.drawRoundRect(s / 2, s / 2, w - s / 2, h - s / 2, h / 2, h / 2, paint)
        paint.style = Paint.Style.FILL
        paint.color = mix.evaluate(pos, Palette.MUTED, Palette.PRIMARY) as Int
        val r = h / 2 - 4 * s
        c.drawCircle(h / 2 + (w - h) * pos, h / 2, r, paint)
    }
}

/**
 * Page background. Flat colour by default; with Blur on it also paints two big accent glows that
 * are blurred by the GPU (Android 12+) or drawn as soft radial gradients on older versions.
 */
class AuroraBackground(context: Context) : View(context) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val useBlur = Palette.GLOW_SCALE > 0f

    init {
        setBackgroundColor(Palette.BG)
        isFocusable = false
        if (useBlur && android.os.Build.VERSION.SDK_INT >= 31) {
            setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP),
            )
        }
    }

    private val game = AppSettings.game
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
        color = 0x0BFFFFFF
    }

    /** Game look: red light spilling from the top-right corner, a dim ember bottom-left, and a faint carbon hatch. */
    private fun drawGame(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = context.resources.displayMetrics.density
        glow.shader = RadialGradient(w, 0f, w * 1.05f, Palette.a(Palette.PRIMARY, 0x4A), Palette.a(Palette.PRIMARY, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, glow)
        glow.shader = RadialGradient(0f, h, w * 0.9f, Palette.a(Palette.SECONDARY, 0x2C), Palette.a(Palette.SECONDARY, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, glow)
        glow.shader = null
        val gap = 14f * d
        var x = -h
        while (x < w) {
            c.drawLine(x, h, x + h, 0f, hatch)
            x += gap
        }
    }

    override fun onDraw(c: Canvas) {
        if (game) { drawGame(c); return }
        if (!useBlur) return
        val w = width.toFloat()
        val h = height.toFloat()
        val strength = if (Palette.LIGHT) 0x55 else 0x80
        blob(c, w * 0.12f, h * 0.10f, w * 0.55f, Palette.GLOW_A, strength)
        blob(c, w * 0.95f, h * 0.78f, w * 0.65f, Palette.GLOW_B, strength)
    }

    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            glow.shader = null
            glow.color = Palette.a(color, alpha)
        } else {
            glow.shader = android.graphics.RadialGradient(
                x, y, r, Palette.a(color, alpha), Palette.a(color, 0), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(x, y, r * 0.8f, glow)
    }
}

/**
 * The highlight behind a selected segment / tab. [moveTo] jumps straight there. [flowTo] is the tab
 * bar's drop-of-water motion, kept GPU-cheap: only the two edges are animated (the leading edge runs
 * ahead and overshoots, the trailing edge follows, so the pill stretches), and it is drawn as plain
 * rounded rects, with no paths and no extra layers while it moves.
 */
class LiquidPill(context: Context, private val radius: Float, private val inset: Float = 0f) : View(context) {
    private var left = 0f
    private var right = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
        color = Palette.PRIMARY_LINE
    }
    private val rect = RectF()
    private val slant = Path()
    private val game = AppSettings.game
    private var placed = false
    private var flow: ValueAnimator? = null
    private val lead = OvershootInterpolator(1.25f)
    private val trail = DecelerateInterpolator(1.6f)

    /**
     * No intrinsic size: the pill only ever fills the track it sits in. Without this a
     * wrap_content parent (the floating tab bar) would size itself to the whole screen, because the
     * default View measuring takes all the space offered in AT_MOST mode.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Palette.a(Palette.PRIMARY, 0x47), Palette.a(Palette.SECONDARY, 0x47)), null, Shader.TileMode.CLAMP,
        )
        gloss.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x44FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
    }

    /** Where the pill should rest ([l]..[r] in this view's own coordinates), with no motion. */
    fun moveTo(l: Float, r: Float) {
        flow?.cancel()
        flow = null
        left = l
        right = r
        placed = true
        invalidate()
    }

    /** Glides to [l]..[r] (falls back to [moveTo] when Icon animation is off). */
    fun flowTo(l: Float, r: Float) {
        if (!placed || !AppSettings.iconAnim || !isAttachedToWindow || (l == right && r == left)) {
            moveTo(l, r)
            return
        }
        val fl = left
        val fr = right
        flow?.cancel()
        val rightward = l > fl
        flow = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val a = lead.getInterpolation((t / 0.7f).coerceAtMost(1f))
                val b = trail.getInterpolation(((t - 0.12f) / 0.88f).coerceIn(0f, 1f))
                if (rightward) {
                    right = fr + (r - fr) * a
                    left = fl + (l - fl) * b
                } else {
                    left = fl + (l - fl) * a
                    right = fr + (r - fr) * b
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && flow === animation) {
                        left = l
                        right = r
                        flow = null
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        flow?.cancel()
        flow = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (!placed || right <= left) return
        rect.set(left, inset, right, height - inset)
        if (game) {
            // slanted plate with a hard red underline, like the game-space tab marker
            val sk = rect.height() * 0.22f
            slant.reset()
            slant.moveTo(rect.left + sk, rect.top); slant.lineTo(rect.right, rect.top)
            slant.lineTo(rect.right - sk, rect.bottom); slant.lineTo(rect.left, rect.bottom); slant.close()
            c.drawPath(slant, fill)
            c.drawPath(slant, line)
            gloss.shader = null
            gloss.color = Palette.PRIMARY
            gloss.strokeWidth = 2f * context.resources.displayMetrics.density
            c.drawLine(rect.left + sk + 3f, rect.bottom - 1f, rect.right - sk * 0.2f, rect.bottom - 1f, gloss)
            return
        }
        c.drawRoundRect(rect, radius, radius, fill)
        if (flow == null) c.drawRoundRect(rect, radius, radius, gloss)
        rect.inset(0.5f, 0.5f)
        c.drawRoundRect(rect, radius, radius, line)
    }

}

/**
 * Segmented control. A single row gets a [LiquidPill] behind the selected value; with
 * [perRow] < labels.size the choices wrap into a grid and the selected chip is tinted instead.
 * [onPick] returns false to reject a selection (the previous one stays highlighted).
 */
class Segmented(
    private val kit: Kit,
    labels: List<String>,
    selected: Int,
    private val perRow: Int = labels.size,
    private val textSp: Float = 14f,
    private val onPick: (Int) -> Boolean,
) : LinearLayout(kit.ctx) {
    private val chips = ArrayList<TextView>()
    private val sliding = perRow >= labels.size
    private var pill: LiquidPill? = null
    private var row: LinearLayout? = null
    var selected = selected
        private set

    init {
        orientation = VERTICAL
        if (sliding) {
            // one solid track, one selected pill, and the labels on top
            val track = FrameLayout(kit.ctx).apply { background = kit.rounded(Palette.TRACK, 16, Palette.STROKE_SOFT, 1) }
            val p = LiquidPill(kit.ctx, kit.dpf(13), kit.dpf(4))
            pill = p
            track.addView(p, FrameLayout.LayoutParams(-1, -1))
            val r = LinearLayout(kit.ctx).apply { setPadding(kit.dp(3), kit.dp(3), kit.dp(3), kit.dp(3)) }
            row = r
            labels.forEachIndexed { i, label ->
                val chip = kit.fit(kit.text(label, textSp, bold = true), textSp, textSp * 0.62f).apply {
                    gravity = Gravity.CENTER
                    setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(8))
                    setOnClickListener { pick(i) }
                }
                chips.add(chip)
                r.addView(chip, LayoutParams(0, -2, 1f))
            }
            track.addView(r, FrameLayout.LayoutParams(-1, -2))
            addView(track, LayoutParams(-1, -2))
            r.addOnLayoutChangeListener { _, l, _, rr, _, _, _, _, _ -> if (rr - l > 0) movePill() }
        } else {
            var rowView: LinearLayout? = null
            labels.forEachIndexed { i, label ->
                if (i % perRow == 0) {
                    rowView = LinearLayout(kit.ctx)
                    addView(rowView, LayoutParams(-1, -2).apply { if (i > 0) topMargin = kit.dp(8) })
                }
                val chip = kit.fit(kit.text(label, textSp, bold = true), textSp, textSp * 0.62f).apply {
                    gravity = Gravity.CENTER
                    setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(8))
                    setOnClickListener { pick(i) }
                }
                chips.add(chip)
                rowView?.addView(chip, LayoutParams(0, -2, 1f).apply { if (i % perRow > 0) marginStart = kit.dp(8) })
            }
            val rem = labels.size % perRow
            if (rem != 0) for (k in rem until perRow) {
                rowView?.addView(View(kit.ctx), LayoutParams(0, 1, 1f).apply { marginStart = kit.dp(8) })
            }
        }
        paint()
    }

    private fun movePill() {
        val r = row ?: return
        val chip = chips.getOrNull(selected) ?: return
        if (chip.width <= 0) return
        val l = r.left + chip.left.toFloat()
        pill?.moveTo(l, l + chip.width)
    }

    private fun pick(i: Int) {
        if (i == selected) return
        val prev = selected
        selected = i
        if (onPick(i)) paint() else selected = prev
    }

    /** Programmatic selection: repaints without calling [onPick]. */
    fun select(i: Int) {
        selected = i.coerceIn(0, chips.size - 1)
        paint()
    }

    private fun paint() {
        chips.forEachIndexed { i, c ->
            val on = i == selected
            c.setTextColor(if (on) Palette.TEXT else Palette.MUTED)
            if (!sliding) {
                c.background =
                    if (on) kit.lit(14)
                    else kit.rounded(Palette.CHIP, 14, Palette.STROKE_SOFT, 1)
            }
        }
        if (sliding) movePill()
    }
}

/**
 * Slider with a live value chip. Values run [min]..[max] in [step] increments.
 * [onPreview] fires while dragging (cheap UI only); [onCommit] fires once when the
 * finger lifts (or on a non-touch change) so heavy work like reconfiguring runs once.
 */
class Slider(
    private val kit: Kit,
    private val min: Int,
    private val max: Int,
    value: Int,
    private val step: Int = 1,
    private val format: (Int) -> String = { it.toString() },
    private val onPreview: (Int) -> Unit = {},
    /** Optional left-hand label on the value line (the in-game drawer uses it instead of a caption above). */
    private val title: String? = null,
    /** Hide the min / max labels under the track (compact layouts). */
    private val showRange: Boolean = true,
    /** True while a finger is on the thumb (the drawer fades out so the effect is visible live). */
    private val onDrag: (Boolean) -> Unit = {},
    private val onCommit: (Int) -> Unit,
) : LinearLayout(kit.ctx) {
    private val label = kit.text(format(value), 13f, Palette.PRIMARY, bold = true).apply {
        fontFeatureSettings = "tnum"
        gravity = Gravity.CENTER
        minWidth = kit.dp(64) // the chip keeps its width while the number changes
        setPadding(kit.dp(8), kit.dp(3), kit.dp(8), kit.dp(3))
        background = kit.rounded(Palette.PRIMARY_SOFT, 10, Palette.PRIMARY_FAINT, 1)
    }
    private val bar = SeekBar(kit.ctx)
    private var dragging = false

    var value: Int = value
        private set

    init {
        orientation = VERTICAL
        // live value chip on the right, range ends under the track
        val head = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        if (title != null) head.addView(kit.text(title, 13.5f, Palette.TEXT, bold = true), LayoutParams(0, -2, 1f))
        else head.addView(View(kit.ctx), LayoutParams(0, 1, 1f))
        head.addView(label)
        addView(head, LayoutParams(-1, -2))

        val track = kit.rounded(Palette.SLIDER_TRACK, 4).apply { setSize(0, kit.dp(6)) }
        val fill = ClipDrawable(kit.gradient(Palette.ACCENT, 4).apply { setSize(0, kit.dp(6)) }, Gravity.START, ClipDrawable.HORIZONTAL)
        bar.progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        if (kit.rm) {
            // game look: a hard red block thumb with a white edge
            val halo = GradientDrawable().apply {
                setColor(0x00000000)
                setSize(kit.dp(32), kit.dp(32))
            }
            val block = GradientDrawable().apply {
                setColor(Palette.PRIMARY)
                cornerRadius = kit.dpf(2)
                setStroke(kit.dp(1), Color.WHITE)
                setSize(kit.dp(10), kit.dp(22))
            }
            bar.thumb = LayerDrawable(arrayOf(halo, block)).apply {
                setLayerInset(1, kit.dp(11), kit.dp(5), kit.dp(11), kit.dp(5))
            }
        } else {
            // white bead with a soft aqua halo (32 dp overall, 22 dp bead)
            val halo = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.PRIMARY_FAINT)
                setSize(kit.dp(32), kit.dp(32))
            }
            val bead = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(kit.dp(1), if (Palette.LIGHT) Palette.STROKE else Color.WHITE)
                setSize(kit.dp(22), kit.dp(22))
            }
            bar.thumb = LayerDrawable(arrayOf(halo, bead)).apply {
                val inset = kit.dp(5)
                setLayerInset(1, inset, inset, inset, inset)
            }
        }
        bar.splitTrack = false
        bar.thumbOffset = 0
        bar.setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(10))
        bar.max = (max - min) / step
        bar.progress = (value - min) / step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                this@Slider.value = min + p * step
                label.text = format(this@Slider.value)
                onPreview(this@Slider.value)
                if (!fromUser && !dragging) onCommit(this@Slider.value)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true; onDrag(true) }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                onDrag(false)
                onCommit(this@Slider.value)
            }
        })
        addView(bar, LayoutParams(-1, -2).apply { topMargin = kit.dp(2) })
        val foot = LinearLayout(kit.ctx)
        foot.addView(kit.text(format(min), 11f, Palette.DIM).apply { fontFeatureSettings = "tnum" }, LayoutParams(0, -2, 1f))
        foot.addView(kit.text(format(max), 11f, Palette.DIM).apply { gravity = Gravity.END; fontFeatureSettings = "tnum" }, LayoutParams(0, -2, 1f))
        if (showRange) addView(foot, LayoutParams(-1, -2).apply { marginStart = kit.dp(4); marginEnd = kit.dp(4) })
    }

    /** Greys the slider out and ignores touches (e.g. a setting that cannot change right now). */
    fun setLocked(locked: Boolean) {
        bar.isEnabled = !locked
        alpha = if (locked) 0.4f else 1f
    }

    /** Programmatic set: moves the thumb and label without calling [onCommit]. */
    fun set(v: Int) {
        val c = v.coerceIn(min, max)
        value = c
        label.text = format(c)
        dragging = true
        bar.progress = (c - min) / step
        dragging = false
    }
}


/**
 * Plate with two chamfered corners (top-left and bottom-right), the angular shape used by the game
 * look. The cut edges carry short bright brackets in [accent]; the rest of the outline is [line].
 */
class CutDrawable(
    private val from: Int,
    private val to: Int,
    private val line: Int,
    private val accent: Int,
    private val strokePx: Float,
    private val cutPx: Float,
    private val tickPx: Float,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx; color = line }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx * 1.8f
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        color = accent
    }
    private val body = Path()
    private val brackets = Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val i = strokePx
        val l = i; val t = i; val r = w - i; val bt = h - i
        val cut = cutPx.coerceAtMost(minOf(w, h) / 2.5f)
        val tick = tickPx.coerceAtMost(minOf(w, h) / 2f)
        body.reset()
        body.moveTo(l + cut, t); body.lineTo(r, t); body.lineTo(r, bt - cut)
        body.lineTo(r - cut, bt); body.lineTo(l, bt); body.lineTo(l, t + cut); body.close()
        brackets.reset()
        brackets.moveTo(l, t + cut + tick); brackets.lineTo(l, t + cut); brackets.lineTo(l + cut, t); brackets.lineTo(l + cut + tick, t)
        brackets.moveTo(r, bt - cut - tick); brackets.lineTo(r, bt - cut); brackets.lineTo(r - cut, bt); brackets.lineTo(r - cut - tick, bt)
        fillPaint.shader = LinearGradient(0f, 0f, w, h, from, to, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.drawPath(body, fillPaint)
        c.drawPath(body, linePaint)
        c.drawPath(brackets, accentPaint)
        c.restore()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha; linePaint.alpha = alpha; accentPaint.alpha = alpha
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Two slanted bars (a "//" mark) that replace the round section pip in the game look. */
class SlashMark(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bar = w * 0.30f
        val sk = w * 0.34f
        paint.color = color
        for (k in 0..1) {
            val x = k * (bar + w * 0.10f)
            paint.alpha = if (k == 0) 0xFF else 0x88
            path.reset()
            path.moveTo(x + sk, 0f); path.lineTo(x + sk + bar, 0f)
            path.lineTo(x + bar, h); path.lineTo(x, h); path.close()
            c.drawPath(path, paint)
        }
    }
}
