package com.prirai.android.nira.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = hex(0x006A6A),
    onPrimary = hex(0xFFFFFF),
    primaryContainer = hex(0x9CF1F0),
    onPrimaryContainer = hex(0x002020),
    secondary = hex(0x4A6363),
    onSecondary = hex(0xFFFFFF),
    secondaryContainer = hex(0xCCE8E7),
    onSecondaryContainer = hex(0x051F1F),
    tertiary = hex(0x4B607C),
    onTertiary = hex(0xFFFFFF),
    tertiaryContainer = hex(0xD3E4FF),
    onTertiaryContainer = hex(0x041C35),
    error = hex(0xBA1A1A),
    onError = hex(0xFFFFFF),
    errorContainer = hex(0xFFDAD6),
    onErrorContainer = hex(0x410002),
    surface = hex(0xF4FBFA),
    onSurface = hex(0x161D1D),
    surfaceVariant = hex(0xDAE5E4),
    onSurfaceVariant = hex(0x3F4948),
    surfaceContainerLow = hex(0xEEF5F4),
    surfaceContainer = hex(0xE8EFEE),
    surfaceContainerHigh = hex(0xE3E9E9),
    surfaceContainerHighest = hex(0xDDE4E3),
    background = hex(0xF4FBFA),
    onBackground = hex(0x161D1D),
    outline = hex(0x6F7979),
    outlineVariant = hex(0xBEC9C8),
)

private val DarkColorScheme = darkColorScheme(
    primary = hex(0x80D5D4),
    onPrimary = hex(0x003737),
    primaryContainer = hex(0x004F50),
    onPrimaryContainer = hex(0x9CF1F0),
    secondary = hex(0xB0CCCB),
    onSecondary = hex(0x1B3534),
    secondaryContainer = hex(0x324B4B),
    onSecondaryContainer = hex(0xCCE8E7),
    tertiary = hex(0xB3C8E8),
    onTertiary = hex(0x1C314B),
    tertiaryContainer = hex(0x334863),
    onTertiaryContainer = hex(0xD3E4FF),
    error = hex(0xFFB4AB),
    onError = hex(0x690005),
    errorContainer = hex(0x93000A),
    onErrorContainer = hex(0xFFDAD6),
    surface = hex(0x0E1514),
    onSurface = hex(0xDDE4E3),
    surfaceVariant = hex(0x3F4948),
    onSurfaceVariant = hex(0xBEC9C8),
    surfaceContainerLow = hex(0x161D1D),
    surfaceContainer = hex(0x1A2121),
    surfaceContainerHigh = hex(0x252B2B),
    surfaceContainerHighest = hex(0x303636),
    background = hex(0x0E1514),
    onBackground = hex(0xDDE4E3),
    outline = hex(0x899392),
    outlineVariant = hex(0x3F4948),
)

private val AmoledDarkColorScheme = DarkColorScheme.copy(
    surface = AmoledBlack,
    background = AmoledBlack,
    surfaceVariant = AmoledSurfaceVariant,
    surfaceContainer = AmoledSurfaceVariant,
    surfaceContainerLow = AmoledBlack,
    surfaceContainerHigh = AmoledSurfaceDim,
    surfaceContainerHighest = AmoledSurfaceDim
)

private val PrivateLightColorScheme = lightColorScheme(
    primary = PrivatePurple60,
    onPrimary = Color.White,
    primaryContainer = PrivatePurple80,
    onPrimaryContainer = Color.White,
    secondary = PrivatePurple40,
    surface = PrivateLightSurface,
    onSurface = PrivatePurple10,
    background = PrivateLightBackground,
    onBackground = PrivatePurple10
)

private val PrivateDarkColorScheme = darkColorScheme(
    primary = PrivatePurple80,
    onPrimary = Color.White,
    primaryContainer = PrivatePurple60,
    onPrimaryContainer = Color.White,
    secondary = PrivatePurple40,
    surface = PrivateDarkSurface,
    onSurface = PrivateOnDarkSurface,
    background = PrivateDarkBackground,
    onBackground = PrivateOnDarkSurface
)

private val PrivateAmoledColorScheme = darkColorScheme(
    primary = PrivatePurple80,
    onPrimary = Color.White,
    primaryContainer = PrivatePurple60,
    onPrimaryContainer = Color.White,
    secondary = PrivatePurple40,
    surface = AmoledBlack,
    onSurface = PrivateOnDarkSurface,
    background = AmoledBlack,
    onBackground = PrivateOnDarkSurface,
    surfaceVariant = AmoledSurfaceVariant,
    surfaceContainer = AmoledSurfaceVariant,
    surfaceContainerLow = AmoledBlack,
    surfaceContainerHigh = AmoledSurfaceDim,
    surfaceContainerHighest = AmoledSurfaceDim
)

/**
 * The single source of truth for the color scheme the app shows, shared by every
 * [NiraTheme] so all surfaces render in the exact palette the user selected.
 */
@Composable
private fun resolveNiraColorScheme(
    darkTheme: Boolean,
    isPrivateMode: Boolean,
    amoledMode: Boolean,
    dynamicColor: Boolean
) = when {
    // Private mode with AMOLED
    isPrivateMode && darkTheme && amoledMode -> PrivateAmoledColorScheme
    // Private mode without AMOLED
    isPrivateMode && darkTheme -> PrivateDarkColorScheme
    isPrivateMode && !darkTheme -> PrivateLightColorScheme
    // Dynamic colors (Android 12+)
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) {
            val baseDynamic = dynamicDarkColorScheme(context)
            if (amoledMode) {
                baseDynamic.withBlackSurfaces()
            } else {
                baseDynamic
            }
        } else {
            dynamicLightColorScheme(context)
        }
    }
    // Static colors with AMOLED
    darkTheme && amoledMode -> AmoledDarkColorScheme
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
}

/**
 * The dynamic scheme is already a complete, tuned Material 3 scheme, so it is used
 * as the system delivers it. Only the AMOLED variant changes it, taking the surface
 * ladder to the same near-black steps every static AMOLED palette uses, while
 * keeping the dynamic accents and containers.
 */
private fun ColorScheme.withBlackSurfaces() =
    copy(
        background = AmoledBlack,
        surface = AmoledBlack,
        surfaceContainerLowest = AmoledBlack,
        surfaceContainerLow = AmoledSurfaceVariant,
        surfaceContainer = AmoledSurfaceVariant,
        surfaceContainerHigh = AmoledSurfaceDim,
        surfaceContainerHighest = AmoledSurfaceDim,
    )

/**
 * Nira's Material 3 Expressive theme: [MaterialExpressiveTheme] with the expressive
 * motion scheme, so every component that reads [MotionScheme] specs moves the way
 * Material 3 Expressive defines. All UI surfaces must be wrapped in this theme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NiraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isPrivateMode: Boolean = false,
    amoledMode: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = resolveNiraColorScheme(darkTheme, isPrivateMode, amoledMode, dynamicColor)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            // Set status bar and navigation bar colors
            if (isPrivateMode) {
                // Purple for private mode
                window.statusBarColor = colorScheme.primary.toArgb()
                window.navigationBarColor = colorScheme.primary.toArgb()
            } else if (darkTheme && amoledMode) {
                // Pure black for AMOLED mode
                window.statusBarColor = AmoledBlack.toArgb()
                window.navigationBarColor = AmoledBlack.toArgb()
            } else if (darkTheme) {
                // Dark surface for normal dark mode
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
            }
            // Set light/dark status bar icons
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme && !isPrivateMode
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = NiraShapes,
        typography = Typography,
        content = content
    )
}

/** Rounder than the Material default (4/8/12/16/28): softer cards, sheets and dialogs. */
private val NiraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
