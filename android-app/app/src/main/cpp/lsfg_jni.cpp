// JNI entry points for com.firstt175.novaframe.session.NativeBridge.
//
// Phase 3: extractShaders extracts precompiled FP16/FP32 SPIR-V resources
// (Lossless Scaling 3.2.2.0) and writes them to disk.
// Phase 4: probeShaders validates that every cached SPIR-V blob is accepted by
// the device driver via vkCreateShaderModule.
// Phase 5: initContext / pushFrame / setOutputSurface / destroyContext wire
// the LSFG_3_1 Vulkan pipeline through the AHB bridge.

#include "android_shader_loader.hpp"
#include "android_vk_probe.hpp"
#include "crash_reporter.hpp"
#include "lsfg_render_loop.hpp"
#ifdef LSFG_HAVE_NCNN
#include "NcnnInterpolator.hpp"
#include "IfrnetInterpolator.hpp"
#endif

#include <android/hardware_buffer_jni.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <cmath>

namespace {

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

#ifdef LSFG_HAVE_NCNN
lsfg_android::NcnnInterpolator *g_ncnnInterpolator = nullptr;
lsfg_android::IfrnetInterpolator *g_ifrnetInterpolator = nullptr;
#endif

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_initCrashReporter(
        JNIEnv *env, jobject /*thiz*/, jstring crashPath, jstring logPath) {
    lsfg_android::init_crash_reporter(
        jstring_to_std(env, crashPath),
        jstring_to_std(env, logPath));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_extractShaders(
        JNIEnv *env, jobject /*thiz*/,
        jstring dllPath, jstring /*dllSha256*/, jstring cacheDir) {
    const std::string path = jstring_to_std(env, dllPath);
    const std::string cache = jstring_to_std(env, cacheDir);
    if (path.empty() || cache.empty()) {
        return lsfg_android::kErrDllUnreadable;
    }
    return lsfg_android::extract_dll_to_spirv(path, cache);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_probeShaders(
        JNIEnv *env, jobject /*thiz*/, jstring cacheDir) {
    return lsfg_android::probe_shaders_on_device(jstring_to_std(env, cacheDir));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_initContext(
        JNIEnv *env, jobject /*thiz*/,
        jstring cacheDir, jint width, jint height,
        jint multiplier, jfloat flowScale,
        jboolean performance, jboolean hdr,
        jboolean framegenFp16,
        jboolean aiBackend, jstring aiModelDir,
        jint aiEngine) {
    const std::string cache = jstring_to_std(env, cacheDir);
    if (cache.empty() || width <= 0 || height <= 0) {
        return lsfg_android::kErrDllUnreadable;
    }
    const bool wantAi = aiBackend == JNI_TRUE;
    if (!wantAi) {
        // Sanity check: at least one cached SPIR-V file must exist. Without shaders,
        // LSFG_3_1::initialize() will throw inside framegen (we catch it but the
        // pipeline will be useless anyway). Failing fast here gives a cleaner error
        // path back to Kotlin so the service can stay in mirror mode.
        // id 255 is "mipmaps"'s base id; +kFp32SpirvIdOffset gives its FP32
        // SPIR-V counterpart (353), the default cache populated by extraction.
        // Skipped for the AI backend, which never touches the LSFG shader cache.
        auto probeShader = lsfg_android::load_cached_spirv(
            cache, 255 + lsfg_android::kFp32SpirvIdOffset, lsfg_android::ShaderCache::Fp32Spirv);
        if (probeShader.empty()) {
            return lsfg_android::kErrMissingResource;
        }
    }
    const lsfg_android::RenderLoopConfig cfg{
        .width = static_cast<uint32_t>(width),
        .height = static_cast<uint32_t>(height),
        .multiplier = static_cast<int>(multiplier),
        .flowScale = static_cast<float>(flowScale),
        .performance = performance == JNI_TRUE,
        .hdr = hdr == JNI_TRUE,
        .framegenFp16 = framegenFp16 == JNI_TRUE,
        .aiBackend = wantAi,
        .aiModelDir = jstring_to_std(env, aiModelDir),
        .aiEngine = static_cast<int>(aiEngine),
    };
    return lsfg_android::initRenderLoop(cache.c_str(), cfg);
}

// Human-readable Vulkan device name (e.g. "Adreno (TM) 740") for gpu index
// [index], matching the device index NcnnInterpolator::load()/
// IfrnetInterpolator::load() default to (index -1 from Kotlin resolves to
// device 0 there). Returns an empty string if index is out of range or this
// .so was built without ncnn.
extern "C" JNIEXPORT jstring JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getVulkanGpuName(
        JNIEnv *env, jobject /*thiz*/, jint index) {
#ifdef LSFG_HAVE_NCNN
    const std::string name = lsfg_android::ncnnGpuName(static_cast<int>(index));
    return env->NewStringUTF(name.c_str());
#else
    (void) index;
    return env->NewStringUTF("");
#endif
}

// Vulkan API version this device's GPU driver actually supports (e.g.
// "1.3.106"), used by the Device Info card. See
// android_vk_probe.hpp::get_vulkan_device_api_version_string() — this
// negotiates via vkEnumerateInstanceVersion rather than assuming 1.1, so
// the render loop and this readout always agree on what the driver speaks.
extern "C" JNIEXPORT jstring JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getVulkanApiVersion(
        JNIEnv *env, jobject /*thiz*/) {
    const std::string version = lsfg_android::get_vulkan_device_api_version_string();
    return env->NewStringUTF(version.c_str());
}

// GPU vendor, integrated/discrete, driver version, and an approximate VRAM
// figure (largest DEVICE_LOCAL heap — on mobile's near-universal unified
// memory this is a GPU-addressable slice of shared RAM, not dedicated
// video RAM; see GpuInfo's doc comment in android_vk_probe.hpp). Exposed
// as four separate getters rather than one packed call to match the
// existing getVulkanGpuName getter above.
extern "C" JNIEXPORT jstring JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getGpuVendor(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(lsfg_android::get_vulkan_gpu_info().vendor.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getGpuDeviceType(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(lsfg_android::get_vulkan_gpu_info().deviceType.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getGpuDriverVersion(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(lsfg_android::get_vulkan_gpu_info().driverVersion.c_str());
}

// -1 if the probe failed or the driver reported no DEVICE_LOCAL heap.
extern "C" JNIEXPORT jlong JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getGpuVramMb(JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(lsfg_android::get_vulkan_gpu_info().deviceLocalHeapMb);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_isFramegenFp16Supported(
        JNIEnv *env, jobject /*thiz*/, jstring cacheDir) {
    // Two prerequisites: the GPU has to expose shaderFloat16, AND the FP16
    // SPIR-V cache must already be populated by the DLL extraction step.
    // The UI ANDs them so the toggle appears only when the user can actually
    // enable it without surprises.
    if (!lsfg_android::device_supports_float16()) {
        return JNI_FALSE;
    }
    if (cacheDir == nullptr) {
        return JNI_FALSE;
    }
    const std::string cache = jstring_to_std(env, cacheDir);
    if (cache.empty()) {
        return JNI_FALSE;
    }
    return lsfg_android::fp16_shaders_available(cache) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setOutputSurface(
        JNIEnv *env, jobject /*thiz*/, jobject surface, jint w, jint h) {
    ANativeWindow *win = (surface != nullptr)
        ? ANativeWindow_fromSurface(env, surface)
        : nullptr;
    lsfg_android::setOutputSurface(win, static_cast<uint32_t>(w), static_cast<uint32_t>(h));
    if (win != nullptr) {
        // setOutputSurface has acquired its own reference; release ours.
        ANativeWindow_release(win);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setRecordingSurface(
        JNIEnv *env, jobject /*thiz*/, jobject surface, jint w, jint h) {
    ANativeWindow *win = (surface != nullptr)
        ? ANativeWindow_fromSurface(env, surface)
        : nullptr;
    lsfg_android::setRecordingSurface(win, static_cast<uint32_t>(w), static_cast<uint32_t>(h));
    if (win != nullptr) ANativeWindow_release(win);
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setWaitForBusyGeneration(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    lsfg_android::setWaitForBusyGeneration(static_cast<bool>(enabled));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setAllowGenerationWhenBusy(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    lsfg_android::setAllowGenerationWhenBusy(static_cast<bool>(enabled));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setLosslessQueue(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    lsfg_android::setLosslessQueue(static_cast<bool>(enabled));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_pushFrame(
        JNIEnv *env, jobject /*thiz*/, jobject hardwareBuffer, jlong timestampNs, jlong frameId) {
    if (hardwareBuffer == nullptr) return;
    // ZERO-COPY: AHardwareBuffer_fromHardwareBuffer() exposes the native graphic
    // buffer backing the Java HardwareBuffer; it does not read/copy pixel data.
    // pushFrame() acquires its own native reference before the Java object is
    // allowed to close, so the capture process can release its reference safely.
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    lsfg_android::pushFrame(ahb, static_cast<int64_t>(timestampNs), static_cast<uint64_t>(frameId));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getGeneratedFrameCount(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(lsfg_android::getGeneratedFrameCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getPostedFrameCount(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(lsfg_android::getPostedFrameCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getUniqueCaptureCount(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(lsfg_android::getUniqueCaptureCount());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getAverageQueueMs(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jdouble>(lsfg_android::getAverageQueueMs());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getAverageLatencyMs(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return static_cast<jdouble>(lsfg_android::getAverageLatencyMs());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getRecentPostIntervalsNs(
        JNIEnv *env, jobject /*thiz*/, jlongArray outArray) {
    if (outArray == nullptr) return 0;
    const jsize cap = env->GetArrayLength(outArray);
    if (cap <= 0) return 0;
    jlong *buf = env->GetLongArrayElements(outArray, nullptr);
    if (buf == nullptr) return 0;
    static_assert(sizeof(jlong) == sizeof(int64_t), "jlong must be int64_t");
    const uint32_t written = lsfg_android::getRecentPostIntervalsNs(
        reinterpret_cast<int64_t *>(buf), static_cast<uint32_t>(cap));
    env->ReleaseLongArrayElements(outArray, buf, 0);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getProfileWindowNs(
        JNIEnv *env, jobject /*thiz*/, jlongArray outArray) {
    if (outArray == nullptr) return 0;
    const jsize cap = env->GetArrayLength(outArray);
    if (cap < 6) return 0;
    jlong *buf = env->GetLongArrayElements(outArray, nullptr);
    if (buf == nullptr) return 0;
    static_assert(sizeof(jlong) == sizeof(int64_t), "jlong must be int64_t");
    const uint32_t written = lsfg_android::getProfileWindowNs(
        reinterpret_cast<int64_t *>(buf), static_cast<uint32_t>(cap));
    env->ReleaseLongArrayElements(outArray, buf, 0);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_getFpsSnapshot(
        JNIEnv *env, jobject /*thiz*/, jfloatArray outArray) {
    if (outArray == nullptr) return JNI_FALSE;
    const jsize cap = env->GetArrayLength(outArray);
    if (cap < 2) return JNI_FALSE;
    jfloat *buf = env->GetFloatArrayElements(outArray, nullptr);
    if (buf == nullptr) return JNI_FALSE;
    static_assert(sizeof(jfloat) == sizeof(float), "jfloat must be float");
    const bool ok = lsfg_android::getFpsSnapshot(
        reinterpret_cast<float *>(buf), static_cast<uint32_t>(cap));
    env->ReleaseFloatArrayElements(outArray, buf, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setBypass(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean bypass) {
    lsfg_android::setBypass(bypass == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setImageEnhancement(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled, jint backend,
        jfloat contrast, jfloat saturation) {
    lsfg_android::setImageEnhancement(
        enabled == JNI_TRUE, static_cast<int>(backend),
        static_cast<float>(contrast), static_cast<float>(saturation));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setUpscaleEnabled(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    lsfg_android::setUpscaleEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_processImageEnhancementPreview(
        JNIEnv *env, jobject /*thiz*/, jobject bitmap, jboolean enabled, jint backend,
        jfloat contrast, jfloat saturation, jboolean upscaleEnabled, jint upscaleFilter,
        jfloat renderResolutionScale) {
    if (bitmap == nullptr) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 2 || info.height < 2) return JNI_FALSE;
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) return JNI_FALSE;

    auto *px = static_cast<uint8_t *>(pixels);
    const uint32_t w = info.width, h = info.height;
    const uint32_t stride = info.stride;
    const float scale = std::clamp(static_cast<float>(renderResolutionScale), 0.1f, 1.0f);
    const uint32_t rw = std::max(1u, static_cast<uint32_t>(std::lround(w * scale)));
    const uint32_t rh = std::max(1u, static_cast<uint32_t>(std::lround(h * scale)));

    // Match render-loop ordering: the game is first rendered/captured at the reduced
    // resolution, then post-process is applied to that image, then presentation upscales it.
    std::vector<uint8_t> work(static_cast<size_t>(rw) * rh * 4u);
    if (rw == w && rh == h) {
        for (uint32_t y=0; y<h; ++y)
            std::memcpy(work.data() + static_cast<size_t>(y)*w*4u, px + static_cast<size_t>(y)*stride, static_cast<size_t>(w)*4u);
    } else {
        for (uint32_t y=0; y<rh; ++y) {
            const uint32_t sy = std::min(h-1, static_cast<uint32_t>((static_cast<uint64_t>(y)*h)/rh));
            for (uint32_t x=0; x<rw; ++x) {
                const uint32_t sx = std::min(w-1, static_cast<uint32_t>((static_cast<uint64_t>(x)*w)/rw));
                const uint8_t *sp = px + static_cast<size_t>(sy)*stride + sx*4u;
                std::memcpy(work.data() + (static_cast<size_t>(y)*rw+x)*4u, sp, 4);
            }
        }
    }

    if (enabled == JNI_TRUE) {
        const float c = std::clamp(static_cast<float>(contrast), 0.5f, 1.5f);
        const float sat = std::clamp(static_cast<float>(saturation), 0.0f, 2.0f);
        for (uint32_t y=0; y<rh; ++y) {
            for (uint32_t x=0; x<rw; ++x) {
                uint8_t *p = work.data() + (static_cast<size_t>(y)*rw+x)*4u;
                float r=p[0], gg=p[1], b=p[2];
                r=(r-128.0f)*c+128.0f; gg=(gg-128.0f)*c+128.0f; b=(b-128.0f)*c+128.0f;
                const float l=0.2126f*r+0.7152f*gg+0.0722f*b;
                p[0]=static_cast<uint8_t>(std::clamp(l+(r-l)*sat,0.0f,255.0f));
                p[1]=static_cast<uint8_t>(std::clamp(l+(gg-l)*sat,0.0f,255.0f));
                p[2]=static_cast<uint8_t>(std::clamp(l+(b-l)*sat,0.0f,255.0f));
            }
        }
    }

    // The live path must still fill the display surface. Disabling the optional upscale
    // setting therefore selects the render-loop's cheapest nearest fallback.
    const bool linear = upscaleEnabled == JNI_TRUE && upscaleFilter != 0;
    for (uint32_t y=0; y<h; ++y) {
        float fy=((y+0.5f)*rh/h)-0.5f;
        int y0=static_cast<int>(std::floor(fy)); float ty=fy-y0;
        if(!linear){y0=std::clamp(static_cast<int>(std::lround(fy)),0,static_cast<int>(rh)-1);ty=0;}
        int y1=std::clamp(y0+1,0,static_cast<int>(rh)-1); y0=std::clamp(y0,0,static_cast<int>(rh)-1);
        for(uint32_t x=0;x<w;++x){
            float fx=((x+0.5f)*rw/w)-0.5f;
            int x0=static_cast<int>(std::floor(fx)); float tx=fx-x0;
            if(!linear){x0=std::clamp(static_cast<int>(std::lround(fx)),0,static_cast<int>(rw)-1);tx=0;}
            int x1=std::clamp(x0+1,0,static_cast<int>(rw)-1); x0=std::clamp(x0,0,static_cast<int>(rw)-1);
            uint8_t *dp=px+static_cast<size_t>(y)*stride+x*4u;
            const uint8_t *a=work.data()+(static_cast<size_t>(y0)*rw+x0)*4u;
            const uint8_t *b=work.data()+(static_cast<size_t>(y0)*rw+x1)*4u;
            const uint8_t *c2=work.data()+(static_cast<size_t>(y1)*rw+x0)*4u;
            const uint8_t *d=work.data()+(static_cast<size_t>(y1)*rw+x1)*4u;
            for(int k=0;k<4;++k){float v0=a[k]+(b[k]-a[k])*tx;float v1=c2[k]+(d[k]-c2[k])*tx;dp[k]=static_cast<uint8_t>(std::clamp(v0+(v1-v0)*ty,0.0f,255.0f));}
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    (void)backend;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setUpscaleFilter(
        JNIEnv * /*env*/, jobject /*thiz*/, jint mode) {
    lsfg_android::setUpscaleFilter(static_cast<int>(mode));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setPresentMode(
        JNIEnv * /*env*/, jobject /*thiz*/, jint mode) {
    lsfg_android::setPresentMode(static_cast<int32_t>(mode));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setGenerationDeadlineMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jint deadlineMs) {
    lsfg_android::setGenerationDeadlineMs(static_cast<int>(deadlineMs));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setBypassGenDeadlineMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jint deadlineMs) {
    lsfg_android::setBypassGenDeadlineMs(static_cast<int>(deadlineMs));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setBypassGenResumeDelayMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jint delayMs) {
    lsfg_android::setBypassGenResumeDelayMs(static_cast<int>(delayMs));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setMaxQueuedRealFrames(
        JNIEnv * /*env*/, jobject /*thiz*/, jint depth) {
    lsfg_android::setMaxQueuedRealFrames(static_cast<int>(depth));
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_setAutoDisableOnDeviceLostEnabled(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    lsfg_android::setAutoDisableOnDeviceLostEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_resumeFramegenAfterAutoDisable(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return lsfg_android::resumeFramegenAfterAutoDisable() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_destroyContext(JNIEnv * /*env*/, jobject /*thiz*/) {
    lsfg_android::shutdownRenderLoop();
}

// -----------------------------------------------------------------------------
// AI (ncnn) frame-gen backend. Only functional when this .so was built with
// ncnn found (see the LSFG_HAVE_NCNN block in CMakeLists.txt) — otherwise
// these all return lsfg_android::kNcnnErrNotBuilt / false / -1 without
// touching any global state, so calling them is always safe.
//
// initAiInterpolator/aiInterpolatePreview below are the standalone
// load()/interpolate() pair used by the settings-screen "test this model"
// action. The live per-frame path is separate: lsfg_render_loop.cpp owns
// its own NcnnInterpolator instance (g.ai, populated by initContext's
// aiBackend/aiModelDir args) and drives it directly from workerThread via
// runAiInterpolate() — see the LSFG_HAVE_NCNN block there for the real
// per-frame AHB readback/upload stage.
extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_initAiInterpolator(
        JNIEnv *env, jobject /*thiz*/,
        jstring modelDir, jboolean useVulkan, jint vulkanDeviceIndex, jint numThreads, jint engine) {
#ifdef LSFG_HAVE_NCNN
    const std::string dir = jstring_to_std(env, modelDir);
    if (dir.empty()) {
        return lsfg_android::kNcnnErrModelMissing;
    }
    if (engine == 1) {
        if (g_ifrnetInterpolator == nullptr) {
            g_ifrnetInterpolator = new lsfg_android::IfrnetInterpolator();
        }
        return g_ifrnetInterpolator->load(
            dir, useVulkan == JNI_TRUE, static_cast<int>(vulkanDeviceIndex), static_cast<int>(numThreads));
    }
    if (g_ncnnInterpolator == nullptr) {
        g_ncnnInterpolator = new lsfg_android::NcnnInterpolator();
    }
    return g_ncnnInterpolator->load(
        dir, useVulkan == JNI_TRUE, static_cast<int>(vulkanDeviceIndex), static_cast<int>(numThreads));
#else
    (void) env; (void) modelDir; (void) useVulkan; (void) vulkanDeviceIndex; (void) numThreads; (void) engine;
    return -1; // lsfg_android::kNcnnErrNotBuilt — kept as a literal since the header isn't included
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_releaseAiInterpolator(JNIEnv * /*env*/, jobject /*thiz*/) {
#ifdef LSFG_HAVE_NCNN
    if (g_ncnnInterpolator != nullptr) {
        g_ncnnInterpolator->unload();
        delete g_ncnnInterpolator;
        g_ncnnInterpolator = nullptr;
    }
    if (g_ifrnetInterpolator != nullptr) {
        g_ifrnetInterpolator->unload();
        delete g_ifrnetInterpolator;
        g_ifrnetInterpolator = nullptr;
    }
#endif
}

// frameA/frameC: direct RGBA8 byte buffers (width*height*4 each) — pass
// android.graphics.Bitmap.copyPixelsToBuffer()'d direct ByteBuffers from
// Kotlin. outFrame: a single pre-allocated direct ByteBuffer, width*height*4
// bytes, that receives the interpolated frame at outIndex/multiplier (e.g.
// outIndex=1, multiplier=2 for the classic midpoint). Returns 0
// (lsfg_android::kNcnnOk) on success, a negative NcnnInterpolator error code
// otherwise. Intended for a settings-screen "test this model" preview, not
// per-frame session use (see the comment above initAiInterpolator). `engine`
// selects which loaded interpolator to run against: 0 = RIFE
// (g_ncnnInterpolator), 1 = IFRNet (g_ifrnetInterpolator) — must match
// whichever engine was passed to the initAiInterpolator() call that loaded it.
extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_aiInterpolatePreview(
        JNIEnv *env, jobject /*thiz*/,
        jobject frameA, jobject frameC, jint width, jint height,
        jobject outFrame, jint outIndex, jint multiplier, jfloat flowScale, jint engine) {
#ifdef LSFG_HAVE_NCNN
    const bool useIfrnet = (engine == 1);
    if (useIfrnet) {
        if (g_ifrnetInterpolator == nullptr || !g_ifrnetInterpolator->isLoaded()) {
            return lsfg_android::kNcnnErrNotLoaded;
        }
    } else {
        if (g_ncnnInterpolator == nullptr || !g_ncnnInterpolator->isLoaded()) {
            return lsfg_android::kNcnnErrNotLoaded;
        }
    }
    auto *a = static_cast<uint8_t *>(env->GetDirectBufferAddress(frameA));
    auto *c = static_cast<uint8_t *>(env->GetDirectBufferAddress(frameC));
    auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(outFrame));
    if (a == nullptr || c == nullptr || out == nullptr) {
        return lsfg_android::kNcnnErrBadArgs;
    }
    if (outIndex < 1 || outIndex >= multiplier) {
        return lsfg_android::kNcnnErrBadArgs;
    }

    const size_t frameBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
    std::vector<uint8_t> scratch(frameBytes * static_cast<size_t>(multiplier - 1));
    std::vector<uint8_t *> outPtrs(multiplier - 1);
    for (int i = 0; i < multiplier - 1; ++i) {
        outPtrs[i] = scratch.data() + static_cast<size_t>(i) * frameBytes;
    }

    const int rc = useIfrnet
        ? g_ifrnetInterpolator->interpolate(
              a, c, static_cast<int>(width), static_cast<int>(height),
              outPtrs.data(), static_cast<int>(multiplier), static_cast<float>(flowScale))
        : g_ncnnInterpolator->interpolate(
              a, c, static_cast<int>(width), static_cast<int>(height),
              outPtrs.data(), static_cast<int>(multiplier), static_cast<float>(flowScale));
    if (rc != lsfg_android::kNcnnOk) {
        return rc;
    }

    std::memcpy(out, outPtrs[outIndex - 1], frameBytes);
    return lsfg_android::kNcnnOk;
#else
    (void) env; (void) frameA; (void) frameC; (void) width; (void) height;
    (void) outFrame; (void) outIndex; (void) multiplier; (void) flowScale; (void) engine;
    return -1; // lsfg_android::kNcnnErrNotBuilt
#endif
}

// AI-FRAMEGEN-EDITOR --------------------------------------------------------
// Batch variant of aiInterpolatePreview used by the video editor's offline AI
// frame generation. One model run yields ALL (multiplier - 1) in-between frames
// between frameA and frameC, written back-to-back into `outFrames`
// (frame k lives at byte offset (k - 1) * width * height * 4). The preview call
// above computes the same frames but copies out only one, which would make
// 3x/4x cost (multiplier - 1) times more model runs than necessary.
// All three buffers must be direct ByteBuffers; outFrames needs capacity for
// (multiplier - 1) frames. `engine`: 0 = RIFE, 1 = IFRNet, same as the preview call.
extern "C" JNIEXPORT jint JNICALL
Java_com_firstt175_novaframe_session_NativeBridge_aiInterpolateBatch(
        JNIEnv *env, jobject /*thiz*/,
        jobject frameA, jobject frameC, jint width, jint height,
        jobject outFrames, jint multiplier, jfloat flowScale, jint engine) {
#ifdef LSFG_HAVE_NCNN
    const bool useIfrnet = (engine == 1);
    if (useIfrnet) {
        if (g_ifrnetInterpolator == nullptr || !g_ifrnetInterpolator->isLoaded()) {
            return lsfg_android::kNcnnErrNotLoaded;
        }
    } else {
        if (g_ncnnInterpolator == nullptr || !g_ncnnInterpolator->isLoaded()) {
            return lsfg_android::kNcnnErrNotLoaded;
        }
    }
    if (width <= 0 || height <= 0 || multiplier < 2 || multiplier > 8) {
        return lsfg_android::kNcnnErrBadArgs;
    }
    auto *a = static_cast<uint8_t *>(env->GetDirectBufferAddress(frameA));
    auto *c = static_cast<uint8_t *>(env->GetDirectBufferAddress(frameC));
    auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(outFrames));
    if (a == nullptr || c == nullptr || out == nullptr) {
        return lsfg_android::kNcnnErrBadArgs;
    }

    const size_t frameBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
    const jlong capA = env->GetDirectBufferCapacity(frameA);
    const jlong capC = env->GetDirectBufferCapacity(frameC);
    const jlong capOut = env->GetDirectBufferCapacity(outFrames);
    if (capA < static_cast<jlong>(frameBytes) || capC < static_cast<jlong>(frameBytes) ||
        capOut < static_cast<jlong>(frameBytes * static_cast<size_t>(multiplier - 1))) {
        return lsfg_android::kNcnnErrBadArgs;
    }

    std::vector<uint8_t *> outPtrs(static_cast<size_t>(multiplier - 1));
    for (int i = 0; i < multiplier - 1; ++i) {
        outPtrs[static_cast<size_t>(i)] = out + static_cast<size_t>(i) * frameBytes;
    }

    return useIfrnet
        ? g_ifrnetInterpolator->interpolate(
              a, c, static_cast<int>(width), static_cast<int>(height),
              outPtrs.data(), static_cast<int>(multiplier), static_cast<float>(flowScale))
        : g_ncnnInterpolator->interpolate(
              a, c, static_cast<int>(width), static_cast<int>(height),
              outPtrs.data(), static_cast<int>(multiplier), static_cast<float>(flowScale));
#else
    (void) env; (void) frameA; (void) frameC; (void) width; (void) height;
    (void) outFrames; (void) multiplier; (void) flowScale; (void) engine;
    return -1; // lsfg_android::kNcnnErrNotBuilt
#endif
}
