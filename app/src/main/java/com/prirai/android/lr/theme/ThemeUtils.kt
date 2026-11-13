package com.prirai.android.lr.theme

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.settings.ThemeChoice

fun applyAppTheme(choice: Int) {
    val mode = when (choice) {
        ThemeChoice.SYSTEM.ordinal -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeChoice.LIGHT.ordinal -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }
    AppCompatDelegate.setDefaultNightMode(mode)
}

fun applyAppTheme(context: Context) {
    applyAppTheme(UserPreferences(context).appThemeChoice)
}

/**
 * Apply complete theme including Material You dynamic colors and AMOLED mode
 */
fun applyCompleteTheme(activity: Activity) {
    applyAppTheme(activity)
    
    // Apply Material You dynamic colors if enabled
    if (ThemeManager.shouldUseDynamicColors(activity)) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(activity)
    }
    
    // Apply AMOLED mode and system bars
    ThemeManager.applySystemBarsTheme(activity, false)
}