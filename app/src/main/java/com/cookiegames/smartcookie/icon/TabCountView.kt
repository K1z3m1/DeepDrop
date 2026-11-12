package com.cookiegames.smartcookie.icon

import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.extensions.preferredLocale
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.withStyledAttributes
import java.text.NumberFormat

/**
 * A view that draws a count enclosed by a border. Defaults to drawing zero, draws infinity if the
 * number is greater than 99.
 *
 * Attributes:
 * - [R.styleable.TabCountView_tabIconColor] - The color used to draw the number and border.
 * Defaults to black.
 * - [R.styleable.TabCountView_tabIconTextSize] - The count text size, defaults to 14.
 * - [R.styleable.TabCountView_tabIconBorderRadius] - The radius of the border's corners. Defaults
 * to 0.
 * - [R.styleable.TabCountView_tabIconBorderWidth] - The width of the border. Defaults to 0.
 */
class TabCountView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val numberFormat = NumberFormat.getInstance(context.preferredLocale)
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val overMode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    private val paint: Paint = Paint().apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private var borderRadius: Float = 0F
    private var borderWidth: Float = 0F
    private var baseTextSize: Float = 14F
    private val workingRect = RectF()

    private var count: Int = 0
    private var isIncognito: Boolean = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        context.withStyledAttributes(attrs, R.styleable.TabCountView) {
            paint.color = getColor(R.styleable.TabCountView_tabIconColor, Color.BLACK)
            baseTextSize = getDimension(R.styleable.TabCountView_tabIconTextSize, 14F)
            paint.textSize = baseTextSize
            borderRadius = getDimension(R.styleable.TabCountView_tabIconBorderRadius, 0F)
            borderWidth = getDimension(R.styleable.TabCountView_tabIconBorderWidth, 0F)
        }
    }

    /**
     * Update the number count displayed by the view.
     */
    fun updateCount(count: Int) {
        this.count = count
        contentDescription = if (count > 99) ":D" else count.toString()
        invalidate()
    }

    fun setIsIncognito(incognito: Boolean) {
        this.isIncognito = incognito
        invalidate()
    }

    fun setColor(@androidx.annotation.ColorInt color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val text: String = if (count > 99) {
            if (isIncognito) ";)" else ":D"
        } else {
            numberFormat.format(count)
        }

        paint.xfermode = overMode

        workingRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(workingRect, borderRadius, borderRadius, paint)

        paint.xfermode = clearMode

        val innerRadius = borderRadius - 1
        workingRect.set(borderWidth, borderWidth, (width - borderWidth), (height - borderWidth))
        canvas.drawRoundRect(workingRect, innerRadius, innerRadius, paint)

        paint.xfermode = overMode

        // Auto-scale text to fit inside the rounded box without clipping
        val innerWidth = width - (borderWidth * 2) - 4f
        var currentTextSize = baseTextSize
        paint.textSize = currentTextSize
        val textWidth = paint.measureText(text)
        if (textWidth > innerWidth && innerWidth > 0) {
            currentTextSize = baseTextSize * (innerWidth / textWidth)
            paint.textSize = currentTextSize
        }

        val xPos = width / 2F
        val yPos = height / 2 - (paint.descent() + paint.ascent()) / 2

        canvas.drawText(text, xPos, yPos, paint)

        super.onDraw(canvas)
    }

}
