package app.fluxa

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * iOS-style "frosted glass" for the floating dock (start / stop buttons and the tab bar).
 *
 *  - Blur OFF in Settings: glossy but solid surfaces (cheap, always readable).
 *  - Blur ON: the surfaces turn translucent and show a blurred copy of whatever is scrolling behind them.
 *
 * The blurred copy is a tiny bitmap (1/8 size, box-blurred on the CPU), refreshed only while the
 * content behind the dock moves, so it costs next to nothing and works on every Android version.
 */
object Glass {
    enum class Tone { NEUTRAL, ACCENT, DANGER }

    fun make(kit: Kit, tone: Tone, radiusDp: Int, backdrop: GlassBackdrop?): GlassDrawable =
        GlassDrawable(kit.dpf(radiusDp) * AppSettings.radiusScale, tone, backdrop, kit.dpf(1))
}

/** A shared, blurred snapshot of the content behind the dock. */
class GlassBackdrop {
    var bitmap: Bitmap? = null
        private set

    /** Bumped every time the bitmap content changes (the shaders are rebuilt when it differs). */
    var version = 0
        private set

    /** Dock pixels per bitmap pixel. */
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set

    private var canvas: Canvas? = null
    private var target: View? = null
    private var sources: List<View> = emptyList()
    private val users = ArrayList<View>()
    private val loc = IntArray(2)
    private val tLoc = IntArray(2)
    private var px = IntArray(0)
    private var tmp = IntArray(0)
    private var pending = false
    private var pumpUntil = 0L

    private val capture = Runnable {
        pending = false
        captureNow()
        if (android.os.SystemClock.uptimeMillis() < pumpUntil) request()
    }

    /** [target] is the dock; [sources] are drawn (in order) as the backdrop, e.g. the page glow + the scrolling pages. */
    fun bind(target: View, sources: List<View>) {
        this.target = target
        this.sources = sources
    }

    fun addUser(v: View) {
        if (users.none { it === v }) users.add(v)
    }

    /** Position of [view] inside the dock, in dock pixels. False when [view] is not inside it. */
    fun offsetIn(view: View, out: FloatArray): Boolean {
        val t = target ?: return false
        var x = 0f
        var y = 0f
        var v: View = view
        while (v !== t) {
            x += v.left
            y += v.top
            val p = v.parent as? View ?: return false
            v = p
        }
        out[0] = x
        out[1] = y
        return true
    }

    /** Re-snapshots the backdrop on the next frame (throttled to ~30 per second). */
    fun request() {
        val t = target ?: return
        if (!AppSettings.blur || pending) return
        pending = true
        t.postOnAnimationDelayed(capture, 66)
    }

    /** Keeps refreshing for [ms] (used while a page transition is moving the content). */
    fun pump(ms: Long) {
        pumpUntil = max(pumpUntil, android.os.SystemClock.uptimeMillis() + ms)
        request()
    }

    fun release() {
        target?.removeCallbacks(capture)
        pending = false
        bitmap?.recycle()
        bitmap = null
        canvas = null
        users.clear()
        target = null
        sources = emptyList()
    }

    private fun captureNow() {
        val t = target ?: return
        if (!AppSettings.blur || t.width <= 0 || t.height <= 0 || !t.isAttachedToWindow) return
        val bw = max(8, t.width / STEP)
        val bh = max(4, t.height / STEP)
        var bm = bitmap
        if (bm == null || bm.width != bw || bm.height != bh) {
            bm?.recycle()
            bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap = bm
            canvas = Canvas(bm)
            px = IntArray(bw * bh)
            tmp = IntArray(bw * bh)
        }
        val c = canvas ?: return
        scaleX = t.width.toFloat() / bw
        scaleY = t.height.toFloat() / bh
        c.drawColor(Palette.BG)
        t.getLocationInWindow(tLoc)
        for (s in sources) {
            if (s.width <= 0 || s.height <= 0 || s.visibility != View.VISIBLE) continue
            s.getLocationInWindow(loc)
            c.save()
            c.scale(1f / scaleX, 1f / scaleY)
            c.translate((loc[0] - tLoc[0]).toFloat(), (loc[1] - tLoc[1]).toFloat())
            runCatching { s.draw(c) }
            c.restore()
        }
        bm.getPixels(px, 0, bw, 0, 0, bw, bh)
        boxBlur(px, tmp, bw, bh, 2)
        boxBlur(px, tmp, bw, bh, 2)
        bm.setPixels(px, 0, bw, 0, 0, bw, bh)
        version++
        for (u in users) u.invalidate()
    }

    private fun boxBlur(px: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        // horizontal: px -> out
        for (y in 0 until h) {
            val row = y * w
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val p = px[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; rr += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
            }
            for (x in 0 until w) {
                out[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val add = px[row + min(x + r + 1, w - 1)]
                val sub = px[row + max(x - r, 0)]
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                g += ((add shr 8) and 255) - ((sub shr 8) and 255)
                b += (add and 255) - (sub and 255)
            }
        }
        // vertical: out -> px
        for (x in 0 until w) {
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val p = out[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24; rr += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
            }
            for (y in 0 until h) {
                px[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val add = out[min(y + r + 1, h - 1) * w + x]
                val sub = out[max(y - r, 0) * w + x]
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                g += ((add shr 8) and 255) - ((sub shr 8) and 255)
                b += (add and 255) - (sub and 255)
            }
        }
    }

    private companion object {
        const val STEP = 16
    }
}

/**
 * A rounded glass surface: backdrop blur (Blur on) -> tint -> top gloss -> hairline rim.
 * While the view is pressed the glass turns more transparent and brighter, like iOS.
 */
class GlassDrawable(
    private val radius: Float,
    private val tone: Glass.Tone,
    private val backdrop: GlassBackdrop?,
    private val density: Float,
) : Drawable() {
    private val blurOn = AppSettings.blur
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val rect = RectF()
    private val game = AppSettings.game
    private val shapePath = Path()
    private val rimPath = Path()
    private val bracketPath = Path()
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density) * 1.8f
        strokeCap = Paint.Cap.SQUARE
        color = Palette.PRIMARY
    }
    private val off = FloatArray(2)
    private val m = Matrix()
    private var shader: BitmapShader? = null
    private var shaderBmp: Bitmap? = null
    private var shaderVersion = -1
    private var press = 0f
    private var pressAnim: ValueAnimator? = null

    private fun white(alpha: Int) = Palette.a(0xFFFFFFFF.toInt(), alpha)

    private fun baseColor(): Int = when (tone) {
        Glass.Tone.NEUTRAL -> if (blurOn) 0 else Palette.SURFACE_HI
        Glass.Tone.ACCENT -> if (blurOn) 0 else Palette.PRIMARY
        Glass.Tone.DANGER -> if (blurOn) 0 else Palette.SURFACE
    }

    private fun tintColor(): Int = when (tone) {
        Glass.Tone.NEUTRAL -> if (!blurOn) 0 else if (Palette.LIGHT) white(0x99) else white(0x24)
        Glass.Tone.ACCENT -> if (blurOn) Palette.a(Palette.PRIMARY, 0xD2) else 0
        Glass.Tone.DANGER -> Palette.a(Palette.DANGER, if (blurOn) 0x3A else 0x26)
    }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        if (game) {
            val cut = min(radius * 0.4f, h / 3f)
            val sw = max(1f, density)
            poly(shapePath, 0f, 0f, w, h, cut)
            poly(rimPath, sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f, cut)
            val tk = min(h, w) * 0.45f
            val i = sw
            bracketPath.reset()
            bracketPath.moveTo(i, cut + tk); bracketPath.lineTo(i, cut); bracketPath.lineTo(cut, i); bracketPath.lineTo(cut + tk, i)
            bracketPath.moveTo(w - i, h - cut - tk); bracketPath.lineTo(w - i, h - cut); bracketPath.lineTo(w - cut, h - i); bracketPath.lineTo(w - cut - tk, h - i)
            glossPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(white(if (tone == Glass.Tone.ACCENT) 0x30 else 0x12), white(0x00)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            rimPaint.shader = null
            rimPaint.color = when (tone) {
                Glass.Tone.NEUTRAL -> Palette.STROKE
                Glass.Tone.ACCENT -> white(0x70)
                Glass.Tone.DANGER -> Palette.a(Palette.DANGER, 0xA0)
            }
            bracketPaint.color = if (tone == Glass.Tone.ACCENT) 0xFFFFFFFF.toInt() else if (tone == Glass.Tone.DANGER) Palette.DANGER else Palette.PRIMARY
            return
        }
        val topA = when {
            tone == Glass.Tone.ACCENT -> 0x66
            Palette.LIGHT -> 0x99
            else -> 0x3C
        }
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(white(topA), white(0x0A), white(0x00)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        val hi = if (Palette.LIGHT) 0xE6 else 0xA0
        val rimEnd = if (tone == Glass.Tone.DANGER) Palette.a(Palette.DANGER, 0x80)
        else if (Palette.LIGHT) Palette.STROKE else white(0x50)
        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(white(hi), white(0x10), rimEnd),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun poly(p: Path, l: Float, t: Float, r: Float, b: Float, cut: Float) {
        p.reset()
        p.moveTo(l + cut, t); p.lineTo(r, t); p.lineTo(r, b - cut)
        p.lineTo(r - cut, b); p.lineTo(l, b); p.lineTo(l, t + cut); p.close()
    }

    private fun shape(c: Canvas, p: Paint, r: Float) {
        if (game) c.drawPath(shapePath, p) else c.drawRoundRect(rect, r, r, p)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(radius, min(w, h) / 2f)
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        rect.set(0f, 0f, w, h)

        // 1. blurred backdrop
        val view = callback as? View
        val bd = backdrop
        val bmp = bd?.bitmap
        if (blurOn && bd != null && bmp != null && view != null && bd.offsetIn(view, off)) {
            bd.addUser(view)
            var sh = shader
            if (sh == null || shaderBmp !== bmp || shaderVersion != bd.version) {
                sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader = sh
                shaderBmp = bmp
                shaderVersion = bd.version
            }
            m.setScale(bd.scaleX, bd.scaleY)
            m.postTranslate(-off[0], -off[1])
            sh.setLocalMatrix(m)
            backPaint.shader = sh
            shape(c, backPaint, r)
        }

        // 2. solid base (Blur off) and tint
        val base = baseColor()
        if (base != 0) {
            paint.color = base
            shape(c, paint, r)
        }
        val tint = tintColor()
        if (tint != 0) {
            val a = ((tint ushr 24) * (1f - 0.42f * press)).toInt().coerceIn(0, 255)
            paint.color = Palette.a(tint, a)
            shape(c, paint, r)
        }

        // 3. gloss + pressed wash
        glossPaint.alpha = (190 + 65 * press).toInt().coerceIn(0, 255)
        shape(c, glossPaint, r)
        if (press > 0.01f) {
            paint.color = white((0x2A * press).toInt())
            shape(c, paint, r)
        }

        // 4. rim
        if (game) {
            c.drawPath(rimPath, rimPaint)
            c.drawPath(bracketPath, bracketPaint)
            c.restore()
            return
        }
        val sw = rimPaint.strokeWidth
        rect.inset(sw / 2f, sw / 2f)
        c.drawRoundRect(rect, max(0f, r - sw / 2f), max(0f, r - sw / 2f), rimPaint)
        c.restore()
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = state.any { it == android.R.attr.state_pressed }
        animatePress(if (pressed) 1f else 0f)
        return true
    }

    private fun animatePress(to: Float) {
        if (press == to && pressAnim == null) return
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, to).apply {
            duration = if (to > 0f) 110L else 280L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                press = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Dock button icons: ready-made Material vectors (play / stop). */
class GlyphView(context: Context, private val kind: Kind, color: Int) : android.widget.ImageView(context) {
    enum class Kind { PLAY, STOP }

    var color: Int = color
        set(v) { field = v; setColorFilter(v) }

    init {
        setImageResource(if (kind == Kind.PLAY) R.drawable.fluxa_ic_play else R.drawable.fluxa_ic_stop)
        scaleType = ScaleType.FIT_CENTER
        setColorFilter(color)
    }
}

/**
 * iOS-like press feedback: the view shrinks a little while held and springs back on release.
 * Touches are not consumed, so the normal click listener still fires.
 */
fun View.glassPress(pressedScale: Float = 0.95f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(110)
                    .setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(OvershootInterpolator(2.4f)).start()
        }
        false
    }
}
