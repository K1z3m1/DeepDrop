package com.firstt175.novaframe.ui.screens

import com.firstt175.novaframe.ui.components.NovaFilterChip
import com.firstt175.novaframe.ui.components.NovaSlider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.firstt175.novaframe.prefs.NovaPreferences
import com.firstt175.novaframe.prefs.UpscaleFilter
import com.firstt175.novaframe.session.NativeBridge
import com.firstt175.novaframe.ui.components.IconBadge
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image enhancement + upscale + render-resolution cards, with the live native preview.
 * Emits its cards directly into the parent Column, so it's meant to be called from
 * inside [OverlayDisplayScreen]'s Column (which supplies the scrolling, padding and
 * the 14 dp spacing between cards).
 */
@Composable
fun ImageEnhancementSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { NovaPreferences(ctx) }
    val initial = remember { prefs.load() }

    var enabled by remember { mutableStateOf(initial.imageEnhancementEnabled) }
    var contrast by remember { mutableFloatStateOf(initial.imageEnhancementContrast) }
    var saturation by remember { mutableFloatStateOf(initial.imageEnhancementSaturation) }
    var upscaleEnabled by remember { mutableStateOf(initial.upscaleEnabled) }
    var upscaleFilter by remember { mutableStateOf(initial.upscaleFilter) }
    var renderResolution by remember { mutableFloatStateOf(initial.renderResolutionScale) }
    var preview by remember {
        mutableStateOf(requireNotNull(BitmapFactory.decodeStream(ctx.assets.open("image/1.png"))))
    }
    val source = remember {
        // Keep one clean source bitmap. Native processing always works on a fresh copy,
        // preventing slider changes from accumulating rounding errors.
        requireNotNull(BitmapFactory.decodeStream(ctx.assets.open("image/1.png")))
    }

    fun saveLive() {
        prefs.setImageEnhancementEnabled(enabled)
        prefs.setImageEnhancementContrast(contrast)
        prefs.setImageEnhancementSaturation(saturation)
        prefs.setUpscaleEnabled(upscaleEnabled)
        prefs.setUpscaleFilter(upscaleFilter)
        prefs.setRenderResolutionScale(renderResolution)
        runCatching {
            NativeBridge.setImageEnhancement(enabled, 1, contrast, saturation)
            NativeBridge.setUpscaleEnabled(upscaleEnabled)
            NativeBridge.setUpscaleFilter(upscaleFilter.ordinal)
        }
    }

    LaunchedEffect(enabled, contrast, saturation, upscaleEnabled, upscaleFilter, renderResolution) {
        saveLive()
        val result = withContext(Dispatchers.Default) {
            val copy = source.copy(Bitmap.Config.ARGB_8888, true)
            val ok = runCatching {
                NativeBridge.processImageEnhancementPreview(
                    copy,
                    enabled,
                    1,
                    contrast,
                    saturation,
                    upscaleEnabled,
                    upscaleFilter.ordinal,
                    renderResolution,
                )
            }.getOrDefault(false)
            if (ok) copy else source.copy(Bitmap.Config.ARGB_8888, true)
        }
        preview = result
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 6f)
        panX += panChange.x
        panY += panChange.y
    }

    NovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.AutoAwesome, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column {
                Text("LIVE RENDER-LOOP PREVIEW", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Preview uses the native render-loop image settings. There is no Compose color-filter calculation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier.fillMaxWidth().height(280.dp)
                .background(Color.Black)
                .clipToBounds()
                .transformable(transformState)
                .pointerInput(Unit) {
                    // Double tap is intentionally kept simple: reset the zoom/pan state.
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Image enhancement preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                }
            )
            Text(
                "Pinch / drag to zoom • double tap to reset",
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (enabled) "LIVE • GPU / Vulkan • ${upscaleFilter.name}" else "LIVE PREVIEW OFF",
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    NovaCard {
        SectionHeader(eyebrow = "IMAGE ENHANCEMENT / POST PROCESS", title = null)
        NovaFilterChip(
            selected = enabled,
            onClick = { enabled = !enabled },
            label = { Text(if (enabled) "Enabled" else "Disabled") },
            leadingIcon = { IconBadge(icon = Icons.Filled.AutoAwesome, size = 20.dp) }
        )
        Spacer(Modifier.height(10.dp))
        Text("Processing backend  •  GPU / Vulkan", style = MaterialTheme.typography.bodyMedium)
        Text("Contrast  ${"%.2f".format(contrast)}×", style = MaterialTheme.typography.bodyMedium)
        NovaSlider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..1.5f)
        Text("Saturation  ${"%.2f".format(saturation)}×", style = MaterialTheme.typography.bodyMedium)
        NovaSlider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
    }

    NovaCard {
        SectionHeader(eyebrow = "UPSCALE", title = null)
        NovaFilterChip(
            selected = upscaleEnabled,
            onClick = { upscaleEnabled = !upscaleEnabled },
            label = { Text(if (upscaleEnabled) "Upscale Enabled" else "Upscale Disabled") },
        )
        Spacer(Modifier.height(8.dp))
        Text("Scaling filter", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NovaFilterChip(
                selected = upscaleFilter == UpscaleFilter.NEAREST,
                onClick = { upscaleFilter = UpscaleFilter.NEAREST },
                label = { Text("Nearest") }, modifier = Modifier.weight(1f)
            )
            NovaFilterChip(
                selected = upscaleFilter == UpscaleFilter.BILINEAR,
                onClick = { upscaleFilter = UpscaleFilter.BILINEAR },
                label = { Text("Bilinear") }, modifier = Modifier.weight(1f)
            )
        }
        Text(
            if (upscaleEnabled) "The selected filter is used by the render-loop presentation path when render resolution is below display resolution."
            else "Upscale customization is off; presentation falls back to the cheapest nearest filter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    NovaCard {
        SectionHeader(eyebrow = "RENDER / OVERLAY SETTINGS", title = null)
        Text("Render resolution  ${(renderResolution * 100f).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        NovaSlider(
            value = renderResolution,
            onValueChange = { renderResolution = it },
            valueRange = 0.1f..1f,
            steps = 17,
        )
        Text(
            "This is the same render-resolution control used by SettingsDrawerOverlay. Lower values reduce the captured/rendered buffer before it is presented back at display size.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text("Frame generation", style = MaterialTheme.typography.titleSmall)
        Text("Multiplier: ${initial.multiplier}×    Flow scale: ${"%.2f".format(initial.flowScale)}", style = MaterialTheme.typography.bodySmall)
        Text("Performance: ${if (initial.performanceMode) "On" else "Off"}    HDR: ${if (initial.hdrMode) "On" else "Off"}", style = MaterialTheme.typography.bodySmall)
        Text("FP16 shaders: ${if (initial.framegenFp16) "On" else "Off"}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Frame scheduling", style = MaterialTheme.typography.titleSmall)
        Text("Wait for busy generation: ${if (initial.waitForBusyGeneration) "On" else "Off"}", style = MaterialTheme.typography.bodySmall)
        Text("Allow generation while busy: ${if (initial.allowGenerationWhenBusy) "On" else "Off"}", style = MaterialTheme.typography.bodySmall)
        Text("Lossless queue: ${if (initial.losslessQueue) "On" else "Off"}", style = MaterialTheme.typography.bodySmall)
    }

    NovaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AspectRatio, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Preview controls", style = MaterialTheme.typography.titleSmall)
                Text("Zoom is view-only and does not affect the render-loop settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NovaFilterChip(
                selected = false,
                onClick = { zoom = 1f; panX = 0f; panY = 0f },
                label = { Text("Reset") },
                leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
            )
        }
    }
}
