package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import org.koitharu.kotatsu.R

/**
 * Edge-to-edge status bar protection: a flat surface-colour gradient fades out just below the
 * (transparent) status bar so system icons stay legible without a solid header bar.
 *
 * This intentionally does not do any real-time blur of the content scrolling underneath: a
 * per-frame offscreen blur re-record is a real, continuous GPU cost for a purely decorative
 * effect, and a flat fade reads simpler and costs nothing beyond drawing one gradient.
 */
class StatusBarBlurView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	// Kept for source/binding compatibility with callers that set the scroll target; no longer
	// used for anything since there is no blur to sample from it.
	var blurTarget: View? = null

	private var topInset = 0
	private val extraHeight = resources.getDimensionPixelOffset(R.dimen.margin_normal)
	private val surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)

	private val fadeDrawable = run {
		val alphas = floatArrayOf(0.85f, 0.62f, 0.4f, 0.22f, 0.1f, 0f)
		GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			IntArray(alphas.size) { i -> ColorUtils.setAlphaComponent(surfaceColor, (alphas[i] * 255f).toInt()) },
		)
	}

	init {
		setWillNotDraw(false)
	}

	override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
		val newTop = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
			.getInsets(WindowInsetsCompat.Type.systemBars()).top
		if (newTop != topInset) {
			topInset = newTop
			requestLayout()
		}
		return super.onApplyWindowInsets(insets)
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (topInset + extraHeight) * 6 / 5)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (h <= 0) {
			return
		}
		fadeDrawable.setBounds(0, 0, w, h)
	}

	override fun onDraw(canvas: Canvas) {
		if (width <= 0 || height <= 0) {
			return
		}
		fadeDrawable.draw(canvas)
	}
}
