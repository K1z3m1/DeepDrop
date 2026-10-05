#pragma once

#include <cstdint>
#include <mutex>
#include <string>

// Only ever compiled/linked when the ncnn Android prebuilt SDK was found at
// configure time — see the LSFG_HAVE_NCNN block in CMakeLists.txt. Callers in
// lsfg_jni.cpp must guard every use of this header behind `#ifdef LSFG_HAVE_NCNN`.

namespace lsfg_android {

// Guards ncnn::Net load()/unload() (which internally call
// register_custom_layer + create_pipeline/destroy_pipeline — i.e. SPIR-V
// compilation and VkPipeline creation/teardown) across EVERY
// NcnnInterpolator/IfrnetInterpolator instance in the process, not just
// within one instance.
//
// There are two independent instances that can be alive at once, both
// bound to the same Vulkan device (vulkanDeviceIndex -1 -> device 0 in
// both call sites): the settings-screen "test this model" instance
// (g_ncnnInterpolator/g_ifrnetInterpolator in lsfg_jni.cpp) and the live
// session's instance (g.ai/g.aiIfrnet in lsfg_render_loop.cpp). Nothing
// previously stopped a settings-screen load() — compiling a new custom
// layer pipeline via create_pipeline() — from running concurrently with
// the live session's g.ai submitting per-frame Vulkan compute work on
// that same shared ncnn::VulkanDevice. That race is a credible cause of
// the SIGBUS crashes seen with fault addresses like 0x0000000100000001
// (a garbage/misaligned pointer, not a real heap address) landing inside
// this .so shortly after a load() completed successfully — i.e. driver-
// level corruption from the concurrent access, not a bug in the load()
// call that happened to be running.
//
// interpolate() itself is NOT guarded by this: concurrent inference from
// two Net instances sharing one VulkanDevice is the normal, supported
// ncnn usage pattern. Only load()/unload() (pipeline creation/teardown)
// need to be serialized against each other, process-wide.
//
// Recursive because load() unconditionally calls unload() first (reload
// path) and both take this same lock — a plain std::mutex would
// self-deadlock on that first line of load().
std::recursive_mutex &gpuLoadMutex();

// Error codes returned to Kotlin via JNI (mirrors the style of
// android_shader_loader.hpp's kErr* constants — keep 0 == ok).
constexpr int kNcnnOk = 0;
constexpr int kNcnnErrNotBuilt = -1;       // this .so was built without ncnn (see CMakeLists.txt)
constexpr int kNcnnErrModelMissing = -2;   // flownet.param/.bin missing in modelDir
constexpr int kNcnnErrLoadFailed = -3;     // ncnn::Net::load_param/load_model rejected a file
constexpr int kNcnnErrNotLoaded = -4;      // interpolate() called before a successful load()
constexpr int kNcnnErrBadArgs = -5;        // bad width/height/multiplier

// Runs a single-network RIFE (IFNet, e.g. rife-v4.25-lite) ncnn graph
// against a pair of RGBA8 frames, producing the frame(s) between them.
//
// This replaces the previous two-stage FlowNetLite + RefineNetLite pipeline.
// A RIFE v4.x-style net takes the two source frames plus a per-pixel
// timestep map and predicts the frame at that timestep directly in one
// forward pass — no separate refine network, and (unlike the old
// architecture) arbitrary timesteps are natively supported, so
// interpolate() computes each output frame's timestep directly
// (k / multiplier) instead of recursing through fixed t=0.5 midpoints.
//
// Model shape contract (verified against a rife-v4.25-lite_ensembleFalse
// flownet.param exported the standard rife-ncnn-vulkan way):
//   in0  = frame A, RGB, normalized to [0,1], padded up to a multiple of 32
//   in1  = frame C, same shape as in0
//   in2  = timestep map: 1-channel, same H/W as the padded frames, every
//          pixel filled with the requested timestep in [0,1]
//   out0 = predicted frame at that timestep, RGB [0,1], same padded shape
//
// The .param also references a "rife.Warp" layer type that isn't part of
// stock ncnn — RifeWarp.cpp/.hpp implement and register it as a Vulkan compute layer before load_param() is called.
//
// Hybrid policy: load() keeps ncnn CPU execution enabled and, when allowGpu is
// true, enables Vulkan compute in the same network. Vulkan-capable layers run on
// GPU; unsupported layers fall back to CPU without loading a second model.
class NcnnInterpolator {
public:
    NcnnInterpolator();
    ~NcnnInterpolator();

    NcnnInterpolator(const NcnnInterpolator &) = delete;
    NcnnInterpolator &operator=(const NcnnInterpolator &) = delete;

    // modelDir must contain flownet.param and flownet.bin (the standard
    // rife-ncnn-vulkan file names — rename an exported "flownet_param.txt"
    // to "flownet.param" if that's how it was handed to you).
    //
    // Hybrid load. allowGpu enables Vulkan execution in the same ncnn graph;
    // vulkanDeviceIndex selects the ncnn Vulkan device when >= 0. numThreads
    // controls ncnn's CPU worker pool (0 uses the full online CPU count).
    int load(const std::string &modelDir, bool allowGpu, int vulkanDeviceIndex, int numThreads);

    void unload();
    bool isLoaded() const;

    // frameA/frameC: interleaved RGBA8 buffers, width*height*4 bytes each —
    // the two real captured frames to interpolate between.
    // outFrames: array of (multiplier - 1) pointers, each already allocated
    // to width*height*4 bytes, that receive the interpolated frames in
    // chronological order between A and C.
    // flowScale: accepted for call-site compatibility with the old
    // FlowNetLite-based signature, but unused — a single-pass RIFE net has
    // no separate low-res flow stage to downscale. Pass anything in (0,1].
    int interpolate(const uint8_t *frameA, const uint8_t *frameC,
                     int width, int height,
                     uint8_t **outFrames, int multiplier,
                     float flowScale);

private:
    struct Impl;
    Impl *impl_;
};

// Free function wrapping ncnn::get_gpu_info() so callers (lsfg_jni.cpp's
// getVulkanGpuName JNI export) never need to include ncnn's <gpu.h> or link
// the `ncnn` CMake target directly. That target's
// INTERFACE_COMPILE_OPTIONS (-fno-rtti/-fno-exceptions) apply to every
// source file of any target that links it directly — which previously
// broke the real try/catch handling in lsfg_render_loop.cpp once lsfg-android
// itself linked `ncnn`. Keeping this function here, compiled into the
// isolated lsfg-ncnn-interpolator static lib alongside NcnnInterpolator,
// keeps that isolation intact while still exposing GPU probing to JNI.
std::string ncnnGpuName(int index);

} // namespace lsfg_android
