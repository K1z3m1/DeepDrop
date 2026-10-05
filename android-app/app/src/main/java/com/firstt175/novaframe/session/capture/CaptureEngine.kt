package com.firstt175.novaframe.session.capture

import com.firstt175.novaframe.session.NovaLog
import com.firstt175.novaframe.session.NativeBridge

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Wraps [MediaProjection] + [VirtualDisplay] and directs the captured content onto a Surface
 * the caller provides, at a caller-specified size so overlay and capture stay pixel-aligned.
 *
 * The Surface lifecycle is fragile — a valid Surface can disappear at any time (orientation
 * change, SurfaceFlinger reshuffling, user entering another immersive app). [setSurface]
 * and [clearSurface] track that lifecycle without tearing down the VirtualDisplay itself,
 * which is important: re-creating the VirtualDisplay would require a second MediaProjection
 * consent on some OEMs.
 *
 * Capture is intentionally dumb: receive a frame from ImageReader, hand it to
 * NativeBridge.pushFrame(). Scheduling, backlog handling, frame dropping and
 * latency policy all belong to the native render loop's own queue — see
 * CaptureMetrics for the (also engine-independent) FPS/frame-graph telemetry.
 */
class CaptureEngine(
    private val ctx: Context,
    private val mediaProjection: MediaProjection,
) {
    private val nextFrameId = java.util.concurrent.atomic.AtomicLong(0L)

    private var virtualDisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var currentSurface: Surface? = null

    @Volatile
    private var currentW: Int = 0

    @Volatile
    private var currentH: Int = 0

    // --- LSFG mode ---
    //
    // In mirror mode (setSurface) the VirtualDisplay points straight at the overlay
    // SurfaceView and there is no frame generation. In LSFG mode the VirtualDisplay
    // points at an ImageReader instead, and each frame's HardwareBuffer is forwarded
    // to the native render loop via NativeBridge.pushFrame(). The native side blits
    // generated frames to the overlay (which it learned about via setOutputSurface).
    private var lsfgReader: ImageReader? = null
    private var lsfgThread: HandlerThread? = null
    private var lsfgHandler: Handler? = null

    @Volatile
    private var lsfgNativeInputEnabled: Boolean = true

    fun setNovaNativeInputEnabled(enabled: Boolean) {
        lsfgNativeInputEnabled = enabled
    }

    /**
     * ImageReader queue depth. RenderLoop owns all frame scheduling and
     * latency decisions; capture only forwards frames into its queue.
     * Must be >= 2 for acquireNextImage() to have room to pipeline at all.
     */
    @Volatile
    var lsfgQueueDepth: Int = 3
        set(value) { field = value.coerceAtLeast(2) }

    @Synchronized
    fun setNovaMode(width: Int, height: Int) {
        // Android 14+ treats each MediaProjection token as single-use for
        // createVirtualDisplay() on many OEM builds. Keep the existing display
        // alive and retarget it from mirror output to the ImageReader instead
        // of releasing it and creating a second display.
        currentSurface = null

        stopNovaMode()

        val t = HandlerThread("lsfg-capture-lsfg").also { it.start() }
        val h = Handler(t.looper)
        lsfgThread = t
        lsfgHandler = h

        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888,
            /* maxImages */
            // Reduced from the original 5/2 split: each buffered image at full
            // display resolution (RGBA8888) is several MB, so the queue depth is
            // real, measurable RAM — 5 buffers at 1080x2400 is ~52MB just for this
            // queue. 3 still gives the pipeline a couple of frames of slack to
            // preserve temporal continuity for framegen without paying for two
            // buffers' worth of memory that mostly sit idle between captures.
            lsfgQueueDepth,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        reader.setOnImageAvailableListener({ r ->
            // Capture is intentionally dumb: take the next captured frame and
            // hand it to the native render-loop queue. Scheduling, backlog
            // handling, frame dropping and latency policy belong to renderLoop.
            val img = runCatching { r.acquireNextImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val hb = img.hardwareBuffer
                if (hb != null) {
                    if (lsfgNativeInputEnabled) {
                        runCatching { NativeBridge.pushFrame(hb, img.timestamp, nextFrameId.incrementAndGet()) }
                            .onFailure { NovaLog.w(TAG, "pushFrame failed", it) }
                    }
                    hb.close()
                }
            } finally {
                img.close()
            }
        }, h)
        lsfgReader = reader

        currentW = width
        currentH = height

        val dpi = ctx.resources.displayMetrics.densityDpi
        val vd = virtualDisplay
        if (vd == null) {
            val flags = 0
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "lsfg-vd",
                width, height, dpi,
                flags,
                reader.surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() { NovaLog.i(TAG, "LSFG VD paused") }
                    override fun onResumed() { NovaLog.i(TAG, "LSFG VD resumed") }
                    override fun onStopped() { NovaLog.i(TAG, "LSFG VD stopped") }
                },
                h,
            )
            NovaLog.i(TAG, "LSFG mode active ${width}x${height} @${dpi}dpi flags=0x${flags.toString(16)}")
        } else {
            runCatching { vd.resize(width, height, dpi) }
                .onFailure { NovaLog.w(TAG, "LSFG resize failed", it) }
            runCatching { vd.surface = reader.surface }
                .onFailure { NovaLog.w(TAG, "LSFG retarget failed", it) }
            NovaLog.i(TAG, "LSFG mode retargeted ${width}x${height} @${dpi}dpi")
        }
    }

    @Synchronized
    private fun stopNovaMode() {
        lsfgNativeInputEnabled = false
        runCatching { virtualDisplay?.surface = null }
            .onFailure { NovaLog.w(TAG, "detaching LSFG surface failed", it) }
        runCatching { lsfgReader?.close() }
        lsfgReader = null
        lsfgThread?.quitSafely()
        lsfgThread = null
        lsfgHandler = null
    }

    /**
     * Points the capture at a freshly-valid Surface. Safe to call multiple times: the first
     * call lazily creates the VirtualDisplay; later calls swap the destination and resize.
     */
    @Synchronized
    fun setSurface(outputSurface: Surface, width: Int, height: Int) {
        NovaLog.i(TAG, "setSurface enter ${width}x${height} valid=${outputSurface.isValid} hasVD=${virtualDisplay != null}")
        if (!outputSurface.isValid) {
            NovaLog.w(TAG, "setSurface called with invalid Surface; ignoring")
            return
        }
        lsfgNativeInputEnabled = false
        currentSurface = outputSurface
        currentW = width
        currentH = height

        val vd = virtualDisplay
        if (vd == null) {
            val t = HandlerThread("lsfg-capture").also { it.start() }
            val h = Handler(t.looper)
            thread = t
            handler = h

            val dpi = ctx.resources.displayMetrics.densityDpi
            // IMPORTANT: flags=0 is what scrcpy and most screen-share apps use.
            // MediaProjection already delivers the physical display frames by
            // default. Adding AUTO_MIRROR or PRESENTATION creates a secondary
            // logical display and on PowerVR this feeds the overlay back into
            // itself (first frame freezes, captures itself, loop closes).
            val flags = 0
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "lsfg-vd",
                width, height, dpi,
                flags,
                outputSurface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() { NovaLog.i(TAG, "VirtualDisplay paused") }
                    override fun onResumed() { NovaLog.i(TAG, "VirtualDisplay resumed") }
                    override fun onStopped() { NovaLog.i(TAG, "VirtualDisplay stopped") }
                },
                h,
            )
            NovaLog.i(TAG, "VirtualDisplay created ${width}x${height} @${dpi}dpi flags=0x${flags.toString(16)}")
        } else {
            val dpi = ctx.resources.displayMetrics.densityDpi
            // Resize BEFORE swapping the surface: some drivers stop posting frames to
            // the new surface until its dimensions match the VirtualDisplay.
            runCatching { vd.resize(width, height, dpi) }
                .onFailure { NovaLog.w(TAG, "resize failed", it) }
            runCatching { vd.surface = outputSurface }
                .onFailure { NovaLog.w(TAG, "setting surface failed", it) }
            NovaLog.i(TAG, "VirtualDisplay retargeted ${width}x${height}")
        }
    }

    /**
     * Detaches the current Surface without destroying the VirtualDisplay. The display keeps
     * running (paused) until [setSurface] attaches a new one.
     */
    @Synchronized
    fun clearSurface() {
        val vd = virtualDisplay ?: return
        currentSurface = null
        runCatching { vd.surface = null }
            .onFailure { NovaLog.w(TAG, "clearing surface failed", it) }
        NovaLog.i(TAG, "Surface detached from VirtualDisplay")
    }

    @Synchronized
    fun stop() {
        stopNovaMode()
        virtualDisplay?.release()
        virtualDisplay = null
        currentSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /**
     * Halts input pumping into the native pipeline without destroying the
     * MediaProjection token. Used by the service before re-creating the LSFG
     * context: we want the C++ worker to drain its queue before destroyContext
     * so a slow vkDeviceWaitIdle doesn't run while a new pushFrame is racing
     * the teardown. setNovaMode() is called afterwards to re-create the
     * ImageReader against the new context's input AHBs.
     */
    @Synchronized
    fun pauseNovaInput() {
        stopNovaMode()
        currentSurface = null
    }

    companion object {
        private const val TAG = "CaptureEngine"
    }
}
