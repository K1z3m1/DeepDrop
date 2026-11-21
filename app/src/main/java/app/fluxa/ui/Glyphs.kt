package app.fluxa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan

/**
 * Inline icons for text. No emoji or symbol characters are used anywhere in the app: a string that needs
 * an icon carries a token such as "{ic:tick}", which is drawn as a ready-made vector icon
 * (res/drawable fluxa_ic_*) in the text colour. Works for TextViews ([apply]) and Canvas text ([draw]).
 */
object Glyphs {
    private val MAP: Map<String, Int> = mapOf(
        "tick" to R.drawable.fluxa_ic_tick,
        "close" to R.drawable.fluxa_ic_close,
        "star" to R.drawable.fluxa_ic_star,
        "bolt" to R.drawable.fluxa_ic_bolt,
        "dot" to R.drawable.fluxa_ic_dot,
        "note" to R.drawable.fluxa_ic_note,
        "play" to R.drawable.fluxa_ic_play,
        "arrow" to R.drawable.fluxa_ic_arrow,
        "swap" to R.drawable.fluxa_ic_swap,
    )
    private val TOKEN = Regex("\\{ic:([a-z]+)\\}")
    private const val OBJ = "\uFFFC"

    fun has(s: CharSequence?): Boolean = s != null && s.contains("{ic:")

    /** Text with the tokens removed, for places that cannot show icons (toasts, dialogs). */
    fun plain(s: String): String = TOKEN.replace(s) { if (it.groupValues[1] == "arrow") ">" else "" }.trim()

    private fun drawable(ctx: Context, name: String): Drawable? =
        MAP[name]?.let { androidx.core.content.ContextCompat.getDrawable(ctx, it)?.mutate() }

    private fun size(paint: Paint) = paint.textSize * 0.95f
    private fun advance(paint: Paint) = size(paint) + paint.textSize * 0.12f

    private fun paintIcon(c: Canvas, d: Drawable, x: Float, baseline: Float, paint: Paint) {
        val s = size(paint)
        val top = baseline - s * 0.86f
        d.setBounds(x.toInt(), top.toInt(), (x + s).toInt(), (top + s).toInt())
        d.colorFilter = PorterDuffColorFilter(paint.color, PorterDuff.Mode.SRC_IN)
        d.alpha = paint.alpha
        d.draw(c)
    }

    private class GlyphSpan(private val d: Drawable?) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) =
            advance(paint).toInt()

        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            d?.let { paintIcon(c, it, x + paint.textSize * 0.06f, y.toFloat(), paint) }
        }
    }

    /** [s] with every {ic:name} token replaced by an icon span. */
    fun apply(ctx: Context, s: CharSequence?): CharSequence? {
        if (!has(s)) return s
        val str = s.toString()
        val out = SpannableStringBuilder()
        var last = 0
        for (m in TOKEN.findAll(str)) {
            out.append(str, last, m.range.first)
            val at = out.length
            out.append(OBJ)
            out.setSpan(GlyphSpan(drawable(ctx, m.groupValues[1])), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        out.append(str, last, str.length)
        return out
    }

    /** Width of [text] when its tokens are drawn as icons. */
    fun measure(paint: Paint, text: String): Float {
        var w = 0f
        var last = 0
        for (m in TOKEN.findAll(text)) {
            w += paint.measureText(text, last, m.range.first) + advance(paint)
            last = m.range.last + 1
        }
        return w + paint.measureText(text, last, text.length)
    }

    /** Draws [text] at ([x], [baseline]) with its tokens as icons in the paint's colour. */
    fun draw(ctx: Context, c: Canvas, text: String, x: Float, baseline: Float, paint: Paint) {
        var cx = x
        var last = 0
        for (m in TOKEN.findAll(text)) {
            if (m.range.first > last) { c.drawText(text, last, m.range.first, cx, baseline, paint); cx += paint.measureText(text, last, m.range.first) }
            drawable(ctx, m.groupValues[1])?.let { paintIcon(c, it, cx + paint.textSize * 0.06f, baseline, paint) }
            cx += advance(paint)
            last = m.range.last + 1
        }
        if (last < text.length) c.drawText(text, last, text.length, cx, baseline, paint)
    }
}
