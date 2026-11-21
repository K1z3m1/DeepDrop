package app.fluxa

import app.fluxa.R
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Records the frames the presenter shows (generated + real) to an mp4 in Movies/<app>.
 *
 * Video: the presenter blits every shown frame into [inputSurface] (a MediaCodec H.264 input
 * surface) through its existing Vulkan queue - see Fluxa.setRecordTarget. Orientation is fixed by
 * the user (portrait/landscape); a frame of the other orientation is aspect-fit with black bars.
 *
 * Audio: app sound via AudioPlaybackCapture on the already granted MediaProjection, optionally
 * mixed with the microphone (outside sound), encoded as AAC.
 */
class ScreenRecorder(
    private val context: Context,
    private val projection: MediaProjection,
    private val options: RecordOptions,
    private val screenW: Int,
    private val screenH: Int,
    private val onProblem: (String) -> Unit,
    /** True = no frame-generation presenter: the projection mirrors the screen straight into the encoder. */
    private val direct: Boolean = false,
    private val directDpi: Int = 320,
    private val directHz: Int = 60,
) {
    var videoWidth = 0
        private set
    var videoHeight = 0
        private set

    @Volatile var hasAudio = false
        private set
    @Volatile var micActive = false
        private set

    private val resolver = context.contentResolver
    private var outUri: Uri? = null
    private var outFd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private val sink = Sink()

    private var videoEnc: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var directDisplay: android.hardware.display.VirtualDisplay? = null
    private var videoThread: Thread? = null

    private var audioEnc: MediaCodec? = null
    private var playback: AudioRecord? = null
    private var mic: AudioRecord? = null
    private var audioThread: Thread? = null
    private var micThread: Thread? = null
    private val micRing = ShortRing(SAMPLE_RATE * 2)

    @Volatile private var stopRequested = false
    @Volatile private var stopRequestedAtMs = 0L
    private var originUs = 0L
    private var started = false
    private var feedDetached = false

    val elapsedMs: Long
        get() = if (!started) 0L else (System.nanoTime() / 1000 - originUs) / 1000

    val isRecording: Boolean get() = started && !stopRequested

    // ------------------------------------------------------------------ start

    /** Opens the file + encoders and connects the presenter to the encoder. False = nothing started. */
    fun start(): Boolean {
        if (started) return true
        try {
            if (!openOutput()) {
                abandon()
                return false
            }
            if (!startVideoEncoder()) {
                abandon()
                return false
            }
            startAudio() // audio problems never block the video
            originUs = System.nanoTime() / 1000
            started = true

            videoThread = Thread({ videoLoop() }, "fluxa-rec-video").also { it.start() }
            if (hasAudio) {
                if (micActive) micThread = Thread({ micLoop() }, "fluxa-rec-mic").also { it.start() }
                audioThread = Thread({ audioLoop() }, "fluxa-rec-audio").also { it.start() }
            }
            if (direct) {
                // Panel-only session: nothing generates frames, so mirror the screen into the encoder.
                directDisplay = projection.createVirtualDisplay(
                    "fluxa-record", videoWidth, videoHeight, directDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null,
                )
                if (directDisplay == null) throw IllegalStateException("virtual display")
            } else {
                // From now on the presenter feeds every shown frame into the encoder.
                Fluxa.setRecordTarget(inputSurface, videoWidth, videoHeight)
            }
            return true
        } catch (t: Throwable) {
            onProblem("เริ่มอัดจอไม่ได้: ${t.message}")
            started = false
            stopRequested = true
            runCatching { directDisplay?.release() }
            directDisplay = null
            teardownCodecs()
            runCatching { playback?.release() }
            runCatching { mic?.release() }
            playback = null
            mic = null
            abandon()
            return false
        }
    }

    private fun openOutput(): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${context.getString(R.string.fluxa_app_name)}_$stamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/${context.getString(R.string.fluxa_app_name)}")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        if (uri == null) {
            onProblem("สร้างไฟล์วิดีโอไม่ได้")
            return false
        }
        outUri = uri
        val fd = resolver.openFileDescriptor(uri, "rw")
        if (fd == null) {
            onProblem("เปิดไฟล์วิดีโอไม่ได้")
            return false
        }
        outFd = fd
        muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return true
    }

    private fun targetSize(align: Int): Pair<Int, Int> {
        val long = max(screenW, screenH).toFloat()
        val short = min(screenW, screenH).toFloat()
        val scale = min(1f, MAX_LONG_SIDE / long)
        fun fit(v: Float) = max(align, ((v * scale / align).roundToInt()) * align)
        val l = fit(long)
        val s = fit(short)
        return if (options.portrait) s to l else l to s
    }

    private fun startVideoEncoder(): Boolean {
        val fpsHint = hz().coerceIn(30, 120)
        // (alignment, fps): first the exact-ish size at the real rate, then safer fallbacks.
        val attempts = listOf(2 to fpsHint, 16 to fpsHint, 16 to 60)
        var last: Throwable? = null
        for ((align, fps) in attempts) {
            val (w, h) = targetSize(align)
            var codec: MediaCodec? = null
            try {
                val bitrate = (w.toLong() * h * fps / 10).coerceIn(8_000_000L, 50_000_000L).toInt()
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                videoEnc = codec
                inputSurface = surface
                videoWidth = w
                videoHeight = h
                return true
            } catch (t: Throwable) {
                last = t
                runCatching { codec?.release() }
            }
        }
        onProblem("เครื่องนี้เข้ารหัสวิดีโอไม่ได้: ${last?.message}")
        return false
    }

    private fun startAudio() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onProblem("ไม่ได้อนุญาตไมโครโฟน/เสียง จึงอัดแค่ภาพ")
            return
        }
        try {
            val stereo = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(stereo)
                .setBufferSizeInBytes(max(minBuf * 4, 32768))
                .setAudioPlaybackCaptureConfig(capture)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                onProblem("จับเสียงแอพไม่ได้ จึงอัดแค่ภาพ")
                return
            }
            playback = rec
        } catch (t: Throwable) {
            onProblem("จับเสียงแอพไม่ได้ จึงอัดแค่ภาพ")
            return
        }

        if (options.withMic) {
            try {
                val mono = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(mono)
                    .setBufferSizeInBytes(max(minBuf * 4, 16384))
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    mic = rec
                    micActive = true
                } else {
                    rec.release()
                    onProblem("เปิดไมค์ไม่ได้ จึงอัดแค่เสียงแอพ")
                }
            } catch (t: Throwable) {
                onProblem("เปิดไมค์ไม่ได้ จึงอัดแค่เสียงแอพ")
            }
        }

        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 2 * 2 * 4)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioEnc = codec
            hasAudio = true
            sink.expectAudio = true
        } catch (t: Throwable) {
            onProblem("เข้ารหัสเสียงไม่ได้ จึงอัดแค่ภาพ")
            runCatching { playback?.release() }
            runCatching { mic?.release() }
            playback = null
            mic = null
            micActive = false
        }
    }

    // ------------------------------------------------------------------ video

    private fun videoLoop() {
        val enc = videoEnc ?: return
        val info = MediaCodec.BufferInfo()
        var lastOut = -1L
        var intervalUs = 1_000_000L / hz().coerceIn(MIN_HZ, MAX_HZ)
        var nextIntervalRefreshMs = 0L
        try {
            while (true) {
                if (stopRequested && System.currentTimeMillis() - stopRequestedAtMs > DRAIN_TIMEOUT_MS) {
                    break
                }
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.addVideoTrack(enc.outputFormat)
                    continue
                }
                if (idx < 0) continue
                val buf = enc.getOutputBuffer(idx)
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buf != null && info.size > 0 && !config) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs >= nextIntervalRefreshMs) {
                        intervalUs = 1_000_000L / hz().coerceIn(MIN_HZ, MAX_HZ)
                        nextIntervalRefreshMs = nowMs + 250
                    }
                    // Generated + real frames of one source frame are presented back to back, so
                    // their surface timestamps are (almost) equal. Spread them over the output
                    // frame interval, but never let the video run more than MAX_LAG behind real time.
                    val rel = max(0L, info.presentationTimeUs - originUs)
                    var out = max(rel, lastOut + intervalUs)
                    if (out - rel > MAX_LAG_US) out = rel + MAX_LAG_US
                    if (out <= lastOut) out = lastOut + 1
                    lastOut = out
                    info.presentationTimeUs = out
                    sink.write(true, buf, info)
                }
                enc.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (t: Throwable) {
            onProblem("อัดวิดีโอผิดพลาด: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ audio

    private fun micLoop() {
        val rec = mic ?: return
        val buf = ShortArray(CHUNK_FRAMES)
        try {
            rec.startRecording()
            while (!stopRequested) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                if (n > 0) micRing.write(buf, n)
            }
        } catch (t: Throwable) {
        }
    }

    private fun audioLoop() {
        val rec = playback ?: return
        val enc = audioEnc ?: return
        val pcm = ShortArray(CHUNK_FRAMES * 2)
        val micBuf = ShortArray(CHUNK_FRAMES)
        val info = MediaCodec.BufferInfo()
        var framesDone = 0L
        var originAudioUs = -1L
        try {
            rec.startRecording()
            while (!stopRequested) {
                val n = rec.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                if (n == 0) continue
                val frames = n / 2
                if (originAudioUs < 0) {
                    originAudioUs = System.nanoTime() / 1000 - frames * 1_000_000L / SAMPLE_RATE
                }
                if (micActive) {
                    val got = micRing.read(micBuf, frames)
                    for (i in 0 until got) {
                        val m = micBuf[i].toInt()
                        pcm[2 * i] = clip(pcm[2 * i] + m)
                        pcm[2 * i + 1] = clip(pcm[2 * i + 1] + m)
                    }
                }
                val ptsUs = max(0L, originAudioUs - originUs) + framesDone * 1_000_000L / SAMPLE_RATE
                queueAudio(enc, pcm, n, ptsUs, false)
                framesDone += frames
                drainAudio(enc, info, false)
            }
            // end of stream
            val endPts = max(0L, (if (originAudioUs < 0) originUs else originAudioUs) - originUs) +
                framesDone * 1_000_000L / SAMPLE_RATE
            queueAudio(enc, pcm, 0, endPts, true)
            drainAudio(enc, info, true)
        } catch (t: Throwable) {
            onProblem("อัดเสียงผิดพลาด: ${t.message}")
        }
    }

    private fun clip(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun queueAudio(enc: MediaCodec, pcm: ShortArray, shorts: Int, ptsUs: Long, eos: Boolean) {
        var tries = 0
        while (tries++ < 50) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx < 0) continue
            val buf = enc.getInputBuffer(idx) ?: return
            buf.clear()
            val bytes = if (eos) 0 else min(shorts * 2, buf.capacity())
            if (bytes > 0) buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, bytes / 2)
            enc.queueInputBuffer(idx, 0, bytes, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            return
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.addAudioTrack(enc.outputFormat)
                continue
            }
            if (idx < 0) {
                if (!untilEos || System.currentTimeMillis() > deadline) return
                continue
            }
            val buf = enc.getOutputBuffer(idx)
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buf != null && info.size > 0 && !config) sink.write(false, buf, info)
            enc.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    // ------------------------------------------------------------------ stop

    /** Disconnects the presenter from the encoder (fast, call on the main thread before releasing the presenter). */
    fun detachFeed() {
        if (feedDetached) return
        feedDetached = true
        if (direct) {
            runCatching { directDisplay?.release() }
            directDisplay = null
        } else {
            Fluxa.setRecordTarget(null, 0, 0)
        }
    }

    /** Rate the screen shows frames at: the presenter's output, or the display refresh when mirroring directly. */
    private fun hz(): Int = if (direct) directHz else Fluxa.outputHz()

    /** Finishes the file on a worker thread; [done] runs there when the mp4 is final. */
    fun stopAsync(done: (Uri?) -> Unit) {
        detachFeed()
        if (stopRequested) {
            done(null)
            return
        }
        stopRequestedAtMs = System.currentTimeMillis() // before the flag: the drain loop reads both
        stopRequested = true
        Thread({
            val uri = finish()
            done(uri)
        }, "fluxa-rec-stop").start()
    }

    private fun finish(): Uri? {
        runCatching { videoEnc?.signalEndOfInputStream() }
        videoThread?.join(DRAIN_TIMEOUT_MS + 1000)
        audioThread?.join(DRAIN_TIMEOUT_MS + 1000)
        micThread?.join(1000)
        runCatching { playback?.stop() }
        runCatching { mic?.stop() }
        runCatching { playback?.release() }
        runCatching { mic?.release() }
        playback = null
        mic = null
        teardownCodecs()
        val ok = sink.finish()
        val uri = outUri
        runCatching { outFd?.close() }
        outFd = null
        if (uri == null) return null
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        runCatching {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun teardownCodecs() {
        runCatching { videoEnc?.stop() }
        runCatching { videoEnc?.release() }
        videoEnc = null
        runCatching { audioEnc?.stop() }
        runCatching { audioEnc?.release() }
        audioEnc = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }

    /** Start failed half way: drop the empty file. */
    private fun abandon() {
        runCatching { muxer?.release() }
        muxer = null
        runCatching { outFd?.close() }
        outFd = null
        outUri?.let { runCatching { resolver.delete(it, null, null) } }
        outUri = null
    }

    // ------------------------------------------------------------------ muxer

    /** Collects the tracks, starts the muxer once every track has a format, queues samples until then. */
    private inner class Sink {
        private val lock = Any()
        private var videoTrack = -1
        private var audioTrack = -1
        private var muxerStarted = false
        private var wroteVideo = false
        private var pendingBytes = 0L
        private val pending = ArrayList<Pending>()
        @Volatile var expectAudio = false

        private inner class Pending(val video: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

        fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
            val m = muxer ?: return@synchronized
            if (videoTrack < 0) videoTrack = m.addTrack(format)
            maybeStart()
        }

        fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
            val m = muxer ?: return@synchronized
            if (audioTrack < 0) audioTrack = m.addTrack(format)
            maybeStart()
        }

        private fun maybeStart() {
            if (muxerStarted || videoTrack < 0) return
            if (expectAudio && audioTrack < 0) return
            val m = muxer ?: return
            m.start()
            muxerStarted = true
            for (p in pending) writeNow(m, p.video, p.data, p.info)
            pending.clear()
            pendingBytes = 0
        }

        private fun writeNow(m: MediaMuxer, video: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
            val track = if (video) videoTrack else audioTrack
            if (track < 0) return
            if (video) wroteVideo = true
            runCatching { m.writeSampleData(track, data, info) }
        }

        fun write(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
            val m = muxer ?: return@synchronized
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            if (muxerStarted) {
                writeNow(m, video, buf, info)
            } else if (pendingBytes < MAX_PENDING_BYTES) {
                val copy = ByteBuffer.allocate(info.size)
                copy.put(buf)
                copy.flip()
                val i = MediaCodec.BufferInfo()
                i.set(0, info.size, info.presentationTimeUs, info.flags)
                pending.add(Pending(video, copy, i))
                pendingBytes += info.size
            }
        }

        /** True when a playable file (with at least one video sample) was written. */
        fun finish(): Boolean = synchronized(lock) {
            val m = muxer ?: return false
            var ok = false
            if (muxerStarted) {
                ok = wroteVideo
                runCatching { m.stop() }.onFailure { ok = false }
            }
            runCatching { m.release() }
            muxer = null
            ok
        }
    }

    /** Minimal thread-safe ring of PCM samples: drops the oldest when full. */
    private class ShortRing(private val capacity: Int) {
        private val data = ShortArray(capacity)
        private var head = 0
        private var size = 0

        @Synchronized
        fun write(src: ShortArray, count: Int) {
            for (i in 0 until count) {
                if (size == capacity) {
                    head = (head + 1) % capacity
                    size--
                }
                data[(head + size) % capacity] = src[i]
                size++
            }
        }

        @Synchronized
        fun read(dst: ShortArray, max: Int): Int {
            val n = min(min(max, size), dst.size)
            for (i in 0 until n) {
                dst[i] = data[head]
                head = (head + 1) % capacity
            }
            size -= n
            return n
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHUNK_FRAMES = 1024
        private const val MAX_LONG_SIDE = 1920f
        private const val MIN_HZ = 24
        private const val MAX_HZ = 240
        private const val MAX_LAG_US = 250_000L
        private const val DRAIN_TIMEOUT_MS = 3_000L
        private const val MAX_PENDING_BYTES = 16L * 1024 * 1024
    }
}
