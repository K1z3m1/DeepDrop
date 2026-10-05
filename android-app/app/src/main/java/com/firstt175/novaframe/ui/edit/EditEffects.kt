package com.firstt175.novaframe.ui.edit

import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Size of the picture after rotation and aspect-ratio change (before any
 * export-resolution downscale). Mirrors what Media3's Presentation does so the
 * text overlay can be sized in real pixels.
 */
fun outputSize(v: VisualSettings, src: SourceInfo): Pair<Int, Int> {
    var w = src.width.toFloat()
    var h = src.height.toFloat()
    if (v.rotation % 180 != 0) {
        val t = w; w = h; h = t
    }
    val req = v.aspect.ratio
    if (req != null) {
        val inAr = w / h
        if (v.aspectFit) {
            if (req > inAr) w = h * req else h = w / req
        } else {
            if (req > inAr) h = w / req else w = h * req
        }
    }
    return w.roundToInt().coerceAtLeast(2) to h.roundToInt().coerceAtLeast(2)
}

/**
 * The whole-picture effect chain, shared by the live preview and the export so
 * what you see is what you get. Order: geometry → colour → text → (export only) downscale.
 */
@OptIn(UnstableApi::class)
fun buildVideoEffects(v: VisualSettings, src: SourceInfo, maxHeight: Int = 0): List<Effect> {
    val out = ArrayList<Effect>()

    if (v.rotation % 360 != 0 || v.flipH) {
        out += ScaleAndRotateTransformation.Builder()
            .setScale(if (v.flipH) -1f else 1f, 1f)
            // Media3 rotates counter-clockwise; the UI rotates clockwise.
            .setRotationDegrees(((360 - v.rotation % 360) % 360).toFloat())
            .build()
    }

    v.aspect.ratio?.let { ratio ->
        out += Presentation.createForAspectRatio(
            ratio,
            if (v.aspectFit) Presentation.LAYOUT_SCALE_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        )
    }

    val p = v.preset
    if (p.red != 1f || p.green != 1f || p.blue != 1f) {
        out += RgbAdjustment.Builder()
            .setRedScale(p.red)
            .setGreenScale(p.green)
            .setBlueScale(p.blue)
            .build()
    }
    val brightness = (p.brightness + v.brightness).coerceIn(-1f, 1f)
    val contrast = (p.contrast + v.contrast).coerceIn(-1f, 1f)
    val saturation = ((p.saturation + v.saturation) * 100f).coerceIn(-100f, 100f)
    if (abs(brightness) > 0.001f) out += Brightness(brightness)
    if (abs(contrast) > 0.001f) out += Contrast(contrast)
    if (abs(saturation) > 0.1f) out += HslAdjustment.Builder().adjustSaturation(saturation).build()

    v.text?.takeIf { it.text.isNotBlank() }?.let { layer ->
        val (_, outH) = outputSize(v, src)
        out += OverlayEffect(ImmutableList.of<TextureOverlay>(buildTextOverlay(layer, outH)))
    }

    if (maxHeight > 0) {
        val (_, outH) = outputSize(v, src)
        if (outH > maxHeight) out += Presentation.createForHeight(maxHeight)
    }
    return out
}

@OptIn(UnstableApi::class)
private fun buildTextOverlay(layer: TextLayer, outHeight: Int): TextOverlay {
    val text = layer.text.replace('\n', ' ').take(80)
    val span = SpannableString(text)
    val scale = (layer.size * outHeight / TextOverlay.TEXT_SIZE_PIXELS).coerceAtLeast(0.2f)
    span.setSpan(RelativeSizeSpan(scale), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    span.setSpan(ForegroundColorSpan(layer.color), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (layer.box) {
        span.setSpan(BackgroundColorSpan(0xAA000000.toInt()), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Anchor the matching edge of the text to a point 8% in from that edge of the frame.
    val settings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(layer.h * 0.92f, layer.v * 0.92f)
        .setOverlayFrameAnchor(layer.h.toFloat(), layer.v.toFloat())
        .build()
    return TextOverlay.createStaticTextOverlay(span, settings)
}

/** Per-segment audio (volume) and timing (speed) effects for the export. */
@OptIn(UnstableApi::class)
fun buildSegmentEffects(seg: Segment, src: SourceInfo): Effects {
    val audio = ArrayList<AudioProcessor>()
    val video = ArrayList<Effect>()
    val changesSpeed = abs(seg.speed - 1f) > 0.001f

    if (src.hasAudio) {
        if (abs(seg.volume - 1f) > 0.001f) {
            val mixer = ChannelMixingAudioProcessor()
            for (ch in linkedSetOf(1, 2, src.audioChannels.coerceAtLeast(1))) {
                mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(seg.volume))
            }
            audio += mixer
        }
        if (changesSpeed) {
            audio += SonicAudioProcessor().apply {
                setSpeed(seg.speed)
                setPitch(1f) // time-stretch: faster/slower without chipmunk pitch
            }
        }
    }
    if (changesSpeed) video += SpeedChangeEffect(seg.speed)
    return Effects(audio, video)
}
