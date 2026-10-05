package com.firstt175.novaframe.session.capture

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
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
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import com.firstt175.novaframe.session.NovaLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max

/**
 * Hardware H.264 screen recorder fed directly by the shared final-frame render tee,
 * with optional AAC audio.
 *
 * Video: the native Vulkan tee presents each displayed frame into [recordingSurface],
 * the input Surface of a MediaCodec H.264 encoder. There is deliberately NO second
 * MediaProjection/VirtualDisplay: recording only adds encoding on top of the normal
 * session path.
 *
 * Audio ([Audio]):
 *  - [Audio.MIC]: the microphone.
 *  - [Audio.APP]: what the game/apps are playing, captured with the Android 10+
 *    AudioPlaybackCapture API through the session's MediaProjection. Apps can opt out
 *    of being captured, in which case that audio is simply silent.
 *
 * MediaRecorder cannot take a playback-capture source, so encoding and muxing are done
 * here with MediaCodec + MediaMuxer. Audio and video share the monotonic clock
 * (video buffer timestamps come from the BufferQueue, audio timestamps from
 * System.nanoTime), which keeps them in sync.
 */
class GameScreenRecorder(private val ctx: Context) {
    enum class Audio { OFF, MIC, APP }

    @Volatile var isRecording = false
        private set

    /** True when the running recording actually has an audio track. */
    @Volatile var audioActive = false
        private set

    private var muxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var inputSurface: Surface? = null
    private var pendingUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var startedAtMs = 0L

    private var videoDrainThread: Thread? = null
    private var audioPumpThread: Thread? = null
    private var audioDrainThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var abortRequested = false

    // ---- muxer coordination (tracks can only be added before the muxer starts) ----
    private val muxLock = ReentrantLock()
    private val muxCond = muxLock.newCondition()
    private var muxerStarted = false
    private var expectAudio = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoFirstPtsUs = Long.MIN_VALUE
    private var audioFirstPtsUs = Long.MIN_VALUE
    private var basesComputed = false
    private var baseVideoUs = 0L
    private var baseAudioUs = 0L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    @Volatile private var writtenSamples = 0L

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(width: Int, height: Int, audio: Audio, projection: MediaProjection?): Boolean {
        if (isRecording || Build.VERSION.SDK_INT < 29 || width <= 0 || height <= 0) return false
        val resolver = ctx.contentResolver
        val name = "NovaFrame_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NovaFrame")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: return false

        var pfd: ParcelFileDescriptor? = null
        var mux: MediaMuxer? = null
        var vCodec: MediaCodec? = null
        var surface: Surface? = null
        var aRecord: AudioRecord? = null
        var aCodec: MediaCodec? = null
        try {
            // MediaMuxer needs a seekable descriptor it can also read back from.
            pfd = resolver.openFileDescriptor(uri, "rw") ?: error("open output failed")
            mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate(width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = vCodec.createInputSurface()

            // Audio is best-effort: if it can't be set up the recording continues
            // video-only (the caller can tell from audioActive).
            if (audio != Audio.OFF) {
                try {
                    val rec = createAudioRecord(audio, projection)
                    aRecord = rec
                    val enc = createAacEncoder()
                    aCodec = enc
                    // Start capture only once the encoder is ready, and inside this block so
                    // a device that refuses (e.g. playback capture not allowed) falls back
                    // to video-only instead of failing the whole recording.
                    enc.start()
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        error("AudioRecord did not start")
                    }
                } catch (t: Throwable) {
                    NovaLog.w(TAG, "audio ($audio) unavailable — recording video only", t)
                    runCatching { aRecord?.release() }
                    runCatching { aCodec?.release() }
                    aRecord = null
                    aCodec = null
                }
            }

            // Fresh coordination state for this recording.
            stopRequested = false
            abortRequested = false
            muxerStarted = false
            expectAudio = aCodec != null
            videoTrack = -1
            audioTrack = -1
            videoFirstPtsUs = Long.MIN_VALUE
            audioFirstPtsUs = Long.MIN_VALUE
            basesComputed = false
            baseVideoUs = 0L
            baseAudioUs = 0L
            lastVideoPtsUs = -1L
            lastAudioPtsUs = -1L
            writtenSamples = 0L

            muxer = mux
            videoCodec = vCodec
            audioCodec = aCodec
            audioRecord = aRecord
            inputSurface = surface
            pendingUri = uri
            outputPfd = pfd

            vCodec.start()

            val vc: MediaCodec = vCodec!!
            videoDrainThread = Thread({ drainLoop(vc, isVideo = true) }, "novaframe-rec-video").also { it.start() }
            if (aCodec != null && aRecord != null) {
                val ac: MediaCodec = aCodec!!
                val ar: AudioRecord = aRecord!!
                val mono = audio == Audio.MIC
                audioDrainThread = Thread({ drainLoop(ac, isVideo = false) }, "novaframe-rec-audio-out").also { it.start() }
                audioPumpThread = Thread({ audioPump(ar, ac, mono) }, "novaframe-rec-audio-in").also { it.start() }
            }

            audioActive = aCodec != null
            startedAtMs = SystemClock.elapsedRealtime()
            isRecording = true
            NovaLog.i(TAG, "record start ${width}x${height} audio=$audio active=$audioActive uri=$uri")
            return true
        } catch (t: Throwable) {
            NovaLog.w(TAG, "record start failed", t)
            stopRequested = true
            abortRequested = true
            runCatching { aRecord?.release() }
            runCatching { aCodec?.release() }
            runCatching { vCodec?.release() }
            runCatching { surface?.release() }
            runCatching { mux?.release() }
            runCatching { pfd?.close() }
            runCatching { resolver.delete(uri, null, null) }
            muxer = null
            videoCodec = null
            audioCodec = null
            audioRecord = null
            inputSurface = null
            outputPfd = null
            pendingUri = null
            audioActive = false
            return false
        }
    }

    @Synchronized
    fun stop(): Uri? {
        if (!isRecording) return null
        val uri = pendingUri
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        isRecording = false
        finishAndRelease(graceful = true)
        val hasContent = writtenSamples > 0L
        if (uri != null) {
            if (elapsed < 500L || !hasContent) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
            } else {
                runCatching {
                    ctx.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
            }
        }
        pendingUri = null
        audioActive = false
        NovaLog.i(TAG, "record stop ${elapsed}ms samples=$writtenSamples uri=$uri")
        return if (elapsed >= 500L && hasContent) uri else null
    }

    @Synchronized
    fun abort() {
        if (!isRecording && pendingUri == null) return
        isRecording = false
        abortRequested = true
        finishAndRelease(graceful = false)
        pendingUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
        pendingUri = null
        audioActive = false
    }

    fun recordingSurface(): Surface? = inputSurface

    fun elapsedMs(): Long = if (!isRecording) 0L else SystemClock.elapsedRealtime() - startedAtMs

    // ---- teardown ----

    private fun finishAndRelease(graceful: Boolean) {
        stopRequested = true
        muxLock.withLock { muxCond.signalAll() }
        // Ends a blocking AudioRecord.read() so the pump can queue end-of-stream.
        runCatching { audioRecord?.stop() }
        audioPumpThread.joinQuietly(2_000)
        if (graceful) runCatching { videoCodec?.signalEndOfInputStream() }
        videoDrainThread.joinQuietly(3_000)
        audioDrainThread.joinQuietly(3_000)
        videoDrainThread = null
        audioDrainThread = null
        audioPumpThread = null

        runCatching { if (muxerStarted && writtenSamples > 0L) muxer?.stop() }
            .onFailure { NovaLog.w(TAG, "muxer stop failed", it) }
        runCatching { muxer?.release() }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { audioRecord?.release() }
        runCatching { inputSurface?.release() }
        muxer = null
        videoCodec = null
        audioCodec = null
        audioRecord = null
        inputSurface = null
        runCatching { outputPfd?.close() }
        outputPfd = null
    }

    private fun Thread?.joinQuietly(ms: Long) {
        if (this == null) return
        try {
            join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- encoder output → muxer ----

    private fun drainLoop(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idleTicks = 0
        try {
            while (!abortRequested) {
                val idx = codec.dequeueOutputBuffer(info, 20_000L)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // After stop, give the encoder about a second to flush its EOS.
                        if (stopRequested && ++idleTicks > 50) break
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(isVideo, codec.outputFormat)
                    idx >= 0 -> {
                        idleTicks = 0
                        val buf = codec.getOutputBuffer(idx)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (buf != null && info.size > 0 && !isConfig) {
                            writeSample(isVideo, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            NovaLog.w(TAG, "drain loop (${if (isVideo) "video" else "audio"}) ended with error", t)
        }
    }

    private fun onFormat(isVideo: Boolean, format: MediaFormat) {
        val m = muxer ?: return
        muxLock.withLock {
            if (muxerStarted) return
            if (isVideo) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
            if (videoTrack >= 0 && (!expectAudio || audioTrack >= 0)) {
                m.start()
                muxerStarted = true
                muxCond.signalAll()
            }
        }
    }

    private fun writeSample(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        muxLock.withLock {
            if (isVideo) {
                if (videoFirstPtsUs == Long.MIN_VALUE) {
                    videoFirstPtsUs = info.presentationTimeUs
                    muxCond.signalAll() // the other track may be waiting for this timestamp
                }
            } else {
                if (audioFirstPtsUs == Long.MIN_VALUE) audioFirstPtsUs = info.presentationTimeUs
            }
            // Wait until the muxer has started (every expected track reported its format)
            // AND the video's first timestamp is known: the shared time base is derived
            // from both, so whichever thread gets here first must not compute it early.
            while (!muxerStarted || (!basesComputed && videoFirstPtsUs == Long.MIN_VALUE)) {
                if (abortRequested || stopRequested) return
                muxCond.await(50, TimeUnit.MILLISECONDS)
            }
            if (!basesComputed) computeBases()

            val base = if (isVideo) baseVideoUs else baseAudioUs
            var pts = info.presentationTimeUs - base
            if (pts < 0L) pts = 0L
            val last = if (isVideo) lastVideoPtsUs else lastAudioPtsUs
            if (pts <= last) pts = last + 1
            if (isVideo) lastVideoPtsUs = pts else lastAudioPtsUs = pts

            info.set(info.offset, info.size, pts, info.flags)
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            m.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
            writtenSamples++
        }
    }

    /**
     * Video timestamps come from the BufferQueue (CLOCK_MONOTONIC) and audio ones from
     * System.nanoTime() (also CLOCK_MONOTONIC), so normally both share one clock and the
     * earlier of the two first timestamps becomes t=0. If the video timestamps turn out
     * to be on some other timeline, each track is rebased to its own start instead.
     */
    private fun computeBases() {
        val nowUs = System.nanoTime() / 1_000L
        val v = videoFirstPtsUs
        val a = audioFirstPtsUs
        val sameClock = v != Long.MIN_VALUE && abs(v - nowUs) < 10_000_000L
        if (sameClock) {
            val common = if (expectAudio && a != Long.MIN_VALUE) minOf(v, a) else v
            baseVideoUs = common
            baseAudioUs = common
        } else {
            baseVideoUs = if (v != Long.MIN_VALUE) v else 0L
            baseAudioUs = if (a != Long.MIN_VALUE) a else 0L
        }
        basesComputed = true
    }

    // ---- audio capture → AAC encoder ----

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(audio: Audio, projection: MediaProjection?): AudioRecord {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val record = if (audio == Audio.APP) {
            val proj = projection ?: error("app audio needs an active MediaProjection")
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, encoding)
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuf * 2, 32_768))
                .build()
        } else {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                encoding,
                max(minBuf * 2, 16_384),
            )
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord failed to initialize")
        }
        return record
    }

    private fun createAacEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /**
     * Reads PCM from [rec] and feeds it to the AAC [codec]. The encoder always takes
     * 16-bit stereo, so mono mic audio is duplicated into both channels.
     *
     * A short run of silence is queued first, timestamped just BEFORE recording began.
     * That makes the encoder report its output format straight away, so the muxer can
     * start even if the source (e.g. a silent game) hasn't produced any data yet.
     * Later timestamps follow the sample count, and re-anchor to the wall clock after a
     * stall so audio stays in sync with the video.
     */
    private fun audioPump(rec: AudioRecord, codec: MediaCodec, mono: Boolean) {
        val chunkFrames = 1024
        val readBuf = ShortArray(chunkFrames * if (mono) 1 else 2)
        val stereoBuf = if (mono) ShortArray(chunkFrames * 2) else readBuf
        val primeFrames = 8 * chunkFrames
        var framesQueued = 0L
        var anchorUs = System.nanoTime() / 1_000L - primeFrames * 1_000_000L / SAMPLE_RATE
        muxLock.withLock { audioFirstPtsUs = anchorUs }
        try {
            val silence = ShortArray(chunkFrames * 2)
            var primed = 0
            while (primed < primeFrames && !stopRequested) {
                val pts = anchorUs + framesQueued * 1_000_000L / SAMPLE_RATE
                queuePcm(codec, silence, silence.size, pts)
                framesQueued += chunkFrames
                primed += chunkFrames
            }
            while (!stopRequested) {
                val n = rec.read(readBuf, 0, readBuf.size)
                if (n < 0) break
                if (n == 0) {
                    Thread.sleep(2)
                    continue
                }
                val frames = if (mono) n else n / 2
                val outShorts: Int
                if (mono) {
                    for (i in 0 until frames) {
                        stereoBuf[2 * i] = readBuf[i]
                        stereoBuf[2 * i + 1] = readBuf[i]
                    }
                    outShorts = frames * 2
                } else {
                    outShorts = frames * 2
                }
                val nowUs = System.nanoTime() / 1_000L
                val startUs = nowUs - frames * 1_000_000L / SAMPLE_RATE
                val expected = anchorUs + framesQueued * 1_000_000L / SAMPLE_RATE
                val pts = if (abs(startUs - expected) > 60_000L) {
                    anchorUs = startUs - framesQueued * 1_000_000L / SAMPLE_RATE
                    startUs
                } else {
                    expected
                }
                queuePcm(codec, stereoBuf, outShorts, pts)
                framesQueued += frames
            }
        } catch (t: Throwable) {
            NovaLog.w(TAG, "audio pump ended with error", t)
        } finally {
            // End-of-stream so the audio drain thread can finish cleanly.
            val endPts = anchorUs + framesQueued * 1_000_000L / SAMPLE_RATE
            var idx = -1
            var tries = 0
            while (idx < 0 && tries++ < 50) {
                idx = try {
                    codec.dequeueInputBuffer(20_000L)
                } catch (_: Throwable) {
                    -1
                }
            }
            if (idx >= 0) {
                runCatching { codec.queueInputBuffer(idx, 0, 0, endPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            }
        }
    }

    private fun queuePcm(codec: MediaCodec, pcm: ShortArray, shorts: Int, ptsUs: Long): Boolean {
        val idx = codec.dequeueInputBuffer(20_000L)
        if (idx < 0) return false // encoder busy: drop this chunk rather than stall capture
        val input = codec.getInputBuffer(idx) ?: return false
        input.clear()
        input.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, shorts)
        codec.queueInputBuffer(idx, 0, shorts * 2, ptsUs, 0)
        return true
    }

    private fun bitrate(w: Int, h: Int): Int = when {
        w.toLong() * h >= 8_000_000L -> 16_000_000
        w.toLong() * h >= 4_000_000L -> 12_000_000
        w.toLong() * h >= 2_000_000L -> 8_000_000
        else -> 5_000_000
    }

    companion object {
        private const val TAG = "NovaFrameRecorder"
        private const val SAMPLE_RATE = 44_100
    }
}
