package com.firstt175.novaframe.session.capture

import com.firstt175.novaframe.session.NovaLog
import com.firstt175.novaframe.session.NativeBridge

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * FPS / frame-pacing-graph telemetry for the HUD.
 *
 * This reads exclusively from NativeBridge's render-loop counters
 * (getUniqueCaptureCount / getPostedFrameCount / getGeneratedFrameCount).
 * Those counters live in the native render loop itself, so there is exactly
 * one set of numbers to poll regardless of how frames reach pushFrame().
 */
object CaptureMetrics {

    fun interface FpsListener {
        fun onFpsUpdate(capturedFps: Float, postedFps: Float)
    }

    fun interface FrameGraphListener {
        fun onFrameGraphSample(realFps: Float, generatedFps: Float)
    }

    @Volatile
    private var fpsListener: FpsListener? = null

    @Volatile
    private var graphListener: FrameGraphListener? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var fpsPoller: Runnable? = null
    private var graphPoller: Runnable? = null

    // 1 Hz FPS-counter window state — kept separate from the graph poller's
    // counters below so the two windows don't steal each other's deltas.
    private var fpsWindowStartMs: Long = 0L
    private var lastUniqueCaptureCount: Long = 0L
    private var lastPostedCount: Long = 0L

    private var graphWindowStartMs: Long = 0L
    private var graphLastUniqueCaptureCount: Long = 0L
    private var graphLastGeneratedCount: Long = 0L
    // EMA state for the 5 Hz frame graph. Raw per-window counts jitter because
    // the 200 ms window catches an integer number of worker cycles (typically
    // 5-7 of ~33 ms), and the boundary varies — so 20 % raw jitter even when
    // the underlying rate is steady. α=0.35 → ~3-sample lag ≈ 600 ms settling,
    // slow enough to kill aliasing, fast enough to follow real rate changes.
    private var graphRealEma: Float = 0f
    private var graphGenEma: Float = 0f

    fun setFpsListener(l: FpsListener?) {
        fpsListener = l
    }

    fun setFrameGraphListener(l: FrameGraphListener?) {
        graphListener = l
    }

    private fun ensureThread(): Handler {
        val existing = handler
        if (existing != null) return existing
        val t = HandlerThread("lsfg-metrics").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        return h
    }

    private fun maybeQuitThread() {
        if (fpsPoller == null && graphPoller == null) {
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    @Synchronized
    fun startFpsCounter() {
        if (fpsPoller != null) return
        val h = ensureThread()

        fpsWindowStartMs = SystemClock.elapsedRealtime()
        lastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        lastPostedCount = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)

        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - fpsWindowStartMs).coerceAtLeast(1L)
                fpsWindowStartMs = now

                // Real: unique capture arrivals in the last window — same
                // counter the frame graph's "real" line is derived from.
                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - lastUniqueCaptureCount).coerceAtLeast(0L)
                lastUniqueCaptureCount = uniqNow
                val realFps = uniqDelta * 1000f / elapsed

                // Total: everything actually posted to the overlay surface
                // (real + generated) in the last window.
                val postedNow = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
                val postedDelta = (postedNow - lastPostedCount).coerceAtLeast(0L)
                lastPostedCount = postedNow
                val totalFps = postedDelta * 1000f / elapsed

                fpsListener?.onFpsUpdate(realFps, totalFps)
                handler?.postDelayed(this, 1000L)
            }
        }
        fpsPoller = poll
        h.postDelayed(poll, 1000L)
        NovaLog.i(TAG, "FPS counter started")
    }

    @Synchronized
    fun stopFpsCounter() {
        fpsPoller?.let { handler?.removeCallbacks(it) }
        fpsPoller = null
        maybeQuitThread()
    }

    @Synchronized
    fun startFrameGraph() {
        if (graphPoller != null) return
        val h = ensureThread()

        graphWindowStartMs = SystemClock.elapsedRealtime()
        graphLastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        graphLastGeneratedCount = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
        graphRealEma = 0f
        graphGenEma = 0f

        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - graphWindowStartMs).coerceAtLeast(1L)
                graphWindowStartMs = now

                // Real line: target-app render rate, measured as the rate of
                // capture frames whose pixel content changed vs. the prior
                // frame — the ground truth for "how fast is the game actually
                // producing new content".
                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - graphLastUniqueCaptureCount).coerceAtLeast(0L)
                graphLastUniqueCaptureCount = uniqNow
                val realFpsRaw = uniqDelta * 1000f / elapsed

                // Generated line: LSFG-interpolated output frames.
                val genNow = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
                val genDelta = (genNow - graphLastGeneratedCount).coerceAtLeast(0L)
                graphLastGeneratedCount = genNow
                val genFpsRaw = genDelta * 1000f / elapsed

                graphRealEma = if (graphRealEma <= 0.01f) realFpsRaw
                               else EMA_ALPHA * realFpsRaw + (1f - EMA_ALPHA) * graphRealEma
                graphGenEma = if (graphGenEma <= 0.01f) genFpsRaw
                              else EMA_ALPHA * genFpsRaw + (1f - EMA_ALPHA) * graphGenEma

                graphListener?.onFrameGraphSample(graphRealEma, graphGenEma)
                handler?.postDelayed(this, 200L)
            }
        }
        graphPoller = poll
        h.postDelayed(poll, 200L)
        NovaLog.i(TAG, "Frame graph started")
    }

    @Synchronized
    fun stopFrameGraph() {
        graphPoller?.let { handler?.removeCallbacks(it) }
        graphPoller = null
        maybeQuitThread()
    }

    /** Stops both pollers and drops listeners. Call once when the session fully ends. */
    @Synchronized
    fun reset() {
        stopFpsCounter()
        stopFrameGraph()
        fpsListener = null
        graphListener = null
    }

    private const val EMA_ALPHA = 0.35f
    private const val TAG = "CaptureMetrics"
}
