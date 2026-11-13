@file:Suppress("TooManyFunctions")

package com.prirai.android.lr.ext

import android.graphics.Rect
import android.view.View
import androidx.annotation.Dimension
import androidx.annotation.Dimension.Companion.DP
import androidx.annotation.VisibleForTesting
import androidx.core.view.WindowInsetsCompat

/**
 * Fills a [Rect] with data about a view's location in the screen.
 *
 * @see View.getLocationOnScreen
 * @see View.getRectWithViewLocation for a version of this that is relative to a window
 */
fun View.getRectWithScreenLocation(): Rect {
    val locationOnScreen = IntArray(2).apply { getLocationOnScreen(this) }
    return Rect(
        locationOnScreen[0],
        locationOnScreen[1],
        locationOnScreen[0] + width,
        locationOnScreen[1] + height
    )
}

/**
 * A safer version of [ViewCompat.getRootWindowInsets] that does not throw a NullPointerException
 * if the view is not attached.
 */
fun View.getWindowInsets(): WindowInsetsCompat? {
    return rootWindowInsets?.let {
        WindowInsetsCompat.toWindowInsetsCompat(it)
    }
}

/**
 * Checks if the keyboard is visible
 *
 * Inspired by https://stackoverflow.com/questions/2150078/how-to-check-visibility-of-software-keyboard-in-android
 * API 30 adds a native method for this. We should use it (and a compat method if one
 * is added) when it becomes available
 */
fun View.isKeyboardVisible(): Boolean {
    // Since we have insets in M and above, we don't need to guess what the keyboard height is.
    val minimumKeyboardHeight = 0
    return getKeyboardHeight() > minimumKeyboardHeight
}

@VisibleForTesting
internal fun View.getWindowVisibleDisplayFrame(): Rect = with(Rect()) {
    getWindowVisibleDisplayFrame(this)
    this
}

@VisibleForTesting
internal fun View.getKeyboardHeight(): Int {
    val windowRect = getWindowVisibleDisplayFrame()
    val statusBarHeight = windowRect.top
    var keyboardHeight = rootView.height - (windowRect.height() + statusBarHeight)
    getWindowInsets()?.let {
        keyboardHeight -= it.stableInsetBottom
    }

    return keyboardHeight
}

/**
 * The assumed minimum height of the keyboard.
 */
@VisibleForTesting
@Dimension(unit = DP)
internal const val MINIMUM_KEYBOARD_HEIGHT = 100
