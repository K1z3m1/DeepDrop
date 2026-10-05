#include "lsfg_render_loop.hpp"
#include "android_vk_session.hpp"
#include "android_vk_probe.hpp"
#include "ahb_image_bridge.hpp"
#include "android_shader_loader.hpp"
#ifdef LSFG_HAVE_NCNN
#include "NcnnInterpolator.hpp"
#include "IfrnetInterpolator.hpp"
#include "ncnn_cpu_policy.hpp"
#endif
#include "gpu_image_enhancement.hpp"

#include "lsfg_3_1.hpp"
#include "lsfg_3_1p.hpp"
#include "lsfg_memory.hpp"

#include <volk.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>

#include <algorithm>
#include <limits>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <cmath>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <memory>
#include <unordered_map>
#include <sys/resource.h>
#include <sys/types.h>
#include <unistd.h>

// android/thread_defs.h is a platform-private header not shipped in the NDK
// sysroot, so it never resolves in app builds. Define the constants we need
// (match AOSP's system/core/libutils/include/utils/ThreadDefs.h).
#ifndef ANDROID_PRIORITY_URGENT_DISPLAY
#define ANDROID_PRIORITY_URGENT_DISPLAY (-8)
#endif
#ifndef ANDROID_PRIORITY_URGENT_AUDIO
#define ANDROID_PRIORITY_URGENT_AUDIO (-19)
#endif

#include "crash_reporter.hpp"

#define LOG_TAG "lsfg-vk-loop"
#define LOGE(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGW(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_WARN,  __VA_ARGS__)
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO,  __VA_ARGS__)
#define LOGD(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_DEBUG, __VA_ARGS__)

namespace lsfg_android {

namespace {

struct State {
    using Clock = std::chrono::steady_clock;

    // Guards ONLY g.pendingFrames / g.stopRequested / g.pendingCv (the
    // captured-frame queue). Deliberately does NOT guard swapchain/present
    // state — see presentMu below for why that's a separate lock.
    std::mutex mu;
    // Guards g.outWindow, g.swap.*, and everything blitOutputToWindow() /
    // setOutputSurface() / createSwapchain() / destroySwapchain() touch.
    // Held internally by blitOutputToWindow() around its whole
    // acquire/blit/present sequence (see its definition) — callers don't
    // need to take it themselves. Split out from g.mu on purpose:
    // createSwapchain() can hit a vkDeviceWaitIdle() (via destroySwapchain())
    // that runs for a few ms, and that must never be on the same lock as
    // pushFrame()'s queue push, or a swapchain rebuild would stall capture
    // ingestion the same way the old inline waitIdle() stalled it.
    // Also what actually makes it safe for genWaitThread (the backgrounded
    // CPU-waitIdle-fallback job — see cpuFallbackGenInFlight below) to post a
    // generated frame while the main worker posts the next real frame:
    // Vulkan requires external synchronization for concurrent submissions/
    // presents on the same queue, and this lock is what provides it.
    std::mutex presentMu;
    // Set for the lifetime of a backgrounded CPU-waitIdle-fallback generation
    // job (see workerThread()). While true, a new framegen present must not
    // be started: framegen's output AHBs are fixed buffers reused in place,
    // so issuing presentContext() again before the previous job's waitIdle()
    // + blit has consumed those buffers would race the GPU write against our
    // read. Checked as part of generationAllowed; cleared by the background
    // job right before it exits.
    std::atomic<bool> cpuFallbackGenInFlight{false};
    bool initialized = false;
    bool performanceMode = false;
    bool framegenInitOk = false;  // tracks whether LSFG_3_1::initialize succeeded
    bool framegenFp16 = false;    // load IDs 304..351 (FP16 SPIR-V) instead of 353..400 (FP32 SPIR-V)
    bool hdr = false;
    float flowScale = 1.0f;
    int32_t framegenCtxId = -1;
    int multiplier = 2;          // generationCount

    VulkanSession vk{};

    AhbImage inSlot[2]{};        // ping-pong inputs
    uint64_t realFrameCount = 0;   // number of REAL captures used to build zero-copy pairs
    uint64_t presentsDone = 0;   // mirrors framegen's internal frameIdx

    // Vulkan swapchain state. When live, blitOutputToWindow takes the
    // GPU-only fast path: vkAcquireNextImageKHR → vkCmdBlitImage from the
    // framegen output AHB → vkQueuePresentKHR. This eliminates the CPU
    // memcpy (AHardwareBuffer_lock(CPU_READ) + ANativeWindow_lock +
    // per-row memcpy) that was the single biggest cost at multiplier ≥ 2.
    //
    // The WSI path is disabled when the extension chain or surface support is
    // unavailable. The presentation pipeline stays GPU-only; a failed WSI path
    // drops the frame rather than copying pixels through the CPU.
    struct SwapchainState {
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        VkExtent2D extent{};
        VkFormat format = VK_FORMAT_UNDEFINED;
        std::vector<VkImage> images;
        // One acquire semaphore per slot, cycled round-robin. Vulkan spec
        // forbids reusing a semaphore until the corresponding acquire has
        // completed; N+1 is the safe lower bound.
        std::vector<VkSemaphore> acquireSems;
        // One render-done semaphore per swapchain image (vkQueuePresentKHR
        // waits on the image's semaphore before presenting).
        std::vector<VkSemaphore> renderSems;
        uint32_t acquireCursor = 0;
        // Track whether each swapchain image has already been presented. After
        // the first present, the image must be transitioned from PRESENT_SRC_KHR.
        std::vector<uint8_t> imageWasPresented;
        bool outOfDate = false;
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
        // Set when an attempt to build the swapchain failed (e.g. the compute
        // queue family cannot present, or the surface rejects TRANSFER_DST
        // usage). Once set we stop retrying for the rest of the session and
        // use the CPU blit path exclusively. Cleared by destroySwapchain()
        // so a surface re-attach gets a fresh attempt.
        bool disabledForSession = false;
        bool storageUsage = false;
        // True when GPU enhancement was requested but this surface cannot be a
        // storage-image target (encoder/MediaRecorder surfaces usually cannot).
        // Stops the "rebuild with STORAGE" retry loop; frames are enhanced into
        // `stage` instead and then copied into the swapchain image.
        bool storageUnavailable = false;
        // Fit the source into the target with black bars instead of stretching it
        // over the whole image. Set for the encoder tee, whose canvas size is fixed
        // for the whole recording while the screen (source) can rotate.
        bool letterbox = false;
        // Offscreen storage image the enhancement pass renders into when the
        // target cannot take storage writes (or must be letterboxed).
        AhbImage stage{};
        // Command-ring slot of the last submission that used `stage`. Its descriptor
        // set is rewritten every frame, so the previous staged frame must have
        // finished on the GPU before the next one is recorded.
        int stageRingSlot = -1;
    } swap;

    // Secondary WSI target owned by MediaRecorder. It is intentionally separate
    // from the display swapchain so recording never replaces or blocks the real
    // presentation path. A failed recording present is ignored for display.
    ANativeWindow *recordWindow = nullptr;
    uint32_t recordWidth = 0;
    uint32_t recordHeight = 0;
    SwapchainState recordSwap{};

    // Cross-device framegen completion semaphores. Each semaphore is created
    // exportable on the host/session VkDevice, its opaque FD is imported by
    // framegen, and framegen signals it after the corresponding generated
    // frame is complete. The host queue waits on it before reading that AHB.
    // Tickets are retired non-blockingly from the blit fence; there is no
    // CPU waitIdle() in the framegen hot path.
    struct FramegenCompletionTicket {
        VkSemaphore semaphore = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        // Whether `fence` is privately owned by this ticket and must be
        // vkDestroyFence'd when reaped. False for tickets created from the
        // successful-blit path in blitOutputToSwapchain(), where `fence` is
        // borrowed from the shared command ring (g.vk.ringFences) and is
        // reused/owned there — destroying it here would be a use-after-free
        // for the ring. True for tickets from drainFramegenCompletionSemaphore(),
        // which vkCreateFence's a fence exclusively for that one ticket.
        bool ownsFence = false;
    };
    std::vector<FramegenCompletionTicket> framegenCompletionTickets;
    // NOTE: cross-device GPU->GPU completion sync (exportable semaphores) has
    // been removed. Generation completion is always awaited via the CPU-side
    // LSFG_*::waitIdle() path on genWorkerThread (see useCpuWaitIdleFallback
    // below) — that path already runs off the capture/present hot path, so it
    // costs no more than the semaphore path did, without the driver-dependent
    // failure mode where a single rejected semaphore export used to latch
    // generation off for the rest of the process.

    // ANativeWindow width/height the swapchain was built for. When the
    // overlay reshapes (rare, mostly on orientation change), we recreate.
    uint32_t swapWinW = 0;
    uint32_t swapWinH = 0;
    // AI backend (ncnn RIFE flownet) state. `aiRequested` mirrors
    // cfg.aiBackend from the most recent initRenderLoop call; `aiLoaded`
    // reflects whether NcnnInterpolator::load() actually succeeded. Kept
    // outside the #ifdef so runAi's condition below compiles identically
    // whether or not this .so was built with ncnn — it just stays false.
    bool aiRequested = false;
    bool aiLoaded = false;
    // Mirrors cfg.aiEngine from the most recent initRenderLoop call (0 =
    // RIFE/NcnnInterpolator, 1 = IFRNet/IfrnetInterpolator). Only one of
    // `ai`/`aiIfrnet` below is ever non-null at a time — runAiInterpolate()
    // branches on this to know which one. Kept outside the #ifdef for the
    // same reason as aiRequested/aiLoaded.
    int aiEngine = 0;
#ifdef LSFG_HAVE_NCNN
    NcnnInterpolator *ai = nullptr;
    IfrnetInterpolator *aiIfrnet = nullptr;
#endif
    std::atomic<bool> bypass{false}; // skip framegen, blit raw input
    // Live image enhancement. GPU/Vulkan only. The final presentation pass
    // reads each REAL/GENERATED source without modifying it, then applies
    // enhancement followed by the selected upscale into the swapchain.
    std::atomic<bool> imageEnhancementEnabled{false};
    std::atomic<int> imageEnhancementBackend{1}; // GPU/Vulkan only
    std::atomic<float> imageEnhancementContrast{1.0f};
    std::atomic<float> imageEnhancementSaturation{1.0f};
        // Filter used when the framegen output size differs from the swapchain size.
    // 0 = VK_FILTER_NEAREST, 1 = VK_FILTER_LINEAR (bilinear). Read by
    // blitOutputToWindow() on every presented frame.
    std::atomic<bool> upscaleEnabled{true};
    std::atomic<int> upscaleFilterMode{1};
    // Requested swapchain present mode, stored as the raw VkPresentModeKHR
    // value (IMMEDIATE=0, MAILBOX=1, FIFO=2). Defaults to MAILBOX to match
    // the previous hard-coded behaviour. Read by createSwapchain() (to pick
    // the mode at (re)creation time) and by blitOutputToWindow() (to detect
    // a live change and force a swapchain recreate). Written by
    // setPresentMode() from any thread.
    std::atomic<int> presentModeRequested{1 /* VK_PRESENT_MODE_MAILBOX_KHR */};
    std::unique_ptr<GpuImageEnhancement> gpuImageEnhancement;
    // Independently tunable frame-scheduling knobs (replace the old two-value
    // NO_WAIT/WAIT_GENERATION mode enum so behavior can be dialed in without
    // adding a new mode + new code branch for every combination).
    std::atomic<bool> waitForBusyGeneration{false};
    std::atomic<bool> allowGenerationWhenBusy{false};
    std::atomic<bool> losslessQueue{false};
    // Auto-bypass triggered when framegen returns VK_ERROR_DEVICE_LOST during
    // presentContext. Distinct from the user-controlled `bypass` so the user
    // toggle isn't silently flipped by a recoverable driver event. Cleared on
    // every initRenderLoop / context recreation — if the next session
    // succeeds, framegen runs again. Stuck-on means the next presentContext
    // would error too, so passthrough is the right behaviour anyway.
    std::atomic<bool> framegenAutoDisabled{false};
    std::atomic<uint64_t> cacheHits{0};
    std::atomic<uint64_t> cacheMisses{0};
    std::vector<AhbImage> outputs; // multiplier-many outputs
    // Stable metadata for each generated output. The image buffers are reused,
    // so the ID must live separately from the AHB itself. outputFrameIds[i]
    // identifies the i-th generated frame between the REAL pair owned by
    // generationSourceFrameId. This lets the presentation path verify that
    // generated frames are consumed strictly in order for x2..x8.
    std::vector<uint64_t> outputFrameIds;
    std::vector<uint32_t> outputFrameOrdinals;
    uint64_t generationSourceFrameId = 0;
    uint64_t lastPresentedGeneratedFrameId = 0;
    uint64_t nextGeneratedFrameId = 1;
    // Reusable AI host staging buffers. These are allocated once per resolution
    // instead of creating/destroying several large vectors every frame. The
    // actual model math remains Vulkan-only; this buffer is only the legacy
    // RGBA8 bridge required by the bundled ncnn build.
    std::vector<uint8_t> aiInputAStaging;
    std::vector<uint8_t> aiInputCStaging;
    std::vector<std::vector<uint8_t>> aiOutputStaging;
    std::vector<uint8_t*> aiOutputPtrs;

    // AHB → VkImage import cache. MediaProjection rotates a small pool (2-4)
    // of AHBs; re-importing on every frame wastes vkCreateImage +
    // vkAllocateMemory. The cache holds its own AHardwareBuffer_acquire ref
    // so entries outlive the per-frame release. Evicted FIFO beyond kAhbCacheMax.
    // Capped at 4 (not 8): MediaProjection rotates a pool of 2-4 buffers, so a
    // cache of 8 pins up to 4 stale/duplicate AHBs' worth of GPU-shared memory
    // (each ~10 MB at 1080x2408 RGBA8) with zero cache-hit benefit — pure waste
    // that pushes the process toward the low-memory killer on constrained
    // devices when a second heavy app (e.g. video playback) shares memory.
    // Must be fully cleared (destroyAhbImage + AHardwareBuffer_release for each
    // entry) before vkDestroyDevice in the cleanup path.
    static constexpr size_t kAhbCacheMax = 4;
    std::unordered_map<AHardwareBuffer*, AhbImage> ahbImportCache;
    // In ZERO_COPY mode these are the cached capture AHBs forming the current
    // interpolation pair. The import cache owns their lifetime.
    AHardwareBuffer* zeroCopyCurrentAhb = nullptr;
    AHardwareBuffer* zeroCopyPreviousAhb = nullptr;

    // Output surface for the final blit. Owned (acquired from JNI).
    ANativeWindow *outWindow = nullptr;
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
    // Once we have produced a CPU buffer on this ANativeWindow (via
    // ANativeWindow_lock / setBuffersGeometry), the BufferQueue is bound to a
    // CPU producer. Mali (Bifrost/Valhall) and many other drivers will then
    // return VK_ERROR_NATIVE_WINDOW_IN_USE_KHR (-1000000001) for any subsequent
    // vkCreateAndroidSurfaceKHR on the same native window — the producer slot
    // can't be retargeted live. We track this taint per-attached window and
    // skip the WSI swapchain attempt entirely, avoiding the spammy retries seen
    // on Mali-G57 and the cascade where a failed surface creation correlates
    // with a DEVICE_LOST on framegen's compute queue. Reset on each
    // setOutputSurface(win) call, since a freshly-acquired ANativeWindow has
    // no producer yet.
    bool windowCpuProducerLocked = false;
    // Feedback-loop gate: suppresses blits when framegen output luma is very
    // dark, indicating setSkipScreenshot failed and MediaProjection is capturing
    // the overlay instead of the game.  Keeping the overlay transparent lets the
    // next capture see the real game and breaks the loop.
    bool     lumaGateOpen      = false;
    uint32_t lumaGateDarkCount = 0;
    int64_t  lumaGateStartNs   = 0; // steady_clock ns at first suppressed dark frame

    // Ordered capture queue. losslessQueue=true retains every capture until
    // its turn is processed; losslessQueue=false uses the configurable bounded
    // queue policy. The worker owns the frame it has popped.
    struct PendingFrame {
        AHardwareBuffer *ahb = nullptr;
        Clock::time_point queuedAt{};
        int64_t captureTimestampNs = 0;
        uint64_t frameId = 0;
    };
    std::deque<PendingFrame> pendingFrames;

    // Unified presentation queue. REAL and GENERATED frames enter the same
    // queue only after capture/generation is complete. Ordering is by the
    // capture/source frameId first, then by ordinal inside that source pair:
    // generated ordinals are 1..N and the REAL right-hand boundary uses the
    // maximum uint32 value. This keeps the ordering independent of the
    // currently selected generation multiplier/filter/backend.
    struct FinalFrame {
        AhbImage image{};
        uint64_t sourceFrameId = 0;
        uint32_t ordinal = 0;
        bool generated = false;
        uint64_t generatedFrameId = 0;
        VkSemaphore completionSemaphore = VK_NULL_HANDLE;
        int64_t captureTimestampNs = 0;
    };
    std::deque<FinalFrame> finalFrames;

    // Monotonically increasing capture epoch. Framegen jobs are invalidated when newer real frames arrive.
    std::atomic<uint64_t> latestFrameId{0};
    // Forces the next processed REAL frame to become a fresh pairing anchor.
    // Set on manual bypass so no frame from before the discontinuity can
    // ever be used as an interpolation predecessor after FrameGen resumes.
    std::atomic<bool> pairingResetPending{false};
    // Submitted FrameGen may still be reading both input images. Never overwrite
    // either slot until its completion ticket (or CPU fallback wait) retires.
    std::atomic<bool> framegenInputsInFlight{false};
    std::condition_variable pendingCv; // signaled when a frame is queued (consumer wakes)
    bool stopRequested = false;

    std::thread worker;
    // Persistent thread that runs CPU-waitIdle-fallback generation jobs
    // (see genWorkerThread()). Spawned once in initRenderLoop() alongside
    // `worker`, parked on genCv the rest of the time — NOT spawned per job,
    // to avoid paying Android thread-creation cost on every fallback frame.
    // shutdownRenderLoop() signals genStopRequested and joins this before
    // tearing down Vulkan/framegen state.
    std::thread genWaitThread;
    std::mutex genMu;
    std::condition_variable genCv;
    bool genJobPending = false;
    // If false, the fallback worker only drains waitIdle; its generated
    // outputs are discarded so REAL never waits behind the fallback.
    bool genJobPublish = true;
    bool genStopRequested = false;
    // Job parameters, written by workerThread() under genMu before setting
    // genJobPending, read by genWorkerThread() after waking.
    bool genJobPerfMode = false;
    // Epoch of the REAL frame that owns the background generation job.
    // Any newer capture or bypass transition invalidates the job output.
    uint64_t genJobFrameId = 0;
    // Absolute steady-clock deadline for the generated result. 0 disables the deadline.
    uint64_t genJobDeadlineNs = 0;
    // Completion notification for CPU-waitIdle fallback. Generated frames for
    // a pair must be published before that pair's current REAL frame.
    std::condition_variable genDoneCv;
    uint64_t genCompletedFrameId = 0;
    std::atomic<uint64_t> genReadyFrameId{0};
    std::atomic<bool> genReady{false};
    std::atomic<uint64_t> generatedFrames{0};
    // Counts every successful post (WSI present) to the overlay.
    // This is the ground-truth "frames on screen" metric — includes real
    // captures AND LSFG-generated frames. Used by the HUD total-fps counter
    // instead of the old `capturedFps + genFps` which double-counted.
    std::atomic<uint64_t> postedFrames{0};
    // Counts every capture frame that arrives (regardless of content).
    // Used as the HUD's "real fps" / unique-capture metric. Previously this
    // used a per-pixel luma hash to detect duplicate frames from MediaProjection,
    // but the CPU cost of reading back AHB pixels was not worth the metric
    // accuracy gain. We now count every arriving frame as unique — the number
    // is the true capture rate from the OS capture path, which closely tracks
    // the target app's render rate in practice.
    std::atomic<uint64_t> uniqueCaptures{0};

    // Ring buffer of recent post timestamps (ns from CLOCK_MONOTONIC, via
    // steady_clock). Consumed by the HUD frame-pacing graph to show real
    // frame-to-frame intervals instead of rolling counts.
    static constexpr size_t kPostRingSize = 128;
    std::atomic<uint64_t> postRingTimestamps[kPostRingSize]{};
    std::atomic<uint64_t> postRingHead{0};

    // Same idea as postRingTimestamps, but for capture arrivals (pushFrame).
    // Lets getFpsSnapshot derive "real fps" from actual elapsed time between
    // captures instead of a counter+delta over a fixed polling window.
    std::atomic<uint64_t> captureRingTimestamps[kPostRingSize]{};
    std::atomic<uint64_t> captureRingHead{0};

    std::atomic<uint32_t> pushLogCount{0};
    std::atomic<uint32_t> blitLogCount{0};

    // Snapshot of the last completed kProfileWindow rolling window. Updated
    // atomically by the worker thread when a window closes (see workerThread,
    // ProfileAccum). Layout: [copyNs, presentNs, waitIdleNs, blitNs, totalNs,
    // samples]. Each value is the SUM over the window — divide by samples to
    // get the per-frame average. samples == 0 means no window has completed
    // since session start.
    std::atomic<int64_t> profileSnapshotCopyNs{0};
    std::atomic<int64_t> profileSnapshotPresentNs{0};
    std::atomic<int64_t> profileSnapshotWaitIdleNs{0};
    std::atomic<int64_t> profileSnapshotBlitNs{0};
    std::atomic<int64_t> profileSnapshotTotalNs{0};
    std::atomic<int64_t> profileSnapshotQueueNs{0};
    std::atomic<int64_t> profileSnapshotLatencyNs{0};
    std::atomic<int64_t> profileSnapshotBlitCount{0};
    std::atomic<int64_t> profileSnapshotSamples{0};

    std::atomic<int64_t> lastProcessedCaptureTimestampNs{0};
    // Bounds how many REAL captures may sit queued behind the one currently
    // being processed before pushFrame() evicts the oldest ones. 1 reproduces
    // the original "queue bounded to one waiting frame" behaviour. User-tunable
    // via setMaxQueuedRealFrames(); clamped to [1, 8].
    std::atomic<int> maxQueuedRealFrames{1};
    // 0 = disabled; otherwise generated output must complete within this many ms of capture.
    std::atomic<int> generationDeadlineMs{0};
    // BypassGen is deliberately separate from the manual `bypass` switch.
    // It is a temporary GPU-saving state entered when a REAL frame is already
    // too old to justify starting another generation job.
    std::atomic<int> bypassGenDeadlineMs{0};
    std::atomic<int> bypassGenResumeDelayMs{50};
    std::atomic<bool> bypassGenActive{false};
    std::atomic<uint64_t> bypassGenUntilNs{0};
    // Whether a VK_ERROR_DEVICE_LOST during presentContext is allowed to
    // auto-disable framegen for the rest of the session (passthrough until
    // the next context reinit). ON by default — this is a crash-recovery
    // safety net, not a load-balancing policy, but user-tunable via
    // setAutoDisableOnDeviceLostEnabled() for anyone who'd rather keep
    // retrying framegen after a device-lost event.
    std::atomic<bool> autoDisableOnDeviceLostEnabled{true};

};

State g{};

// Apply pacing tunables to `g` with sane clamps. Zero/negative values
// fall back to defaults so partial updates from JNI can't accidentally
// disable the pacer.
void handleFramegenException(const char *callSite, const std::exception &e) {
    const char *what = e.what() != nullptr ? e.what() : "(null)";
    LOGE("%s threw: %s", callSite, what);
    const bool isDeviceLost = std::strstr(what, "error -4") != nullptr ||
                              std::strstr(what, "DEVICE_LOST")  != nullptr;
    if (!isDeviceLost) return;
    // User-tunable via setAutoDisableOnDeviceLostEnabled(); ON by default
    // (crash-recovery safety net, not a load-balancing policy). When
    // disabled, framegen is allowed to keep retrying on subsequent frames
    // instead of latching into passthrough for the rest of the session.
    if (!g.autoDisableOnDeviceLostEnabled.load(std::memory_order_relaxed)) {
        LOGE("%s: VK_ERROR_DEVICE_LOST — auto-disable is turned off by user setting, framegen will keep retrying",
             callSite);
        return;
    }
    if (!g.framegenAutoDisabled.load(std::memory_order_relaxed)) {
        LOGE("%s: VK_ERROR_DEVICE_LOST — auto-disabling framegen for this session (passthrough until next context reinit)",
             callSite);
        g.framegenAutoDisabled.store(true, std::memory_order_relaxed);
    }
}

// Presentation is intentionally uncapped at the application level. Vulkan WSI
// present mode is the only presentation pacing mechanism.
// Record a successful post (WSI present) for HUD metrics.
// Increments postedFrames and pushes the current steady-clock timestamp into
// the ring buffer so the pacing graph can compute real inter-frame intervals.
void recordOverlayPost() {
    g.postedFrames.fetch_add(1, std::memory_order_relaxed);
    const uint64_t nowNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            State::Clock::now().time_since_epoch()).count());
    const uint64_t slot = g.postRingHead.fetch_add(1, std::memory_order_relaxed)
                          % State::kPostRingSize;
    g.postRingTimestamps[slot].store(nowNs, std::memory_order_relaxed);
}

// ---- Shared Vulkan one-shot helpers -------------------------------------------
//
// Every AHB-side image transition in this file (copy, GPU blit, swapchain
// blit, initial layout transition) builds VkImageMemoryBarrier literals that
// differ only in image/access/layout/family — factored here so each call site
// is one line instead of a ~9-line struct.
VkImageMemoryBarrier makeImageBarrier(VkImage image, VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                                       VkImageLayout oldLayout, VkImageLayout newLayout,
                                       uint32_t srcFamily, uint32_t dstFamily) {
    return VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = srcAccess,
        .dstAccessMask = dstAccess,
        .oldLayout = oldLayout,
        .newLayout = newLayout,
        .srcQueueFamilyIndex = srcFamily,
        .dstQueueFamilyIndex = dstFamily,
        .image = image,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
}

// Allocate a one-shot primary command buffer from the session pool, run
// `record` to fill it, submit on the compute queue and block until the GPU
// has finished. Uses a per-submission VkFence (not vkQueueWaitIdle) so we
// only stall on this submission, not the whole queue. Frees the fence and
// command buffer before returning either way.
//
// Used by every transient (allocate → record → submit → wait → free) Vulkan
// op in this file: initial layout transitions, the CPU-side AHB copy, and
// the linear-blit fallback. The session's persistent command ring
// (acquireCommandRing) is a separate, non-transient path used by the
// steady-state swapchain blit.
bool runTransientCommands(const std::function<void(VkCommandBuffer)> &record) {
    const VkCommandBufferAllocateInfo cbai{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = g.vk.commandPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkCommandBuffer cb = VK_NULL_HANDLE;
    if (g.vk.fn.vkAllocateCommandBuffers(g.vk.device, &cbai, &cb) != VK_SUCCESS) return false;

    const VkCommandBufferBeginInfo bi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    g.vk.fn.vkBeginCommandBuffer(cb, &bi);
    record(cb);
    g.vk.fn.vkEndCommandBuffer(cb);

    const VkFenceCreateInfo fci{ .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VkFence fence = VK_NULL_HANDLE;
    g.vk.fn.vkCreateFence(g.vk.device, &fci, nullptr, &fence);

    const VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &cb,
    };
    const bool submitted = g.vk.fn.vkQueueSubmit(g.vk.computeQueue, 1, &si, fence) == VK_SUCCESS;
    if (submitted) g.vk.fn.vkWaitForFences(g.vk.device, 1, &fence, VK_TRUE, UINT64_MAX);
    if (fence != VK_NULL_HANDLE) g.vk.fn.vkDestroyFence(g.vk.device, fence, nullptr);
    g.vk.fn.vkFreeCommandBuffers(g.vk.device, g.vk.commandPool, 1, &cb);
    return submitted;
}

// ---- Vulkan swapchain helpers ------------------------------------------------
//
// The swapchain lives on top of the overlay's ANativeWindow and provides the
// GPU-only output path: generated frames are vkCmdBlitImage'd from their AHB
// storage directly into the swapchain image and presented via vkQueuePresentKHR.
// No CPU touch of the pixel data.
//
// DEFAULT OFF. An earlier run on Adreno 840 / Android 14 crashed in a driver
// call during createSwapchain before any of the per-step LOGIs could fire —
// the Adreno compute queue reports `vkGetPhysicalDeviceSurfaceSupportKHR =
// VK_TRUE` but then crashes inside `vkQueuePresentKHR` (known quirk on some
// Qualcomm revisions). Keeping the code path in-tree with aggressive logging
// so future device revisions / validation work can flip this flag without
// another refactor. To enable for testing, set `kEnableWsiSwapchain` to true.
constexpr bool kEnableWsiSwapchain = true;

void destroySwapchain() {
    // Must drain every in-flight ring submission before touching the images
    // — the swapchain images are referenced by recorded CBs via barriers and
    // destroying them while those CBs are still executing = SIGSEGV on some
    // drivers (observed on Qualcomm). vkDeviceWaitIdle is the sledgehammer.
    if (g.vk.device != VK_NULL_HANDLE && g.vk.fn.vkDeviceWaitIdle != nullptr) {
        g.vk.fn.vkDeviceWaitIdle(g.vk.device);
    }
    if (g.gpuImageEnhancement) {
        g.gpuImageEnhancement->clearDestinationBindings(g.vk);
    }
    if (g.swap.stage.image != VK_NULL_HANDLE || g.swap.stage.memory != VK_NULL_HANDLE ||
            g.swap.stage.ahb != nullptr) {
        destroyAhbImage(g.vk, g.swap.stage);
    }
    g.swap.stage = AhbImage{};
    g.swap.stageRingSlot = -1;
    g.swap.storageUnavailable = false;

    if (g.vk.fn.vkDestroySemaphore != nullptr) {
        for (VkSemaphore s : g.swap.acquireSems) {
            if (s != VK_NULL_HANDLE) g.vk.fn.vkDestroySemaphore(g.vk.device, s, nullptr);
        }
        for (VkSemaphore s : g.swap.renderSems) {
            if (s != VK_NULL_HANDLE) g.vk.fn.vkDestroySemaphore(g.vk.device, s, nullptr);
        }
    }
    g.swap.acquireSems.clear();
    g.swap.renderSems.clear();
    g.swap.images.clear();
    g.swap.imageWasPresented.clear();
    g.swap.acquireCursor = 0;
    g.swap.outOfDate = false;
    g.swap.disabledForSession = false;
    g.swap.storageUsage = false;

    if (g.swap.swapchain != VK_NULL_HANDLE && g.vk.fn.vkDestroySwapchainKHR != nullptr) {
        g.vk.fn.vkDestroySwapchainKHR(g.vk.device, g.swap.swapchain, nullptr);
        g.swap.swapchain = VK_NULL_HANDLE;
    }
    if (g.swap.surface != VK_NULL_HANDLE && g.vk.instance != VK_NULL_HANDLE) {
        // Surface destruction uses the instance-level function. volk populates
        // it globally after volkLoadInstance.
        if (g.vk.pfnDestroySurfaceKHR != nullptr) {
            g.vk.pfnDestroySurfaceKHR(g.vk.instance, g.swap.surface, nullptr);
        } else {
            vkDestroySurfaceKHR(g.vk.instance, g.swap.surface, nullptr);
        }
        g.swap.surface = VK_NULL_HANDLE;
    }
    g.swap.extent = {0, 0};
    g.swap.format = VK_FORMAT_UNDEFINED;
}

// Build (or rebuild) the swapchain on the current outWindow, using the mode
// requested via setPresentMode(). No fallback: if the surface doesn't
// advertise that mode, presentation is disabled for the session.
//
// Safe to call multiple times; previous swapchain is torn down first.
bool createSwapchain() {
    // GPU-only presentation. A failure disables WSI for this session; there is
    // deliberately no CPU pixel fallback in the hot path.
    LOGI("createSwapchain: enter outWindow=%p hasSwapchain=%d enable=%d cpuTainted=%d",
         static_cast<void *>(g.outWindow), (int)g.vk.hasSwapchain,
         (int)kEnableWsiSwapchain, (int)g.windowCpuProducerLocked);
    destroySwapchain();
    if (!kEnableWsiSwapchain) return false;
    if (!g.vk.hasSwapchain) return false;
    if (g.outWindow == nullptr) return false;
    if (g.vk.instance == VK_NULL_HANDLE) return false;
    // Once the worker has produced any CPU buffer on this ANativeWindow
    // (any CPU producer, if present, etc.) the BufferQueue is locked to
    // a CPU producer and vkCreateAndroidSurfaceKHR will return
    // VK_ERROR_NATIVE_WINDOW_IN_USE_KHR (-1000000001) on Mali / many other
    // drivers. Don't even attempt — failed surface creation has been observed
    // to correlate with framegen-side DEVICE_LOST on Mali-G57 (likely an
    // instance-level state corruption when the WSI driver path bails late).
    if (g.windowCpuProducerLocked) {
        LOGI("createSwapchain: skipping — window already bound to CPU producer this session");
        return false;
    }
    // Use the session-cached function pointer instead of volk's global. The
    // global gets clobbered to NULL when framegen's Instance::Instance()
    // calls volkLoadInstance() against its own surface-less VkInstance,
    // because that instance can't resolve vkCreateAndroidSurfaceKHR. The
    // session pointer was resolved against OUR instance at session-init time
    // and survives that overwrite.
    PFN_vkCreateAndroidSurfaceKHR pfnCreateSurface = g.vk.pfnCreateAndroidSurfaceKHR;
    if (pfnCreateSurface == nullptr) pfnCreateSurface = vkCreateAndroidSurfaceKHR;
    if (pfnCreateSurface == nullptr) {
        LOGW("vkCreateAndroidSurfaceKHR fn ptr is NULL — volk didn't load it");
        return false;
    }

    LOGI("createSwapchain: calling vkCreateAndroidSurfaceKHR");
    const VkAndroidSurfaceCreateInfoKHR sci{
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = g.outWindow,
    };
    const VkResult sres = pfnCreateSurface(g.vk.instance, &sci, nullptr, &g.swap.surface);
    if (sres != VK_SUCCESS) {
        // VK_ERROR_NATIVE_WINDOW_IN_USE_KHR means the ANativeWindow is locked
        // to a CPU producer (or another consumer). The state is sticky — no
        // amount of retrying on the same window will recover. Pin the taint
        // so subsequent createSwapchain calls bail at the early gate above
        // instead of repeating the same failed driver call (which on some
        // Mali / Adreno revisions can leave the instance in a tainted state
        // and induce a DEVICE_LOST on framegen's separate device).
        if (sres == VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) {
            g.windowCpuProducerLocked = true;
            LOGW("vkCreateAndroidSurfaceKHR: window in use by CPU producer (rc=%d) — WSI disabled for this surface",
                 (int)sres);
        } else {
            LOGW("vkCreateAndroidSurfaceKHR failed (rc=%d) — WSI disabled", (int)sres);
        }
        g.swap.surface = VK_NULL_HANDLE;
        return false;
    }
    LOGI("createSwapchain: surface=%p", static_cast<void *>(g.swap.surface));

    // Can our compute queue actually present on this surface?
    VkBool32 canPresent = VK_FALSE;
    const VkResult suppr = g.vk.pfnGetPhysicalDeviceSurfaceSupportKHR(g.vk.physicalDevice,
            g.vk.computeFamilyIdx, g.swap.surface, &canPresent);
    LOGI("createSwapchain: surfaceSupport rc=%d canPresent=%d", (int)suppr, (int)canPresent);
    if (suppr != VK_SUCCESS || canPresent != VK_TRUE) {
        LOGE("compute queue family %u cannot present on this surface — MAILBOX presentation unavailable",
             g.vk.computeFamilyIdx);
        destroySwapchain();
        return false;
    }

    VkSurfaceCapabilitiesKHR caps{};
    if (g.vk.pfnGetPhysicalDeviceSurfaceCapabilitiesKHR(g.vk.physicalDevice,
            g.swap.surface, &caps) != VK_SUCCESS) {
        LOGW("vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed");
        destroySwapchain();
        return false;
    }

    // Keep the presentation path on one simple, low-latency format: RGBA8
    // sRGB. HDR is still available through the separate toggle later, but the
    // default runtime should stay on the narrowest possible path so the swapchain
    // can stay fully GPU-native and avoid any extra format conversion work.
    uint32_t fmtCount = 0;
    g.vk.pfnGetPhysicalDeviceSurfaceFormatsKHR(g.vk.physicalDevice, g.swap.surface,
                                         &fmtCount, nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtCount);
    if (fmtCount > 0) {
        g.vk.pfnGetPhysicalDeviceSurfaceFormatsKHR(g.vk.physicalDevice, g.swap.surface,
                                             &fmtCount, fmts.data());
    }
    VkSurfaceFormatKHR chosen{VK_FORMAT_R8G8B8A8_UNORM, VK_COLORSPACE_SRGB_NONLINEAR_KHR};
    for (const auto &f : fmts) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM &&
            f.colorSpace == VK_COLORSPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
            break;
        }
    }
    if (fmts.empty()) {
        chosen = {VK_FORMAT_R8G8B8A8_UNORM, VK_COLORSPACE_SRGB_NONLINEAR_KHR};
    }

    // Presentation policy is user-selectable via setPresentMode() (default
    // MAILBOX, matching the previous hard-coded behaviour). No fallback: if
    // the requested mode isn't advertised by this surface, presentation is
    // disabled for the session rather than silently switching modes.
    const VkPresentModeKHR presentMode =
        static_cast<VkPresentModeKHR>(g.presentModeRequested.load(std::memory_order_relaxed));

    uint32_t presentModeCount = 0;
    if (g.vk.pfnGetPhysicalDeviceSurfacePresentModesKHR != nullptr &&
        g.vk.pfnGetPhysicalDeviceSurfacePresentModesKHR(
            g.vk.physicalDevice, g.swap.surface, &presentModeCount, nullptr) == VK_SUCCESS &&
        presentModeCount > 0) {
        std::vector<VkPresentModeKHR> modes(presentModeCount);
        if (g.vk.pfnGetPhysicalDeviceSurfacePresentModesKHR(
                g.vk.physicalDevice, g.swap.surface, &presentModeCount, modes.data()) == VK_SUCCESS) {
            bool selectedAvailable = false;
            for (const auto mode : modes) {
                if (mode == presentMode) {
                    selectedAvailable = true;
                    break;
                }
            }
            if (!selectedAvailable) {
                LOGE("requested present mode %d is unsupported by surface — presentation disabled",
                     static_cast<int>(presentMode));
                destroySwapchain();
                return false;
            }
        } else {
            LOGW("failed to enumerate present modes — WSI disabled");
            destroySwapchain();
            return false;
        }
    } else {
        LOGW("present-mode query unavailable — WSI disabled");
        destroySwapchain();
        return false;
    }
    g.swap.presentMode = presentMode;

    // MAILBOX and FIFO both benefit from an extra swapchain image beyond the
    // surface minimum, so the compositor/producer has one to work with a step
    // ahead while SurfaceFlinger holds the currently-displayed image.
    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }
    if (imageCount < 2) imageCount = 2;

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == 0 || extent.width == UINT32_MAX) {
        extent.width = g.outWidth > 0 ? g.outWidth : 1920;
    }
    if (extent.height == 0 || extent.height == UINT32_MAX) {
        extent.height = g.outHeight > 0 ? g.outHeight : 1080;
    }

    // TRANSFER_DST is mandatory for the GPU-native swapchain copy.
    // Some drivers require explicit opt-in via capabilities.supportedUsageFlags.
    if ((caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
        LOGW("swapchain surface does not advertise TRANSFER_DST usage — WSI disabled");
        destroySwapchain();
        return false;
    }

    // The GPU enhancement path can render directly into the acquired swapchain
    // image. Request STORAGE only when the surface advertises it; otherwise the
    // normal transfer path remains available.
    const bool storageUsage = g.imageEnhancementEnabled.load(std::memory_order_relaxed) &&
        g.imageEnhancementBackend.load(std::memory_order_relaxed) == 1 &&
        (caps.supportedUsageFlags & VK_IMAGE_USAGE_STORAGE_BIT) != 0;
    VkImageUsageFlags swapUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (storageUsage) swapUsage |= VK_IMAGE_USAGE_STORAGE_BIT;
    g.swap.storageUnavailable =
        g.imageEnhancementEnabled.load(std::memory_order_relaxed) &&
        g.imageEnhancementBackend.load(std::memory_order_relaxed) == 1 &&
        (caps.supportedUsageFlags & VK_IMAGE_USAGE_STORAGE_BIT) == 0;

    const VkSwapchainCreateInfoKHR sci2{
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = g.swap.surface,
        .minImageCount = imageCount,
        .imageFormat = chosen.format,
        .imageColorSpace = chosen.colorSpace,
        .imageExtent = extent,
        .imageArrayLayers = 1,
        .imageUsage = swapUsage,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = caps.currentTransform,
        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = presentMode,
        .clipped = VK_TRUE,
    };
    g.swap.storageUsage = storageUsage;
    LOGI("createSwapchain: vkCreateSwapchainKHR fmt=%d extent=%ux%u imageCount=%u storage=%d",
         (int)chosen.format, extent.width, extent.height, imageCount, storageUsage ? 1 : 0);
    if (g.vk.fn.vkCreateSwapchainKHR == nullptr) {
        LOGW("vkCreateSwapchainKHR fn ptr is NULL");
        destroySwapchain();
        return false;
    }
    if (g.vk.fn.vkCreateSwapchainKHR(g.vk.device, &sci2, nullptr,
            &g.swap.swapchain) != VK_SUCCESS) {
        LOGW("vkCreateSwapchainKHR failed");
        destroySwapchain();
        return false;
    }
    LOGI("createSwapchain: swapchain=%p", static_cast<void *>(g.swap.swapchain));

    uint32_t realCount = 0;
    g.vk.fn.vkGetSwapchainImagesKHR(g.vk.device, g.swap.swapchain, &realCount, nullptr);
    g.swap.images.resize(realCount);
    g.swap.imageWasPresented.assign(realCount, 0);
    g.vk.fn.vkGetSwapchainImagesKHR(g.vk.device, g.swap.swapchain, &realCount,
                                    g.swap.images.data());

    // Semaphore pools:
    //   acquireSems: one per "in-flight frame" slot (we use realCount + 1)
    //   renderSems:  one per swapchain image (vkQueuePresent waits on this
    //                semaphore for the specific image we rendered to)
    g.swap.acquireSems.resize(realCount + 1, VK_NULL_HANDLE);
    g.swap.renderSems.resize(realCount, VK_NULL_HANDLE);
    const VkSemaphoreCreateInfo semInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
    };
    for (auto &s : g.swap.acquireSems) {
        if (g.vk.fn.vkCreateSemaphore(g.vk.device, &semInfo, nullptr, &s) != VK_SUCCESS) {
            LOGW("vkCreateSemaphore(acquire) failed");
            destroySwapchain();
            return false;
        }
    }
    for (auto &s : g.swap.renderSems) {
        if (g.vk.fn.vkCreateSemaphore(g.vk.device, &semInfo, nullptr, &s) != VK_SUCCESS) {
            LOGW("vkCreateSemaphore(render) failed");
            destroySwapchain();
            return false;
        }
    }

    g.swap.extent = extent;
    g.swap.format = chosen.format;
    g.swap.acquireCursor = 0;
    g.swap.outOfDate = false;
    LOGI("Swapchain ready: %ux%u fmt=%d images=%u mode=%d",
         extent.width, extent.height, (int)chosen.format, realCount, (int)presentMode);
    return true;
}

// Reap cross-device framegen completion semaphores without blocking.
void reapFramegenCompletionTickets() {
    if (g.vk.device == VK_NULL_HANDLE || g.vk.fn.vkGetFenceStatus == nullptr ||
            g.vk.fn.vkDestroySemaphore == nullptr) return;
    size_t write = 0;
    for (size_t i = 0; i < g.framegenCompletionTickets.size(); ++i) {
        auto &t = g.framegenCompletionTickets[i];
        const bool done = (t.fence == VK_NULL_HANDLE) ||
            (g.vk.fn.vkGetFenceStatus(g.vk.device, t.fence) == VK_SUCCESS);
        if (done) {
            if (t.semaphore != VK_NULL_HANDLE)
                g.vk.fn.vkDestroySemaphore(g.vk.device, t.semaphore, nullptr);
            // Only destroy the fence if this ticket privately owns it
            // (from drainFramegenCompletionSemaphore's vkCreateFence). A
            // ticket from the successful-blit path borrows a shared command-
            // ring fence that the ring itself still owns and reuses —
            // destroying it here would be a use-after-free the next time the
            // ring cycles back to that slot.
            if (t.ownsFence && t.fence != VK_NULL_HANDLE && g.vk.fn.vkDestroyFence != nullptr)
                g.vk.fn.vkDestroyFence(g.vk.device, t.fence, nullptr);
        } else {
            g.framegenCompletionTickets[write++] = t;
        }
    }
    g.framegenCompletionTickets.resize(write);
    if (g.framegenCompletionTickets.empty() &&
        !g.cpuFallbackGenInFlight.load(std::memory_order_acquire)) {
        g.framegenInputsInFlight.store(false, std::memory_order_release);
    }
}

void destroyFramegenCompletionTickets() {
    if (g.vk.device == VK_NULL_HANDLE || g.vk.fn.vkDestroySemaphore == nullptr) {
        g.framegenCompletionTickets.clear();
        return;
    }
    for (auto &t : g.framegenCompletionTickets) {
        if (t.semaphore != VK_NULL_HANDLE)
            g.vk.fn.vkDestroySemaphore(g.vk.device, t.semaphore, nullptr);
        if (t.ownsFence && t.fence != VK_NULL_HANDLE && g.vk.fn.vkDestroyFence != nullptr)
            g.vk.fn.vkDestroyFence(g.vk.device, t.fence, nullptr);
    }
    g.framegenCompletionTickets.clear();
}

// Recent average interval between successful WSI posts, in ns. recordOverlayPost()
// runs for both REAL and GENERATED frames (see blitOutputToWindow), so this is the
// actual combined output cadence already being achieved on this device right now —
// e.g. multiplier=2 sustaining real+gen posts averages toward the "120fps" interval
// without us having to assume a display refresh rate or trust the generation
// multiplier as a promise of what's actually landing on screen.
// Returns 0 when there isn't enough history yet (cold start) or posting has
// stalled (torn/non-monotonic ring read) — callers must have a fallback for that.
uint64_t recentPostIntervalNs() {
    constexpr uint64_t kSampleCount = 16;
    const uint64_t head = g.postRingHead.load(std::memory_order_acquire);
    if (head < 2) return 0;
    const uint64_t validEntries = std::min<uint64_t>(head, State::kPostRingSize);
    const uint64_t span = std::min<uint64_t>(kSampleCount, validEntries - 1);
    if (span == 0) return 0;
    const uint64_t newestTs = g.postRingTimestamps[(head - 1) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    const uint64_t oldestTs = g.postRingTimestamps[(head - 1 - span) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    if (newestTs == 0 || oldestTs == 0 || oldestTs >= newestTs) return 0;
    return (newestTs - oldestTs) / span;
}

// Blit `src` AHB-backed VkImage to the next swapchain image and present.
// Returns true on success. On VK_ERROR_OUT_OF_DATE_KHR or _SUBOPTIMAL_KHR the
// swapchain is marked dirty; the caller's next blit will recreate it.
bool blitOutputToSwapchain(const AhbImage &src, VkSemaphore framegenDoneSemaphore = VK_NULL_HANDLE) {
    // Note: Called with g.presentMu held from blitOutputToWindow.
    if (g.swap.swapchain == VK_NULL_HANDLE) return false;
    if (src.image == VK_NULL_HANDLE) return false;
    reapFramegenCompletionTickets();
    if (g.swap.outOfDate) {
        if (!createSwapchain()) return false;
    }

    // If GPU enhancement is enabled after the swapchain was created, rebuild
    // once so the selected post-process can legally write storage images.
    const bool enhancementRequested =
        g.imageEnhancementEnabled.load(std::memory_order_relaxed) &&
        g.imageEnhancementBackend.load(std::memory_order_relaxed) == 1;
    if (enhancementRequested && !g.swap.storageUsage && !g.swap.storageUnavailable) {
        destroySwapchain();
        if (!createSwapchain()) return false;
    }

    if (g.swap.acquireSems.empty()) return false;

    // ---- Destination rectangle ------------------------------------------------
    // Display path: stretch the whole source over the whole swapchain image (its
    // extent follows the live window). Encoder tee (g.swap.letterbox): the canvas is
    // fixed for the whole recording, so when the screen rotates / the aspect changes
    // fit the frame with black bars rather than stretching it.
    int32_t fitX = 0;
    int32_t fitY = 0;
    uint32_t fitW = g.swap.extent.width;
    uint32_t fitH = g.swap.extent.height;
    if (g.swap.letterbox && src.extent.width > 0 && src.extent.height > 0 &&
            fitW > 0 && fitH > 0) {
        const uint64_t lhs = static_cast<uint64_t>(src.extent.width) * g.swap.extent.height;
        const uint64_t rhs = static_cast<uint64_t>(src.extent.height) * g.swap.extent.width;
        const uint64_t diff = lhs > rhs ? lhs - rhs : rhs - lhs;
        if (diff * 100u > std::max(lhs, rhs)) { // more than ~1% aspect difference
            if (lhs > rhs) {
                fitH = static_cast<uint32_t>(rhs / src.extent.width);   // source wider: fit width
            } else {
                fitW = static_cast<uint32_t>(lhs / src.extent.height);  // source taller: fit height
            }
            fitW = std::clamp<uint32_t>(fitW & ~1u, 2u, g.swap.extent.width);
            fitH = std::clamp<uint32_t>(fitH & ~1u, 2u, g.swap.extent.height);
            fitX = static_cast<int32_t>((g.swap.extent.width - fitW) / 2);
            fitY = static_cast<int32_t>((g.swap.extent.height - fitH) / 2);
        }
    }
    const bool fitsWhole =
        fitW == g.swap.extent.width && fitH == g.swap.extent.height;

    // Enhancement that cannot render straight into the swapchain image (target has
    // no STORAGE usage, e.g. the MediaRecorder surface, or the frame must be
    // letterboxed) renders into an offscreen stage image first and is then copied
    // over. Without this the encoder tee silently fell back to a plain blit of the
    // RAW frame whenever enhancement was on.
    bool viaStage = enhancementRequested &&
        (!g.swap.storageUsage || !fitsWhole) &&
        src.format == VK_FORMAT_R8G8B8A8_UNORM &&
        g.swap.format == VK_FORMAT_R8G8B8A8_UNORM;
    if (viaStage) {
        AhbImage &st = g.swap.stage;
        if (st.image == VK_NULL_HANDLE || st.extent.width != fitW || st.extent.height != fitH) {
            if (g.vk.device != VK_NULL_HANDLE && g.vk.fn.vkDeviceWaitIdle != nullptr) {
                g.vk.fn.vkDeviceWaitIdle(g.vk.device);
            }
            // Cached storage views are keyed by VkImage handle; a recreated image can
            // reuse the handle value, so drop them together with the old image.
            if (g.gpuImageEnhancement) g.gpuImageEnhancement->clearDestinationBindings(g.vk);
            destroyAhbImage(g.vk, st);
            st = AhbImage{};
            if (createAhbImage(g.vk, fitW, fitH, VK_FORMAT_R8G8B8A8_UNORM, st) != kOk) {
                LOGW("blitOutputToSwapchain: stage image %ux%u allocation failed; "
                     "enhancement skipped for this target", fitW, fitH);
                destroyAhbImage(g.vk, st);
                st = AhbImage{};
                viaStage = false;
            }
        }
    }

    // The stage image has ONE descriptor set that is rewritten for every frame; the
    // GPU must be done with the previous staged frame before it is touched again.
    // Encoder tee samples are droppable, so skip this one instead of stalling.
    if (viaStage && g.swap.stageRingSlot >= 0) {
        const uint32_t slot = static_cast<uint32_t>(g.swap.stageRingSlot);
        if (slot < kCommandRingSize && g.vk.ringFenceArmed[slot] &&
                g.vk.fn.vkGetFenceStatus(g.vk.device, g.vk.ringFences[slot]) != VK_SUCCESS) {
            return false;
        }
    }

    // Take the command buffer BEFORE acquiring a swapchain image. If the ring is
    // exhausted the frame is simply dropped; doing it after the acquire (as before)
    // left an acquired image that was never presented and an acquire semaphore
    // with a pending signal, which slowly starved the swapchain.
    VkCommandBuffer cb = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    if (!acquireCommandRing(g.vk, cb, fence)) return false;

    // Pick an acquire semaphore from the round-robin pool. Using a single
    // semaphore risks "semaphore already has a pending wait" on fast backs.
    VkSemaphore acquireSem = g.swap.acquireSems[g.swap.acquireCursor];
    g.swap.acquireCursor = (g.swap.acquireCursor + 1) % g.swap.acquireSems.size();

    uint32_t imageIdx = 0;
    // Adaptive acquire: do not use a zero-timeout path that silently discards
    // frames when the presentation queue is temporarily busy. Frame scheduling
    // handles GPU load; the WSI stage must not become a hidden FPS limiter.
    //
    // Timeout is derived from the actual recent combined post cadence
    // (recentPostIntervalNs(), REAL+GEN together) instead of a fixed guess —
    // a device sustaining ~120 posts/sec tightens this toward ~8ms, ~60
    // posts/sec relaxes it toward ~16ms, self-correcting either way since
    // recentPostIntervalNs() reflects what's actually landing, not what the
    // multiplier merely requests. 2x margin absorbs normal jitter without
    // spurious VK_TIMEOUT drops. Clamped, not unbounded: this call runs with
    // g.presentMu held (see caller), and setOutputSurface() blocks on that
    // same mutex to clear g.outWindow on overlay stop/surface detach — an
    // unbounded wait here would turn a stalled compositor into a permanent
    // deadlock of the stop path instead of a dropped frame. Falls back to a
    // flat 16ms (60Hz-equivalent) before enough post history exists (cold
    // start) or once posting has stalled entirely.
    constexpr uint64_t kMinAcquireTimeoutNs = 4'000'000ULL;    // ~240Hz floor
    constexpr uint64_t kMaxAcquireTimeoutNs = 33'000'000ULL;   // ~30Hz ceiling
    constexpr uint64_t kFallbackAcquireTimeoutNs = 16'000'000ULL; // 60Hz-equivalent
    // The encoder is an optional tee. It must never hold the display path
    // behind a full BufferQueue: if MediaCodec is busy, skip this recording
    // sample and let the next displayed frame be the next recording sample.
    const bool encoderSurface = g.recordWindow != nullptr && g.outWindow == g.recordWindow;
    const uint64_t observedIntervalNs = recentPostIntervalNs();
    const uint64_t acquireTimeoutNs = encoderSurface
        ? 0ULL
        : (observedIntervalNs == 0
            ? kFallbackAcquireTimeoutNs
            : std::clamp(observedIntervalNs * 2, kMinAcquireTimeoutNs, kMaxAcquireTimeoutNs));
    const VkResult ar = g.vk.fn.vkAcquireNextImageKHR(
        g.vk.device, g.swap.swapchain, acquireTimeoutNs, acquireSem, VK_NULL_HANDLE, &imageIdx);
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) {
        g.swap.outOfDate = true;
        return false;
    }
    if (ar == VK_TIMEOUT) {
        // Presentation was busy for this short window. Do not drop the real
        // frame upstream; let the scheduler retry through dynamic pacing.
        return false;
    }
    if (ar == VK_NOT_READY) {
        return false;
    }
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        LOGW("vkAcquireNextImageKHR returned %d", (int)ar);
        return false;
    }

    if (imageIdx >= g.swap.images.size()) {
        LOGW("vkAcquireNextImageKHR returned OOB index %u (size %zu)", imageIdx, g.swap.images.size());
        return false;
    }

    const VkCommandBufferBeginInfo bi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    g.vk.fn.vkBeginCommandBuffer(cb, &bi);

    // Source is always kept read-only by the final presentation path.
    // When GPU enhancement is selected, the compute pass reads the source and
    // writes directly to the acquired swapchain image, performing enhancement
    // first and the selected upscale filter second. This prevents post-process
    // writes from ever modifying the REAL framegen input buffers.
    const uint32_t foreign = VK_QUEUE_FAMILY_EXTERNAL;
    bool gpuEnhance =
        enhancementRequested &&
        g.swap.storageUsage &&
        fitsWhole &&
        src.format == VK_FORMAT_R8G8B8A8_UNORM &&
        g.swap.format == VK_FORMAT_R8G8B8A8_UNORM;

    if (gpuEnhance) {
        if (!g.gpuImageEnhancement)
            g.gpuImageEnhancement = std::make_unique<GpuImageEnhancement>();
        gpuEnhance = g.gpuImageEnhancement->initialize(g.vk);
        if (!gpuEnhance) {
            static std::atomic<bool> loggedGpuEnhanceFailure{false};
            if (!loggedGpuEnhanceFailure.exchange(true, std::memory_order_relaxed))
                LOGW("image-enhancement: Vulkan GPU pipeline unavailable; "
                     "presenting without enhancement");
        }
    }

    if (viaStage) {
        if (!g.gpuImageEnhancement)
            g.gpuImageEnhancement = std::make_unique<GpuImageEnhancement>();
        viaStage = g.gpuImageEnhancement->initialize(g.vk);
        if (!viaStage) {
            static std::atomic<bool> loggedStageInitFailure{false};
            if (!loggedStageInitFailure.exchange(true, std::memory_order_relaxed))
                LOGW("image-enhancement: Vulkan GPU pipeline unavailable for staged target");
        }
    }
    // The source is read by the compute pass in both the direct and staged modes.
    const bool srcCompute = gpuEnhance || viaStage;

    const VkImageLayout srcLayout = srcCompute
        ? VK_IMAGE_LAYOUT_GENERAL
        : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    const VkAccessFlags srcAccess = srcCompute
        ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
        : VK_ACCESS_TRANSFER_READ_BIT;

    const VkImageLayout dstOldLayout =
        g.swap.imageWasPresented[imageIdx]
            ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
            : VK_IMAGE_LAYOUT_UNDEFINED;
    const VkImageLayout dstWorkLayout = gpuEnhance
        ? VK_IMAGE_LAYOUT_GENERAL
        : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    const VkAccessFlags dstWorkAccess = gpuEnhance
        ? VK_ACCESS_SHADER_WRITE_BIT
        : VK_ACCESS_TRANSFER_WRITE_BIT;

    const VkImageMemoryBarrier srcAcquire = makeImageBarrier(
        src.image, 0, srcAccess,
        VK_IMAGE_LAYOUT_UNDEFINED, srcLayout,
        foreign, g.vk.computeFamilyIdx);

    const VkImageMemoryBarrier dstAcquire = makeImageBarrier(
        g.swap.images[imageIdx],
        g.swap.imageWasPresented[imageIdx] ? VK_ACCESS_MEMORY_READ_BIT : 0,
        dstWorkAccess,
        dstOldLayout, dstWorkLayout,
        VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);

    VkImageMemoryBarrier pre[2] = {srcAcquire, dstAcquire};
    g.vk.fn.vkCmdPipelineBarrier(
        cb,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        gpuEnhance ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                   : (viaStage ? (VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                                  VK_PIPELINE_STAGE_TRANSFER_BIT)
                               : VK_PIPELINE_STAGE_TRANSFER_BIT),
        0, 0, nullptr, 0, nullptr, 2, pre);

    bool srcInGeneral = srcCompute; // layout the source is in for the release barrier
    bool stageCopied = false;

    // Letterbox bars: clear the whole target to black before the fitted frame goes in.
    if (!gpuEnhance && !fitsWhole) {
        VkClearColorValue black{};
        black.float32[0] = 0.0f;
        black.float32[1] = 0.0f;
        black.float32[2] = 0.0f;
        black.float32[3] = 1.0f;
        const VkImageSubresourceRange clearRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        g.vk.fn.vkCmdClearColorImage(
            cb, g.swap.images[imageIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            &black, 1, &clearRange);
        const VkMemoryBarrier clearDone{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        };
        g.vk.fn.vkCmdPipelineBarrier(
            cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 1, &clearDone, 0, nullptr, 0, nullptr);
    }

    // Staged enhancement: source -> enhancement + upscale -> stage image -> copy into
    // the (possibly letterboxed) swapchain image. Same shader and settings as the
    // direct path, so the recorded frame matches what the display shows.
    if (viaStage) {
        const VkImage stageImg = g.swap.stage.image;
        const VkImageMemoryBarrier stageIn = makeImageBarrier(
            stageImg, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
        g.vk.fn.vkCmdPipelineBarrier(
            cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &stageIn);

        const int stageFilterMode =
            g.upscaleFilterMode.load(std::memory_order_relaxed);
        const float stageContrast =
            g.imageEnhancementContrast.load(std::memory_order_relaxed);
        const float stageSaturation =
            g.imageEnhancementSaturation.load(std::memory_order_relaxed);

        if (g.gpuImageEnhancement->record(
                g.vk, cb, src.image, src.extent,
                stageImg, g.swap.stage.extent,
                stageFilterMode, stageContrast, stageSaturation)) {
            const VkImageMemoryBarrier stageOut = makeImageBarrier(
                stageImg, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
            g.vk.fn.vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &stageOut);
            const VkImageCopy stageRegion{
                .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .srcOffset = {0, 0, 0},
                .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .dstOffset = {fitX, fitY, 0},
                .extent = {fitW, fitH, 1},
            };
            g.vk.fn.vkCmdCopyImage(
                cb, stageImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                g.swap.images[imageIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1, &stageRegion);
            stageCopied = true;
        } else {
            LOGW("image-enhancement: staged compute record failed; using transfer path");
            const VkImageMemoryBarrier srcBack = makeImageBarrier(
                src.image, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
            g.vk.fn.vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &srcBack);
            srcInGeneral = false;
        }
    }

    if (gpuEnhance) {
        const int filterMode =
            g.upscaleFilterMode.load(std::memory_order_relaxed);
        const float contrast =
            g.imageEnhancementContrast.load(std::memory_order_relaxed);
        const float saturation =
            g.imageEnhancementSaturation.load(std::memory_order_relaxed);

        if (!g.gpuImageEnhancement->record(
                g.vk, cb, src.image, src.extent,
                g.swap.images[imageIdx], g.swap.extent,
                filterMode, contrast, saturation)) {
            LOGW("image-enhancement: compute record failed; using transfer path");
            gpuEnhance = false;

            const VkImageMemoryBarrier srcBack = makeImageBarrier(
                src.image, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
            const VkImageMemoryBarrier dstBack = makeImageBarrier(
                g.swap.images[imageIdx], VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier back[2] = {srcBack, dstBack};
            g.vk.fn.vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 2, back);
        }
    }

    if (!gpuEnhance && !stageCopied) {
        static std::atomic<bool> loggedBlitMode{false};

        if (src.extent.width == g.swap.extent.width &&
            src.extent.height == g.swap.extent.height) {
            if (!loggedBlitMode.exchange(true, std::memory_order_relaxed)) {
                LOGW("blitOutputToSwapchain: 1:1 scale detected. "
                     "Using FAST-PATH vkCmdCopyImage.");
            }
            const VkImageCopy region{
                .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .srcOffset = {0, 0, 0},
                .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .dstOffset = {0, 0, 0},
                .extent = {src.extent.width, src.extent.height, 1},
            };
            g.vk.fn.vkCmdCopyImage(
                cb, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                g.swap.images[imageIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1, &region);
        } else {
            const int filterMode =
                g.upscaleFilterMode.load(std::memory_order_relaxed);
            const VkFilter blitFilter =
                filterMode == 0 ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;

            if (!loggedBlitMode.exchange(true, std::memory_order_relaxed)) {
                LOGW("blitOutputToSwapchain: Scaled output detected "
                     "(%ux%u -> %ux%u). Using %s vkCmdBlitImage.",
                     src.extent.width, src.extent.height,
                     g.swap.extent.width, g.swap.extent.height,
                     blitFilter == VK_FILTER_NEAREST ? "VK_FILTER_NEAREST"
                                                     : "VK_FILTER_LINEAR");
            }

            const VkImageBlit region{
                .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .srcOffsets = {{0, 0, 0}, {
                    static_cast<int32_t>(src.extent.width),
                    static_cast<int32_t>(src.extent.height), 1,
                }},
                .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .dstOffsets = {{fitX, fitY, 0}, {
                    fitX + static_cast<int32_t>(fitW),
                    fitY + static_cast<int32_t>(fitH), 1,
                }},
            };
            g.vk.fn.vkCmdBlitImage(
                cb, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                g.swap.images[imageIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1, &region, blitFilter);
        }
    }

    if (gpuEnhance) {
        const VkImageMemoryBarrier srcRelease = makeImageBarrier(
            src.image,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, 0,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, foreign);
        const VkImageMemoryBarrier dstPresent = makeImageBarrier(
            g.swap.images[imageIdx], VK_ACCESS_SHADER_WRITE_BIT, 0,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
        VkImageMemoryBarrier post[2] = {srcRelease, dstPresent};
        g.vk.fn.vkCmdPipelineBarrier(
            cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 2, post);
    } else {
        const VkImageMemoryBarrier srcRelease = makeImageBarrier(
            src.image,
            srcInGeneral ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                         : VK_ACCESS_TRANSFER_READ_BIT,
            0,
            srcInGeneral ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, foreign);
        const VkImageMemoryBarrier dstPresent = makeImageBarrier(
            g.swap.images[imageIdx], VK_ACCESS_TRANSFER_WRITE_BIT, 0,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
        VkImageMemoryBarrier post[2] = {srcRelease, dstPresent};
        g.vk.fn.vkCmdPipelineBarrier(
            cb,
            srcInGeneral ? (VK_PIPELINE_STAGE_TRANSFER_BIT |
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                         : VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 2, post);
    }

    g.vk.fn.vkEndCommandBuffer(cb);

    VkSemaphore renderSem = g.swap.renderSems[imageIdx];
    VkSemaphore waitSems[2] = { acquireSem, framegenDoneSemaphore };
    const VkPipelineStageFlags waitStages[2] = {
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        static_cast<VkPipelineStageFlags>(
            gpuEnhance ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                       : (viaStage ? (VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                                      VK_PIPELINE_STAGE_TRANSFER_BIT)
                                   : VK_PIPELINE_STAGE_TRANSFER_BIT))
    };
    const uint32_t waitCount = framegenDoneSemaphore != VK_NULL_HANDLE ? 2u : 1u;
    const VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = waitCount,
        .pWaitSemaphores = waitSems,
        .pWaitDstStageMask = waitStages,
        .commandBufferCount = 1,
        .pCommandBuffers = &cb,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &renderSem,
    };
    if (g.vk.fn.vkQueueSubmit(g.vk.computeQueue, 1, &si, fence) != VK_SUCCESS) {
        LOGW("vkQueueSubmit(swapchain blit) failed");
        return false;
    }
    // Arm the ring fence manually — submitCommandRing would have done this
    // but we bypassed it to add the semaphore wait/signal pair.
    for (uint32_t i = 0; i < kCommandRingSize; ++i) {
        if (g.vk.ringFences[i] == fence) {
            g.vk.ringFenceArmed[i] = true;
            if (viaStage) g.swap.stageRingSlot = static_cast<int>(i);
            break;
        }
    }
    if (framegenDoneSemaphore != VK_NULL_HANDLE) {
        g.framegenCompletionTickets.push_back({framegenDoneSemaphore, fence});
    }

    const VkPresentInfoKHR pi{
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &renderSem,
        .swapchainCount = 1,
        .pSwapchains = &g.swap.swapchain,
        .pImageIndices = &imageIdx,
    };
    const VkResult pr = g.vk.fn.vkQueuePresentKHR(g.vk.computeQueue, &pi);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
        // Note for next pass — don't fall back this frame (we already posted).
        g.swap.outOfDate = true;
    } else if (pr != VK_SUCCESS) {
        LOGW("vkQueuePresentKHR returned %d", (int)pr);
        return false;
    }

    g.swap.imageWasPresented[imageIdx] = 1;
    // No present-mode fallback or software VSync. The selected WSI mode owns
    // presentation semantics for this swapchain.
    return true;
}

// Transition a freshly-created AHB-backed inSlot image UNDEFINED→GENERAL
// and release ownership to VK_QUEUE_FAMILY_EXTERNAL. This must be called once
// per inSlot after createAhbImage so that every subsequent copyAhbImage can
// acquire with oldLayout=GENERAL, preserving Mali AFRC state across frames.
void initInSlotImageLayout(VkImage image) {
    // Transition UNDEFINED→GENERAL and release to EXTERNAL in one barrier.
    const bool ok = runTransientCommands([&](VkCommandBuffer cb) {
        const VkImageMemoryBarrier barrier = makeImageBarrier(image, 0, 0,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, VK_QUEUE_FAMILY_EXTERNAL);
        g.vk.fn.vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);
    });
    if (!ok) {
        LOGE("initInSlotImageLayout: transient command submission failed");
        return;
    }
    LOGI("initInSlotImageLayout: image=%p initialized to GENERAL/EXTERNAL", (void*)image);
}

// Copy `src` AhbImage into `dst` AhbImage on the compute queue using a
// transient command buffer. Both images are AHB-backed so layouts are
// EXTERNAL initially; we transition to TRANSFER_DST/SRC, copy, transition
// back to GENERAL so framegen can read them.
//
// Uses transient alloc+free per call rather than the session's CB ring:
// on Adreno the per-frame vkAllocateCommandBuffers/vkFreeCommandBuffers
// pair runs in single-digit microseconds, while vkResetCommandBuffer +
// vkWaitForFences (the ring path) was measurably slower in field testing.
// Frame transfer is permanently zero-copy: MediaProjection AHardwareBuffers are
// passed directly to LSFG via presentContextAHB(). No CPU/GPU pixel-copy path
// exists in the render loop.

bool initFramegen(const char *cacheDir) {
    const std::string cache(cacheDir ? cacheDir : "");

    // There are two shader sources, in order of preference for a given
    // session:
    //
    //   1. FP16 SPIR-V (Lossless.dll IDs 304..351): explicitly selected.
    //   2. FP32 SPIR-V (Lossless.dll IDs 353..400): explicitly selected/default.
    // The selected precision is exclusive; there is no cross-precision fallback.
    //
    // FP32 SPIR-V is the default: it uses `OpMemoryModel Logical GLSL450`
    // with NO VulkanMemoryModel capability — verified across the entire
    // 353..400 range via _analysis/dump_fp32_spv.py / _analysis/fp32/. That
    // makes it work on devices that lack vulkanMemoryModel (Mali Bifrost/
    // Valhall G57/G68/G77), which a VMM-requiring shader would DEVICE_LOST
    // on at first compute dispatch (see Mali-G57 field log "presentContext
    // threw: Unable to submit command buffer (error -4)").
    const bool fp16Available = fp16_shaders_available(cache);
    const bool fp32SpirvAvailable = fp32_spirv_shaders_available(cache);

    // Precision is exclusive. Never silently switch precision or use a
    // cross-tier shader: the selected Lossless Scaling 3.2.2.0 SPIR-V set
    // must be complete before a framegen session is allowed to start.
    const bool useFp16 = g.framegenFp16;
    const bool selectedAvailable = useFp16 ? fp16Available : fp32SpirvAvailable;

    if (!selectedAvailable) {
        if (useFp16) {
            LOGE("FP16 framegen requested but the complete Lossless Scaling 3.2.2.0 "
                 "FP16 SPIR-V set (resources 304..351) is unavailable in %s — "
                 "no FP32 fallback will be used", cache.c_str());
        } else {
            LOGE("FP32 framegen requested but the complete Lossless Scaling 3.2.2.0 "
                 "FP32 SPIR-V set (resources 353..400) is unavailable in %s — "
                 "no FP16 fallback will be used", cache.c_str());
        }
        return false;
    }

    if (useFp16) {
        LOGI("Loading ONLY FP16 SPIR-V cache from Lossless Scaling 3.2.2.0 "
             "(resource IDs 304..351)");
    } else {
        LOGI("Loading ONLY FP32 SPIR-V cache from Lossless Scaling 3.2.2.0 "
             "(resource IDs 353..400)");
    }

    auto loader = [cache, useFp16](const std::string &name) -> std::vector<uint8_t> {
        // Framegen requests shaders by symbolic name (e.g. "p_mipmaps");
        // we cache them on disk by numeric resource ID. Each of the two
        // sources has its own ID range and on-disk subdirectory, both keyed
        // off the shared base id via constant offsets.
        uint32_t id = 0;
        ShaderCache source = ShaderCache::Fp32Spirv;
        if (useFp16) {
            id = shader_name_to_resource_id_fp16(name);
            source = ShaderCache::Fp16Spirv;
        } else {
            id = shader_name_to_resource_id_fp32_spirv(name);
            source = ShaderCache::Fp32Spirv;
        }
        if (id == 0) {
            LOGE("Unknown shader name '%s' from framegen", name.c_str());
            return {};
        }
        auto spirv = load_cached_spirv(cache, id, source);

        // No cross-precision fallback. A missing shader is fatal for this
        // selected precision so an FP32 session can never accidentally execute
        // an FP16 shader (or vice versa).
        if (spirv.empty()) {
            LOGE("Shader '%s' (id %u) missing from cache (%s)",
                 name.c_str(), id, cache.c_str());
        }
        return spirv;
    };

    try {
        if (g.performanceMode) {
            LSFG_3_1P::initialize(g.vk.deviceUuid, g.hdr, g.flowScale,
                                  static_cast<uint64_t>(g.multiplier), loader);
        } else {
            LSFG_3_1::initialize(g.vk.deviceUuid, g.hdr, g.flowScale,
                                 static_cast<uint64_t>(g.multiplier), loader);
        }
        return true;
    } catch (const std::exception &e) {
        LOGE("LSFG_3_1::initialize threw: %s — likely missing extension or shader. Continuing in capture-only mode.", e.what());
        return false;
    } catch (...) {
        LOGE("LSFG_3_1::initialize threw unknown exception");
        return false;
    }
}

bool createFramegenContext() {
    // Pass AHardwareBuffer pointers to framegen's Android variant. Framegen
    // imports them in its own VkDevice and shares pixel storage with us via
    // the AHB itself — we keep ownership and refcount on the Android side.
    if (g.inSlot[0].ahb == nullptr || g.inSlot[1].ahb == nullptr) {
        LOGE("Input AhbImages have no AHB pointer");
        return false;
    }
    std::vector<AHardwareBuffer*> outAhbs;
    outAhbs.reserve(g.outputs.size());
    for (auto &o : g.outputs) {
        if (o.ahb == nullptr) {
            LOGE("Output AhbImage missing AHB pointer");
            return false;
        }
        outAhbs.push_back(o.ahb);
    }

    try {
        if (g.performanceMode) {
            g.framegenCtxId = LSFG_3_1P::createContextFromAHB(
                g.inSlot[0].ahb, g.inSlot[1].ahb, outAhbs,
                g.inSlot[0].extent, g.inSlot[0].format);
        } else {
            g.framegenCtxId = LSFG_3_1::createContextFromAHB(
                g.inSlot[0].ahb, g.inSlot[1].ahb, outAhbs,
                g.inSlot[0].extent, g.inSlot[0].format);
        }
    } catch (const std::exception &e) {
        LOGE("createContextFromAHB threw: %s", e.what());
        return false;
    }
    {
        const auto mem = LSFG::getMemoryStats();
        LOGI("framegen image memory: pooled=%.1f MiB scratch=%.1f MiB "
             "(would be %.1f MiB unaliased) allocations=%u",
             static_cast<double>(mem.persistentBytes) / (1024.0 * 1024.0),
             static_cast<double>(mem.scratchBytes) / (1024.0 * 1024.0),
             static_cast<double>(mem.scratchUnaliasedBytes) / (1024.0 * 1024.0),
             mem.allocationCount);
    }
    return true;
}

// Output blit. Has two paths:
//
//  1. GPU fast path (WSI): vkCmdBlitImage from the output AHB's VkImage
//     straight into the next swapchain image, then vkQueuePresentKHR. Zero
//     CPU touch of the pixel data. Saves ~3-5 ms/blit at 1080p.
//
//  The CPU/software blit fallback (AHardwareBuffer_lock(CPU_READ) +
//  ANativeWindow_lock + per-row memcpy) has been REMOVED. This is now a
//  GPU-only presentation path: if the WSI swapchain isn't available on the
//  current surface (missing extension, driver rejects the surface, compute
//  queue can't present, etc.) the frame is dropped instead of falling back
//  to CPU. This trades away compatibility with devices/drivers that reject
//  the WSI path (e.g. the Mali-G57 window-in-use / Adreno present quirks
//  documented around createSwapchain()) for guaranteeing every posted frame
//  went through the GPU. On an affected device this means no frames get
//  posted at all rather than a degraded CPU blit — check logcat for
//  "blit dropped" warnings if the overlay goes blank.
//
// Returns true iff the frame was actually posted (recordOverlayPost() ran).
// Callers use this to keep generatedFrames/postedFrames in lockstep instead
// of assuming every attempted blit succeeds — see the comment at the
// generatedFrames.fetch_add() call site in workerThread().
void drainFramegenCompletionSemaphore(VkSemaphore semaphore) {
    if (semaphore == VK_NULL_HANDLE || g.vk.device == VK_NULL_HANDLE) return;
    for (const auto &t : g.framegenCompletionTickets)
        if (t.semaphore == semaphore) return; // already consumed by a submitted blit
    VkFence fence = VK_NULL_HANDLE;
    const VkFenceCreateInfo fci{ .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    if (g.vk.fn.vkCreateFence(g.vk.device, &fci, nullptr, &fence) != VK_SUCCESS) return;
    const VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    const VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &semaphore,
        .pWaitDstStageMask = &stage,
    };
    if (g.vk.fn.vkQueueSubmit(g.vk.computeQueue, 1, &si, fence) != VK_SUCCESS) {
        g.vk.fn.vkDestroyFence(g.vk.device, fence, nullptr);
        return;
    }
    g.framegenCompletionTickets.push_back({semaphore, fence, /*ownsFence=*/true});
}

// CPU image post-process used by the live settings. The operation is deliberately
// simple and allocation-free after the first frame: one reusable RGBA8 staging
// buffer, then contrast/saturation. It runs immediately before presentation, so
// changing the controls affects the next frame without a session restart.
// Non-RGBA AHBs are left untouched.
// Called with g.presentMu held. Temporarily reuses the existing swapchain
// machinery for the encoder surface. This keeps one battle-tested WSI path
// instead of maintaining two subtly different Vulkan blitters. No display
// state is touched outside the lock and the encoder target uses FIFO, which is
// the most broadly accepted mode for MediaCodec input surfaces.
bool blitOutputToRecordingSurfaceLocked(const AhbImage &out) {
    if (g.recordWindow == nullptr || g.recordWidth == 0 || g.recordHeight == 0) return false;
    auto savedSwap = std::move(g.swap);
    ANativeWindow *savedWindow = g.outWindow;
    const uint32_t savedW = g.outWidth;
    const uint32_t savedH = g.outHeight;
    const uint32_t savedSwapW = g.swapWinW;
    const uint32_t savedSwapH = g.swapWinH;
    const int savedPresentMode = g.presentModeRequested.load(std::memory_order_relaxed);
    g.swap = std::move(g.recordSwap);
    // Encoder canvas size is fixed for the whole recording; a rotation changes the
    // source aspect, so fit it with black bars instead of stretching it.
    g.swap.letterbox = true;
    g.outWindow = g.recordWindow;
    g.outWidth = g.recordWidth;
    g.outHeight = g.recordHeight;
    g.swapWinW = g.recordWidth;
    g.swapWinH = g.recordHeight;
    g.presentModeRequested.store(static_cast<int>(VK_PRESENT_MODE_FIFO_KHR), std::memory_order_relaxed);
    bool ok = false;
    if (g.swap.swapchain == VK_NULL_HANDLE) {
        ok = createSwapchain();
    }
    if (ok || g.swap.swapchain != VK_NULL_HANDLE) {
        ok = blitOutputToSwapchain(out, VK_NULL_HANDLE);
    }
    g.recordSwap = std::move(g.swap);
    g.presentModeRequested.store(savedPresentMode, std::memory_order_relaxed);
    g.outWindow = savedWindow;
    g.outWidth = savedW;
    g.outHeight = savedH;
    g.swapWinW = savedSwapW;
    g.swapWinH = savedSwapH;
    g.swap = std::move(savedSwap);
    return ok;
}

bool blitOutputToWindow(const AhbImage &out, VkSemaphore framegenDoneSemaphore = VK_NULL_HANDLE) {

    // NOTE: g.outWindow is intentionally NOT checked here. setOutputSurface()
    // (the detach/attach path) clears it under g.presentMu, and this function
    // used to check it before taking that lock — a TOCTOU race where the
    // surface detaches between this check and the lock below would sail
    // through, reach createSwapchain() with a null window, fail, and
    // permanently set disabledForSession=true over what was really just a
    // transient detach (observed at overlay-stop time: "Output surface
    // detached" immediately followed by "MAILBOX swapchain creation
    // failed"). The real check now happens under the lock, right before it's
    // used.
    if (out.ahb == nullptr) {
        if (framegenDoneSemaphore != VK_NULL_HANDLE)
            drainFramegenCompletionSemaphore(framegenDoneSemaphore);
        return false;
    }

    if (!kEnableWsiSwapchain || !g.vk.hasSwapchain || g.swap.disabledForSession) {
        if (framegenDoneSemaphore != VK_NULL_HANDLE)
            drainFramegenCompletionSemaphore(framegenDoneSemaphore);
        LOGE("WSI presentation unavailable on this surface; frame not submitted");
        return false;
    }

    // g.presentMu (not g.mu): swapchain/present state has its own lock,
    // deliberately separate from g.mu (which only guards g.pendingFrames /
    // g.stopRequested). createSwapchain() below can hit a vkDeviceWaitIdle()
    // (via destroySwapchain()) that runs for a few ms — holding g.mu across
    // that would block pushFrame() from enqueueing new captures for the
    // duration, reintroducing exactly the kind of stall this file is trying
    // to get rid of elsewhere. Callers (this thread, or genWaitThread posting
    // a generated frame) are serialized against each other here instead.
    std::lock_guard<std::mutex> lock(g.presentMu);
    // Re-check here, under the same lock setOutputSurface() uses to clear
    // g.outWindow. If it's null now, the surface was detached (or a
    // teardown is in progress) — a normal, expected drop, not a driver/
    // capability failure, so skip the frame quietly without touching
    // disabledForSession.
    if (g.outWindow == nullptr) {
        if (framegenDoneSemaphore != VK_NULL_HANDLE)
            drainFramegenCompletionSemaphore(framegenDoneSemaphore);
        return false;
    }
    // Re-read the requested mode each present; setPresentMode() may have
    // been called since the swapchain was created, in which case the
    // mismatch below tears the swapchain down so createSwapchain() picks
    // up the new mode on the next present.
    const VkPresentModeKHR requestedVkMode =
        static_cast<VkPresentModeKHR>(g.presentModeRequested.load(std::memory_order_relaxed));
    if (g.swap.swapchain != VK_NULL_HANDLE && g.swap.presentMode != requestedVkMode) {
        // Mode changes are applied on the render thread, so swapchain teardown is
        // serialized with presentation and never races the Vulkan queue.
        destroySwapchain();
    }
    if (g.swap.swapchain == VK_NULL_HANDLE) {
        if (!createSwapchain()) {
            // g.outWindow was just confirmed non-null above under g.mu, the
            // same lock setOutputSurface() holds while clearing it, so this
            // failure can't be that race — it's a genuine capability/driver
            // problem and disabling WSI for the rest of the session is
            // warranted.
            g.swap.disabledForSession = true;
            LOGE("swapchain creation failed; frame not submitted");
            return false;
        }
    }
    if (blitOutputToSwapchain(out, framegenDoneSemaphore)) {
        recordOverlayPost();
        // Recording is a tee after the real display post. Never let encoder
        // back-pressure decide whether a REAL frame reaches the user.
        if (g.recordWindow != nullptr) {
            (void)blitOutputToRecordingSurfaceLocked(out);
        }
        return true;
    }
    if (framegenDoneSemaphore != VK_NULL_HANDLE)
        drainFramegenCompletionSemaphore(framegenDoneSemaphore);
    LOGE("blit/present failed; frame not submitted");
    return false;
}

#ifdef LSFG_HAVE_NCNN
// Runs the ncnn AI backend for one frame pair. The model inference is GPU-only;
// input slots (oldSlot = previous capture, newSlot = just-copied current
// capture — matching the same chronological order LSFG's own frameIdx
// tracking uses), calls NcnnInterpolator::interpolate(), and CPU-writes each
// resulting frame straight into g.outputs[i]'s AHB so the existing
// timedBlit()/blitOutputToWindow() loop in workerThread can present them
// exactly like LSFG-generated output — no other code downstream needs to
// know which backend produced the pixels.
//
// AHardwareBuffer row stride can exceed width*4 bytes (GPU alignment
// padding), so every lock goes through a tightly-packed staging buffer —
// NcnnInterpolator's interpolate() contract assumes no stride.
//
// Input-side host visibility: no extra barrier is needed here.
// processRealFrameIntoSlot() (via copyAhbImage) already issues a
// TRANSFER_WRITE -> HOST_READ barrier before returning, so by the time this
// runs, whichever slot was just written this iteration is safe to
// CPU-read, and the other slot was already flushed on a prior iteration and
// hasn't been GPU-written since.
bool runAiInterpolate(int oldSlot, int newSlot, uint32_t w, uint32_t h) {
    const bool useIfrnet = (g.aiEngine == 1);
    if (useIfrnet) {
        if (g.aiIfrnet == nullptr || !g.aiIfrnet->isLoaded()) return false;
    } else {
        if (g.ai == nullptr || !g.ai->isLoaded()) return false;
    }

    auto lockRead = [](const AhbImage &img, std::vector<uint8_t> &staging) -> bool {
        void *ptr = nullptr;
        if (AHardwareBuffer_lock(img.ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                  -1, nullptr, &ptr) != 0 || ptr == nullptr) {
            return false;
        }
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(img.ahb, &desc);
        const size_t strideBytes = static_cast<size_t>(desc.stride) * 4; // stride is in pixels
        const size_t rowBytes = static_cast<size_t>(desc.width) * 4;
        staging.resize(static_cast<size_t>(desc.height) * rowBytes);
        const uint8_t *src = static_cast<const uint8_t *>(ptr);
        for (uint32_t row = 0; row < desc.height; ++row) {
            std::memcpy(staging.data() + static_cast<size_t>(row) * rowBytes,
                        src + static_cast<size_t>(row) * strideBytes, rowBytes);
        }
        AHardwareBuffer_unlock(img.ahb, nullptr);
        return true;
    };

    auto lockWrite = [](const AhbImage &img, const uint8_t *staging) -> bool {
        void *ptr = nullptr;
        if (AHardwareBuffer_lock(img.ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                  -1, nullptr, &ptr) != 0 || ptr == nullptr) {
            return false;
        }
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(img.ahb, &desc);
        const size_t strideBytes = static_cast<size_t>(desc.stride) * 4;
        const size_t rowBytes = static_cast<size_t>(desc.width) * 4;
        uint8_t *dst = static_cast<uint8_t *>(ptr);
        for (uint32_t row = 0; row < desc.height; ++row) {
            std::memcpy(dst + static_cast<size_t>(row) * strideBytes,
                        staging + static_cast<size_t>(row) * rowBytes, rowBytes);
        }
        // fence == nullptr: block until the write is visible to other
        // consumers (the WSI swapchain's vkCmdBlitImage right after this).
        AHardwareBuffer_unlock(img.ahb, nullptr);
        return true;
    };

    const size_t frameBytes = static_cast<size_t>(w) * h * 4u;
    if (g.aiInputAStaging.size() != frameBytes) g.aiInputAStaging.resize(frameBytes);
    if (g.aiInputCStaging.size() != frameBytes) g.aiInputCStaging.resize(frameBytes);

    if (!lockRead(g.inSlot[oldSlot], g.aiInputAStaging) ||
        !lockRead(g.inSlot[newSlot], g.aiInputCStaging)) {
        LOGE("AI backend: failed to bridge input AHBs");
        return false;
    }

    // Reuse output staging allocations across frames. This removes a large
    // per-frame allocation/free cycle at 1080p+ and prevents heap fragmentation.
    const size_t outputCount = g.outputs.size();
    if (g.aiOutputStaging.size() != outputCount) {
        g.aiOutputStaging.resize(outputCount);
        g.aiOutputPtrs.resize(outputCount);
    }
    for (size_t i = 0; i < outputCount; ++i) {
        if (g.aiOutputStaging[i].size() != frameBytes)
            g.aiOutputStaging[i].resize(frameBytes);
        g.aiOutputPtrs[i] = g.aiOutputStaging[i].data();
    }

    // g.outputs.size() == g.multiplier (extra frames per pair); NcnnInterpolator
    // wants the total segment count (extra + 1).
    const int totalMult = g.multiplier + 1;

    // g.flowScale was inverted at init time for LSFG's convention
    // (g.flowScale = 1/userFlow); both interpolators want the plain
    // user-facing 0..1 fraction back, so invert it again here. RIFE and
    // IFRNet share the exact same interpolate() call shape (see the
    // "intentionally interchangeable" note in IfrnetInterpolator.hpp), so
    // only the object the call is made on differs.
    const int rc = useIfrnet
        ? g.aiIfrnet->interpolate(g.aiInputAStaging.data(), g.aiInputCStaging.data(),
                                   static_cast<int>(w), static_cast<int>(h),
                                   g.aiOutputPtrs.data(), totalMult, 1.0f / g.flowScale)
        : g.ai->interpolate(g.aiInputAStaging.data(), g.aiInputCStaging.data(),
                             static_cast<int>(w), static_cast<int>(h),
                             g.aiOutputPtrs.data(), totalMult, 1.0f / g.flowScale);
    if (rc != kNcnnOk) {
        LOGE("AI backend interpolate() failed rc=%d", rc);
        return false;
    }

    for (size_t i = 0; i < g.outputs.size(); ++i) {
        if (!lockWrite(g.outputs[i], g.aiOutputStaging[i].data())) {
            LOGE("AI backend: failed to CPU-lock output AHB %zu for write", i);
            return false;
        }
    }
    return true;
}
#endif // LSFG_HAVE_NCNN

// Persistent thread for CPU-waitIdle-fallback framegen completion. Spawned
// once (alongside `worker`) and parked on g.genCv the rest of the time — see
// genWaitThread's declaration for why this isn't a thread spawned per job.
// Runs LSFG_*::waitIdle() + the generated-output blit off the main capture/
// present loop, so a slow wait on a device without exportable cross-device
// semaphores can't stall real-frame ingestion.
void genWorkerThread() {
    while (true) {
        bool perfMode = false;
        bool publishOutputs = true;
        uint64_t jobFrameId = 0;
        uint64_t jobDeadlineNs = 0;
        {
            std::unique_lock<std::mutex> lock(g.genMu);
            g.genCv.wait(lock, []{ return g.genStopRequested || g.genJobPending; });
            if (g.genStopRequested && !g.genJobPending) return;
            perfMode = g.genJobPerfMode;
            publishOutputs = g.genJobPublish;
            jobFrameId = g.genJobFrameId;
            jobDeadlineNs = g.genJobDeadlineNs;
            g.genJobPending = false;
        }

        try {
            if (perfMode) LSFG_3_1P::waitIdle();
            else          LSFG_3_1::waitIdle();
        } catch (const std::exception &e) {
            handleFramegenException("waitIdle", e);
            {
                std::lock_guard<std::mutex> doneLock(g.genMu);
                g.genCompletedFrameId = std::max(g.genCompletedFrameId, jobFrameId);
            }
            g.cpuFallbackGenInFlight.store(false, std::memory_order_release);
            g.genDoneCv.notify_all();
            continue;
        }
        // Generation completion is only made READY here. Presentation is
        // owned by the render worker so generated frames can never overtake a
        // REAL frame. In NO-WAIT mode the worker will discard a READY result if
        // a newer REAL frame has already been displayed. In WAIT mode it waits
        // for this READY result before displaying that REAL frame.
        const uint64_t completedAtNs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                State::Clock::now().time_since_epoch()).count());
        const bool withinDeadline =
            jobDeadlineNs == 0 || completedAtNs <= jobDeadlineNs;
        if (!withinDeadline) {
            LOGI("genWorkerThread: generation missed deadline frameId=%llu deadlineMs=%d",
                 static_cast<unsigned long long>(jobFrameId),
                 g.generationDeadlineMs.load(std::memory_order_relaxed));
        }
        const bool validForSession =
            publishOutputs && withinDeadline &&
            jobFrameId != 0 &&
            !g.bypass.load(std::memory_order_relaxed) &&
            !g.stopRequested;

        if (validForSession) {
            g.genReadyFrameId.store(jobFrameId, std::memory_order_release);
            g.genReady.store(true, std::memory_order_release);
        }

        {
            std::lock_guard<std::mutex> doneLock(g.genMu);
            g.genCompletedFrameId = std::max(g.genCompletedFrameId, jobFrameId);
        }
        g.cpuFallbackGenInFlight.store(false, std::memory_order_release);
        if (g.framegenCompletionTickets.empty()) {
            g.framegenInputsInFlight.store(false, std::memory_order_release);
        }
        g.genDoneCv.notify_all();
    }
}

void enqueueFinalFrame(const AhbImage &image, uint64_t sourceFrameId,
                      uint32_t ordinal, bool generated,
                      uint64_t generatedFrameId, VkSemaphore completionSemaphore,
                      int64_t captureTimestampNs) {
    if (image.image == VK_NULL_HANDLE || sourceFrameId == 0) {
        if (completionSemaphore != VK_NULL_HANDLE)
            drainFramegenCompletionSemaphore(completionSemaphore);
        return;
    }

    State::FinalFrame frame{
        .image = image,
        .sourceFrameId = sourceFrameId,
        .ordinal = ordinal,
        .generated = generated,
        .generatedFrameId = generatedFrameId,
        .completionSemaphore = completionSemaphore,
        .captureTimestampNs = captureTimestampNs,
    };

    // lower_bound gives the queue a deterministic presentation order even if
    // generation completion and REAL capture production happen at different
    // times. No fixed multiplier or frame-rate value is used here.
    const auto it = std::lower_bound(
        g.finalFrames.begin(), g.finalFrames.end(), frame,
        [](const State::FinalFrame &a, const State::FinalFrame &b) {
            if (a.sourceFrameId != b.sourceFrameId)
                return a.sourceFrameId < b.sourceFrameId;
            return a.ordinal < b.ordinal;
        });
    g.finalFrames.insert(it, frame);
}

void workerThread() {
    // Do not set CPU affinity here. The worker inherits the process/default
    // scheduler mask and Android is free to place it on any online CPU.
    // CPU-heavy AI work configures ncnn separately to use the full topology.
    // Elevate scheduling priority: this thread owns the capture→framegen→present
    // pipeline. There is no software VSync deadline or frame limiter here.
    //
    // Pushed to ANDROID_PRIORITY_URGENT_AUDIO (-19) rather than the previous
    // URGENT_DISPLAY (-8) — the strongest nice value an app can self-assign
    // without root — on an explicit "give this everything, thermal/scheduling
    // fairness be damned" request. This is a deliberate tradeoff: it can starve
    // less latency-sensitive background threads (this device's, and other
    // apps') and burns more power/heat for it. Requires no special permission —
    // any process may renice its own threads into this range.
    if (setpriority(PRIO_PROCESS, gettid(), ANDROID_PRIORITY_URGENT_AUDIO) != 0) {
        // Some OEM kernels cap the range unprivileged apps can self-renice
        // into; if the aggressive value is rejected, fall back one step
        // rather than silently staying at the SCHED_OTHER default.
        if (setpriority(PRIO_PROCESS, gettid(), ANDROID_PRIORITY_URGENT_DISPLAY) != 0) {
            LOGW("workerThread: setpriority(URGENT_AUDIO/URGENT_DISPLAY) both failed, "
                 "staying at default priority");
        }
    }

    // ---- Frame-time profiling ------------------------------------------------
    //
    // Rolling accumulators over a 60-frame window. The 4 segments are:
    //   copy     — importAhbImage + processRealFrameIntoSlot (input prep)
    //   present  — LSFG_3_1::presentContext (framegen submit; usually <1 ms)
    //   waitIdle — legacy profile bucket; cross-device sync is now GPU→GPU semaphore based
    //   blit     — output blit loop (CPU memcpy + ANativeWindow_unlockAndPost,
    //              one per generated + real frame)
    //   total    — frameWorkStartedAt → end of blit loop
    // The worker is intentionally uncapped; it never sleeps to a display-VSync
    // deadline or any derived output-FPS deadline.
    struct ProfileAccum {
        int64_t copyNs    = 0;
        int64_t presentNs = 0;
        int64_t waitIdleNs= 0;
        int64_t blitNs    = 0;
        int64_t totalNs   = 0;
        int64_t queueNs   = 0;
        int64_t captureToDisplayNs = 0;
        uint32_t blitCount = 0;
        uint32_t samples  = 0;
    } prof{};
    constexpr uint32_t kProfileWindow = 60;

    // The GPU swapchain owns presentation; no CPU-side surface clear or pixel
    // copy is performed in the hot path.
    while (true) {
        State::PendingFrame pendingFrame{};
        {
            std::unique_lock<std::mutex> lock(g.mu);
            g.pendingCv.wait(lock, []{ return g.stopRequested || !g.pendingFrames.empty(); });
            if (g.stopRequested && g.pendingFrames.empty()) return;
            // Preserve REAL-frame order. Capture IDs are monotonic, so the
            // common case needs no sort at all. Only repair the queue when the
            // newest arrival is actually out of order; this avoids an O(n log n)
            // sort on every frame while keeping deterministic ordering.
            if (g.pendingFrames.size() > 1) {
                const auto &last = g.pendingFrames.back();
                const auto &prev = g.pendingFrames[g.pendingFrames.size() - 2];
                if (last.frameId < prev.frameId) {
                    std::stable_sort(g.pendingFrames.begin(), g.pendingFrames.end(),
                        [](const State::PendingFrame& a, const State::PendingFrame& b) {
                            return a.frameId < b.frameId;
                        });
                }
            }
            pendingFrame = g.pendingFrames.front();
            g.pendingFrames.pop_front();
        }
        const auto frameWorkStartedAt = State::Clock::now();
        prof.queueNs += std::chrono::duration_cast<std::chrono::nanoseconds>(
            frameWorkStartedAt - pendingFrame.queuedAt).count();
        int64_t blitWorkNsThisFrame = 0;

        // One presentation path for every frame type. Nothing reaches
        // blitOutputToWindow() directly anymore: REAL and GENERATED frames are
        // first inserted into Final Frame Queue, sorted by source frameId +
        // ordinal, then every item takes the exact same:
        // Image Enhancement -> Upscale -> Swapchain path.
        auto drainFinalFrameQueue = [&]() -> int {
            int postedCount = 0;
            while (!g.finalFrames.empty()) {
                State::FinalFrame frame = g.finalFrames.front();
                g.finalFrames.pop_front();

                const auto t0 = State::Clock::now();
                const bool posted =
                    blitOutputToWindow(frame.image, frame.completionSemaphore);
                const auto t1 = State::Clock::now();
                blitWorkNsThisFrame += std::chrono::duration_cast<
                    std::chrono::nanoseconds>(t1 - t0).count();

                if (posted) {
                    ++postedCount;
                    if (frame.generated) {
                        if (frame.generatedFrameId != 0)
                            g.lastPresentedGeneratedFrameId =
                                frame.generatedFrameId;
                    }
                    const uint64_t postTimeNs = static_cast<uint64_t>(
                        std::chrono::duration_cast<std::chrono::nanoseconds>(
                            t1.time_since_epoch()).count());
                    if (frame.captureTimestampNs > 0 &&
                        postTimeNs > static_cast<uint64_t>(frame.captureTimestampNs)) {
                        prof.captureToDisplayNs += static_cast<int64_t>(
                            postTimeNs -
                            static_cast<uint64_t>(frame.captureTimestampNs));
                        prof.blitCount++;
                    }
                }

                if (frame.generated && posted)
                    g.generatedFrames.fetch_add(1, std::memory_order_relaxed);

                LOGD("FinalFrameQueue: %s sourceId=%llu ordinal=%u generatedId=%llu posted=%d",
                     frame.generated ? "GEN" : "REAL",
                     static_cast<unsigned long long>(frame.sourceFrameId),
                     frame.ordinal,
                     static_cast<unsigned long long>(frame.generatedFrameId),
                     posted ? 1 : 0);
            }
            return postedCount;
        };
        AHardwareBuffer *ahb = pendingFrame.ahb;
        if (ahb == nullptr) continue;
        const uint64_t jobFrameId = pendingFrame.frameId;

        // waitForBusyGeneration is the only place that waits: when enabled,
        // the next REAL frame is not allowed to reach the display until the
        // previous pair's generation has completed. Disabled, this never
        // blocks and late generation is discarded (see the epoch check below).
        if (g.waitForBusyGeneration.load(std::memory_order_relaxed) &&
            g.cpuFallbackGenInFlight.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> genLock(g.genMu);
            g.genDoneCv.wait(genLock, [] {
                return g.genStopRequested ||
                       !g.cpuFallbackGenInFlight.load(std::memory_order_acquire);
            });
            if (g.stopRequested) {
                AHardwareBuffer_release(ahb);
                return;
            }
        }

        reapFramegenCompletionTickets();
        if (g.framegenCompletionTickets.empty() &&
            !g.cpuFallbackGenInFlight.load(std::memory_order_acquire)) {
            g.framegenInputsInFlight.store(false, std::memory_order_release);
        }

        // Import the original AHB for GPU access. The imported image is retained
        // in the cache and passed through without copying its pixels.
        // AHB import cache: MediaProjection rotates a small pool (2-4) of AHBs.
        // Reuse the cached VkImage+VkDeviceMemory instead of allocating per frame.
        AhbImage src{};
        bool srcFromCache = false;
        {
            auto it = g.ahbImportCache.find(ahb);
            if (it != g.ahbImportCache.end()) {
                src = it->second;
                srcFromCache = true;
            }
        }
        if (!srcFromCache) {
            const int rc = importAhbImage(g.vk, ahb, src);
            if (rc != kOk) {
                LOGW("importAhbImage failed rc=%d", rc);
                AHardwareBuffer_release(ahb); // release queue-enqueue ref
                continue;
            }
            // Cache the import; acquire an extra ref so the cache outlives the frame.
            AHardwareBuffer_acquire(ahb);
            if (g.ahbImportCache.size() >= State::kAhbCacheMax) {
                auto oldest = g.ahbImportCache.begin();
                destroyAhbImage(g.vk, oldest->second);
                AHardwareBuffer_release(oldest->first);
                g.ahbImportCache.erase(oldest);
            }
            g.ahbImportCache[ahb] = src;
        }

        const bool pairingResetThisFrame =
            g.pairingResetPending.exchange(false, std::memory_order_acq_rel);

        // Zero-copy pairing: keep the two original MediaProjection AHardwareBuffers.
        // They are imported once and passed directly to LSFG; no pixel copy is performed.
        if (g.realFrameCount == 0 || pairingResetThisFrame) {
            g.zeroCopyPreviousAhb = ahb;
        } else {
            g.zeroCopyPreviousAhb = g.zeroCopyCurrentAhb;
        }
        g.zeroCopyCurrentAhb = ahb;

        g.realFrameCount++;
        const bool firstRealFrame = (g.realFrameCount == 1);

        // Cache owns the AhbImage (both hit and miss paths); only release
        // the per-frame AHB ref that was acquired in pushFrame.
        if (srcFromCache) {
            g.cacheHits.fetch_add(1, std::memory_order_relaxed);
        } else {
            g.cacheMisses.fetch_add(1, std::memory_order_relaxed);
        }
        AHardwareBuffer_release(ahb); // release queue-enqueue ref

        // PROFILE: zero-copy input import phase done.
        const auto tCopyDone = State::Clock::now();

        // Chronological output transaction:
        //   REAL(previous), GEN(previous,current)..., REAL(current)
        //
        // The previous implementation posted REAL(current) before the
        // interpolation for (previous,current), yielding:
        //   REAL(previous), REAL(current), GEN(previous,current)...
        // Keep the current REAL pending until this pair's generated outputs
        // have been published (or generation is skipped/invalidated).
        auto enqueueCurrentReal = [&]() {
            const AhbImage &realImage = src;
            enqueueFinalFrame(
                realImage,
                pendingFrame.frameId,
                std::numeric_limits<uint32_t>::max(),
                false, 0, VK_NULL_HANDLE,
                pendingFrame.captureTimestampNs);
        };

        // AI backend takes priority over the LSFG shader path when it's
        // loaded and active — they're mutually exclusive per session
        // (initRenderLoop only stands up one or the other; see below).
        // Generation admission is gated independently by allowGenerationWhenBusy:
        //   true  = every valid REAL pair is generated, even while another
        //           generation is still in flight (lossless, matches the old
        //           WAIT_GENERATION admission behavior).
        //   false = skip a pair while another generation is busy (matches the
        //           old NO_WAIT admission behavior).
        //
        // The previous pixel-cost/queue-pressure controller duplicated the
        // scheduler and could silently bypass frames, so this stays a hard,
        // explicit contract rather than a best-effort latency heuristic.
        // BypassGen: if this REAL frame is already older than the user's
        // BypassGen deadline, do not start a GPU generation job just to throw
        // the result away later. Enter a temporary REAL-only window instead.
        // This is intentionally evaluated in RenderLoop (never Capture), so
        // capture remains a dumb REAL-frame producer.
        const uint64_t nowNsForBypass = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                State::Clock::now().time_since_epoch()).count());
        const int bypassDeadlineMs = g.bypassGenDeadlineMs.load(std::memory_order_relaxed);
        bool bypassGenActive = g.bypassGenActive.load(std::memory_order_acquire);
        bool bypassGenResumedThisFrame = false;
        if (bypassGenActive) {
            const uint64_t untilNs = g.bypassGenUntilNs.load(std::memory_order_acquire);
            if (untilNs != 0 && nowNsForBypass >= untilNs) {
                if (g.bypassGenActive.exchange(false, std::memory_order_acq_rel)) {
                    g.bypassGenUntilNs.store(0, std::memory_order_release);
                    // Resume from a fresh anchor. The current REAL is shown
                    // normally; the next REAL becomes the first generation pair.
                    g.pairingResetPending.store(true, std::memory_order_release);
                    bypassGenResumedThisFrame = true;
                    LOGI("BypassGen: resume generation after delay=%dms",
                         g.bypassGenResumeDelayMs.load(std::memory_order_relaxed));
                }
                bypassGenActive = false;
            }
        }
        if (!bypassGenActive && bypassDeadlineMs > 0 &&
            pendingFrame.captureTimestampNs > 0 &&
            nowNsForBypass > pendingFrame.captureTimestampNs +
                static_cast<uint64_t>(bypassDeadlineMs) * 1'000'000ULL) {
            const int resumeDelayMs = g.bypassGenResumeDelayMs.load(std::memory_order_relaxed);
            g.bypassGenActive.store(true, std::memory_order_release);
            g.bypassGenUntilNs.store(
                nowNsForBypass + static_cast<uint64_t>(std::max(0, resumeDelayMs)) * 1'000'000ULL,
                std::memory_order_release);
            g.pairingResetPending.store(true, std::memory_order_release);
            bypassGenActive = true;
            LOGI("BypassGen: entered deadline bypass frameId=%llu ageMs=%.2f deadlineMs=%d resumeDelayMs=%d",
                 static_cast<unsigned long long>(pendingFrame.frameId),
                 pendingFrame.captureTimestampNs > 0
                     ? static_cast<double>(nowNsForBypass - pendingFrame.captureTimestampNs) / 1'000'000.0 : 0.0,
                 bypassDeadlineMs, resumeDelayMs);
        }

        const bool framegenInputsBusy =
            g.framegenInputsInFlight.load(std::memory_order_acquire);
        // Retained for the optional AI backend, whose ncnn path still consumes
        // the preallocated input-slot images. The LSFG path itself is zero-copy.
        const int newSlot = (g.presentsDone % 2 == 0) ? 0 : 1;
        const int oldSlot = 1 - newSlot;

        const bool generationAllowed =
            (jobFrameId != 0) &&
            !firstRealFrame &&
            !g.bypass.load(std::memory_order_relaxed) &&
            !bypassGenActive &&
            !bypassGenResumedThisFrame &&
            !pairingResetThisFrame &&
            (!framegenInputsBusy || g.allowGenerationWhenBusy.load(std::memory_order_relaxed));

        const bool runAi = g.aiLoaded && generationAllowed;
        const bool runFramegen = !runAi
                                 && g.framegenCtxId >= 0
                                 && generationAllowed
                                 && !g.framegenAutoDisabled.load(std::memory_order_relaxed);

        if (runFramegen || runAi) {
            // Cross-device GPU->GPU completion semaphores have been removed
            // system-wide: every device now synchronizes generation
            // completion via a CPU-side LSFG_*::waitIdle() on genWorkerThread
            // instead (see the useCpuWaitIdleFallback branch below). That
            // path already runs off the capture/present hot path, so this
            // costs nothing extra, and it removes the driver-dependent
            // failure mode where a rejected semaphore export used to latch
            // generation off for the rest of the process (crossDeviceSyncDisabled,
            // now deleted) or where devices lacking the capability outright
            // (Mali and others advertising VK_KHR_external_semaphore_fd for
            // SYNC_FD only, which framegen's fd-import path can't consume)
            // never generated at all.
            std::vector<VkSemaphore> framegenDoneSems;
            std::vector<int> framegenDoneFds;
            const bool useCpuWaitIdleFallback = runFramegen;
            if (runFramegen) {
                g.framegenInputsInFlight.store(true, std::memory_order_release);
                try {
                    // Empty outSem in the fallback case tells framegen there's
                    // nothing to signal; we synchronize below via waitIdle()
                    // instead.
                    if (g.zeroCopyCurrentAhb == nullptr || g.zeroCopyPreviousAhb == nullptr)
                        throw std::runtime_error("Zero-copy input pair is incomplete");
                    if (g.performanceMode)
                        LSFG_3_1P::presentContextAHB(g.framegenCtxId, g.zeroCopyCurrentAhb, g.zeroCopyPreviousAhb, -1, framegenDoneFds);
                    else
                        LSFG_3_1::presentContextAHB(g.framegenCtxId, g.zeroCopyCurrentAhb, g.zeroCopyPreviousAhb, -1, framegenDoneFds);
                } catch (const std::exception &e) {
                    g.framegenInputsInFlight.store(false, std::memory_order_release);
                    handleFramegenException("presentContext", e);
                    // Imported FDs are owned/closed by framegen. Keep the host
                    // semaphore handles alive until session teardown on failure.
                    for (VkSemaphore sem : framegenDoneSems) {
                        if (sem != VK_NULL_HANDLE)
                            g.framegenCompletionTickets.push_back({sem, VK_NULL_HANDLE});
                    }
                    enqueueCurrentReal();
                drainFinalFrameQueue();
                    continue;
                }
            }
            // PROFILE: presentContext returned (CPU-side; the GPU work is
            // still pending on framegen's queue). For the AI backend this
            // timestamp doesn't mean much on its own — the actual ncnn
            // compute happens below and lands in the waitIdle bucket — but
            // splitting it out isn't worth a second profiling code path.
            const auto tPresentDone = State::Clock::now();

            if (runFramegen) {
                // Framegen's internal frameIdx already advanced the instant
                // presentContext() returned — that's what determines which
                // inSlot is "current" next time, not when the GPU work (or
                // our wait for it) finishes. Advance our mirror here, in
                // lockstep with the call, instead of after the blit/wait
                // below. This also keeps slot bookkeeping correct when the
                // CPU-fallback branch below hands the wait off to a
                // background thread: the next loop iteration must see the
                // updated parity immediately, not whenever that thread gets
                // scheduled.
                g.presentsDone++;
            }

            if (useCpuWaitIdleFallback) {
                // No exportable semaphore was signaled, so ordinarily we'd
                // block here on framegen's own device until this present's
                // GPU work is done. That block used to happen inline on this
                // worker thread — the SAME thread that dequeues and posts
                // real captured frames. A slow waitIdle() (weak GPU, thermal
                // throttling, a heavy multiplier) stalled real-frame
                // ingestion for its entire duration: captures piled up in
                // g.pendingFrames and came out the other side later than
                // they needed to, even though "real frame first" already
                // posts the current capture before this point.
                //
                // Hand the wait (and the generated-output blit that depends
                // on it) off to the persistent genWaitThread instead of
                // doing it inline, so this worker returns to
                // g.pendingCv.wait() immediately and keeps servicing
                // captures at full rate. Only one such job may be in flight
                // at a time — cpuFallbackGenInFlight blocks a new
                // presentContext() from starting until this one's outputs
                // have been consumed, since framegen reuses those output
                // buffers in place and a second present would race the GPU
                // write against our read. That gate also means genWaitThread
                // is always idle (parked on genCv) by the time we get here,
                // so this is just a quick handoff, not a wait.
                g.cpuFallbackGenInFlight.store(true, std::memory_order_release);
                {
                    std::lock_guard<std::mutex> genLock(g.genMu);
                    g.genJobPerfMode = g.performanceMode;
                    g.genJobFrameId = pendingFrame.frameId;
                    const int deadlineMs = g.generationDeadlineMs.load(std::memory_order_relaxed);
                    g.genJobDeadlineNs = (deadlineMs > 0 && pendingFrame.captureTimestampNs > 0)
                        ? pendingFrame.captureTimestampNs + static_cast<uint64_t>(deadlineMs) * 1'000'000ULL
                        : 0;
                    // MUST be true: this CPU-waitIdle branch is now the ONLY
                    // path a LSFG-generated frame ever takes (see the
                    // useCpuWaitIdleFallback comment above — the old
                    // exportable-semaphore publish path it used to defer to
                    // was removed system-wide). Leaving this false — as it
                    // was when this branch really was a last-resort fallback
                    // with a faster alternative to defer to — silently
                    // discarded every generated frame in genWorkerThread's
                    // generationStillValid check (publishOutputs is one of
                    // its ANDed conditions), so LSFG never actually
                    // generated a frame; only REAL frames ever reached the
                    // screen regardless of the configured multiplier.
                    // genWorkerThread's own jobFrameId==currentEpoch/bypass/
                    // stopRequested checks already guard against publishing
                    // a stale frame, so this just needs to say "yes, publish
                    // it when it's still valid" like every other dispatch.
                    g.genJobPublish = true;
                    g.genJobPending = true;
                }
                g.genCv.notify_one();

                // STRICT PRESENT ORDER:
                // The pair represented by this iteration is:
                //   REAL(previous) -> GEN(previous,current) -> REAL(current)
                //
                // The generation itself may wait on genWaitThread, but the
                // CURRENT real frame must NOT be posted until that generation
                // has completed. Otherwise the asynchronous fallback produces
                // the exact bad sequence we are trying to prevent:
                //   REAL(previous) -> REAL(current) -> GEN(previous,current).
                //
                // Capture ingestion remains asynchronous (new captures can
                // accumulate in pendingFrames), but presentation is serialized
                // at this point so GEN can never overtake its right-hand REAL.
                {
                    std::unique_lock<std::mutex> genLock(g.genMu);
                    g.genDoneCv.wait(genLock, [&] {
                        return g.genStopRequested ||
                               g.genCompletedFrameId >= pendingFrame.frameId ||
                               !g.cpuFallbackGenInFlight.load(std::memory_order_acquire);
                    });
                }

                const bool generationCompleted =
                    g.genCompletedFrameId >= pendingFrame.frameId &&
                    !g.bypass.load(std::memory_order_relaxed) &&
                    !g.stopRequested;

                if (generationCompleted &&
                    g.genReady.exchange(false, std::memory_order_acq_rel) &&
                    g.genReadyFrameId.load(std::memory_order_acquire) == pendingFrame.frameId) {
                    g.generationSourceFrameId = pendingFrame.frameId;
                    if (g.outputFrameIds.size() != g.outputs.size())
                        g.outputFrameIds.resize(g.outputs.size(), 0);
                    if (g.outputFrameOrdinals.size() != g.outputs.size())
                        g.outputFrameOrdinals.resize(g.outputs.size());
                    for (size_t i = 0; i < g.outputs.size(); ++i) {
                        g.outputFrameOrdinals[i] = static_cast<uint32_t>(i + 1);
                        g.outputFrameIds[i] = g.nextGeneratedFrameId++;
                        enqueueFinalFrame(
                            g.outputs[i],
                            pendingFrame.frameId,
                            g.outputFrameOrdinals[i],
                            true,
                            g.outputFrameIds[i],
                            VK_NULL_HANDLE,
                            pendingFrame.captureTimestampNs);
                    }
                } else {
                    // Generation failed/cancelled. REAL still advances the
                    // presentation sequence; never display a stale GEN.
                    g.genReady.store(false, std::memory_order_release);
                }

                // Only after the GEN slot for this pair has been consumed (or
                // explicitly discarded) may the right-hand REAL frame appear.
                enqueueCurrentReal();
                drainFinalFrameQueue();
                continue;
            }
            // runFramegen always took the CPU waitIdle branch above and
            // continued early (useCpuWaitIdleFallback is unconditional now),
            // so only the AI path reaches this point.
            const auto tWaitIdleDone = State::Clock::now();
            if (!runFramegen) {
#ifdef LSFG_HAVE_NCNN
                // AI path: the model inference itself is Vulkan/GPU-only.
                // The bundled ncnn bridge still uses host staging buffers to
                // exchange RGBA8 frames with AHardwareBuffer.
                // oldSlot/newSlot mirror the ping-pong bookkeeping just above
                // (newSlot = the capture just copied in this iteration).
                const int oldSlot = 1 - newSlot;
                if (!runAiInterpolate(oldSlot, newSlot,
                                       g.inSlot[0].extent.width, g.inSlot[0].extent.height)) {
                    LOGE("AI backend: runAiInterpolate failed for this frame — preserving REAL frame");
                    enqueueCurrentReal();
                drainFinalFrameQueue();
                    continue;
                }
#else
                // Unreachable: runAi is always false when built without ncnn.
                enqueueCurrentReal();
                drainFinalFrameQueue();
                continue;
#endif
            }

            // Outputs are consumed asynchronously by the host GPU queue.
            // Note: a previous revision auto-disabled framegen here when the
            // GPU pipeline was slower than the source cadence. Removed by
            // design — if the user picks heavy settings (flowScale=1.0 +
            // high multiplier on a weak device) it's their call to live with
            // the resulting frame rate. Silently flipping to passthrough
            // looked like a bug ("framegen stopped after 5s"). The bottom-of-
            // pipeline DEVICE_LOST fallback in presentContext above still
            // protects against unrecoverable driver errors.
            // Generation must still belong to the newest REAL capture at the
            // moment its outputs are about to be published. If a newer capture
            // arrived while presentContext/AI ran, invalidate this output and
            // keep the newer REAL frame as the visible state.
            const uint64_t generationFrameId = pendingFrame.frameId;
            // Presentation order is authoritative here. Once this REAL has
            // been dequeued, its GEN belongs to this pair even if newer
            // captures arrived while the GPU/AI work was running. Newer
            // captures stay queued and are presented only after this pair
            // (REAL -> GEN -> REAL) is complete.
            const bool generationStillValid =
                generationFrameId != 0 &&
                !g.bypass.load(std::memory_order_relaxed) &&
                !g.stopRequested;

            const int deadlineMsNow = g.generationDeadlineMs.load(std::memory_order_relaxed);
            const uint64_t nowForDeadlineNs = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                    State::Clock::now().time_since_epoch()).count());
            const bool generationWithinDeadline =
                deadlineMsNow <= 0 || pendingFrame.captureTimestampNs == 0 ||
                nowForDeadlineNs <= pendingFrame.captureTimestampNs +
                    static_cast<uint64_t>(deadlineMsNow) * 1'000'000ULL;

            if (!generationStillValid || !generationWithinDeadline) {
                if (!generationWithinDeadline) {
                    LOGI("workerThread: generation missed deadline frameId=%llu deadlineMs=%d",
                         static_cast<unsigned long long>(generationFrameId), deadlineMsNow);
                }
                // REAL goes first. Any semaphore-drain wait is queued only
                // after the REAL blit, so stale GEN completion cannot block it.
                enqueueCurrentReal();
                drainFinalFrameQueue();
                for (VkSemaphore sem : framegenDoneSems) {
                    if (sem != VK_NULL_HANDLE)
                        drainFramegenCompletionSemaphore(sem);
                }
                LOGI("workerThread: stale generation suppressed jobFrameId=%llu currentEpoch=%llu bypass=%d",
                     static_cast<unsigned long long>(generationFrameId),
                     static_cast<unsigned long long>(
                         g.latestFrameId.load(std::memory_order_relaxed)),
                     g.bypass.load(std::memory_order_relaxed) ? 1 : 0);
                continue;
            }

            // The current REAL frame is the hard end boundary for this pair.
            // Generation was admitted only after the preflight proved it can
            // finish inside the measured source-frame interval. If the runtime
            // invalidated the job anyway, publish REAL immediately and discard
            // GEN; never reorder a generated frame around a REAL frame.
            //
            // Assign fresh, monotonically increasing IDs to every generated
            // frame in this pair. IDs are assigned in presentation order, not
            // completion order, so x3 is always GEN#A, GEN#B between the two
            // REAL frames; x4..x8 follow the same rule with N-1 outputs.
            g.generationSourceFrameId = generationFrameId;
            if (g.outputFrameIds.size() != g.outputs.size())
                g.outputFrameIds.resize(g.outputs.size(), 0);
            if (g.outputFrameOrdinals.size() != g.outputs.size())
                g.outputFrameOrdinals.resize(g.outputs.size());
            for (size_t i = 0; i < g.outputs.size(); ++i) {
                g.outputFrameOrdinals[i] = static_cast<uint32_t>(i + 1);
                g.outputFrameIds[i] = g.nextGeneratedFrameId++;
            }
            for (size_t outputIndex = 0; outputIndex < g.outputs.size(); ++outputIndex) {
                const uint64_t generatedFrameId = g.outputFrameIds[outputIndex];
                const uint32_t generatedOrdinal = g.outputFrameOrdinals[outputIndex];
                if (generatedFrameId == 0 ||
                    (outputIndex > 0 && generatedFrameId <= g.outputFrameIds[outputIndex - 1])) {
                    LOGE("workerThread: generated frame ID/order violation pair=%llu index=%zu id=%llu",
                         static_cast<unsigned long long>(generationFrameId), outputIndex,
                         static_cast<unsigned long long>(generatedFrameId));
                    continue;
                }
                const VkSemaphore done = outputIndex < framegenDoneSems.size()
                    ? framegenDoneSems[outputIndex] : VK_NULL_HANDLE;
                enqueueFinalFrame(
                    g.outputs[outputIndex],
                    generationFrameId,
                    generatedOrdinal,
                    true,
                    generatedFrameId,
                    done,
                    pendingFrame.captureTimestampNs);
            }
            enqueueCurrentReal();
            drainFinalFrameQueue();

            // Only the AI path still needs this here — runFramegen already
            // advanced g.presentsDone right after presentContext() above
            // (and the CPU-fallback branch continue's before ever reaching
            // this line), so incrementing it again here would double-count.
            if (!runFramegen) g.presentsDone++;

            // PROFILE: accumulate this frame's segments and emit a summary
            // every kProfileWindow frames. Numbers are averages over the
            // completed window.
            const auto tFrameEnd = State::Clock::now();
            using ns = std::chrono::nanoseconds;

            // Policy: stay on the little/efficiency cluster only. Unlike the
            // previous big-cores-first policy, there is no overload escape
            // hatch here — this worker (and ncnn's host-side threads) never
            // schedule onto the big/performance cluster, even if the
            // CPU-side portion of the frame exceeds the 60 Hz budget below.
            // This is intentionally a one-way trade of headroom for staying
            // off the big cluster; the number is only logged for visibility.
            const int64_t cpuHotPathNs =
                std::chrono::duration_cast<ns>(tCopyDone - frameWorkStartedAt).count() +
                std::chrono::duration_cast<ns>(tPresentDone - tCopyDone).count() +
                blitWorkNsThisFrame;
            constexpr int64_t kCpuBudgetNs = 4'000'000;
            if (cpuHotPathNs > kCpuBudgetNs) {
                LOGI("CPU scheduler: little-core CPU work over budget (%.2f ms); "
                     "staying on little cores by policy",
                     cpuHotPathNs / 1'000'000.0);
            }

            prof.copyNs     += std::chrono::duration_cast<ns>(tCopyDone     - frameWorkStartedAt).count();
            prof.presentNs  += std::chrono::duration_cast<ns>(tPresentDone  - tCopyDone).count();
            prof.waitIdleNs += std::chrono::duration_cast<ns>(tWaitIdleDone - tPresentDone).count();
            prof.blitNs     += blitWorkNsThisFrame;
            prof.totalNs    += std::chrono::duration_cast<ns>(tFrameEnd - frameWorkStartedAt).count();
            prof.samples++;
            if (prof.samples >= kProfileWindow) {
                const double n = static_cast<double>(prof.samples);
                const double avgWaitIdleMs = (prof.waitIdleNs / n) / 1'000'000.0;
                const double avgWallEndMs  = (prof.totalNs    / n) / 1'000'000.0;
                const double avgQueueMs    = (prof.queueNs    / n) / 1'000'000.0;
                const double avgLatencyMs  = prof.blitCount > 0 ? (prof.captureToDisplayNs / static_cast<double>(prof.blitCount)) / 1'000'000.0 : 0.0;
                const uint64_t hits = g.cacheHits.exchange(0, std::memory_order_relaxed);
                const uint64_t misses = g.cacheMisses.exchange(0, std::memory_order_relaxed);
                LOGW("frame profile (avg over %u): copy=%.2fms present=%.2fms waitIdle=%.2fms blitWork=%.2fms wallEnd=%.2fms queue=%.2fms latency=%.2fms cache_hits=%llu cache_misses=%llu (gen=%d extra)",
                     prof.samples,
                     (prof.copyNs / n)     / 1'000'000.0,
                     (prof.presentNs / n)  / 1'000'000.0,
                     avgWaitIdleMs,
                     (prof.blitNs / n)     / 1'000'000.0,
                     avgWallEndMs,
                     avgQueueMs,
                     avgLatencyMs,
                     static_cast<unsigned long long>(hits),
                     static_cast<unsigned long long>(misses),
                     g.multiplier);
                // Publish the closed profiling window for the UI / diagnostics.
                g.profileSnapshotCopyNs.store(prof.copyNs, std::memory_order_relaxed);
                g.profileSnapshotPresentNs.store(prof.presentNs, std::memory_order_relaxed);
                g.profileSnapshotWaitIdleNs.store(prof.waitIdleNs, std::memory_order_relaxed);
                g.profileSnapshotBlitNs.store(prof.blitNs, std::memory_order_relaxed);
                g.profileSnapshotTotalNs.store(prof.totalNs, std::memory_order_relaxed);
                g.profileSnapshotQueueNs.store(prof.queueNs, std::memory_order_relaxed);
                g.profileSnapshotLatencyNs.store(prof.captureToDisplayNs, std::memory_order_relaxed);
                g.profileSnapshotBlitCount.store(prof.blitCount, std::memory_order_relaxed);
                g.profileSnapshotSamples.store(prof.samples, std::memory_order_relaxed);
                prof = ProfileAccum{};
            }
        } else {
            // No interpolation was admitted for this capture. Frame Time Sync
            // may pace the REAL output, but never drops or waits on GEN.
            enqueueCurrentReal();
            drainFinalFrameQueue();
        }

    }
}

} // namespace

int initRenderLoop(const char *cacheDir, const RenderLoopConfig &cfg) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (g.initialized) return kRenderLoopAlreadyInit;

    // multiplier in the prefs is the *total* output rate factor (2x = 60fps from 30fps);
    // framegen's generationCount is how many *extra* frames to interpolate per input
    // pair. So generationCount = multiplier - 1, and we allocate that many output AHBs.
    // (Mirrors lsfg-vk-android/src/context.cpp:80-81,101.)
    const int totalMult = cfg.multiplier > 1 ? cfg.multiplier : 2;
    g.multiplier = totalMult - 1;  // generationCount = N extra frames per pair
    g.performanceMode = cfg.performance;
    g.framegenFp16 = cfg.framegenFp16;
    g.hdr = cfg.hdr;
    // flowScale on the prefs slider is "0.25..1.0" in user-friendly form, but
    // framegen wants the reciprocal (Linux passes 1.0f / conf.flowScale at
    // src/context.cpp:101). Larger user value = finer flow grid = better quality.
    const float userFlow = (cfg.flowScale >= 0.25f && cfg.flowScale <= 1.0f) ? cfg.flowScale : 1.0f;
    g.flowScale = 1.0f / userFlow;
    g.generatedFrames.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> genLock(g.genMu);
        g.genCompletedFrameId = 0;
        g.genJobPending = false;
        g.genJobPublish = true;
    }
    g.postedFrames.store(0, std::memory_order_relaxed);
    g.uniqueCaptures.store(0, std::memory_order_relaxed);
    g.postRingHead.store(0, std::memory_order_relaxed);
    for (auto &slot : g.postRingTimestamps) {
        slot.store(0, std::memory_order_relaxed);
    }
    g.captureRingHead.store(0, std::memory_order_relaxed);
    for (auto &slot : g.captureRingTimestamps) {
        slot.store(0, std::memory_order_relaxed);
    }
    g.pushLogCount.store(0, std::memory_order_relaxed);
    g.blitLogCount.store(0, std::memory_order_relaxed);
    g.lumaGateOpen      = false;
    g.lumaGateDarkCount = 0;
    g.lumaGateStartNs   = 0;
    g.realFrameCount = 0;
    g.presentsDone = 0;
    g.generationSourceFrameId = 0;
    g.lastPresentedGeneratedFrameId = 0;
    g.nextGeneratedFrameId = 1;
    std::fill(g.outputFrameIds.begin(), g.outputFrameIds.end(), 0);
    g.latestFrameId.store(0, std::memory_order_relaxed);
    g.pairingResetPending.store(false, std::memory_order_relaxed);
    g.lastProcessedCaptureTimestampNs.store(0, std::memory_order_relaxed);
    // Don't reset g.bypass — the user toggle should persist across re-inits
    // (e.g. when they change multiplier while bypass is on, the new context
    // should also start in bypass).
    // DO reset the framegen auto-disable latch — that flag tracks a driver
    // error tied to the previous device handle, which is being recreated here.
    // Carrying it forward would silently keep framegen off forever after one
    // bad submit, even on a healthy new device.
    g.framegenAutoDisabled.store(false, std::memory_order_relaxed);

    int rc = create_session(g.vk);
    if (rc != kOk) {
        LOGE("create_session failed rc=%d", rc);
        reapFramegenCompletionTickets();
        destroyFramegenCompletionTickets();
        destroy_session(g.vk);
        return kRenderLoopSessionFailed;
    }

    const uint32_t renderW = cfg.width;
    const uint32_t renderH = cfg.height;

    const VkFormat fmt = VK_FORMAT_R8G8B8A8_UNORM;
    for (int i = 0; i < 2; ++i) {
        rc = createAhbImage(g.vk, renderW, renderH, fmt, g.inSlot[i]);
        if (rc != kOk) {
            LOGE("createAhbImage(input %d) failed rc=%d", i, rc);
            shutdownRenderLoop();
            return kRenderLoopBufferAlloc;
        }
        initInSlotImageLayout(g.inSlot[i].image);
    }
    // Number of outputs = generationCount, matching framegen "level".
    g.outputs.resize(g.multiplier);
    g.outputFrameIds.assign(static_cast<size_t>(g.multiplier), 0);
    g.outputFrameOrdinals.resize(static_cast<size_t>(g.multiplier));
    for (size_t i = 0; i < g.outputFrameOrdinals.size(); ++i) {
        g.outputFrameOrdinals[i] = static_cast<uint32_t>(i + 1);
    }
    g.generationSourceFrameId = 0;
    g.lastPresentedGeneratedFrameId = 0;
    g.nextGeneratedFrameId = 1;
    for (int i = 0; i < g.multiplier; ++i) {
        rc = createAhbImage(g.vk, renderW, renderH, fmt, g.outputs[i]);
        if (rc != kOk) {
            LOGE("createAhbImage(output %d) failed rc=%d", i, rc);
            shutdownRenderLoop();
            return kRenderLoopBufferAlloc;
        }
    }

    g.aiRequested = cfg.aiBackend;
    g.aiLoaded = false;
    g.aiEngine = cfg.aiEngine;
    if (cfg.aiBackend) {
#ifdef LSFG_HAVE_NCNN
        // AI backend replaces the LSFG shader chain entirely for this
        // session — skip initFramegen/createFramegenContext so we don't pay
        // for shader compilation we won't use, and so g.framegenCtxId stays
        // -1 (workerThread's runFramegen check already excludes runAi, but
        // this also keeps the "framegen disabled" return code below honest
        // if AI loading fails and there's no LSFG context to fall back to).
        const char *engineName = (cfg.aiEngine == 1) ? "IFRNet" : "RIFE";
        int rc;
        if (cfg.aiEngine == 1) {
            g.aiIfrnet = new IfrnetInterpolator();
            rc = g.aiIfrnet->load(cfg.aiModelDir, true, /*vulkanDeviceIndex*/ -1, ncnnCpuThreadCount());
        } else {
            g.ai = new NcnnInterpolator();
            rc = g.ai->load(cfg.aiModelDir, true, /*vulkanDeviceIndex*/ -1, ncnnCpuThreadCount());
        }
        if (rc == kNcnnOk) {
            g.aiLoaded = true;
            LOGI("AI backend (%s) loaded from %s (GPU-only Vulkan)", engineName, cfg.aiModelDir.c_str());
        } else {
            LOGE("AI backend (%s) GPU/Vulkan load() failed rc=%d (modelDir=%s) — "
                 "AI inference will NOT use CPU; falling back to the LSFG Vulkan shader path",
                 engineName, rc, cfg.aiModelDir.c_str());
            if (cfg.aiEngine == 1) {
                delete g.aiIfrnet;
                g.aiIfrnet = nullptr;
            } else {
                delete g.ai;
                g.ai = nullptr;
            }
        }
#else
        LOGE("AI backend requested but this .so was built without ncnn (LSFG_HAVE_NCNN) — "
             "falling back to LSFG shader path");
#endif
    }

    if (!g.aiLoaded) {
        g.framegenInitOk = initFramegen(cacheDir);
        if (g.framegenInitOk) {
            if (!createFramegenContext()) {
                LOGE("createFramegenContext failed — running in capture-only mode (counter will stay at 0)");
                g.framegenCtxId = -1;
            }
        } else {
            g.framegenCtxId = -1;
        }
    } else {
        g.framegenInitOk = false;
        g.framegenCtxId = -1;
    }

    g.initialized = true;

    g.stopRequested = false;
    g.worker = std::thread(workerThread);
    g.genStopRequested = false;
    g.genJobPending = false;
    g.genJobPublish = true;
    g.genWaitThread = std::thread(genWorkerThread);
    // Do NOT build the swapchain here. At initContext time the overlay's
    // Surface is still owned by the mirror VirtualDisplay producer — it's
    // only detached when setNovaMode() runs (which retargets the VD to the
    // ImageReader). Creating the swapchain before that point races against
    // ANativeWindow's single-producer rule. The first blitOutputToWindow
    // call after LSFG mode is live builds it lazily.
    LOGW("Render loop initialised: capture=%ux%u render=%ux%u totalMult=%dx (gen=%d extra) flowScale=%.2f(internal=%.2f) hdr=%d perf=%d ctxId=%d",
         cfg.width, cfg.height, renderW, renderH, totalMult, g.multiplier,
         userFlow, g.flowScale,
         (int)g.hdr, (int)g.performanceMode, g.framegenCtxId);
    // Tell the caller whether framegen is actually running (either backend).
    // If not, Kotlin will keep the overlay up in mirror mode instead of
    // routing the capture through a dead context.
    return (g.framegenCtxId >= 0 || g.aiLoaded) ? kOk : kRenderLoopFramegenDisabled;
}

void setWaitForBusyGeneration(bool enabled) {
    g.waitForBusyGeneration.store(enabled, std::memory_order_relaxed);
    LOGI("waitForBusyGeneration: %s", enabled ? "true" : "false");
}

void setAllowGenerationWhenBusy(bool enabled) {
    g.allowGenerationWhenBusy.store(enabled, std::memory_order_relaxed);
    LOGI("allowGenerationWhenBusy: %s", enabled ? "true" : "false");
}

void setLosslessQueue(bool enabled) {
    g.losslessQueue.store(enabled, std::memory_order_relaxed);
    LOGI("losslessQueue: %s", enabled ? "true" : "false");
}

void setOutputSurface(ANativeWindow *win, uint32_t w, uint32_t h) {
    // g.presentMu, not g.mu — see blitOutputToWindow()'s lock comment. This
    // call synchronizes with blitOutputToWindow()'s g.outWindow/g.swap
    // access, not with the pending-capture queue.
    std::lock_guard<std::mutex> lock(g.presentMu);
    // Always tear down any prior swapchain first — it holds a VkSurfaceKHR
    // which holds an ANativeWindow reference, and the spec requires the
    // surface outlive the swapchain but be destroyed before the window.
    destroySwapchain();
    if (g.outWindow != nullptr) {
        ANativeWindow_release(g.outWindow);
        g.outWindow = nullptr;
    }
    if (win != nullptr) {
        ANativeWindow_acquire(win);
        g.outWindow = win;
        // These dimensions come from the saved physical/native display size
        // captured before any wm size override. They are the PRESENTATION
        // target, not the current logical display size. Explicitly configure
        // the BufferQueue to use this size before creating the Vulkan surface;
        // otherwise Android may keep a fixed currentExtent matching the
        // temporary low-res wm size (for example 480x1068), and Vulkan will
        // allocate a low-res swapchain even though Kotlin requested 1080x2408.
        g.outWidth = w;
        g.outHeight = h;
        g.swapWinW = w;
        g.swapWinH = h;
        if (w > 0 && h > 0) {
            const int32_t bufferFormat = ANativeWindow_getFormat(win);
            const int32_t geomRc = ANativeWindow_setBuffersGeometry(
                win, static_cast<int32_t>(w), static_cast<int32_t>(h), bufferFormat);
            LOGI("Output buffer geometry requested %ux%u (format=%d) rc=%d",
                 w, h, bufferFormat, geomRc);
        }
        // Fresh ANativeWindow — its BufferQueue producer slot is empty until
        // the first ANativeWindow_lock or vkCreateAndroidSurfaceKHR succeeds.
        // Reset the taint so a new window can take the WSI fast path even if
        // a previous one was poisoned for CPU.
        g.windowCpuProducerLocked = false;
        if (kEnableWsiSwapchain && g.initialized) {
            if (createSwapchain()) {
                LOGW("Output surface attached %ux%u native=%p path=WSI", w, h, static_cast<void *>(win));
            } else {
                LOGW("Output surface attached %ux%u native=%p path=GPU/WSI unavailable", w, h, static_cast<void *>(win));
            }
        } else {
            const char *why = !kEnableWsiSwapchain ? "WSI disabled"
                            : !g.initialized        ? "pre-init"
                                                    : "unknown";
            LOGW("Output surface attached %ux%u native=%p path=GPU/WSI unavailable (%s)",
                 w, h, static_cast<void *>(win), why);
        }
    } else {
        g.outWidth = 0;
        g.outHeight = 0;
        g.swapWinW = 0;
        g.swapWinH = 0;
        LOGW("Output surface detached");
    }
}

void setRecordingSurface(ANativeWindow *win, uint32_t w, uint32_t h) {
    std::lock_guard<std::mutex> lock(g.presentMu);
    if (g.recordWindow != nullptr) {
        // Reclaim the persistent encoder swapchain before releasing its window.
        auto savedSwap = std::move(g.swap);
        g.swap = std::move(g.recordSwap);
        destroySwapchain();
        g.recordSwap = std::move(g.swap);
        g.swap = std::move(savedSwap);
        ANativeWindow_release(g.recordWindow);
        g.recordWindow = nullptr;
    }
    g.recordWidth = 0;
    g.recordHeight = 0;
    if (win != nullptr && w > 0 && h > 0) {
        ANativeWindow_acquire(win);
        g.recordWindow = win;
        g.recordWidth = w;
        g.recordHeight = h;
        LOGI("Recording surface attached %ux%u native=%p", w, h, static_cast<void *>(win));
    } else {
        LOGI("Recording surface detached");
    }
}

void pushFrame(AHardwareBuffer *ahb, int64_t timestampNs, uint64_t frameId) {
    if (ahb == nullptr) return;
    const uint32_t pushLogIndex = g.pushLogCount.load(std::memory_order_relaxed);
    if (pushLogIndex < 30) {
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(ahb, &desc);
        if (g.pushLogCount.fetch_add(1, std::memory_order_relaxed) < 30) {
            LOGW("pushFrame #%u ahb=%ux%u stride=%u fmt=%u usage=0x%llx ts=%lld",
                 pushLogIndex + 1, desc.width, desc.height, desc.stride, desc.format,
                 static_cast<unsigned long long>(desc.usage),
                 static_cast<long long>(timestampNs));
        }
    }

    // Count every arriving capture frame as unique for the HUD's "real fps" metric.
    // We no longer hash pixel content to detect duplicates — the CPU cost of
    // reading back AHardwareBuffer pixels on every frame outweighed the benefit.
    // The OS capture path already throttles delivery to the target app's render
    // rate in practice, so the raw arrival count is a good proxy for real fps.
    g.uniqueCaptures.fetch_add(1, std::memory_order_relaxed);
    {
        const uint64_t nowNs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                State::Clock::now().time_since_epoch()).count());
        const uint64_t slot = g.captureRingHead.fetch_add(1, std::memory_order_relaxed)
                              % State::kPostRingSize;
        g.captureRingTimestamps[slot].store(nowNs, std::memory_order_relaxed);
    }

    AHardwareBuffer_acquire(ahb);
    {
        std::unique_lock<std::mutex> lock(g.mu);
        if (!g.initialized) {
            AHardwareBuffer_release(ahb);
            return;
        }
        if (g.stopRequested) {
            AHardwareBuffer_release(ahb);
            return;
        }
        // Capture is deliberately dumb: retain the buffer and enqueue its
        // real-frame ID. No pacing, generation admission or latency
        // decisions happen in the capture path — but the queue depth itself
        // IS bounded here per maxQueuedRealFrames, or a stalled/slow worker
        // (AI generation falling behind capture, a backgrounded session,
        // etc.) lets pendingFrames grow unbounded: every stale frame in it
        // still gets popped, sorted, and run through the full stale-check/
        // blit path one at a time before the worker can catch up, wasting
        // real generation work on frames nobody will ever see and delaying
        // shutdown behind the backlog.
        g.latestFrameId.store(frameId, std::memory_order_release);
        g.pendingFrames.push_back(State::PendingFrame{
            .ahb = ahb,
            .queuedAt = State::Clock::now(),
            .captureTimestampNs = timestampNs,
            .frameId = frameId,
        });
        // losslessQueue=true: every captured REAL frame stays in the queue
        // until its pair has been processed. losslessQueue=false bounds the
        // queue at maxQueuedRealFrames, since that setting explicitly permits
        // late work to skip.
        if (!g.losslessQueue.load(std::memory_order_relaxed)) {
            const size_t maxQueued = static_cast<size_t>(std::max(
                1, g.maxQueuedRealFrames.load(std::memory_order_relaxed)));
            while (g.pendingFrames.size() > maxQueued) {
                AHardwareBuffer_release(g.pendingFrames.front().ahb);
                g.pendingFrames.pop_front();
            }
        }
    }
    g.pendingCv.notify_one();
}

void shutdownRenderLoop() {
    {
        std::lock_guard<std::mutex> lock(g.mu);
        g.stopRequested = true;
    }
    g.pendingCv.notify_all();
    if (g.worker.joinable()) g.worker.join();
    // genWorkerThread touches g.vk/framegen state via LSFG_*::waitIdle() and
    // blitOutputToWindow(), so it must be stopped and joined before the
    // teardown below runs, or it can use those objects after they're
    // destroyed. `worker` above is already joined, so no new job can be
    // posted to genWaitThread from this point on.
    {
        std::lock_guard<std::mutex> genLock(g.genMu);
        g.genStopRequested = true;
    }
    g.genCv.notify_all();
    if (g.genWaitThread.joinable()) g.genWaitThread.join();
    g.cpuFallbackGenInFlight.store(false, std::memory_order_relaxed);

    {
        std::lock_guard<std::mutex> lock(g.mu);
        for (auto &pending : g.pendingFrames) {
            if (pending.ahb) AHardwareBuffer_release(pending.ahb);
        }
        g.pendingFrames.clear();
        g.finalFrames.clear();

        if (g.framegenCtxId >= 0) {
            try {
                if (g.performanceMode) LSFG_3_1P::deleteContext(g.framegenCtxId);
                else                   LSFG_3_1::deleteContext(g.framegenCtxId);
            } catch (...) {}
            g.framegenCtxId = -1;
        }
        if (g.framegenInitOk) {
            try {
                if (g.performanceMode) LSFG_3_1P::finalize();
                else                   LSFG_3_1::finalize();
            } catch (...) {}
            g.framegenInitOk = false;
        }

#ifdef LSFG_HAVE_NCNN
        if (g.gpuImageEnhancement) {
            g.gpuImageEnhancement->destroy(g.vk);
            g.gpuImageEnhancement.reset();
        }
        if (g.ai != nullptr) {
            g.ai->unload();
            delete g.ai;
            g.ai = nullptr;
        }
        if (g.aiIfrnet != nullptr) {
            g.aiIfrnet->unload();
            delete g.aiIfrnet;
            g.aiIfrnet = nullptr;
        }
#endif
        g.aiLoaded = false;
        g.aiRequested = false;
        g.aiEngine = 0;

        // Clear AHB import cache before any Vulkan teardown.
        for (auto &[cachedAhb, img] : g.ahbImportCache) {
            destroyAhbImage(g.vk, img);
            AHardwareBuffer_release(cachedAhb);
        }
        g.ahbImportCache.clear();

        for (auto &o : g.outputs) destroyAhbImage(g.vk, o);
        g.outputs.clear();
        g.aiInputAStaging.clear();
        g.aiInputCStaging.clear();
        g.aiOutputStaging.clear();
        g.aiOutputPtrs.clear();
        for (int i = 0; i < 2; ++i) destroyAhbImage(g.vk, g.inSlot[i]);

        // Destroy swapchain (and its surface) before the underlying ANativeWindow
        // is touched — surface destruction drops its internal window ref.
        // g.outWindow is intentionally kept live so the worker thread can still
        // blit during the next session's shader-compilation window (before
        // setOutputSurface is called).  setOutputSurface always releases the
        // old reference before acquiring the new one, so there is no leak.
        // Destroy the persistent recorder swapchain before releasing the
        // encoder window / Vulkan session.
        if (g.recordWindow != nullptr) {
            auto savedSwap = std::move(g.swap);
            g.swap = std::move(g.recordSwap);
            destroySwapchain();
            g.recordSwap = std::move(g.swap);
            g.swap = std::move(savedSwap);
            ANativeWindow_release(g.recordWindow);
            g.recordWindow = nullptr;
        }
        g.recordWidth = g.recordHeight = 0;
        destroySwapchain();
        g.swapWinW = 0;
        g.swapWinH = 0;

        reapFramegenCompletionTickets();
        destroyFramegenCompletionTickets();
        destroy_session(g.vk);
        g.initialized = false;
        g.generatedFrames.store(0, std::memory_order_relaxed);
        g.postedFrames.store(0, std::memory_order_relaxed);
        g.uniqueCaptures.store(0, std::memory_order_relaxed);
        g.postRingHead.store(0, std::memory_order_relaxed);
        for (auto &slot : g.postRingTimestamps) {
            slot.store(0, std::memory_order_relaxed);
        }
        g.captureRingHead.store(0, std::memory_order_relaxed);
        for (auto &slot : g.captureRingTimestamps) {
            slot.store(0, std::memory_order_relaxed);
        }
        g.lastProcessedCaptureTimestampNs.store(0, std::memory_order_relaxed);
        g.pairingResetPending.store(false, std::memory_order_relaxed);
    }
    LOGI("Render loop shut down");
}

uint64_t getGeneratedFrameCount() {
    return g.generatedFrames.load(std::memory_order_relaxed);
}

uint64_t getPostedFrameCount() {
    return g.postedFrames.load(std::memory_order_relaxed);
}

uint64_t getUniqueCaptureCount() {
    return g.uniqueCaptures.load(std::memory_order_relaxed);
}

double getAverageQueueMs() {
    const int64_t samples = g.profileSnapshotSamples.load(std::memory_order_relaxed);
    if (samples <= 0) return 0.0;
    const int64_t queueNs = g.profileSnapshotQueueNs.load(std::memory_order_relaxed);
    return (static_cast<double>(queueNs) / samples) / 1'000'000.0;
}

double getAverageLatencyMs() {
    const int64_t blitCount = g.profileSnapshotBlitCount.load(std::memory_order_relaxed);
    if (blitCount <= 0) return 0.0;
    const int64_t latencyNs = g.profileSnapshotLatencyNs.load(std::memory_order_relaxed);
    return (static_cast<double>(latencyNs) / blitCount) / 1'000'000.0;
}

uint32_t getProfileWindowNs(int64_t *out, uint32_t cap) {
    // out layout: [copyNs, presentNs, waitIdleNs, blitNs, totalNs, samples].
    // Each value is the SUM over the last completed kProfileWindow window;
    // divide by samples for per-frame averages. Returns 6 when populated, 0
    // when no window has closed yet (samples==0) or when out is too small.
    if (out == nullptr || cap < 6) return 0;
    const int64_t samples = g.profileSnapshotSamples.load(std::memory_order_relaxed);
    if (samples <= 0) return 0;
    out[0] = g.profileSnapshotCopyNs.load(std::memory_order_relaxed);
    out[1] = g.profileSnapshotPresentNs.load(std::memory_order_relaxed);
    out[2] = g.profileSnapshotWaitIdleNs.load(std::memory_order_relaxed);
    out[3] = g.profileSnapshotBlitNs.load(std::memory_order_relaxed);
    out[4] = g.profileSnapshotTotalNs.load(std::memory_order_relaxed);
    out[5] = samples;
    return 6;
}

uint32_t getRecentPostIntervalsNs(int64_t *outIntervalsNs, uint32_t cap) {
    if (outIntervalsNs == nullptr || cap == 0) return 0;
    // Snapshot the ring head. Entries from (head - kPostRingSize) to (head-1)
    // are populated (older ones are overwritten by wrap-around). For intervals
    // we need consecutive pairs, so we can produce at most min(cap, N-1)
    // where N is how many valid entries are present.
    const uint64_t head = g.postRingHead.load(std::memory_order_acquire);
    if (head < 2) return 0;  // need at least 2 timestamps for one interval
    const uint64_t validEntries = std::min<uint64_t>(head, State::kPostRingSize);
    const uint64_t available = validEntries - 1;
    const uint32_t want = static_cast<uint32_t>(std::min<uint64_t>(cap, available));
    // Walk the ring newest-first: slot (head-1), (head-2), ... subtracting
    // successive pairs to produce intervals. Skip the pair if either half
    // is zero (race with concurrent write during startup).
    uint32_t written = 0;
    uint64_t prevTs = g.postRingTimestamps[(head - 1) % State::kPostRingSize]
                          .load(std::memory_order_relaxed);
    for (uint32_t i = 1; i <= want && written < cap; ++i) {
        const uint64_t slot = (head - 1 - i) % State::kPostRingSize;
        const uint64_t ts = g.postRingTimestamps[slot].load(std::memory_order_relaxed);
        if (ts == 0 || prevTs == 0 || prevTs < ts) {
            // Either a torn read (zero) or non-monotonic (wraparound race):
            // stop and return what we have.
            break;
        }
        outIntervalsNs[written++] = static_cast<int64_t>(prevTs - ts);
        prevTs = ts;
    }
    return written;
}

// Average interval spanning the last `kSampleCount` ring entries (or fewer
// while the ring is still filling), converted to fps. Anchored to actual
// event timestamps rather than a fixed wall-clock polling window, so there's
// no window-aliasing for the caller to smooth out — a short averaging span
// is enough to stay stable frame-to-frame.
float fpsFromRing(const std::atomic<uint64_t> (&ring)[State::kPostRingSize],
                   const std::atomic<uint64_t> &ringHead) {
    constexpr uint64_t kSampleCount = 16;
    const uint64_t head = ringHead.load(std::memory_order_acquire);
    if (head < 2) return 0.0f;
    const uint64_t validEntries = std::min<uint64_t>(head, State::kPostRingSize);
    const uint64_t span = std::min<uint64_t>(kSampleCount, validEntries - 1);
    if (span == 0) return 0.0f;
    const uint64_t newestTs = ring[(head - 1) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    const uint64_t oldestTs = ring[(head - 1 - span) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    // Zero means a torn read during startup; oldest >= newest means a
    // wraparound race. Either way, no usable sample this call — the next
    // poll will have fresh data.
    if (newestTs == 0 || oldestTs == 0 || oldestTs >= newestTs) return 0.0f;
    const double elapsedNs = static_cast<double>(newestTs - oldestTs);
    return static_cast<float>(static_cast<double>(span) * 1'000'000'000.0 / elapsedNs);
}

bool getFpsSnapshot(float *out, uint32_t cap) {
    if (out == nullptr || cap < 2) return false;
    const float realFps = fpsFromRing(g.captureRingTimestamps, g.captureRingHead);
    const float totalFps = fpsFromRing(g.postRingTimestamps, g.postRingHead);
    if (realFps <= 0.0f && totalFps <= 0.0f) return false;
    out[0] = realFps;
    out[1] = totalFps;
    return true;
}

void setImageEnhancement(bool enabled, int backend, float contrast, float saturation) {
    g.imageEnhancementEnabled.store(enabled, std::memory_order_relaxed);
    g.imageEnhancementBackend.store(std::clamp(backend, 0, 1), std::memory_order_relaxed);
    g.imageEnhancementContrast.store(std::clamp(contrast, 0.5f, 1.5f), std::memory_order_relaxed);
    g.imageEnhancementSaturation.store(std::clamp(saturation, 0.0f, 2.0f), std::memory_order_relaxed);
    LOGI("Image enhancement live: enabled=%d backend=%d contrast=%.2f saturation=%.2f",
         enabled ? 1 : 0, std::clamp(backend,0,1),
         std::clamp(contrast,0.5f,1.5f),
         std::clamp(saturation,0.0f,2.0f));
}

void setUpscaleEnabled(bool enabled) {
    g.upscaleEnabled.store(enabled, std::memory_order_relaxed);
    LOGI("Upscale live: enabled=%d", enabled ? 1 : 0);
}

void setUpscaleFilter(int mode) {
    const int clamped = std::clamp(mode, 0, 1);
    g.upscaleFilterMode.store(clamped, std::memory_order_relaxed);
    LOGI("Upscale filter live: mode=%d (%s)", clamped, clamped == 0 ? "NEAREST" : "BILINEAR");
}

void setPresentMode(int32_t mode) {
    // Accepts a raw VkPresentModeKHR value. Only the three modes every
    // Android/Vulkan WSI implementation is expected to expose are honored;
    // anything else (including future/extension modes we haven't wired a
    // UI for) clamps to MAILBOX rather than silently doing nothing.
    VkPresentModeKHR resolved;
    switch (mode) {
        case VK_PRESENT_MODE_IMMEDIATE_KHR:
        case VK_PRESENT_MODE_MAILBOX_KHR:
        case VK_PRESENT_MODE_FIFO_KHR:
            resolved = static_cast<VkPresentModeKHR>(mode);
            break;
        default:
            LOGW("setPresentMode: unrecognized mode %d — defaulting to MAILBOX", mode);
            resolved = VK_PRESENT_MODE_MAILBOX_KHR;
            break;
    }
    const int previous = g.presentModeRequested.exchange(
        static_cast<int>(resolved), std::memory_order_relaxed);
    if (previous != static_cast<int>(resolved)) {
        LOGI("Present mode requested: %d -> %d (applied on next present)",
             previous, static_cast<int>(resolved));
    }
}

void setBypass(bool bypass) {
    // A bypass transition is a hard discontinuity for interpolation. Invalidate
    // every in-flight generation job, keep all REAL captures intact, and force
    // the first REAL frame after resume to become a new pairing anchor.
    const bool previous = g.bypass.exchange(bypass, std::memory_order_acq_rel);
    if (previous != bypass) {
        g.pairingResetPending.store(true, std::memory_order_release);
    } else if (bypass) {
        // Repeated ON calls remain harmless but keep the pairing invalidated.
        g.pairingResetPending.store(true, std::memory_order_release);
    }
    LOGI("Framegen bypass=%d; generation invalidated; pairing reset=%d",
         bypass ? 1 : 0,
         g.pairingResetPending.load(std::memory_order_relaxed) ? 1 : 0);
}

// How many REAL captures may sit queued behind the one currently being
// processed before pushFrame() evicts the oldest ones. 1 reproduces the
// original "queue bounded to one waiting frame" behaviour. Clamped to [1, 8]:
// higher values trade latency/memory for more buffering headroom under
// bursty capture.
void setGenerationDeadlineMs(int deadlineMs) {
    const int clamped = std::clamp(deadlineMs, 0, 100);
    g.generationDeadlineMs.store(clamped, std::memory_order_relaxed);
    LOGI("generationDeadlineMs: %d", clamped);
}

void setBypassGenDeadlineMs(int deadlineMs) {
    const int clamped = std::clamp(deadlineMs, 0, 100);
    g.bypassGenDeadlineMs.store(clamped, std::memory_order_relaxed);
    if (clamped == 0) {
        g.bypassGenActive.store(false, std::memory_order_release);
        g.bypassGenUntilNs.store(0, std::memory_order_release);
    }
    LOGI("BypassGen deadline: %dms", clamped);
}

void setBypassGenResumeDelayMs(int delayMs) {
    const int clamped = std::clamp(delayMs, 0, 2000);
    g.bypassGenResumeDelayMs.store(clamped, std::memory_order_relaxed);
    LOGI("BypassGen resume delay: %dms", clamped);
}

void setMaxQueuedRealFrames(int depth) {
    const int clamped = std::clamp(depth, 1, 8);
    g.maxQueuedRealFrames.store(clamped, std::memory_order_relaxed);
    LOGI("Max queued real frames set to %d", clamped);
}

// Whether a VK_ERROR_DEVICE_LOST during presentContext is allowed to
// auto-disable framegen for the rest of the session. ON by default; turning
// this off lets framegen keep retrying on subsequent frames instead of
// latching into permanent passthrough, at the risk of repeated driver errors.
void setAutoDisableOnDeviceLostEnabled(bool enabled) {
    g.autoDisableOnDeviceLostEnabled.store(enabled, std::memory_order_relaxed);
    LOGI("Auto-disable on device-lost: %s", enabled ? "enabled" : "disabled");
}

// Manually clears a latched device-lost auto-disable without requiring a
// full context reinit. No-op (returns false) if framegen wasn't auto-disabled.
bool resumeFramegenAfterAutoDisable() {
    const bool wasDisabled = g.framegenAutoDisabled.exchange(false, std::memory_order_acq_rel);
    if (wasDisabled) {
        g.pairingResetPending.store(true, std::memory_order_release);
        LOGI("Framegen resumed manually after device-lost auto-disable");
    }
    return wasDisabled;
}

} // namespace lsfg_android

