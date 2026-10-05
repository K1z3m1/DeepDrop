package com.firstt175.novaframe.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.firstt175.novaframe.prefs.AppAppearancePrefs
import com.firstt175.novaframe.prefs.AppThemeMode

private val NovaDarkColorScheme = darkColorScheme(
    primary = NovaPrimary, onPrimary = NovaOnPrimary,
    primaryContainer = NovaPrimaryContainer, onPrimaryContainer = NovaOnPrimaryContainer,
    secondary = NovaSecondary, onSecondary = NovaOnSecondary,
    secondaryContainer = NovaSecondaryContainer, onSecondaryContainer = NovaOnSecondaryContainer,
    tertiary = NovaTertiary, onTertiary = NovaOnTertiary,
    tertiaryContainer = NovaTertiaryContainer, onTertiaryContainer = NovaOnTertiaryContainer,
    error = NovaError, onError = NovaOnError,
    errorContainer = NovaErrorContainer, onErrorContainer = NovaOnErrorContainer,
    background = NovaBackground, onBackground = NovaOnBackground,
    surface = NovaSurface, onSurface = NovaOnSurface, onSurfaceVariant = NovaOnSurfaceVariant,
    surfaceDim = NovaSurfaceDim, surfaceBright = NovaSurfaceBright,
    surfaceContainerLowest = NovaSurfaceContainerLowest, surfaceContainerLow = NovaSurfaceContainerLow,
    surfaceContainer = NovaSurfaceContainer, surfaceContainerHigh = NovaSurfaceContainerHigh,
    surfaceContainerHighest = NovaSurfaceContainerHighest,
    outline = NovaOutline, outlineVariant = NovaOutlineVariant,
)

private val NovaLightColorScheme = lightColorScheme(
    primary = NovaLightPrimary, onPrimary = NovaLightOnPrimary,
    primaryContainer = NovaLightPrimaryContainer, onPrimaryContainer = NovaLightOnPrimaryContainer,
    secondary = NovaLightSecondary, onSecondary = NovaLightOnSecondary,
    secondaryContainer = NovaLightSecondaryContainer, onSecondaryContainer = NovaLightOnSecondaryContainer,
    tertiary = NovaLightTertiary, onTertiary = NovaLightOnTertiary,
    tertiaryContainer = NovaLightTertiaryContainer, onTertiaryContainer = NovaLightOnTertiaryContainer,
    error = NovaLightError, onError = NovaLightOnError,
    errorContainer = NovaLightErrorContainer, onErrorContainer = NovaLightOnErrorContainer,
    background = NovaLightBackground, onBackground = NovaLightOnBackground,
    surface = NovaLightSurface, onSurface = NovaLightOnSurface,
    onSurfaceVariant = NovaLightOnSurfaceVariant, outline = NovaLightOutline,
    outlineVariant = NovaLightOutlineVariant,
    surfaceContainerLowest = Color(0xFFFFFCF7),
    surfaceContainerLow = Color(0xFFF6F0E4),
    surfaceContainer = Color(0xFFF1EADB),
    surfaceContainerHigh = Color(0xFFEBE3D1),
    surfaceContainerHighest = Color(0xFFE5DCC8),
)

// --- iOS 27 Liquid Glass palettes ------------------------------------------------------
// Containers are translucent so the backdrop shows through; how translucent is driven by the
// Clear <-> Tinted slider (intensity 0f..1f). High/Highest stay fairly opaque because dialogs /
// menus / sheets are separate windows with no backdrop behind them and would be unreadable.
private fun mixF(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun glassDarkScheme(t: Float) = NovaDarkColorScheme.copy(
    background = Color.Transparent,
    surface = Color.White.copy(alpha = mixF(0.06f, 0.10f, t)),
    onSurface = Color(0xFFF7F7FB),
    onSurfaceVariant = Color(0xFFF7F7FB).copy(alpha = mixF(0.70f, 0.78f, t)),
    surfaceDim = Color.Transparent,
    surfaceBright = Color.White.copy(alpha = mixF(0.18f, 0.24f, t)),
    surfaceContainerLowest = Color.White.copy(alpha = mixF(0.03f, 0.05f, t)),
    surfaceContainerLow = Color.White.copy(alpha = mixF(0.05f, 0.08f, t)),
    surfaceContainer = Color.White.copy(alpha = mixF(0.08f, 0.12f, t)),
    surfaceContainerHigh = Color(0xFF1C1D2B).copy(alpha = mixF(0.90f, 0.96f, t)),
    surfaceContainerHighest = Color(0xFF262838).copy(alpha = mixF(0.92f, 0.98f, t)),
    outline = Color.White.copy(alpha = 0.34f),
    outlineVariant = Color.White.copy(alpha = 0.16f),
)

private fun glassLightScheme(t: Float) = NovaLightColorScheme.copy(
    background = Color.Transparent,
    surface = Color.White.copy(alpha = mixF(0.35f, 0.62f, t)),
    onSurface = Color(0xFF111318),
    onSurfaceVariant = Color(0xFF4A4D57),
    surfaceDim = Color.Transparent,
    surfaceBright = Color.White.copy(alpha = mixF(0.70f, 0.92f, t)),
    surfaceContainerLowest = Color.White.copy(alpha = mixF(0.24f, 0.44f, t)),
    surfaceContainerLow = Color.White.copy(alpha = mixF(0.32f, 0.54f, t)),
    surfaceContainer = Color.White.copy(alpha = mixF(0.42f, 0.66f, t)),
    surfaceContainerHigh = Color(0xFAFAFAFF),
    surfaceContainerHighest = Color(0xFCFFFFFF),
    outline = Color(0x66111318),
    outlineVariant = Color(0x33111318),
)

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Read once per theme composition; children use LocalAppAppearance instead of hitting prefs.
    val appearance = remember { AppAppearancePrefs.get(context) }
    val isLight = appearance.theme != AppThemeMode.DARK
    val glass = appearance.isGlass
    val base = when {
        glass && isLight -> glassLightScheme(appearance.glassIntensity)
        glass -> glassDarkScheme(appearance.glassIntensity)
        isLight -> NovaLightColorScheme
        else -> NovaDarkColorScheme
    }

    // Surfaces/cards can be made translucent via the appearance settings,
    // giving a lightweight glass-panel look without an expensive full-screen blur.
    val alpha = appearance.surfaceOpacity
    val colorScheme = if (glass) base else base.copy(
        surface = base.surface.copy(alpha = alpha),
        surfaceContainerLowest = base.surfaceContainerLowest.copy(alpha = alpha),
        surfaceContainerLow = base.surfaceContainerLow.copy(alpha = alpha),
        surfaceContainer = base.surfaceContainer.copy(alpha = alpha),
        surfaceContainerHigh = base.surfaceContainerHigh.copy(alpha = alpha),
        surfaceContainerHighest = base.surfaceContainerHighest.copy(alpha = alpha),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
        }
    }

    ProvideGlass(appearance, dark = !isLight) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (glass) NovaGlassTypography else NovaTypography,
            shapes = if (glass) NovaGlassShapes else NovaShapes(appearance.cornerRadius),
            content = content,
        )
    }
}

