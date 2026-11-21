package app.fluxa

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Tiny frametime graph for the HUD: one bar per source (real) frame, newest on the right, with a
 * dashed average line. Bars that are far above the average (stutter) are drawn red.
 * Frametime here = time between two real frames from the game; generated frames fill in between.
 */
class FrameGraphView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    // buffers are reused every second instead of allocating two float arrays per update
    private var ms = FloatArray(0)
    private var msCount = 0
    private var sortBuf = FloatArray(0)
    private var labelText = ""
    private var avg = 0f
    private var p99 = 0f
    private var top = 16.7f

    private val bar = Paint().apply { style = Paint.Style.FILL }
    private val avgLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99FFFFFF.toInt()
        strokeWidth = max(1f, d * 0.8f)
        pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    }

    /** HUD size setting (1.0 = 100 %): scales the graph and its label. */
    private var uiScale = 1f
    fun setUiScale(s: Float) {
        if (s == uiScale) return
        uiScale = s
        label.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f * s, resources.displayMetrics)
        avgLine.strokeWidth = max(1f, d * 0.8f * s)
        avgLine.pathEffect = DashPathEffect(floatArrayOf(3f * d * s, 3f * d * s), 0f)
        requestLayout()
        invalidate()
    }

    /** [us] = newest-last frame intervals in microseconds, [n] valid entries; the last [show] are drawn. */
    fun setSamples(us: IntArray, n: Int, show: Int = SHOWN) {
        val k = min(n, min(show, us.size))
        if (k < 2) {
            msCount = 0
            invalidate()
            return
        }
        if (ms.size < k) {
            ms = FloatArray(k)
            sortBuf = FloatArray(k)
        }
        var sum = 0f
        for (i in 0 until k) {
            val v = us[n - k + i] / 1000f
            ms[i] = v
            sortBuf[i] = v
            sum += v
        }
        java.util.Arrays.sort(sortBuf, 0, k)
        msCount = k
        avg = sum / k
        p99 = sortBuf[min(k - 1, ceil(0.99f * k).toInt() - 1).coerceAtLeast(0)]
        top = max(8f, min(100f, max(avg * 2.2f, p99 * 1.1f)))
        val onePct = if (p99 > 0f) 1000f / p99 else 0f
        // the label text is built once per update, not once per draw
        labelText = String.format(I18n.tr("ต้นทาง") + " %.1f ms · 1%%low %.0f", avg, onePct)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((150 * d * uiScale).toInt(), (46 * d * uiScale).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (msCount == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = 12f * d * uiScale
        val gTop = labelH
        val gH = h - labelH
        c.drawText(labelText, 0f, labelH - 3f * d * uiScale, label)

        val slot = w / SHOWN
        val bw = max(1f, slot - d * 0.5f)
        val spike = max(avg * 2f, avg + 8f)
        for (i in 0 until msCount) {
            val v = ms[i]
            val bh = max(d, gH * min(1f, v / top))
            bar.color = if (v > spike) Palette.DANGER else Palette.PRIMARY
            val x = w - (msCount - i) * slot
            c.drawRect(x, gTop + gH - bh, x + bw, gTop + gH, bar)
        }
        val y = gTop + gH - gH * min(1f, avg / top)
        c.drawLine(0f, y, w, y, avgLine)
    }

    companion object {
        const val SHOWN = 120
    }
}
