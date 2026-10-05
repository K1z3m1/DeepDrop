package com.firstt175.novaframe.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared spacing scale. Screens previously sprinkled ad-hoc dp values
 * (4, 8, 10, 12, 14, 16, 18, 20...) inconsistently, which is why paddings
 * drifted slightly between screens even though they were meant to match.
 * New/updated screens should pull from here instead of hand-picking a
 * number, so the whole app keeps one consistent rhythm.
 */
object NovaSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp

    /** Standard horizontal screen margin used by every top-level screen. */
    val screenHorizontal: Dp = 12.dp
}
