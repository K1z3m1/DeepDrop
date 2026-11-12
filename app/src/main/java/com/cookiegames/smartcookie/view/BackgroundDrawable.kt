package com.cookiegames.smartcookie.view

import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.utils.ThemeUtils
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import androidx.core.content.ContextCompat

/**
 * Create a new transition drawable with the specified list of layers. At least
 * 2 layers are required for this drawable to work properly.
 */
class BackgroundDrawable(
    context: Context
) : TransitionDrawable(
    arrayOf<Drawable>(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * context.resources.displayMetrics.density
            setColor(ContextCompat.getColor(context, R.color.transparent))
        },
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * context.resources.displayMetrics.density
            setColor(ThemeUtils.getColor(context, R.attr.selectedBackground))
        }
    )
) {

    private var isSelected: Boolean = false

    override fun startTransition(durationMillis: Int) {
        if (!isSelected) {
            super.startTransition(durationMillis)
        }
        isSelected = true
    }

    override fun reverseTransition(duration: Int) {
        if (isSelected) {
            super.reverseTransition(duration)
        }
        isSelected = false
    }

}
