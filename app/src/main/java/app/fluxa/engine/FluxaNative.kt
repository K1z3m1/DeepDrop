package app.fluxa

import android.content.Context
import android.view.Surface

object FluxaNative {
    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (!loaded) {
                loaded = runCatching { System.loadLibrary("fluxa") }.isSuccess
            }
            loaded
        }
    }

    @JvmStatic
    external fun nativeCreate(
        context: Context,
        output: Surface,
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
        driverName: String?,
        multiplier: Int,
        flowSide: Int,
        refreshRate: Float,
        sourceRate: Float,
        presentMode: Int,
        upscaleMode: Int,
        upscaleSharp: Int,
        upscaleLook: Long,
    ): Long

    @JvmStatic
    external fun nativeProducerSurface(handle: Long): Surface?

    @JvmStatic
    external fun nativeConfigure(
        handle: Long,
        multiplier: Int,
        flowSide: Int,
        refreshRate: Float,
        sourceRate: Float,
        presentMode: Int,
        upscaleMode: Int,
        upscaleSharp: Int,
        upscaleLook: Long,
    )

    /** GPU cost of the frame generation: 0 full, 1 balanced, 2 lite, 3 ultra-lite. Live. */
    @JvmStatic
    external fun nativeSetGenMode(handle: Long, mode: Int)

    /** Adaptive output rate: 0 = off (fixed multiplier), >0 = aim at this fps. Live. */
    @JvmStatic
    external fun nativeSetTargetFps(handle: Long, fps: Int)

    /** Bit 0: the device can sample with the cubic filter. */
    @JvmStatic
    external fun nativeCapabilities(handle: Long): Int

    @JvmStatic
    external fun nativeRealFrames(handle: Long): Long

    @JvmStatic
    external fun nativeGeneratedFrames(handle: Long): Long

    /** Newest real-frame intervals in microseconds (oldest first) into [out]; returns how many were written. */
    @JvmStatic
    external fun nativeFrameTimes(handle: Long, out: IntArray): Int

    /** Mirrors every shown frame (generated + real) into [surface] (an encoder input surface). */
    @JvmStatic
    external fun nativeRecordStart(handle: Long, surface: Surface, width: Int, height: Int): Boolean

    @JvmStatic
    external fun nativeRecordStop(handle: Long)

    /**
     * HUD burned into the recording: [rgba] is an opaque width x height RGBA8 bitmap (null = hide),
     * placed [marginX]/[marginY] video pixels from the top-left corner of the shown frame.
     */
    @JvmStatic
    external fun nativeRecordHud(handle: Long, rgba: ByteArray?, width: Int, height: Int, marginX: Int, marginY: Int)

    @JvmStatic
    external fun nativeRecordedFrames(handle: Long): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    // ---- still-image upscale (before/after comparison); never use while a session is running ----

    /** -1 = Vulkan unusable here; otherwise bit 0 = ready, bit 1 = cubic filter supported. */
    @JvmStatic
    external fun nativeStillInfo(): Int

    /**
     * Runs the presenter's upscaler (same shaders) on [rgba] (srcW x srcH, R,G,B,A bytes) and returns
     * outW x outH RGBA bytes, or null on failure. [sharp] = strength 0..200 | method shl 8;
     * [look] = colour grade ([DisplayTuner.packLook], 0 = none).
     */
    @JvmStatic
    external fun nativeStillUpscale(
        rgba: ByteArray,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        mode: Int,
        sharp: Int,
        look: Long,
    ): ByteArray?

    @JvmStatic
    external fun nativeStillRelease()
}
