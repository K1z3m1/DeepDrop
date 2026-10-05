#pragma once

// Glue between MediaProjection (AHardwareBuffer frames pushed from Kotlin),
// the framegen LSFG_3_1 pipeline (consumes opaque FDs), and the overlay
// SurfaceView (an ANativeWindow we blit final frames to).
//
// Lifecycle:
//   init()        — create VulkanSession, allocate input/output AhbImages,
//                   call LSFG_3_1::initialize and createContext
//   setOutputSurface(window, w, h) — attach the overlay surface for blit
//   pushFrame(ahb)— enqueue a fresh capture frame; render thread imports it zero-copy
//                   into the next ping-pong slot, present, and blit outputs
//   shutdown()    — flush, deleteContext, finalize, destroy session
//
// Counter:
//   getGeneratedFrameCount() — atomic counter incremented per generated
//   frame (multiplier-many per pushFrame). Drives the overlay's "total fps".

#include <cstdint>
#include <string>

struct ANativeWindow;
struct AHardwareBuffer;

namespace lsfg_android {

constexpr int kRenderLoopAlreadyInit = -40;
constexpr int kRenderLoopSessionFailed = -41;
constexpr int kRenderLoopFramegenFailed = -42;
constexpr int kRenderLoopBufferAlloc = -43;

// Positive "soft" status: init succeeded but framegen is disabled (missing
// extensions, shader load failure, FD export refused, …). The caller should
// fall back to mirror mode — keep the overlay up, feed it the raw capture,
// and surface a notice to the user. The render loop itself stays initialised
// and must be torn down normally via shutdownRenderLoop() when the session ends.
constexpr int kRenderLoopFramegenDisabled = 1;

struct RenderLoopConfig {
    uint32_t width;
    uint32_t height;
    int multiplier;       // generationCount passed to LSFG: total = capture * multiplier
    float flowScale;      // 0.25 .. 1.0
    bool performance;     // selects LSFG_3_1P vs LSFG_3_1
    bool hdr;
    // Use the precompiled SPIR-V FP16 shader variants (Lossless.dll resource
    // IDs 304..351) instead of the default precompiled FP32 SPIR-V set
    // (IDs 353..400). The FP16 variants enable OpCapability Float16 and use
    // mixed FP16/FP32 ops. Requires the GPU to support
    // VK_KHR_shader_float16_int8 + shaderFloat16, and requires the FP16
    // SPIR-V cache to have been populated by the DLL extraction step. The
    // render loop transparently falls back to the FP32 path when either
    // prerequisite is missing.
    bool framegenFp16;
    // Selects the interpolation backend. When true, the render loop skips
    // LSFG_3_1::initialize/createContext entirely and instead loads
    // NcnnInterpolator from `aiModelDir` (the directory AiModelBundleReader.kt
    // copies from the bundled model asset), running the selected RIFE/IFRNet graph on Vulkan GPU only. Ignored (falls back to the
    // LSFG shader path) when this .so was built without ncnn (LSFG_HAVE_NCNN
    // not defined) or when the GPU-only model load fails — check logcat tag
    // "lsfg-render" for the reason. `multiplier`/`flowScale` above are shared
    // between both backends.
    bool aiBackend = false;
    std::string aiModelDir;   // must contain the selected GPU model files
    // Which ncnn graph aiModelDir is expected to contain: 0 = RIFE
    // (NcnnInterpolator, flownet.param/.bin), 1 = IFRNet (IfrnetInterpolator,
    // ifrnet.param/.bin). Mirrors com.firstt175.novaframe.prefs.AiEngine's prefValue
    // ordering — keep them in sync. Any other value falls back to RIFE.
    // Ignored when aiBackend is false.
    int aiEngine = 0;
};

// Initialise render loop: create Vulkan session, allocate ping-pong inputs +
// (multiplier-1) outputs, initialize framegen, create context. Returns kOk
// or one of kRenderLoop* on failure.
//
// `cacheDir` is forwarded to the framegen shader-loader callback.
int initRenderLoop(const char *cacheDir, const RenderLoopConfig &cfg);

// Attach (or replace) the output ANativeWindow. Caller transfers ownership;
// the render loop will release the previous one (if any) and acquire `win`.
// Pass nullptr to detach.
void setOutputSurface(ANativeWindow *win, uint32_t w, uint32_t h);

// Optional secondary presentation target used by the hardware screen recorder.
// It receives the exact same final REAL/GENERATED frame after the display post.
void setRecordingSurface(ANativeWindow *win, uint32_t w, uint32_t h);

// 0 = no-wait, 1 = wait-for-generation.
void setWaitForBusyGeneration(bool enabled);
void setAllowGenerationWhenBusy(bool enabled);
void setLosslessQueue(bool enabled);

// Push a capture frame. The render loop acquires a reference to `ahb`
// (so it stays valid past the caller's Image.close()) and processes it
// asynchronously. `timestampNs` should come from Image.getTimestamp() when
// available so output pacing can follow the real capture cadence.
//
// The internal queue is an unbounded FIFO: every capture that gets in is
// retained and processed; this call never blocks and never drops `ahb`.
// Dynamic load control may bypass only the optional frame-generation work for
// an individual capture. The real capture frame is always processed and
// presented, then generated frames are presented afterward when there is
// enough measured headroom. Safe to call from any thread.
void pushFrame(AHardwareBuffer *ahb, int64_t timestampNs, uint64_t frameId);

// Shut down everything. Idempotent.
void shutdownRenderLoop();

// Counter for the FPS overlay. Returns 0 before init / after shutdown.
uint64_t getGeneratedFrameCount();

// Total frames actually posted to the overlay surface (CPU blit or WSI
// present path). Unlike getGeneratedFrameCount this includes the real
// capture post on each cycle, giving the HUD a ground-truth "total fps".
uint64_t getPostedFrameCount();

// Number of capture frames whose content differs from the previous capture.
// MediaProjection delivers at the display refresh rate, which is usually
// higher than the target app's render rate — duplicates are common. This
// counter is the target app's TRUE render rate (what the HUD should show
// as "real fps"). Computed via a cheap 8×8 luma hash in pushFrame.
uint64_t getUniqueCaptureCount();

// Copies up to `cap` nanosecond-intervals between consecutive overlay posts
// into `outIntervalsNs`. Returns the number of intervals actually written.
// Newest-first order. Used by the HUD frame-pacing graph to show real
// jitter instead of rolling counts.
uint32_t getRecentPostIntervalsNs(int64_t *outIntervalsNs, uint32_t cap);

// Interval-based FPS snapshot — no polling window, no counter+delta math.
// Computed from the average spacing between the last few recorded capture/
// post timestamps (steady_clock, recorded at the moment each event happens).
// Writes [realFps, totalFps] into `out` (must be at least 2 floats) and
// returns true once at least 2 samples exist in both rings; returns false
// (out left untouched) before enough data has accumulated, e.g. right after
// init. realFps tracks capture arrivals (getUniqueCaptureCount's source),
// totalFps tracks every overlay post (getPostedFrameCount's source) —
// same ground truth as the existing counters, just measured as elapsed time
// between events instead of events-per-fixed-window, so there's no
// window-aliasing to smooth out on the caller side.
bool getFpsSnapshot(float *out, uint32_t cap);

// Snapshot of the most recently completed profiling window. `out` must be
// at least 6 longs; on success populates [copyNs, presentNs, waitIdleNs,
// blitNs, totalNs, samples] (each segment is the SUM over the window —
// divide by samples for per-frame averages) and returns 6. Returns 0 when
// no window has completed yet or `cap` < 6.
uint32_t getProfileWindowNs(int64_t *out, uint32_t cap);

// Toggle frame-gen bypass. When true, the worker skips framegen entirely and
// blits the latest captured frame straight to the output surface — useful for
// A/B comparisons against the generated output. Safe to call from any thread.
void setBypass(bool bypass);
// Live image post-processing. backend 0 = CPU pixel enhancement; backend 1 = GPU/Vulkan path.
// CPU mode applies the configured contrast/saturation immediately to each posted frame.
// GPU mode keeps the zero-copy Vulkan presentation path and intentionally avoids CPU readback.
void setImageEnhancement(bool enabled, int backend, float contrast, float saturation);

// Filter used by blitOutputToWindow() when the framegen output resolution differs
// from the swapchain/display resolution. mode: 0 = VK_FILTER_NEAREST, 1 = VK_FILTER_LINEAR
// (bilinear). Values outside that range clamp to bilinear. Safe to call from any thread.
void setUpscaleEnabled(bool enabled);
void setUpscaleFilter(int mode);

// How many REAL captures may sit queued behind the one currently being
// processed before the oldest are evicted. Clamped to [1, 8]; 1 reproduces
// the original "queue bounded to one waiting frame" behaviour.
void setMaxQueuedRealFrames(int depth);
/** Maximum capture-to-generation latency in milliseconds; 0 disables the deadline. */
void setGenerationDeadlineMs(int deadlineMs);// Automatic GPU-saving bypass. The deadline is measured from REAL capture time;
// once entered, generation stays paused for resumeDelayMs, then re-anchors on a
// fresh REAL frame so no pre-bypass frame is paired with a post-bypass frame.
void setBypassGenDeadlineMs(int deadlineMs);
void setBypassGenResumeDelayMs(int delayMs);

// Whether a VK_ERROR_DEVICE_LOST during presentContext is allowed to
// auto-disable framegen for the rest of the session. ON by default (crash
// recovery safety net); resumeFramegenAfterAutoDisable() manually clears an
// already-latched auto-disable without a full context reinit.
void setAutoDisableOnDeviceLostEnabled(bool enabled);
bool resumeFramegenAfterAutoDisable();

// Requested swapchain present mode, as a raw VkPresentModeKHR value
// (VK_PRESENT_MODE_IMMEDIATE_KHR=0, MAILBOX=1, FIFO=2). Defaults to MAILBOX.
// An unrecognized value clamps to MAILBOX. Takes effect on the next
// swapchain (re)creation, which blitOutputToWindow() triggers automatically
// when it detects the live setting no longer matches the active swapchain's
// mode — no explicit reinit needed. No fallback: if the requested mode
// isn't advertised by the surface, the render loop disables presentation
// for the rest of the session rather than silently switching modes. Safe
// to call from any thread.
void setPresentMode(int32_t mode);

double getAverageQueueMs();
double getAverageLatencyMs();

} // namespace lsfg_android
