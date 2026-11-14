package com.prirai.android.lr.ui.theme

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

@Composable
private fun resolveLRColorScheme(
    darkTheme: Boolean,
    isPrivateMode: Boolean,
    amoledMode: Boolean,
    dynamicColor: Boolean
) = when {
    isPrivateMode && darkTheme && amoledMode -> PrivateAmoledColorScheme
    isPrivateMode && darkTheme -> PrivateDarkColorScheme
    isPrivateMode && !darkTheme -> PrivateLightColorScheme
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
    darkTheme && amoledMode -> AmoledDarkColorScheme
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
}

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isPrivateMode: Boolean = false,
    amoledMode: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = resolveLRColorScheme(darkTheme, isPrivateMode, amoledMode, dynamicColor)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            if (isPrivateMode) {
                window.statusBarColor = colorScheme.primary.toArgb()
                window.navigationBarColor = colorScheme.primary.toArgb()
            } else if (darkTheme && amoledMode) {
                window.statusBarColor = AmoledBlack.toArgb()
                window.navigationBarColor = AmoledBlack.toArgb()
            } else if (darkTheme) {
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme && !isPrivateMode
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = LRShapes,
        typography = Typography,
        content = content
    )
}

private val LRShapes = Shapes(
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
