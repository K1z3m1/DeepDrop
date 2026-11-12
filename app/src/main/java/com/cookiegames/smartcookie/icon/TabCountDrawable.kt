package com.cookiegames.smartcookie.icon

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.utils.ThemeUtils

/**
 * A sleek, minimalist Drawable that draws the tab count inside a rounded square icon,
 * matching the Via Browser aesthetic.
 * If count > 99, it draws ":D" (or ";)" in incognito).
 */
class TabCountDrawable(
    private val context: Context,
    private var count: Int = 1,
    private var isIncognito: Boolean = false
) : Drawable() {

    private var defaultColor: Int = ThemeUtils.getColor(context, R.attr.iconColor)
    private var tintList: ColorStateList? = null
    private var tintMode: PorterDuff.Mode = PorterDuff.Mode.SRC_IN
    private var currentColor: Int = defaultColor

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = currentColor
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = currentColor
    }

    private val boxRect = RectF()

    fun updateCount(newCount: Int, incognito: Boolean = isIncognito) {
        if (count != newCount || isIncognito != incognito) {
            count = newCount
            isIncognito = incognito
            invalidateSelf()
        }
    }

    private fun updateColor() {
        val color = tintList?.getColorForState(state, defaultColor) ?: defaultColor
        if (currentColor != color) {
            currentColor = color
            boxPaint.color = color
            textPaint.color = color
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val density = context.resources.displayMetrics.density
        val stroke = 1.8f * density
        boxPaint.strokeWidth = stroke

        val w = if (b.width() > 0) b.width().toFloat() else 24f * density
        val h = if (b.height() > 0) b.height().toFloat() else 24f * density
        val cx = if (b.width() > 0) b.exactCenterX() else w / 2f
        val cy = if (b.height() > 0) b.exactCenterY() else h / 2f

        // Icon box size: 15.5dp x 15.5dp inside 24dp frame
        val boxSize = 15.5f * density
        val half = boxSize / 2f
        boxRect.set(cx - half, cy - half, cx + half, cy + half)

        val cornerRadius = 3.5f * density
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, boxPaint)

        val text = if (count > 99) {
            if (isIncognito) ";)" else ":D"
        } else {
            count.toString()
        }

        // Measure and fit text perfectly inside the box
        val maxTextWidth = boxSize - (3f * density)
        var targetTextSize = boxSize * (if (text.length > 2) 0.50f else 0.60f)
        textPaint.textSize = targetTextSize
        val measuredWidth = textPaint.measureText(text)
        if (measuredWidth > maxTextWidth && measuredWidth > 0) {
            targetTextSize *= (maxTextWidth / measuredWidth)
            textPaint.textSize = targetTextSize
        }

        val fontMetrics = textPaint.fontMetrics
        val textY = cy - ((fontMetrics.ascent + fontMetrics.descent) / 2f)
        canvas.drawText(text, cx, textY, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        boxPaint.alpha = alpha
        textPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        boxPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun setTintList(tint: ColorStateList?) {
        tintList = tint
        updateColor()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        if (tintMode != null) {
            this.tintMode = tintMode
            updateColor()
        }
    }

    override fun isStateful(): Boolean = tintList?.isStateful == true || super.isStateful()

    override fun onStateChange(state: IntArray): Boolean {
        updateColor()
        return true
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = (24 * context.resources.displayMetrics.density).toInt()
    override fun getIntrinsicHeight(): Int = (24 * context.resources.displayMetrics.density).toInt()
}
