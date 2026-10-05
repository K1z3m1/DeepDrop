package com.firstt175.novaframe.ui.edit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class ExportQuality(val label: String, val bitsPerPixel: Double) {
    HIGH("High", 8.0),
    MEDIUM("Medium", 5.0),
    LOW("Low", 3.0),
}

data class ExportOptions(
    /** 0 = keep the edited resolution, otherwise downscale to at most this height. */
    val maxHeight: Int = 0,
    val quality: ExportQuality = ExportQuality.HIGH,
    val hevc: Boolean = false,
    /** 0 = off, otherwise AI frame generation multiplies the frame rate by this (2..4). */
    val aiMultiplier: Int = 0,
)

/**
 * [videoBitrate] is what the edit is rendered at (a bit higher when AI frame gen will re-encode it);
 * [finalBitrate] is what the delivered file is encoded at; [outFps] is only known when [aiMultiplier] > 0.
 */
data class OutputPlan(
    val width: Int,
    val height: Int,
    val videoBitrate: Int,
    val finalBitrate: Int,
    val outFps: Int,
    val approxBytes: Long,
)

fun planOutput(state: EditState, src: SourceInfo, opts: ExportOptions): OutputPlan {
    var (w, h) = outputSize(state.visual, src)
    if (opts.maxHeight > 0 && h > opts.maxHeight) {
        w = (w * opts.maxHeight.toFloat() / h).roundToInt()
        h = opts.maxHeight
    }
    // Round down to even numbers, which hardware encoders require.
    w = w and 1.inv()
    h = h and 1.inv()
    var bitrate = w.toDouble() * h * opts.quality.bitsPerPixel
    if (opts.hevc) bitrate *= 0.7
    val br = bitrate.toLong().coerceIn(1_000_000L, 60_000_000L).toInt()
    val ai = opts.aiMultiplier
    // More frames per second need more bits; the render before AI is bumped to limit generation loss.
    val renderBr = if (ai > 0) (br * 1.5).toLong().coerceAtMost(80_000_000L).toInt() else br
    val finalBr = if (ai > 0) (br * (1.0 + 0.5 * (ai - 1))).toLong().coerceIn(1_000_000L, 80_000_000L).toInt() else br
    val seconds = state.totalOutputMs / 1000.0
    val bytes = ((finalBr + 128_000) * seconds / 8.0).toLong()
    return OutputPlan(w, h, renderBr, finalBr, (src.frameRate * ai).roundToInt(), bytes)
}

class ExportHandle internal constructor(
    private val transformer: Transformer,
    private val tempFile: File,
) {
    @Volatile
    internal var finished = false

    fun cancel() {
        if (finished) return
        finished = true
        transformer.cancel()
        tempFile.delete()
    }
}

/**
 * Renders the edit: every segment (with its own speed/volume) concatenated in
 * order, then the whole-picture effects, encoded and saved to Movies/NovaFrame
 * so it appears in the gallery. Must be called on the main thread.
 */
@OptIn(UnstableApi::class)
fun startExport(
    ctx: Context,
    sourceUri: Uri,
    src: SourceInfo,
    state: EditState,
    opts: ExportOptions,
    onProgress: (Float) -> Unit,
    onDone: (String) -> Unit,
    onError: (String) -> Unit,
    /** When true the rendered file is handed to [onRendered] instead of being saved to the gallery. */
    renderOnly: Boolean = false,
    onRendered: (File) -> Unit = {},
): ExportHandle {
    val appCtx = ctx.applicationContext
    val tempFile = File(appCtx.cacheDir, "edit_${System.currentTimeMillis()}.mp4")
    val main = Handler(Looper.getMainLooper())
    val plan = planOutput(state, src, opts)

    val items = state.segments.map { seg ->
        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(seg.startMs)
                    .setEndPositionMs(seg.endMs.coerceAtLeast(seg.startMs + 1))
                    .build(),
            )
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setEffects(buildSegmentEffects(seg, src))
            .build()
    }

    val composition = Composition.Builder(EditedMediaItemSequence(items))
        .setEffects(Effects(emptyList(), buildVideoEffects(state.visual, src, opts.maxHeight)))
        .build()

    val encoderFactory = DefaultEncoderFactory.Builder(appCtx)
        .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build(),
        )
        .build()

    // Aliases so the listener's own onError()/onCompleted() members can't shadow these callbacks.
    val reportDone = onDone
    val reportError = onError
    val reportRendered = onRendered
    lateinit var handle: ExportHandle
    val transformer = Transformer.Builder(appCtx)
        .setVideoMimeType(if (opts.hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
        .setEncoderFactory(encoderFactory)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (handle.finished) return
                handle.finished = true
                if (renderOnly) {
                    main.post { reportRendered(tempFile) }
                    return
                }
                // Copying a large file must not block the main thread.
                Thread {
                    saveExportedFile(
                        appCtx,
                        tempFile,
                        { name -> main.post { reportDone(name) } },
                        { msg -> main.post { reportError(msg) } },
                    )
                }.start()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                if (handle.finished) return
                handle.finished = true
                tempFile.delete()
                reportError("${exportException.message ?: "Unknown error"} (${exportException.errorCodeName})")
            }
        })
        .build()
    handle = ExportHandle(transformer, tempFile)

    transformer.start(composition, tempFile.absolutePath)

    val progressHolder = ProgressHolder()
    main.post(object : Runnable {
        override fun run() {
            if (handle.finished) return
            val progressState = transformer.getProgress(progressHolder)
            if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(progressHolder.progress / 100f)
            }
            if (progressState != Transformer.PROGRESS_STATE_NOT_STARTED) {
                main.postDelayed(this, 300)
            }
        }
    })
    return handle
}

private fun saveExportedFile(
    ctx: Context,
    tempFile: File,
    onDone: (String) -> Unit,
    onError: (String) -> Unit,
) {
    try {
        onDone(saveVideoToGallery(ctx, tempFile))
    } catch (e: Exception) {
        tempFile.delete()
        onError(e.message ?: "Failed to save clip")
    }
}

/** Copies [tempFile] into Movies/NovaFrame (visible in the gallery) and deletes it. Blocking; returns the file name. */
fun saveVideoToGallery(ctx: Context, tempFile: File, suffix: String = ""): String {
    val name = "NovaFrame_edit_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}$suffix.mp4"
    val resolver = ctx.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NovaFrame")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw java.io.IOException("Could not create output file")
    try {
        resolver.openOutputStream(outUri)?.use { out ->
            tempFile.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("Could not open output file")
        resolver.update(
            outUri,
            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
            null,
            null,
        )
    } catch (e: Exception) {
        resolver.delete(outUri, null, null)
        throw e
    }
    tempFile.delete()
    return name
}
