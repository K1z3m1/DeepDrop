@file:Suppress("TooManyFunctions")

package com.prirai.android.lr.ext

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Dimension
import androidx.annotation.Dimension.Companion.DP
import androidx.annotation.IdRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.prirai.android.lr.BrowserApp
import com.prirai.android.lr.components.Components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.settings.ThemeChoice
import mozilla.components.feature.intent.ext.getSessionId
import mozilla.components.support.utils.SafeIntent

inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        getParcelable(key)
    }
}

val Context.application: BrowserApp
    get() = applicationContext as BrowserApp

val Context.components: Components
    get() = application.components

fun Context.isAppInDarkTheme(): Boolean {
    val prefs = UserPreferences(this)
    val isSystemDark =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    return when (prefs.appThemeChoice) {
        ThemeChoice.LIGHT.ordinal -> false
        ThemeChoice.DARK.ordinal -> true
        else -> isSystemDark
    }
}

val Fragment.components: Components
    get() = context!!.components

fun Fragment.nav(@IdRes id: Int?, directions: NavDirections, options: NavOptions? = null) {
    NavHostFragment.findNavController(this).nav(id, directions, options)
}

inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key)
    }
}

inline fun <reified T : Parcelable> Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        getParcelableArrayListExtra(key)
    }
}

fun getIntentSessionId(intent: SafeIntent): String? {
    return intent.getSessionId()
}

fun NavController.nav(@IdRes id: Int?, directions: NavDirections, navOptions: NavOptions? = null) {
    if (id == null || this.currentDestination?.id == id) {
        this.navigate(directions, navOptions)
    }
}

fun NavController.alreadyOnDestination(@IdRes destId: Int?): Boolean {
    return destId?.let { currentDestination?.id == it || popBackStack(it, false) } ?: false
}

fun View.getRectWithScreenLocation(): Rect {
    val locationOnScreen = IntArray(2).apply { getLocationOnScreen(this) }
    return Rect(
        locationOnScreen[0],
        locationOnScreen[1],
        locationOnScreen[0] + width,
        locationOnScreen[1] + height
    )
}

fun View.getWindowInsets(): WindowInsetsCompat? {
    return rootWindowInsets?.let {
        WindowInsetsCompat.toWindowInsetsCompat(it)
    }
}

fun View.isKeyboardVisible(): Boolean {
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

@VisibleForTesting
@Dimension(unit = DP)

fun ComponentActivity.enableEdgeToEdgeMode(statusBarColor: Int? = null) {
    val isDark = if (statusBarColor != null) {
        isColorDark(statusBarColor)
    } else {
        isAppInDarkTheme()
    }
    
    val barColor = statusBarColor ?: Color.TRANSPARENT
    
    enableEdgeToEdge(
        statusBarStyle = if (isDark) {
            SystemBarStyle.dark(barColor)
        } else {
            SystemBarStyle.light(barColor, barColor)
        },
        navigationBarStyle = if (isDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
    )
    
    if (statusBarColor != null) {
        window.statusBarColor = statusBarColor
    }
}

private fun isColorDark(color: Int): Boolean {
    val red = Color.red(color) / 255.0
    val green = Color.green(color) / 255.0
    val blue = Color.blue(color) / 255.0
    
    val r = if (red <= 0.03928) red / 12.92 else Math.pow((red + 0.055) / 1.055, 2.4)
    val g = if (green <= 0.03928) green / 12.92 else Math.pow((green + 0.055) / 1.055, 2.4)
    val b = if (blue <= 0.03928) blue / 12.92 else Math.pow((blue + 0.055) / 1.055, 2.4)
    
    val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return luminance <= 0.5
}

fun View.applyPersistentInsets(
    applyTop: Boolean = true,
    applyBottom: Boolean = true,
    applyLeft: Boolean = true,
    applyRight: Boolean = true
) {
    val initialPadding = recordInitialPaddingForView(this)
    
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val systemBars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        
        v.updatePadding(
            left = if (applyLeft) initialPadding.left + systemBars.left else initialPadding.left,
            top = if (applyTop) initialPadding.top + systemBars.top else initialPadding.top,
            right = if (applyRight) initialPadding.right + systemBars.right else initialPadding.right,
            bottom = if (applyBottom) initialPadding.bottom + systemBars.bottom else initialPadding.bottom
        )
        
        WindowInsetsCompat.CONSUMED
    }
}

private fun recordInitialPaddingForView(view: View): InitialPadding {
    return InitialPadding(
        view.paddingLeft,
        view.paddingTop,
        view.paddingRight,
        view.paddingBottom
    )
}

private data class InitialPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
