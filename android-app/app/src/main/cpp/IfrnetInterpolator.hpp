#pragma once

#include "NcnnInterpolator.hpp" // reuse the shared lsfg_android::kNcnnErr* / kNcnnOk
                                  // constants — single source of truth so JNI glue
                                  // (lsfg_jni.cpp) and Kotlin's DllPickerScreen.kt
                                  // status-code mapping don't need a second enum.

#include <cstdint>
#include <string>

// Only ever compiled/linked when the ncnn Android prebuilt SDK was found at
// configure time — see the LSFG_HAVE_NCNN block in CMakeLists.txt (same gate
// as NcnnInterpolator; both live in the lsfg-ncnn-interpolator static lib).
// Callers in lsfg_jni.cpp must guard every use of this header behind
// `#ifdef LSFG_HAVE_NCNN`.

namespace lsfg_android {

// Runs a single-network IFRNet ncnn graph against a pair of RGBA8 frames,
// producing the frame(s) between them.
//
// Same call shape as NcnnInterpolator (this codebase's RIFE engine): two
// source frames plus a per-pixel timestep map in, one interpolated frame
// out per forward pass, arbitrary timesteps supported natively so
// interpolate() computes each output frame's timestep directly
// (k / multiplier) instead of recursing through fixed t=0.5 midpoints.
// The two engines are intentionally interchangeable at this API boundary —
// see lsfg_render_loop.cpp for how NcnnInterpolator is currently selected
// and constructed; IfrnetInterpolator is meant to be wired in alongside it
// as a second selectable AI backend, not a replacement.
//
// Model shape contract (verified against an IFRNet_S_Vimeo90K ifrnet.param
// exported the standard ifrnet-ncnn-vulkan way):
//   in0  = frame A, RGB, normalized to [0,1], padded up to a multiple of 32
//   in1  = frame C, same shape as in0
//   in2  = timestep map: 1-channel, same H/W as the padded frames, every
//          pixel filled with the requested timestep in [0,1]
//   out0 = predicted frame at that timestep, RGB [0,1], same padded shape
//
// The .param also references an "ifrnet.Warp" layer type that isn't part
// of stock ncnn — IfrnetWarp.cpp/.hpp implement and register it (Vulkan implementation of upstream ifrnet-ncnn-vulkan's Warp layer) before load_param() is
// called. NOTE: IfrnetWarp's bottom_blobs order (flow, image) is swapped
// from RifeWarp's (image, flow) — see IfrnetWarp.hpp's header comment.
class IfrnetInterpolator {
public:
    IfrnetInterpolator();
    ~IfrnetInterpolator();

    IfrnetInterpolator(const IfrnetInterpolator &) = delete;
    IfrnetInterpolator &operator=(const IfrnetInterpolator &) = delete;

    // modelDir must contain ifrnet.param and ifrnet.bin (the standard
    // ifrnet-ncnn-vulkan file names for e.g. models/IFRNet_S_Vimeo90K/).
    // Pick the "_S_" (small) variant for a bundled/mobile default — 5.7MB
    // vs 11MB for the base model and 40MB for "_L_" — see the model-size
    // comparison already covered in chat before reaching for the base or
    // "_L_" variant.
    //
    // GPU-only load. The Vulkan network is the only ncnn network created; there is
    // no CPU network, benchmark, or fallback. allowGpu/numThreads are ABI-compatible
    // parameters and are ignored except that allowGpu must be true.
    int load(const std::string &modelDir, bool allowGpu, int vulkanDeviceIndex, int numThreads);

    void unload();
    bool isLoaded() const;

    // frameA/frameC: interleaved RGBA8 buffers, width*height*4 bytes each —
    // the two real captured frames to interpolate between.
    // outFrames: array of (multiplier - 1) pointers, each already allocated
    // to width*height*4 bytes, that receive the interpolated frames in
    // chronological order between A and C.
    // flowScale: accepted for call-site compatibility with NcnnInterpolator's
    // signature, but unused — a single-pass IFRNet net has no separate
    // low-res flow stage to downscale. Pass anything in (0,1].
    int interpolate(const uint8_t *frameA, const uint8_t *frameC,
                     int width, int height,
                     uint8_t **outFrames, int multiplier,
                     float flowScale);

private:
    struct Impl;
    Impl *impl_;
};

} // namespace lsfg_android
