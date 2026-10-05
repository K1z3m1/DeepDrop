package com.firstt175.novaframe.ui.edit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Facts about the recording being edited. Width/height are already rotation-corrected. */
data class SourceInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val audioChannels: Int,
    val frameRate: Float,
)

suspend fun loadSourceInfo(ctx: Context, uri: Uri): SourceInfo? = withContext(Dispatchers.IO) {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(ctx, uri)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        if (w == null || h == null || w <= 0 || h <= 0) return@withContext null
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        val (ew, eh) = if (rot == 90 || rot == 270) h to w else w to h

        var channels = 2
        var fps = 30f
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/") && f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                } else if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val r = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                        ?: runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }.getOrNull()
                    if (r != null && r in 1..240) fps = r.toFloat()
                }
            }
        } catch (_: Exception) {
            // Keep the defaults; they only affect frame-step size and volume matrices.
        } finally {
            ex.release()
        }
        SourceInfo(ew, eh, dur, hasAudio, channels, fps)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        mmr.release()
    }
}

/** Evenly spaced thumbnails of the source, filled in progressively for the timeline. */
class Filmstrip(val count: Int) {
    val frames = mutableStateListOf<ImageBitmap?>().apply { repeat(count) { add(null) } }
}

suspend fun fillFilmstrip(ctx: Context, uri: Uri, src: SourceInfo, strip: Filmstrip) {
    val thumbH = 96
    val thumbW = (thumbH * src.width.toFloat() / src.height).toInt().coerceIn(48, 320)
    val mmr = MediaMetadataRetriever()
    try {
        withContext(Dispatchers.IO) { mmr.setDataSource(ctx, uri) }
        for (i in 0 until strip.count) {
            val tUs = ((i + 0.5) / strip.count * src.durationMs * 1000.0).toLong()
            val bmp = withContext(Dispatchers.IO) {
                mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, thumbW, thumbH)
            }
            if (bmp != null) strip.frames[i] = bmp.asImageBitmap()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Thumbnails are cosmetic; the timeline still works on plain blocks.
    } finally {
        mmr.release()
    }
}
