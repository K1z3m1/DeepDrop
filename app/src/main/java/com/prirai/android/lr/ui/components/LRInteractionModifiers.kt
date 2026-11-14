package com.prirai.android.lr.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale =
        animateFloatAsState(
            targetValue = if (isPressed) pressedScale else 1f,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "pressScale",
        )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

fun LazyItemScope.animateLRListItem(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(ITEM_FADE_IN_MILLIS, easing = EaseOutCubic),
        fadeOutSpec = tween(ITEM_FADE_OUT_MILLIS, easing = EaseInCubic),
        placementSpec = spring(dampingRatio = ITEM_PLACEMENT_DAMPING, stiffness = Spring.StiffnessLow),
    )

private const val ITEM_FADE_IN_MILLIS = 300
private const val ITEM_FADE_OUT_MILLIS = 200
private const val ITEM_PLACEMENT_DAMPING = 0.8f

fun LazyGridItemScope.animateLRGridItem(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(ITEM_FADE_IN_MILLIS, easing = EaseOutCubic),
        fadeOutSpec = tween(ITEM_FADE_OUT_MILLIS, easing = EaseInCubic),
        placementSpec = spring(dampingRatio = ITEM_PLACEMENT_DAMPING, stiffness = Spring.StiffnessLow),
    )

fun Modifier.thumbnailGradientOverlay(
    color: Color = Color.Black,
    alpha: Float = 0.75f,
    startFraction: Float = 0.45f,
): Modifier =
    this.drawWithCache {
        val brush =
            Brush.verticalGradient(
                colors =
                    listOf(
                        Color.Transparent,
                        color.copy(alpha = alpha),
                    ),
                startY = size.height * startFraction,
                endY = size.height,
            )
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLRSheetState(skipPartiallyExpanded: Boolean = true): SheetState =
    rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues =
            if (skipPartiallyExpanded) {
                setOf(SheetValue.Hidden, SheetValue.Expanded)
            } else {
                setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
            },
    )
