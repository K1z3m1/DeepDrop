package com.firstt175.novaframe.ui.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import com.firstt175.novaframe.prefs.AiEngine
import com.firstt175.novaframe.prefs.NovaPreferences
import com.firstt175.novaframe.session.NativeBridge
import com.firstt175.novaframe.session.models.BundledIfrnetModel
import com.firstt175.novaframe.session.models.BundledRifeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class AiFrameGenException(message: String) : Exception(message)

/** The ncnn model the app is currently configured to use (same one the Settings screen loads). */
data class AiModelSpec(val dir: String, val engine: Int, val label: String, val flowScale: Float)

/**
 * Resolves the model exactly like DllPickerScreen does: an explicitly selected
 * "My model"/asset model wins and is never silently swapped for a bundled one;
 * otherwise the bundled RIFE/IFRNet model for the selected engine is extracted and used.
 * Does file IO — call off the main thread.
 */
fun resolveAiModel(ctx: Context): AiModelSpec {
    val cfg = NovaPreferences(ctx).load()
    val engine = cfg.aiEngine
    val activeDir = cfg.activeModelDir

    if (activeDir != null) {
        if (cfg.activeModelEngine != engine.nativeValue) {
            throw AiFrameGenException("The model selected in Settings belongs to the other AI engine.")
        }
        val dir = File(activeDir)
        val param = if (engine == AiEngine.IFRNET) "ifrnet.param" else "flownet.param"
        val bin = if (engine == AiEngine.IFRNET) "ifrnet.bin" else "flownet.bin"
        if (!dir.isDirectory || File(dir, param).length() <= 0L || File(dir, bin).length() <= 0L) {
            throw AiFrameGenException("The model selected in Settings is missing or invalid.")
        }
        return AiModelSpec(dir.absolutePath, engine.nativeValue, cfg.activeModelName ?: dir.name, cfg.flowScale)
    }

    return when (engine) {
        AiEngine.IFRNET -> {
            if (!BundledIfrnetModel.ensureExtracted(ctx, cfg.ifrnetModel)) {
                throw AiFrameGenException("Couldn't unpack the bundled model from the APK.")
            }
            AiModelSpec(
                BundledIfrnetModel.modelDir(ctx, cfg.ifrnetModel).absolutePath,
                engine.nativeValue, cfg.ifrnetModel.label, cfg.flowScale,
            )
        }
        AiEngine.RIFE -> {
            if (!BundledRifeModel.ensureExtracted(ctx, cfg.rifeModel)) {
                throw AiFrameGenException("Couldn't unpack the bundled model from the APK.")
            }
            AiModelSpec(
                BundledRifeModel.modelDir(ctx, cfg.rifeModel).absolutePath,
                engine.nativeValue, cfg.rifeModel.label, cfg.flowScale,
            )
        }
    }
}

/** Mirrors lsfg_android::kNcnnErr* in NcnnInterpolator.hpp. */
private fun ncnnError(code: Int): String = when (code) {
    -1 -> "This build has no ncnn (LSFG_HAVE_NCNN), so AI frame gen isn't available."
    -2 -> "The model files are missing. Open Settings → AI model and run the model test again."
    -3 -> "ncnn rejected the model, or its custom Warp layer failed to register (see logcat tag lsfg-ncnn)."
    -4 -> "The model isn't loaded."
    -5 -> "Bad arguments passed to the native interpolator."
    else -> "Unknown ncnn error ($code)."
}

// ---------------------------------------------------------------------------------------------

/** Skip interpolating across gaps longer than this (paused / static VFR stretches). */
private const val MAX_GAP_US = 250_000L

/** Mean per-channel difference (0..255) between two frames above which we treat it as a cut. */
private const val CUT_THRESHOLD = 48.0

/** How many frames to pull from the decoder per request. */
private const val DECODE_CHUNK = 6

/**
 * Offline AI frame generation: reads [input] frame by frame, inserts
 * (multiplier - 1) model-generated frames between every pair of real frames,
 * and encodes the result to [output] at [multiplier]× the frame rate. Timing is
 * preserved: every real frame keeps its original timestamp, generated frames are
 * placed evenly between them, and the audio track is copied untouched — so the
 * clip length and A/V sync don't change.
 *
 * Frames are NOT interpolated across scene cuts (this includes the joins between
 * your edited segments) or across long timestamp gaps; those pairs just keep the
 * real frame, so you never get a morphing blend between unrelated pictures.
 *
 * Cancellable: cancel the calling coroutine. Temp/partial output is the caller's to delete.
 */
suspend fun runAiFrameGen(
    input: File,
    output: File,
    multiplier: Int,
    videoBitrate: Int,
    hevc: Boolean,
    model: AiModelSpec,
    onProgress: (done: Int, total: Int) -> Unit,
) = withContext(Dispatchers.Default) {
    require(multiplier in 2..8)

    val load = NativeBridge.initAiInterpolator(model.dir, true, -1, 1, model.engine)
    if (load != 0) throw AiFrameGenException(ncnnError(load))

    val mmr = MediaMetadataRetriever()
    var encoder: SurfaceVideoEncoder? = null
    var audioEx: MediaExtractor? = null
    try {
        mmr.setDataSource(input.absolutePath)
        val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frameCount = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        if (frameCount < 1 || durationMs <= 0L) throw AiFrameGenException("Couldn't read the rendered clip.")

        val ptsUs = readVideoTimestamps(input)
        val total = if (ptsUs.isEmpty()) frameCount else min(frameCount, ptsUs.size)
        fun ptsOf(i: Int): Long =
            ptsUs.getOrNull(i) ?: (i * durationMs * 1000L / frameCount)

        val reader = FrameReader(mmr, total)
        val first = reader.next() ?: throw AiFrameGenException("Couldn't decode video frames on this device.")
        val w = first.width
        val h = first.height
        if (first.rowBytes != w * 4) throw AiFrameGenException("Unsupported frame layout.")
        val frameBytes = w * h * 4

        // Audio is copied through as-is.
        var audioFormat: MediaFormat? = null
        audioEx = MediaExtractor().also { ex ->
            ex.setDataSource(input.absolutePath)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    ex.selectTrack(i)
                    audioFormat = f
                    break
                }
            }
        }

        val inFps = total * 1000.0 / durationMs
        val outFps = (inFps * multiplier).roundToInt().coerceIn(1, 240)
        encoder = SurfaceVideoEncoder(output, w and 1.inv(), h and 1.inv(), videoBitrate, outFps, hevc, audioFormat)

        var bufPrev = ByteBuffer.allocateDirect(frameBytes)
        var bufCur = ByteBuffer.allocateDirect(frameBytes)
        val bufOut = ByteBuffer.allocateDirect(frameBytes * (multiplier - 1))

        copyTo(first, bufPrev)
        encoder.post(first, ptsOf(0))

        val pairs = (total - 1).coerceAtLeast(0)
        var done = 0
        var idx = 1
        var cur = reader.next()
        while (cur != null && idx < total) {
            ensureActive()
            val pA = ptsOf(idx - 1)
            val pC = ptsOf(idx)
            copyTo(cur, bufCur)

            val dt = pC - pA
            if (dt in 1..MAX_GAP_US && !isSceneCut(bufPrev, bufCur, w, h)) {
                val rc = NativeBridge.aiInterpolateBatch(
                    bufPrev, bufCur, w, h, bufOut, multiplier, model.flowScale, model.engine,
                )
                if (rc != 0) throw AiFrameGenException(ncnnError(rc))
                for (k in 1 until multiplier) {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.setHasAlpha(false)
                    val slice = bufOut.duplicate()
                    slice.position((k - 1) * frameBytes)
                    slice.limit(k * frameBytes)
                    bmp.copyPixelsFromBuffer(slice)
                    encoder.post(bmp, pA + dt * k / multiplier)
                }
            }
            encoder.post(cur, pC)

            val t = bufPrev; bufPrev = bufCur; bufCur = t
            done++
            idx++
            onProgress(done, pairs)
            cur = reader.next()
        }

        encoder.finish(audioEx)
    } finally {
        encoder?.close()
        runCatching { audioEx?.release() }
        runCatching { mmr.release() }
        runCatching { NativeBridge.releaseAiInterpolator() }
    }
    Unit
}

// ---- decoding ---------------------------------------------------------------------------------

private class FrameReader(private val mmr: MediaMetadataRetriever, private val total: Int) {
    private val queue = ArrayDeque<Bitmap>()
    private var next = 0

    fun next(): Bitmap? {
        if (queue.isEmpty()) {
            if (next >= total) return null
            val n = min(DECODE_CHUNK, total - next)
            val frames = mmr.getFramesAtIndex(next, n)
            if (frames.isEmpty()) return null
            for (f in frames) {
                queue.addLast(if (f.config == Bitmap.Config.ARGB_8888) f else f.copy(Bitmap.Config.ARGB_8888, false))
            }
            next += frames.size
        }
        return queue.removeFirst()
    }
}

/** Presentation timestamps of every video sample, ascending. Cheap: demuxing only, no decoding. */
private fun readVideoTimestamps(file: File): List<Long> {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(file.absolutePath)
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                ex.selectTrack(i)
                break
            }
        }
        val out = ArrayList<Long>()
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            out.add(t)
            if (!ex.advance()) break
        }
        out.sort()
        return out
    } finally {
        ex.release()
    }
}

private fun copyTo(bmp: Bitmap, buf: ByteBuffer) {
    buf.clear()
    bmp.copyPixelsToBuffer(buf)
}

/** Cheap cut detector: mean absolute RGB difference over a sparse pixel grid. */
private fun isSceneCut(a: ByteBuffer, b: ByteBuffer, w: Int, h: Int): Boolean {
    var sum = 0L
    var n = 0L
    var y = 0
    while (y < h) {
        val row = y * w * 4
        var x = 0
        while (x < w) {
            val o = row + x * 4
            sum += abs((a.get(o).toInt() and 0xFF) - (b.get(o).toInt() and 0xFF))
            sum += abs((a.get(o + 1).toInt() and 0xFF) - (b.get(o + 1).toInt() and 0xFF))
            sum += abs((a.get(o + 2).toInt() and 0xFF) - (b.get(o + 2).toInt() and 0xFF))
            n += 3
            x += 8
        }
        y += 8
    }
    return n > 0 && sum.toDouble() / n > CUT_THRESHOLD
}

// ---- encoding ---------------------------------------------------------------------------------

/**
 * Hardware encoder fed by drawing bitmaps onto its input surface. Timestamps written to the
 * file are the ones we pass to [post] (the surface itself would stamp wall-clock time), which
 * is what keeps variable-frame-rate sources and the audio track in sync.
 */
private class SurfaceVideoEncoder(
    output: File,
    private val width: Int,
    private val height: Int,
    bitrate: Int,
    fps: Int,
    hevc: Boolean,
    private val audioFormat: MediaFormat?,
) {
    private val codec: MediaCodec
    private val surface: Surface
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private val pending = ArrayDeque<Long>()
    private val rect = Rect(0, 0, width, height)
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var lastPts = -1L

    init {
        fun make(mime: String): MediaCodec {
            val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // output order == input order
            }
            val c = MediaCodec.createEncoderByType(mime)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (t: Throwable) {
                c.release()
                throw t
            }
            return c
        }

        var c: MediaCodec? = null
        if (hevc) c = runCatching { make(MediaFormat.MIMETYPE_VIDEO_HEVC) }.getOrNull()
        codec = c ?: try {
            make(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (t: Throwable) {
            muxer.release()
            throw AiFrameGenException("This device couldn't start a video encoder for ${width}×$height @ $fps fps: ${t.message}")
        }
        surface = codec.createInputSurface()
        codec.start()
    }

    fun post(bmp: Bitmap, ptsUs: Long) {
        pending.addLast(ptsUs)
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bmp, rect, rect, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        drain(endOfStream = false)
    }

    /** Ends the video stream, waits for the encoder, copies the audio over and closes the file. */
    fun finish(audio: MediaExtractor?) {
        codec.signalEndOfInputStream()
        drain(endOfStream = true)
        if (!muxerStarted) throw AiFrameGenException("The encoder produced no video.")
        if (audio != null && audioTrack >= 0) copyAudio(audio)
        muxer.stop()
    }

    fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
        runCatching { muxer.release() }
    }

    private fun startMuxer() {
        videoTrack = muxer.addTrack(codec.outputFormat)
        if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
        muxer.start()
        muxerStarted = true
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idle > 3000) throw AiFrameGenException("The encoder stopped responding.")
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer()
                i >= 0 -> {
                    idle = 0
                    val buf = codec.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        var pts = pending.removeFirstOrNull() ?: (lastPts + 1)
                        if (pts <= lastPts) pts = lastPts + 1 // muxer needs strictly increasing times
                        lastPts = pts
                        info.presentationTimeUs = pts
                        muxer.writeSampleData(videoTrack, buf, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(i, false)
                    if (eos) return
                }
            }
        }
    }

    private fun copyAudio(ex: MediaExtractor) {
        val buf = ByteBuffer.allocate(512 * 1024)
        val bi = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            bi.set(0, n, ex.sampleTime, flags)
            muxer.writeSampleData(audioTrack, buf, bi)
            if (!ex.advance()) break
        }
    }
}
