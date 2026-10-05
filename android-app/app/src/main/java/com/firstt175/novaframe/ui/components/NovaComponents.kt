package com.firstt175.novaframe.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firstt175.novaframe.R
import com.firstt175.novaframe.ui.theme.LocalAppAppearance
import com.firstt175.novaframe.ui.theme.LocalGlassDark
import com.firstt175.novaframe.ui.theme.LocalGlassIntensity
import com.firstt175.novaframe.ui.theme.NovaGlow
import com.firstt175.novaframe.ui.theme.NovaGradientEnd
import com.firstt175.novaframe.ui.theme.NovaPrimary
import com.firstt175.novaframe.ui.theme.NovaStatusBad
import com.firstt175.novaframe.ui.theme.NovaStatusGood
import com.firstt175.novaframe.ui.theme.NovaStatusWarn
import com.firstt175.novaframe.ui.theme.glassSurface
import com.firstt175.novaframe.ui.theme.isGlass

/** True while the iOS 27 Liquid Glass style is active. */
@Composable
private fun rememberIsGlass(): Boolean = LocalAppAppearance.current.isGlass

private data class WaterTap(val point: androidx.compose.ui.geometry.Offset, val id: Long)

/** A lightweight liquid/water pulse that starts exactly where the user touches. */
fun Modifier.waterClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    var tap by remember { mutableStateOf<WaterTap?>(null) }
    val currentTap = tap
    val progress by animateFloatAsState(
        targetValue = if (currentTap == null) 1f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "waterTap",
        finishedListener = { tap = null },
    )
    Modifier
        .pointerInput(enabled) {
            if (enabled) {
                detectTapGestures { offset ->
                    tap = WaterTap(offset, System.nanoTime())
                    onClick()
                }
            }
        }
        .drawWithContent {
            drawContent()
            val ripple = currentTap
            if (ripple != null) {
                val radius = (size.maxDimension * 0.18f) + (size.maxDimension * 0.70f * (1f - progress))
                val alpha = 0.24f * progress
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = alpha), Color.Transparent),
                        center = ripple.point,
                        radius = radius,
                    ),
                    radius = radius,
                    center = ripple.point,
                )
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.75f),
                    radius = radius * 0.82f,
                    center = ripple.point,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
}

/**
 * A card used as the base container throughout the UI.
 *
 * Normal style: solid Material card, accented cards get a thin top strip.
 * iOS 27 Liquid Glass: a lit glass panel whose transparency follows the Clear <-> Tinted slider;
 * accented cards tint the glass with the accent instead of drawing a strip.
 */
@Composable
fun NovaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues? = null,
    accent: Boolean = false,
    accentColor: Color = NovaPrimary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val appearance = LocalAppAppearance.current
    val shape = MaterialTheme.shapes.medium
    val glass = appearance.isGlass
    val container = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer
    val borderAlpha = appearance.borderOpacity
    val border = if (glass) null else if (accent) {
        BorderStroke(1.dp, SolidColor(accentColor.copy(alpha = (borderAlpha + 0.3f).coerceAtMost(1f))))
    } else {
        BorderStroke(1.dp, SolidColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha)))
    }
    val animated = if (appearance.animationsEnabled) modifier.animateContentSize() else modifier
    val intensity = LocalGlassIntensity.current
    val cardModifier = if (glass) {
        animated.glassSurface(
            shape = shape,
            dark = LocalGlassDark.current,
            intensity = intensity,
            tint = if (accent) accentColor else null,
        )
    } else animated
    val effectivePadding = when {
        appearance.compactMode -> PaddingValues(horizontal = 10.dp, vertical = 7.dp)
        contentPadding != null -> contentPadding
        glass -> PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        else -> PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    }
    val cardContent: @Composable ColumnScope.() -> Unit = {
        if (accent && !glass) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accentColor.copy(alpha = 0.85f)),
            )
        }
        Column(
            Modifier.padding(effectivePadding),
            content = content,
        )
    }
    val elevation = CardDefaults.cardElevation(defaultElevation = if (glass) 0.dp else appearance.shadowElevation.dp)
    if (onClick != null) {
        Card(
            modifier = cardModifier.waterClickable(onClick = onClick),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container),
            border = border,
            elevation = elevation,
            content = cardContent,
        )
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = container),
            border = border,
            elevation = elevation,
            content = cardContent,
        )
    }
}

/**
 * Section header. Normal style keeps the orange monospace eyebrow; iOS 27 uses the grouped-list
 * header look — sentence case, small, secondary colour, inset to line up with the card content.
 */
@Composable
fun SectionHeader(
    eyebrow: String,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    val glass = rememberIsGlass()
    Column(modifier = if (glass) modifier.padding(start = 16.dp, top = 6.dp) else modifier.padding(start = 4.dp)) {
        Text(
            text = eyebrow,
            style = if (glass) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            color = if (glass) MaterialTheme.colorScheme.onSurfaceVariant else NovaPrimary,
        )
        if (title != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

enum class StatusTone { Good, Warn, Bad, Neutral }

@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val glass = rememberIsGlass()
    val color = when (tone) {
        StatusTone.Good -> NovaStatusGood
        StatusTone.Warn -> NovaStatusWarn
        StatusTone.Bad -> NovaStatusBad
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (glass) 0.20f else 0.12f))
            .then(if (glass) Modifier.border(0.5.dp, color.copy(alpha = 0.45f), CircleShape) else Modifier)
            .padding(horizontal = if (glass) 10.dp else 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

/**
 * Small icon tile. Normal: tinted square with a coloured glyph. iOS 27: a glossy squircle in
 * the style of a Settings app icon — gradient fill, white glyph, lit top edge.
 * [glassTint] only applies in the glass style so Normal keeps its orange-only palette.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color = NovaPrimary,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    glassTint: Color? = null,
) {
    val glass = rememberIsGlass()
    if (glass) {
        val base = glassTint ?: tint
        val shape = RoundedCornerShape(size * 0.30f)
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            androidx.compose.ui.graphics.lerp(base, Color.White, 0.22f),
                            base,
                        ),
                    ),
                )
                .border(
                    0.75.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.05f))),
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.56f),
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.32f))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.50f),
            )
        }
    }
}

/** Hairline separator inset past the leading icon, like an iOS grouped list. */
@Composable
fun NovaInsetDivider(startInset: Dp = 52.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    )
}

/**
 * iOS-style switch: white thumb, borderless track, accent when on. Use instead of a bare
 * Material3 Switch so every toggle in the app matches.
 */
@Composable
fun NovaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val glass = rememberIsGlass()
    if (glass) {
        LiquidSwitch(checked, onCheckedChange, modifier, enabled)
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = NovaPrimary,
                checkedBorderColor = NovaPrimary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/**
 * iOS-style slider: slim capsule track and a white, softly shadowed thumb. Falls back to the
 * stock Material3 slider in the Normal style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val glass = rememberIsGlass()
    if (!glass) {
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = NovaPrimary,
                activeTrackColor = NovaPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    val dark = LocalGlassDark.current
    val inactive = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.14f)
    val active = if (enabled) NovaPrimary else NovaPrimary.copy(alpha = 0.4f)
    val thumbSize = 28.dp

    // Liquid thumb: swells like a drop when grabbed, and stretches along the track while it
    // is moving (the "lag" value trails the real position, the gap becomes the stretch).
    val source = remember { MutableInteractionSource() }
    val dragged by source.collectIsDraggedAsState()
    val pressed by source.collectIsPressedAsState()
    val grab by animateFloatAsState(
        targetValue = if (dragged || pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 420f),
        label = "sliderGrab",
    )
    val rangeSpan = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fracNow = ((value - valueRange.start) / rangeSpan).coerceIn(0f, 1f)
    val lag by animateFloatAsState(
        targetValue = fracNow,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
        label = "sliderLag",
    )
    val stretch = (kotlin.math.abs(fracNow - lag) * 14f).coerceIn(0f, 0.7f)

    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = source,
        modifier = modifier.fillMaxWidth(),
        thumb = {
            Box(
                Modifier
                    .size(thumbSize)
                    .graphicsLayer {
                        val swell = 1f + 0.25f * grab
                        scaleX = swell * (1f + stretch)
                        scaleY = swell * (1f - 0.25f * stretch)
                    }
                    .shadow(5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                    .background(Color.White, CircleShape)
                    .border(2.dp, NovaPrimary.copy(alpha = (0.45f * grab).coerceIn(0f, 1f)), CircleShape),
            )
        },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
            val frac = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2f)
                        val half = thumbSize.toPx() / 2f
                        drawRoundRect(color = inactive, cornerRadius = r)
                        val x = half + frac * (size.width - 2f * half)
                        drawRoundRect(color = active, size = Size(x, size.height), cornerRadius = r)
                    },
            )
        },
    )
}

/**
 * Segmented control. iOS 27: a glass capsule track with a tinted glass pill on the selection.
 * Normal: the stock Material3 single-choice segmented row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = rememberIsGlass()
    if (!glass) {
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
        return
    }
    val dark = LocalGlassDark.current
    val intensity = LocalGlassIntensity.current
    val capsule = RoundedCornerShape(50)
    val liquid = rememberLiquidSpan(selectedIndex, options.size)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(capsule, dark, intensity = intensity, lift = false)
            .padding(4.dp),
    ) {
        val itemWidth = maxWidth / options.size
        // One glass pill that flows to the chosen segment.
        Box(
            modifier = Modifier
                .offset(x = itemWidth * liquid.left)
                .width(itemWidth * liquid.span)
                .height(38.dp)
                .graphicsLayer { scaleY = 1f - 0.07f * liquid.stretch }
                .glassSurface(
                    shape = capsule,
                    dark = dark,
                    strong = true,
                    intensity = intensity,
                    tint = NovaPrimary,
                    lift = false,
                ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(capsule)
                        .waterClickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ───────────────────────── Liquid building blocks ─────────────────────────

/** Where a liquid selection pill currently is, in units of "items" (0 = left edge of item 0). */
@Immutable
class LiquidSpan(val left: Float, val span: Float, val stretch: Float)

/**
 * Spring-driven pill position. The edge in the direction of travel springs ahead fast while the
 * other edge lags, so the pill stretches like a drop of water mid-flight and wobbles on landing.
 */
@Composable
fun rememberLiquidSpan(selectedIndex: Int, count: Int): LiquidSpan {
    val lastIndex = remember { IntArray(1) { selectedIndex } }
    val goingRight = remember { BooleanArray(1) { true } }
    if (selectedIndex != lastIndex[0]) {
        goingRight[0] = selectedIndex > lastIndex[0]
        lastIndex[0] = selectedIndex
    }
    val lead = spring<Float>(dampingRatio = 0.62f, stiffness = 640f)
    val trail = spring<Float>(dampingRatio = 0.80f, stiffness = 200f)
    val l by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (goingRight[0]) trail else lead,
        label = "liquidLeft",
    )
    val r by animateFloatAsState(
        targetValue = selectedIndex + 1f,
        animationSpec = if (goingRight[0]) lead else trail,
        label = "liquidRight",
    )
    val left = l.coerceIn(0f, count - 0.85f)
    val right = r.coerceIn(left + 0.85f, count.toFloat())
    return LiquidSpan(left, right - left, ((right - left) - 1f).coerceIn(0f, 1.2f))
}

/**
 * Glass-style switch whose thumb is a drop of liquid: it stretches as it travels, squashes a
 * little, widens while pressed and settles with a wobble. Tap or drag to toggle.
 */
@Composable
private fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
) {
    val dark = LocalGlassDark.current
    val trackW = 52.dp
    val trackH = 32.dp
    val thumb = 26.dp
    val pad = 3.dp
    val travel = trackW - pad * 2 - thumb

    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lead = spring<Float>(dampingRatio = 0.55f, stiffness = 520f)
    val trail = spring<Float>(dampingRatio = 0.80f, stiffness = 220f)
    // Turning on: the right edge of the thumb leads. Turning off: the left edge leads.
    val leftP by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (checked) trail else lead,
        label = "switchLeft",
    )
    val rightP by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (checked) lead else trail,
        label = "switchRight",
    )
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "switchPress",
    )
    val offColor = if (dark) Color.White.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.16f)
    val trackColor by animateColorAsState(
        targetValue = if (checked) NovaPrimary else offColor,
        animationSpec = tween(durationMillis = 260),
        label = "switchTrack",
    )

    val left = leftP.coerceIn(0f, 1f)
    val right = rightP.coerceIn(left, 1f)
    val stretch = right - left
    val extra = 4.dp * press

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .size(trackW, trackH)
            .background(trackColor, CircleShape)
            .then(
                if (onCheckedChange != null) {
                    Modifier
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Switch,
                            interactionSource = source,
                            indication = null,
                            onValueChange = onCheckedChange,
                        )
                        .pointerInput(checked, enabled) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    if (enabled) {
                                        if (total > 8f && !checked) onCheckedChange(true)
                                        else if (total < -8f && checked) onCheckedChange(false)
                                    }
                                },
                                onHorizontalDrag = { _, dx -> total += dx },
                            )
                        }
                } else Modifier,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = pad + travel * left - extra * left)
                .width(thumb + travel * stretch + extra)
                .height(thumb)
                .graphicsLayer { scaleY = 1f - 0.10f * stretch }
                .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * Filter chip with a jelly feel: squishes while pressed and pops back with a wobble whenever
 * its selected state changes. Drop-in for Material3 FilterChip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val squish by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f),
        label = "chipSquish",
    )
    val pop = remember { Animatable(1f) }
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (firstRun) {
            firstRun = false
        } else {
            pop.snapTo(0.88f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 380f))
        }
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.graphicsLayer {
            scaleX = squish * pop.value
            scaleY = squish * (2f - pop.value)
        },
        enabled = enabled,
        leadingIcon = leadingIcon,
        interactionSource = source,
        shape = if (rememberIsGlass()) androidx.compose.material3.FilterChipDefaults.shape else RoundedCornerShape(50),
        colors = if (rememberIsGlass()) androidx.compose.material3.FilterChipDefaults.filterChipColors()
        else androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/**
 * Row with leading icon, title, description, and trailing switch.
 */
@Composable
fun ToggleRow(
    icon: ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    glassTint: Color? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .waterClickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        IconBadge(icon = icon, size = 28.dp, glassTint = glassTint)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(8.dp))
        NovaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Slider row with title, value chip, description, and slider.
 */
@Composable
fun ValueSlider(
    title: String,
    valueDisplay: String,
    description: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glassTint: Color? = null,
) {
    val glass = rememberIsGlass()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                IconBadge(icon = leadingIcon, size = 32.dp, glassTint = glassTint)
                Spacer(Modifier.size(10.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(if (glass) CircleShape else RoundedCornerShape(8.dp))
                    .background(NovaPrimary.copy(alpha = if (glass) 0.22f else 0.14f))
                    .padding(horizontal = if (glass) 10.dp else 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = valueDisplay,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = NovaPrimary,
                )
            }
        }
        NovaSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

/**
 * A card that starts collapsed and expands on tap. Use this for advanced /
 * diagnostic / rarely-needed content (raw device info, low-level tuning)
 * so a first-time user sees a short, calm screen by default instead of
 * every card at once — the detail is one tap away, not gone.
 *
 * [subtitle] stays visible even while collapsed so the user knows roughly
 * what's inside before deciding to open it (e.g. a one-line status summary).
 */
@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    startExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    // Only the header row is clickable — not the whole card. Wiring onClick
    // onto NovaCard itself would make every tap inside the expanded content
    // (e.g. tapping a line of body text between two sliders) also toggle
    // the section shut, which fights the user while they're reading it.
    NovaCard(modifier = modifier) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .waterClickable { expanded = !expanded },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(chevron),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Column(content = content)
            }
        }
    }
}

/**
 * Filled "hero" button. Normal: two-tone orange gradient. iOS 27: a tinted glass capsule —
 * vertical accent gradient, glossy top highlight, lit rim and a soft accent glow underneath.
 */
@Composable
fun NovaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val gradient = Brush.horizontalGradient(
        colors = if (enabled) {
            listOf(NovaGlow, NovaGradientEnd)
        } else {
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        },
    )
    val glass = rememberIsGlass()
    val btnShape = RoundedCornerShape(50)
    val glassGradient = Brush.verticalGradient(
        if (enabled) listOf(
            NovaGlow.copy(alpha = 0.96f),
            NovaGradientEnd.copy(alpha = 0.90f),
        ) else listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.07f)),
    )
    val gloss = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (enabled) 0.34f else 0.10f),
        0.5f to Color.Transparent,
    )
    Box(
        modifier = modifier
            .then(
                if (glass && enabled) Modifier.shadow(
                    elevation = 12.dp,
                    shape = btnShape,
                    clip = false,
                    ambientColor = NovaGradientEnd.copy(alpha = 0.45f),
                    spotColor = NovaGradientEnd.copy(alpha = 0.55f),
                ) else Modifier,
            )
            .clip(btnShape)
            .background(if (glass) glassGradient else gradient)
            .then(if (glass) Modifier.background(gloss) else Modifier)
            .then(
                if (glass) Modifier.border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.80f), Color.White.copy(alpha = 0.12f)),
                    ),
                    btnShape,
                ) else Modifier,
            )
            .waterClickable(enabled = enabled) { onClick() }
            .heightIn(min = if (glass) 52.dp else 0.dp)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = NovaOnPrimaryColorForGradient(enabled),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NovaOnPrimaryColorForGradient(enabled),
            )
        }
    }
}

@Composable
private fun NovaOnPrimaryColorForGradient(enabled: Boolean): Color =
    if (enabled) Color(0xFF1A0F00) else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Secondary button. Normal: outlined. iOS 27: a clear glass capsule.
 */
@Composable
fun NovaSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val glass = rememberIsGlass()
    if (glass) {
        val shape = RoundedCornerShape(50)
        val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
        Box(
            modifier = modifier
                .glassSurface(
                    shape = shape,
                    dark = LocalGlassDark.current,
                    intensity = LocalGlassIntensity.current,
                    lift = false,
                )
                .waterClickable(enabled = enabled) { onClick() }
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(text = text, style = MaterialTheme.typography.labelLarge, color = contentColor)
            }
        }
        return
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * App top bar with optional back + trailing icon slot. iOS 27: a floating glass circle for back
 * and a bold large-style title.
 */
@Composable
fun NovaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val glass = rememberIsGlass()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .then(
                        if (glass) Modifier.glassSurface(
                            shape = CircleShape,
                            dark = LocalGlassDark.current,
                            strong = true,
                            intensity = LocalGlassIntensity.current,
                        ) else Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    )
                    .waterClickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (glass) Icons.Filled.ChevronLeft else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(if (glass) 30.dp else 24.dp),
                )
            }
            Spacer(Modifier.size(if (glass) 10.dp else 4.dp))
        } else {
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text = title,
            style = if (glass) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

/**
 * Brand logo mark — renders the app icon inside a rounded tile.
 */
@Composable
fun NovaLogoMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(
            id = com.firstt175.novaframe.R.drawable.nova_app_icon,
        ),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (rememberIsGlass()) size * 0.225f else 8.dp)),
    )
}
