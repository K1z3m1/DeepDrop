package com.prirai.android.lr.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.settings.ThemeChoice
import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.WindowInsetsController
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.android.material.elevation.ElevationOverlayProvider
import com.prirai.android.lr.components.toolbar.ToolbarPosition

object ThemeManager {
    fun applyTheme(context: Context) {
        val prefs = UserPreferences(context)
        when (prefs.appThemeChoice) {
            ThemeChoice.LIGHT.ordinal -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            ThemeChoice.DARK.ordinal -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            ThemeChoice.SYSTEM.ordinal -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }
    
    fun isDarkMode(context: Context): Boolean {
        val prefs = UserPreferences(context)
        return when (prefs.appThemeChoice) {
            ThemeChoice.LIGHT.ordinal -> false
            ThemeChoice.DARK.ordinal -> true
            ThemeChoice.SYSTEM.ordinal -> {
                val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
            else -> {
                val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
    
    fun isAmoledActive(context: Context): Boolean {
        val prefs = UserPreferences(context)
        return prefs.amoledMode && isDarkMode(context)
    }
    
    fun shouldUseDynamicColors(context: Context): Boolean {
        val prefs = UserPreferences(context)
        return prefs.dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
    
    fun getBackgroundColor(context: Context): Int {
        return if (isAmoledActive(context)) {
            Color.BLACK
        } else {
            val typedValue = TypedValue()
            val theme = context.theme
            theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface,
                typedValue,
                true
            )
            if (typedValue.resourceId != 0) {
                ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }
    }
    
    fun getSurfaceColor(context: Context): Int {
        return if (isAmoledActive(context)) {
            Color.BLACK
        } else {
            val typedValue = TypedValue()
            val theme = context.theme
            theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface,
                typedValue,
                true
            )
            if (typedValue.resourceId != 0) {
                ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }
    }
    
    @JvmOverloads
    fun getToolbarBackgroundColor(
        context: Context, 
        useElevation: Boolean = false, 
        elevationDp: Float = 3f
    ): Int {
        return if (isAmoledActive(context)) {
            Color.BLACK
        } else if (useElevation) {
            val elevationPx = elevationDp * context.resources.displayMetrics.density
            ElevationOverlayProvider(context)
                .compositeOverlayWithThemeSurfaceColorIfNeeded(elevationPx)
        } else {
            val typedValue = TypedValue()
            val theme = context.theme
            
            val resolved = theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainer,
                typedValue,
                true
            )
            
            if (!resolved) {
                theme.resolveAttribute(
                    com.google.android.material.R.attr.colorSurface,
                    typedValue,
                    true
                )
            }
            
            if (typedValue.resourceId != 0) {
                ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.M)
    fun applySystemBarsTheme(activity: Activity, isPrivateMode: Boolean = false) {
        val context = activity as Context
        val window = activity.window

        val prefs = UserPreferences(context)
        val toolbarAtBottom = prefs.toolbarPosition ==
            ToolbarPosition.BOTTOM.ordinal
        val isDark = when (prefs.appThemeChoice) {
            ThemeChoice.LIGHT.ordinal -> false
            ThemeChoice.DARK.ordinal -> true
            else -> {
                val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
        val isAmoled = prefs.amoledMode && isDark

        if (isPrivateMode) {
            val purpleColor = ColorConstants.PrivateMode.PURPLE
            window.statusBarColor = purpleColor
            window.navigationBarColor = if (toolbarAtBottom) purpleColor
                else Color.TRANSPARENT
        } else if (isAmoled) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = if (toolbarAtBottom) Color.BLACK
                else Color.TRANSPARENT
        } else {
            val surfaceColor = getSurfaceColor(context)
            window.statusBarColor = surfaceColor
            window.navigationBarColor = if (toolbarAtBottom) surfaceColor
                else Color.TRANSPARENT
        }

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (isDark || isPrivateMode) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            if (isDark || isPrivateMode) {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }
}
