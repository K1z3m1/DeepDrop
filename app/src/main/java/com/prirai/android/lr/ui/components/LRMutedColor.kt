package com.prirai.android.lr.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

private const val MAX_SATURATION = 0.52f
private const val LIGHT_MODE_LIGHTNESS = 0.42f
private const val DARK_MODE_LIGHTNESS = 0.74f

@Composable
fun Color.mutedAdaptive(): Color =
    mutedAdaptive(darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f)

fun Color.mutedAdaptive(darkSurface: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtMost(MAX_SATURATION)
    hsv[2] = if (darkSurface) {
        (hsv[2].coerceAtLeast(DARK_MODE_LIGHTNESS) + DARK_MODE_LIGHTNESS) / 2f
    } else {
        (hsv[2].coerceAtMost(LIGHT_MODE_LIGHTNESS) + LIGHT_MODE_LIGHTNESS) / 2f
    }
    return Color(android.graphics.Color.HSVToColor(hsv))
}
