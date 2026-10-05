package com.firstt175.novaframe.session.overlay

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import kotlin.math.abs
import kotlin.math.min

/**
 * Overlay switch (plain Android View) with a liquid thumb: the edge in the direction of travel
 * springs ahead while the other edge lags, so the thumb stretches like a drop of water, then
 * settles with a small wobble. Widens while pressed. Tap or drag to toggle.
 *
 * Behaves like any CompoundButton: isChecked, setOnCheckedChangeListener, toggle().
 */
internal class LiquidSwitch(
    context: Context,
    private val onColor: Int,
    private val offColor: Int,
) : CompoundButton(context) {

    private val density = context.resources.displayMetrics.density
    private val trackW = 46f * density
    private val trackH = 28f * density
    private val thumbD = 22f * density
    private val pad = 3f * density
    private val travel = trackW - 2f * pad - thumbD
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val argb = ArgbEvaluator()
    private val overshoot = OvershootInterpolator(1.8f)
    private val decel = DecelerateInterpolator(1.6f)

    // Edge positions are normalised: 0 = fully left, 1 = fully right.
    private var curL = 0f
    private var curR = 0f
    private var mix = 0f
    private var press = 0f
    private var fromL = 0f
    private var fromR = 0f
    private var fromMix = 0f
    private var goingOn = false

    private val move = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 420L
        addUpdateListener { step(it.animatedFraction) }
    }
    private var pressAnim: ValueAnimator? = null

    private var downX = 0f
    private var dragging = false

    // CompoundButton's constructor calls setChecked() before our fields exist; guard on this flag
    // (a primitive, so it reads false until the initializer below has run).
    private var ready = false

    init {
        background = null
        isClickable = true
        snapTo(isChecked)
        ready = true
    }

    private fun target(on: Boolean) = if (on) 1f else 0f

    private fun snapTo(on: Boolean) {
        move.cancel()
        curL = target(on)
        curR = curL
        mix = curL
        invalidate()
    }

    override fun setChecked(checked: Boolean) {
        val was = isChecked
        super.setChecked(checked)
        if (!ready || was == isChecked) return
        if (isAttachedToWindow && width > 0) animateTo(isChecked) else snapTo(isChecked)
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        if (ready) snapTo(isChecked)
    }

    private fun animateTo(on: Boolean) {
        move.cancel()
        fromL = curL
        fromR = curR
        fromMix = mix
        goingOn = on
        move.start()
    }

    private fun step(t: Float) {
        val to = target(goingOn)
        val lead = overshoot.getInterpolation(min(1f, t / 0.8f))
        val trail = decel.getInterpolation(t)
        // Turning on: the right edge leads. Turning off: the left edge leads.
        curL = fromL + (to - fromL) * (if (goingOn) trail else lead)
        curR = fromR + (to - fromR) * (if (goingOn) lead else trail)
        mix = fromMix + (to - fromMix) * trail
        invalidate()
    }

    private fun animatePress(to: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, to).apply {
            duration = 180L
            interpolator = OvershootInterpolator(2f)
            addUpdateListener {
                press = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackW.toInt(), trackH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val a = if (isEnabled) 255 else 120
        val m = mix.coerceIn(0f, 1f)
        paint.color = argb.evaluate(m, offColor, onColor) as Int
        paint.alpha = a * paint.alpha / 255
        rect.set(0f, 0f, trackW, trackH)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint)

        val ext = 3f * density * press
        val l = pad + travel * curL - ext * curL
        val r = pad + thumbD + travel * curR + ext * (1f - curR)
        val stretch = (curR - curL).coerceIn(0f, 1f)
        val h = thumbD * (1f - 0.10f * stretch)
        val top = (trackH - h) / 2f
        rect.set(l, top, r, top + h)

        // Soft drop shadow, then the white thumb.
        paint.color = 0x33000000
        paint.alpha = 51 * a / 255
        rect.offset(0f, density)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        rect.offset(0f, -density)
        paint.color = 0xFFFFFFFF.toInt()
        paint.alpha = a
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                dragging = false
                animatePress(1f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP -> {
                animatePress(0f)
                if (dragging) {
                    val want = e.x > width / 2f
                    if (want != isChecked) isChecked = want
                } else {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePress(0f)
                return true
            }
        }
        return true
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

    override fun onDetachedFromWindow() {
        move.cancel()
        pressAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * SeekBar thumb that behaves like a drop of liquid: swells when grabbed (pressed state) and
 * stretches along the track while it is being dragged, then relaxes with a little wobble.
 * Animation is frame-driven from draw(), so there are no animators to leak.
 */
internal class LiquidThumbDrawable(
    private val density: Float,
    private val fillColor: Int,
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val oval = RectF()

    private var grab = 0f
    private var grabVel = 0f
    private var grabTarget = 0f
    private var stretch = 0f
    private var lastCx = Float.NaN

    // A little headroom beyond the 20dp drop so the swell is not clipped by the SeekBar.
    override fun getIntrinsicWidth() = (26f * density).toInt()
    override fun getIntrinsicHeight() = (26f * density).toInt()

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = state.contains(android.R.attr.state_pressed)
        grabTarget = if (pressed) 1f else 0f
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        val cx = bounds.exactCenterX()
        if (!lastCx.isNaN()) {
            val dx = abs(cx - lastCx) / density
            if (dx > 0.01f) stretch = min(0.6f, stretch + dx / 10f)
        }
        lastCx = cx
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        // Damped spring toward the grab target, and exponential relax for the stretch.
        grabVel = (grabVel + (grabTarget - grab) * 0.18f) * 0.72f
        grab += grabVel
        stretch *= 0.86f
        val moving = abs(grabTarget - grab) > 0.003f || abs(grabVel) > 0.003f || stretch > 0.003f

        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val swell = 1f + 0.28f * grab.coerceIn(0f, 1.3f)
        val base = 10f * density
        val rx = base * swell * (1f + stretch)
        val ry = base * swell * (1f - 0.25f * stretch)
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(oval, fill)
        val half = ring.strokeWidth / 2f
        oval.inset(half, half)
        canvas.drawOval(oval, ring)

        if (moving) invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        ring.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        ring.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
