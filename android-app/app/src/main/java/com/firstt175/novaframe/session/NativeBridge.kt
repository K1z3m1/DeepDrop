package com.firstt175.novaframe.session

import com.firstt175.novaframe.session.capture.CaptureEngine
import com.firstt175.novaframe.session.models.BundledIfrnetModel
import com.firstt175.novaframe.session.models.BundledRifeModel

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Surface

/**
 * JNI entry points into liblsfg-android.so.
 * Implementations live in app/src/main/cpp/lsfg_jni.cpp.
 *
 * Every method returns a simple result so the Kotlin side can surface errors in the UI
 * without throwing across JNI.
 */
object NativeBridge {

    init {
        System.loadLibrary("lsfg-android")
    }

    /**
     * Installs native signal handlers (SIGSEGV/SIGABRT/SIGBUS/SIGFPE/SIGILL) that write a
     * crash report to [crashPath] and mirror every native LOG* call into [logPath].
     * Idempotent — subsequent calls are no-ops. Call once, early, from Application.onCreate.
     */
    external fun initCrashReporter(crashPath: String, logPath: String)

    /**
     * Extracts the precompiled FP16/FP32 SPIR-V shader resources from the user-provided
     * Lossless.dll (requires exactly Lossless Scaling 3.2.2.0), writing the result into [cacheDir].
     * Idempotent — a cached SPIR-V set is reused on subsequent calls as long as [dllSha256]
     * matches.
     *
     * @return 0 on success, non-zero error code otherwise.
     */
    external fun extractShaders(
        dllPath: String,
        dllSha256: String,
        cacheDir: String,
    ): Int

    /**
     * Creates a headless Vulkan instance + device and runs `vkCreateShaderModule` across every
     * cached SPIR-V blob. Useful to catch driver-side rejection of the extracted SPIR-V
     * before we try to build the full pipeline.
     *
     * @return 0 on success; -10 no Vulkan loader, -11 cache missing, -12 shader rejected.
     */
    external fun probeShaders(cacheDir: String): Int

    /**
     * Initialises the Vulkan session, allocates ping-pong AHardwareBuffer-backed input images
     * plus [multiplier]-many output images, and creates a framegen LSFG_3_1 context.
     *
     * @return 0 on success; negative codes from android_vk_session.hpp / lsfg_render_loop.hpp
     *   describe specific failures (no Vulkan, missing extensions, AHB allocation failure, etc.).
     *   On a -41/-42 the Kotlin side should fall back to mirror mode and surface a notice.
     */
    // Kotlin doesn't allow default parameter values on `external fun` (there's
    // no method body for the compiler to generate the synthetic $default
    // wrapper against), so every caller must pass all four AI params
    // explicitly. Pass aiBackend=false to keep using the LSFG shader path.
    external fun initContext(
        cacheDir: String,
        width: Int,
        height: Int,
        multiplier: Int,
        flowScale: Float,
        performance: Boolean,
        hdr: Boolean,
        framegenFp16: Boolean,
        aiBackend: Boolean,
        aiModelDir: String,
        // Which ncnn graph aiModelDir contains: 0 = RIFE (flownet.param/.bin),
        // 1 = IFRNet (ifrnet.param/.bin). Mirrors
        // com.firstt175.novaframe.prefs.AiEngine.prefValue ordering. Ignored when
        // aiBackend is false.
        aiEngine: Int,
    ): Int

    /**
     * Human-readable Vulkan device name (e.g. "Adreno (TM) 740") for gpu
     * [index]. Device 0 is what RIFE/IFRNet actually load onto (Kotlin
     * always passes vulkanDeviceIndex = -1, which resolves to 0 natively).
     * Returns "" if [index] is out of range or this build has no ncnn.
     */
    external fun getVulkanGpuName(index: Int): String

    /**
     * Vulkan API version this device's GPU driver actually supports, formatted
     * as "major.minor.patch" (e.g. "1.3.106"), or "unknown" on any probe
     * failure. Negotiated live via vkEnumerateInstanceVersion rather than
     * assumed — see get_vulkan_device_api_version_string() in
     * android_vk_probe.cpp. Used by the Device Info card.
     */
    external fun getVulkanApiVersion(): String

    /** GPU vendor decoded from VkPhysicalDeviceProperties::vendorID, e.g. "Qualcomm (Adreno)". */
    external fun getGpuVendor(): String

    /** "integrated", "discrete", "virtual", "cpu", or "other" — VkPhysicalDeviceType decoded. */
    external fun getGpuDeviceType(): String

    /** GPU driver version as "major.minor.patch" (imprecise for NVIDIA's packed encoding, exact elsewhere). */
    external fun getGpuDriverVersion(): String

    /**
     * Approximate VRAM in MiB: the largest Vulkan DEVICE_LOCAL memory heap.
     * On mobile's near-universal unified memory architecture this is a
     * GPU-addressable slice of shared system RAM, not dedicated video RAM —
     * there's no portable Vulkan query that distinguishes the two, so treat
     * this as an estimate. -1 if the probe failed.
     */
    external fun getGpuVramMb(): Long

    /**
     * Reports whether the FP16 frame-generation shader path is usable on this
     * device. Two prerequisites must both hold:
     *  - The Vulkan driver advertises VK_KHR_shader_float16_int8 with
     *    `shaderFloat16=VK_TRUE`.
     *  - The DLL extraction step has populated `<cacheDir>/fp16/` with the
     *    49 SPIR-V FP16 shader variants from Lossless.dll resource IDs 304..351.
     *
     * The UI uses this to grey out the "FP16 frame-gen shaders" toggle on
     * unsupported hardware or before the user has picked a DLL. Cheap to
     * call (single VkInstance create + feature query, no device created).
     */
    external fun isFramegenFp16Supported(cacheDir: String): Boolean

    /**
     * Attaches the overlay [surface] as the destination for blit of generated frames.
     * Pass null to detach. Safe to call before initContext (it just stashes the window).
     */
    external fun setOutputSurface(surface: Surface?, w: Int, h: Int)

    /** Attaches an optional hardware-encoder surface. Frames are teed after the display post. */
    external fun setRecordingSurface(surface: Surface?, w: Int, h: Int)

    /**
     * Hands a fresh capture frame (as a [HardwareBuffer]) to the native render loop.
     * The native side acquires its own reference, so the caller may close the source
     * Image/HardwareBuffer immediately after this returns.
     */
    external fun pushFrame(hardwareBuffer: HardwareBuffer, timestampNs: Long, frameId: Long)

    /**
     * Atomic counter of frames produced by framegen since the last initContext.
     * Used by CaptureEngine's FPS poller to compute the "total fps" delta.
     */
    external fun setWaitForBusyGeneration(enabled: Boolean)

    /** Admit a new generation even while a previous one is still busy, instead of skipping it. */
    external fun setAllowGenerationWhenBusy(enabled: Boolean)

    /** Keep every captured real frame queued instead of bounding the queue at maxQueuedRealFrames. */
    external fun setLosslessQueue(enabled: Boolean)

    external fun getGeneratedFrameCount(): Long

    /**
     * Total frames actually posted to the overlay surface since the last
     * initContext (both real captures AND LSFG-generated frames, across CPU
     * blit and WSI present paths). This is the ground-truth count for
     * "total fps" in the HUD — replaces the old `capturedFps + genFps` sum
     * which conflated capture rate with post rate.
     */
    external fun getPostedFrameCount(): Long

    /**
     * Number of capture frames whose pixel content differs from the previous
     * capture. MediaProjection delivers at the display refresh rate, which is
     * usually higher than the target app's render rate — consecutive captures
     * are often pixel-identical duplicates of the same game frame. This
     * counter approximates the target app's TRUE render rate (what the HUD
     * should show as "real fps"). Computed via an 8×8 luma hash in pushFrame.
     */
    external fun getUniqueCaptureCount(): Long

    /**
     * Average native queue residency time in milliseconds for the last completed profiling window.
     */
    external fun getAverageQueueMs(): Double

    /**
     * Average end-to-end latency in milliseconds (capture-to-display) for the last completed profiling window.
     */
    external fun getAverageLatencyMs(): Double

    /**
     * Fills [outIntervalsNs] with the nanosecond intervals between consecutive
     * overlay posts, newest-first. Returns the number of intervals actually
     * written (may be fewer than the array length if the session is young).
     * Used by the HUD frame-pacing graph to show real jitter instead of rolling
     * counts.
     */
    external fun getRecentPostIntervalsNs(outIntervalsNs: LongArray): Int

    /**
     * Interval-based FPS snapshot — measured from the actual elapsed time
     * between the most recent capture/post events (native ring buffers),
     * not from a counter delta over a fixed polling window. On success
     * fills `out[0]` = real fps (capture rate) and `out[1]` = total fps
     * (posted rate, real + generated) and returns true. `out` must have
     * length >= 2. Returns false before enough events have been recorded
     * (e.g. immediately after init) — the caller should keep its previous
     * displayed value in that case.
     */
    external fun getFpsSnapshot(out: FloatArray): Boolean

    /**
     * Snapshot of the most recently completed profiling window from the native
     * worker thread. `out` must have length >= 6; on success it's populated with
     * `[copyNs, presentNs, waitIdleNs, blitNs, totalNs, samples]` (segment SUMS
     * over the window — divide by samples for per-frame averages) and the call
     * returns 6. Returns 0 when no window has closed yet (samples == 0).
     */
    external fun getProfileWindowNs(out: LongArray): Int

    /**
     * Toggle frame-generation bypass. When true, the native render loop blits
     * the latest captured frame straight to the overlay surface and skips
     * framegen entirely — useful for A/B comparisons against the generated output.
     * Persists across re-inits triggered by other parameter changes.
     */
    external fun setBypass(bypass: Boolean)

    /** Live post-process controls. Backend 0 = CPU, 1 = GPU/Vulkan presentation path. */
    external fun setImageEnhancement(enabled: Boolean, backend: Int, contrast: Float, saturation: Float)

    /** Filter used to scale the framegen output to the display surface when their
     *  resolutions differ. 0 = Nearest, 1 = Bilinear. */
    external fun setUpscaleEnabled(enabled: Boolean)

    external fun setUpscaleFilter(mode: Int)

    /**
     * Runs the same native image-enhancement/upscale parameter path used by the
     * render loop on a preview bitmap. No Compose/Java color processing is used.
     * The bitmap is modified in place.
     */
    external fun processImageEnhancementPreview(
        bitmap: Bitmap,
        enabled: Boolean,
        backend: Int,
        contrast: Float,
        saturation: Float,
        upscaleEnabled: Boolean,
        upscaleFilter: Int,
        renderResolutionScale: Float,
    ): Boolean

    /**
     * Swapchain presentation mode, as a raw VkPresentModeKHR value
     * (IMMEDIATE=0, MAILBOX=1, FIFO=2 — see [com.firstt175.novaframe.prefs.PresentMode]).
     * Applied on the next swapchain (re)creation, triggered automatically
     * from the render thread the first time it notices the live setting no
     * longer matches the active swapchain. No fallback: if the surface
     * doesn't support the requested mode, presentation is disabled natively
     * for the rest of the session.
     */
    external fun setPresentMode(mode: Int)

    /**
     * How many REAL captures may sit queued behind the one currently being
     * processed before the oldest are evicted. Clamped natively to [1, 8]; 1
     * reproduces the original "queue bounded to one waiting frame" behaviour.
     * Higher trades latency/memory for more buffering headroom.
     */
    external fun setMaxQueuedRealFrames(depth: Int)

    /** Maximum capture-to-generation latency in milliseconds; 0 disables the deadline. */
    external fun setGenerationDeadlineMs(deadlineMs: Int)

    /** Automatically skip GPU frame generation once a REAL frame reaches the configured age. 0 disables. */
    external fun setBypassGenDeadlineMs(deadlineMs: Int)

    /** Keep BypassGen active for this many milliseconds before allowing generation to resume. */
    external fun setBypassGenResumeDelayMs(delayMs: Int)

    /**
     * Whether a VK_ERROR_DEVICE_LOST during presentContext is allowed to
     * auto-disable framegen for the rest of the session. ON by default
     * (crash-recovery safety net); turning it off lets framegen keep retrying
     * on later frames instead of latching into permanent passthrough.
     */
    external fun setAutoDisableOnDeviceLostEnabled(enabled: Boolean)

    /**
     * Manually clears an already-latched device-lost auto-disable without a
     * full context reinit. Returns true if it was actually latched (and is
     * now cleared), false if framegen wasn't auto-disabled to begin with.
     */
    external fun resumeFramegenAfterAutoDisable(): Boolean

    /** Tears down the Vulkan context. Safe to call multiple times. */
    external fun destroyContext()

    // -------------------------------------------------------------------
    // AI (ncnn) frame-gen backend — FlowNetLite + RefineNetLite loaded from
    // a bundled APK asset or user-imported model folder (see DllPickerScreen's
    // "AI MODEL (NCNN)" card). Only functional in builds where CMakeLists.txt
    // found the ncnn Android SDK (LSFG_HAVE_NCNN); otherwise every call here
    // returns a negative "not built" code without side effects, so it's
    // always safe to call these regardless of how the .so was built.
    //
    // Two distinct entry points into the same backend:
    //  - initContext(..., aiBackend=true, aiModelDir=...) runs the Vulkan model live
    //    inside the capture session's render loop. AI inference and custom Warp
    //    layers are GPU-only; no CPU ncnn inference fallback exists.
    //    backends share the rest of the pacing/blit pipeline and are
    //    mutually exclusive per session (LSFG_3_1::initialize is skipped
    //    entirely when the AI backend loads successfully).
    //  - initAiInterpolator/aiInterpolatePreview below are a standalone
    //    load+run pair for validating an imported model outside of a live
    //    session (e.g. a "test model" action in Settings) — they do not
    //    affect and are not affected by an active initContext session.
    // -------------------------------------------------------------------

    /**
     * Loads the ncnn graph for the selected [engine] from [modelDir] — RIFE's
     * flownet.param/.bin (0) or IFRNet's ifrnet.param/.bin (1). [modelDir]
     * comes from [BundledRifeModel.ensureExtracted] or
     * [BundledIfrnetModel.ensureExtracted] depending on which engine is
     * being tested. Returns 0 on success, a negative lsfg_android::kNcnnErr*
     * code otherwise (see NcnnInterpolator.hpp) — most notably -1 if this
     * .so was built without ncnn at all.
     */
    external fun initAiInterpolator(
        modelDir: String,
        useVulkan: Boolean,
        vulkanDeviceIndex: Int,
        numThreads: Int,
        /** 0 = RIFE (flownet.param/.bin), 1 = IFRNet (ifrnet.param/.bin). */
        engine: Int,
    ): Int

    /** Frees the loaded ncnn networks. Safe to call even if nothing was loaded. */
    external fun releaseAiInterpolator()

    /**
     * Runs one interpolation pass between two RGBA8 frames and writes a single
     * output frame — the one at position [outIndex] out of [multiplier] steps
     * between them (e.g. outIndex=1, multiplier=2 for the classic exact
     * midpoint) — into [outFrame]. [frameA]/[frameC]/[outFrame] must be
     * direct ByteBuffers of exactly `width*height*4` bytes. Returns 0 on
     * success, a negative lsfg_android::kNcnnErr* code otherwise. Meant for a
     * settings-screen model preview, not per-frame session use — see the
     * class-level note above.
     */
    external fun aiInterpolatePreview(
        frameA: java.nio.ByteBuffer,
        frameC: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        outFrame: java.nio.ByteBuffer,
        outIndex: Int,
        multiplier: Int,
        flowScale: Float,
        /** 0 = RIFE (flownet.param/.bin), 1 = IFRNet (ifrnet.param/.bin). Must match
         *  whichever engine was passed to the [initAiInterpolator] call that loaded it. */
        engine: Int,
    ): Int

    /**
     * AI-FRAMEGEN-EDITOR: one model run producing all (multiplier - 1) frames between
     * [frameA] and [frameC]. Frame k (1-based) is written at byte offset
     * (k - 1) * width * height * 4 of [outFrames], which must be a direct ByteBuffer with
     * room for (multiplier - 1) RGBA8 frames. [frameA]/[frameC] are direct RGBA8 buffers.
     * Same return codes and [engine] values as [aiInterpolatePreview]; the model must
     * already be loaded through [initAiInterpolator].
     */
    external fun aiInterpolateBatch(
        frameA: java.nio.ByteBuffer,
        frameC: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        outFrames: java.nio.ByteBuffer,
        multiplier: Int,
        flowScale: Float,
        engine: Int,
    ): Int
}
