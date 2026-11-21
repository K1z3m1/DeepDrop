package app.fluxa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Before / after viewer. Both bitmaps cover the same image (their own resolution differs), so one
 * transform maps them onto the screen: left of the divider shows [before], right of it [after].
 * Drag the divider (anywhere near the line) to move it, drag elsewhere to pan, pinch / double-tap
 * to zoom. Zoomed in far enough, pixels are drawn unfiltered so the real pixel structure shows.
 */
class CompareView(context: Context, private val kit: Kit) : View(context) {
    private var before: Bitmap? = null
    private var after: Bitmap? = null
    private var baseW = 0
    private var baseH = 0

    private var split = 0.5f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fit = 1f
    private var zoomed = false

    var beforeLabel = "ก่อน"
    var afterLabel = "หลัง"
    var message: String? = null
        set(value) { field = value; invalidate() }

    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = kit.dpf(2)
        setShadowLayer(kit.dpf(3), 0f, 0f, 0xAA000000.toInt())
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(kit.dpf(4), 0f, kit.dpf(1), 0x99000000.toInt())
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SECONDARY }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = kit.dpf(12)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED
        textSize = kit.dpf(14)
        textAlign = Paint.Align.CENTER
    }

    private val grabSlop = kit.dpf(32)
    private var draggingSplit = false
    private var panning = false
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(d.focusX, d.focusY, scale * d.scaleFactor)
            return true
        }
    })
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed && scale > fit * 1.05f) resetZoom() else zoomAt(e.x, e.y, maxOf(fit * 4f, 3f))
            return true
        }
    })

    init {
        setBackgroundColor(Palette.BG)
    }

    /** [bw] x [bh] is the shared image space (the full-resolution frame) both bitmaps cover. */
    fun setImages(before: Bitmap?, after: Bitmap?, bw: Int, bh: Int) {
        val sameBase = bw == baseW && bh == baseH
        this.before = before
        this.after = after
        baseW = bw
        baseH = bh
        if (!sameBase) {
            computeFit()
            resetZoom()
        }
        invalidate()
    }

    /** Divider position 0..1 (1 = only the "before" side, 0 = only the "after" side). */
    fun setSplit(fraction: Float) {
        split = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun resetZoom() {
        zoomed = false
        computeFit()
        scale = fit
        tx = 0f
        ty = 0f
        clampPan()
        invalidate()
    }

    private fun computeFit() {
        fit = if (baseW > 0 && baseH > 0 && width > 0 && height > 0) {
            minOf(width.toFloat() / baseW, height.toFloat() / baseH)
        } else 1f
    }

    private fun maxScale() = maxOf(fit, 8f)

    private fun zoomAt(fx: Float, fy: Float, target: Float) {
        if (baseW == 0) return
        val s = target.coerceIn(fit, maxScale())
        // keep the image point under the focus fixed
        val ix = (fx - tx) / scale
        val iy = (fy - ty) / scale
        scale = s
        tx = fx - ix * s
        ty = fy - iy * s
        zoomed = s > fit * 1.001f
        clampPan()
        invalidate()
    }

    private fun clampPan() {
        val iw = baseW * scale
        val ih = baseH * scale
        tx = if (iw <= width) (width - iw) / 2f else tx.coerceIn(width - iw, 0f)
        ty = if (ih <= height) (height - ih) / 2f else ty.coerceIn(height - ih, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val oldFit = fit
        computeFit()
        if (!zoomed) {
            scale = fit
            tx = 0f
            ty = 0f
        } else if (oldFit > 0f) {
            scale = (scale / oldFit * fit).coerceIn(fit, maxScale())
        }
        clampPan()
    }

    private fun splitX() = width * split

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tapDetector.onTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = ev.x
                lastY = ev.y
                draggingSplit = abs(ev.x - splitX()) <= grabSlop
                panning = !draggingSplit
                if (draggingSplit) moveSplit(ev.x)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                draggingSplit = false
                panning = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // continue panning with the finger that is left
                val keep = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(keep)
                lastY = ev.getY(keep)
                panning = ev.pointerCount - 1 == 1
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingSplit) {
                    moveSplit(ev.x)
                } else if (panning && ev.pointerCount == 1 && !scaleDetector.isInProgress) {
                    tx += ev.x - lastX
                    ty += ev.y - lastY
                    lastX = ev.x
                    lastY = ev.y
                    clampPan()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingSplit = false
                panning = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun moveSplit(x: Float) {
        split = (x / width).coerceIn(0f, 1f)
        invalidate()
    }

    private fun drawSide(canvas: Canvas, bm: Bitmap?, clipL: Float, clipR: Float) {
        if (bm == null || clipR <= clipL) return
        canvas.save()
        canvas.clipRect(clipL, 0f, clipR, height.toFloat())
        // magnified past ~2.5 screen px per bitmap px: show real pixels instead of a blur
        bitmapPaint.isFilterBitmap = (baseW * scale) / bm.width < 2.5f
        canvas.drawBitmap(bm, null, dst, bitmapPaint)
        canvas.restore()
    }

    private fun pill(canvas: Canvas, text: String, x: Float, y: Float, alignRight: Boolean) {
        val pad = kit.dpf(6)
        val w = textPaint.measureText(text) + pad * 2
        val h = textPaint.textSize + pad * 1.6f
        val l = if (alignRight) x - w else x
        canvas.drawRoundRect(l, y, l + w, y + h, kit.dpf(8), kit.dpf(8), pillPaint)
        canvas.drawText(text, l + pad, y + h - pad * 1.05f, textPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseW == 0 || before == null) {
            message?.let { canvas.drawText(I18n.tr(it), width / 2f, height / 2f, msgPaint) }
            return
        }
        dst.set(tx, ty, tx + baseW * scale, ty + baseH * scale)
        val sx = splitX()
        drawSide(canvas, before, 0f, sx)
        drawSide(canvas, after, sx, width.toFloat())

        val m = kit.dpf(8)
        if (sx > kit.dpf(90)) pill(canvas, I18n.tr(beforeLabel), m, m, false)
        if (width - sx > kit.dpf(90)) pill(canvas, I18n.tr(afterLabel), width - m, m, true)

        canvas.drawLine(sx, 0f, sx, height.toFloat(), linePaint)
        val cy = height / 2f
        val r = kit.dpf(16)
        canvas.drawCircle(sx, cy, r, handlePaint)
        val a = kit.dpf(5)
        val path = android.graphics.Path().apply {
            moveTo(sx - a * 0.6f, cy); lineTo(sx - a * 1.9f, cy - a); lineTo(sx - a * 1.9f, cy + a); close()
            moveTo(sx + a * 0.6f, cy); lineTo(sx + a * 1.9f, cy - a); lineTo(sx + a * 1.9f, cy + a); close()
        }
        canvas.drawPath(path, arrowPaint)

        message?.let {
            val h = kit.dpf(28)
            canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), pillPaint)
            canvas.drawText(it, width / 2f, height - h / 2f + msgPaint.textSize / 3f, msgPaint)
        }
    }
}
