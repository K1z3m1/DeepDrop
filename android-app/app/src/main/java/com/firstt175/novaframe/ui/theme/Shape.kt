package com.firstt175.novaframe.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

fun NovaShapes(radius: Float = 14f) = Shapes(
    extraSmall = RoundedCornerShape((radius * 0.30f).dp),
    small = RoundedCornerShape((radius * 0.50f).dp),
    medium = RoundedCornerShape(radius.dp),
    large = RoundedCornerShape((radius * 1.15f).dp),
    extraLarge = RoundedCornerShape((radius * 1.35f).dp),
)

/**
 * iOS 27 uses one consistent, generous corner language instead of per-screen radii: controls are
 * capsules, cards/sheets share a single large radius, and nested elements step down concentrically
 * (inner radius = outer radius - padding) so corners always look parallel.
 */
val NovaGlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)
