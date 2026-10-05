package com.firstt175.novaframe.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.firstt175.novaframe.prefs.AppAppearance
import com.firstt175.novaframe.prefs.AppStyle

/** Appearance read ONCE per theme composition (was re-read from SharedPreferences by every card). */
val LocalAppAppearance = staticCompositionLocalOf { AppAppearance() }

/** True when the Liquid Glass style is active and the current theme is the dark variant. */
val LocalGlassDark = compositionLocalOf { true }

/**
 * iOS 27 "Liquid Glass" slider value: 0f = Clear (most transparent, strongest refraction rim),
 * 1f = Tinted (most opaque, best contrast). Read by every glass surface so one slider in
 * Appearance restyles the whole app, exactly like Settings > Display & Brightness > Liquid Glass.
 */
val LocalGlassIntensity = compositionLocalOf { 0.45f }

val AppAppearance.isGlass: Boolean get() = style == AppStyle.LIQUID_GLASS

private val BackdropDark = Color(0xFF07080F)
private val BackdropLight = Color(0xFFEEF1F8)

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Full-screen backdrop for Liquid Glass. Drawn ONCE into a cached brush set (drawWithCache),
 * so it costs nothing per frame — no runtime blur / RenderEffect, which would compete with
 * the frame-generation work for GPU time.
 */
@Composable
fun LiquidGlassBackdrop(dark: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(
        modifier
            .background(if (dark) BackdropDark else BackdropLight)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h)
                val blobs = if (dark) listOf(
                    Triple(Offset(w * 0.10f, h * 0.06f), r * 0.70f, Color(0xFFFF8A2B).copy(alpha = 0.46f)),
                    Triple(Offset(w * 1.00f, h * 0.28f), r * 0.62f, Color(0xFF5E5CE6).copy(alpha = 0.42f)),
                    Triple(Offset(w * 0.18f, h * 0.88f), r * 0.72f, Color(0xFFBF3F9E).copy(alpha = 0.34f)),
                    Triple(Offset(w * 0.92f, h * 0.98f), r * 0.58f, Color(0xFF32ADE6).copy(alpha = 0.30f)),
                ) else listOf(
                    Triple(Offset(w * 0.10f, h * 0.06f), r * 0.70f, Color(0xFFFFB067).copy(alpha = 0.60f)),
                    Triple(Offset(w * 1.00f, h * 0.28f), r * 0.62f, Color(0xFF9DB4FF).copy(alpha = 0.58f)),
                    Triple(Offset(w * 0.15f, h * 0.90f), r * 0.72f, Color(0xFFF5A3E0).copy(alpha = 0.48f)),
                    Triple(Offset(w * 0.92f, h * 0.98f), r * 0.58f, Color(0xFF8FE3F2).copy(alpha = 0.48f)),
                )
                val brushes = blobs.map { (c, rad, col) ->
                    Brush.radialGradient(listOf(col, Color.Transparent), center = c, radius = rad)
                }
                onDrawBehind { brushes.forEach { drawRect(it) } }
            },
    )
}

/**
 * iOS 27 glass panel. Four cheap, cached layers (no blur, so no extra GPU work next to the
 * frame-generation pipeline):
 *
 *  1. body      – a tint scrim that grows with [intensity] plus a faint white lift;
 *  2. specular  – a soft highlight bleeding in from the top-left, strongest on Clear;
 *  3. rim       – a 1.25dp lit edge (bright TL / BR, dark in between) = the refraction edge;
 *  4. lens edge – a wide, very faint inner glow that fakes the glass thickness.
 *
 * @param intensity 0f = Clear … 1f = Tinted (see [LocalGlassIntensity]).
 * @param tint optional accent that colours the glass slightly (accented cards, selected pills).
 */
fun Modifier.glassSurface(
    shape: Shape,
    dark: Boolean,
    strong: Boolean = false,
    intensity: Float = 0.45f,
    tint: Color? = null,
    lift: Boolean = true,
): Modifier {
    val t = intensity.coerceIn(0f, 1f)
    val boost = if (strong) 1f else 0f

    val scrim: Color
    val liftTop: Color
    val liftBottom: Color
    if (dark) {
        scrim = Color(0xFF0B0C16).copy(alpha = (mix(0.04f, 0.66f, t) + 0.10f * boost).coerceAtMost(0.9f))
        liftTop = Color.White.copy(alpha = mix(0.13f, 0.10f, t) + 0.05f * boost)
        liftBottom = Color.White.copy(alpha = mix(0.04f, 0.06f, t) + 0.03f * boost)
    } else {
        scrim = Color.Transparent
        liftTop = Color.White.copy(alpha = (mix(0.42f, 0.90f, t) + 0.08f * boost).coerceAtMost(0.98f))
        liftBottom = Color.White.copy(alpha = (mix(0.20f, 0.80f, t) + 0.08f * boost).coerceAtMost(0.95f))
    }
    val specA = if (dark) mix(0.30f, 0.10f, t) else mix(0.60f, 0.25f, t)
    val rimBright = if (dark) mix(0.72f, 0.36f, t) else 1f
    val rimDim = if (dark) 0.05f else 0.18f
    val lensA = if (dark) mix(0.10f, 0.03f, t) else mix(0.30f, 0.10f, t)

    val base = if (lift) this.shadow(
        elevation = if (dark) 10.dp else 8.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f),
    ) else this

    return base
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val w = size.width
            val h = size.height
            val body = Brush.verticalGradient(listOf(liftTop, liftBottom))
            val specular = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = specA), Color.Transparent),
                center = Offset(w * 0.16f, 0f),
                radius = maxOf(w, h) * 0.85f,
            )
            val rim = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = rimBright),
                    Color.White.copy(alpha = rimDim),
                    Color.White.copy(alpha = rimDim),
                    Color.White.copy(alpha = rimBright * 0.55f),
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            )
            val lens = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = lensA), Color.Transparent, Color.White.copy(alpha = lensA * 0.7f)),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            )
            val rimStroke = Stroke(width = 1.25.dp.toPx())
            val lensStroke = Stroke(width = 7.dp.toPx())
            val hairline = Stroke(width = 0.5.dp.toPx())
            onDrawBehind {
                if (scrim.alpha > 0f) drawOutline(outline, scrim)
                drawOutline(outline, body)
                if (tint != null) drawOutline(outline, tint.copy(alpha = mix(0.16f, 0.24f, t)))
                drawOutline(outline, specular)
                drawOutline(outline, lens, style = lensStroke)
                if (!dark) drawOutline(outline, Color.Black.copy(alpha = 0.07f), style = hairline)
                drawOutline(outline, rim, style = rimStroke)
            }
        }
}

/**
 * iOS 27 uniform edge layer: whenever content scrolls behind a bar, the system lays down one
 * even scrim so bar text/icons stay readable. Put this behind a top bar (top = true) or a
 * tab bar / action row (top = false).
 */
fun Modifier.glassEdgeScrim(dark: Boolean, top: Boolean, intensity: Float = 0.45f): Modifier {
    val color = if (dark) BackdropDark else BackdropLight
    val a = mix(0.55f, 0.94f, intensity.coerceIn(0f, 1f))
    val brush = Brush.verticalGradient(
        colors = if (top) listOf(color.copy(alpha = a), Color.Transparent)
        else listOf(Color.Transparent, color.copy(alpha = a)),
    )
    return this.drawBehind { drawRect(brush) }
}

/** Interpolated accent used for glass fills that must stay legible at any intensity. */
fun glassAccentFill(accent: Color, intensity: Float): Color =
    lerp(accent.copy(alpha = 0.78f), accent.copy(alpha = 0.94f), intensity.coerceIn(0f, 1f))

@Composable
fun ProvideGlass(appearance: AppAppearance, dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppAppearance provides appearance,
        LocalGlassDark provides dark,
        LocalGlassIntensity provides appearance.glassIntensity,
        content = content,
    )
}
