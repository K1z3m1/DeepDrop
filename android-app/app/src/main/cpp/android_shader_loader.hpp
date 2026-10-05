#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace lsfg_android {

// Error codes returned to Kotlin via JNI. Keep kOk == 0.
constexpr int kOk = 0;
constexpr int kErrDllUnreadable = -1;
constexpr int kErrMissingResource = -2;
constexpr int kErrWriteFailed = -4;
// A C++ exception (e.g. std::bad_alloc, std::out_of_range) was thrown while
// parsing dllPath and got caught at the extraction boundary instead of
// propagating into JNI-managed code, where an uncaught exception is
// undefined behavior and normally aborts the whole process. Surfaced as an
// ordinary error so a malformed/corrupted DLL just fails the import instead
// of crashing the app.
constexpr int kErrParseException = -5;

// Parses Lossless.dll at [dllPath] and writes one file per precompiled
// SPIR-V resource that LSFG uses. Two caches get populated:
//   - <cacheDir>/fp16/<resId>.spv     FP16 SPIR-V (304..351) verbatim from DLL
//   - <cacheDir>/fp32/<resId>.spv     FP32 SPIR-V (353..400) verbatim from DLL
// Requires Lossless Scaling 3.2.2.0+, which ships shaders as native SPIR-V
// instead of DXBC. Fails if neither set is complete. The FP32 SPIR-V cache
// is preferred at load time because it uses `OpMemoryModel Logical GLSL450`
// without `VulkanMemoryModel`, so it works on devices that lack
// vulkanMemoryModel (Mali Bifrost/Valhall) where a VMM-requiring shader
// would hit VK_ERROR_DEVICE_LOST on first dispatch.
int extract_dll_to_spirv(const std::string &dllPath, const std::string &cacheDir);

// Source identifier for load_cached_spirv.
enum class ShaderCache {
    Fp16Spirv,  // <cacheDir>/fp16/<id>.spv    (precompiled FP16 SPIR-V)
    Fp32Spirv,  // <cacheDir>/fp32/<id>.spv    (precompiled FP32 SPIR-V)
};

// Reads a cached SPIR-V file back from disk. Returns an empty vector on
// missing/unreadable files — the caller decides whether that's a fatal error.
std::vector<uint8_t> load_cached_spirv(const std::string &cacheDir, uint32_t resId,
                                       ShaderCache source = ShaderCache::Fp32Spirv);

// Maps a framegen shader name (e.g. "p_mipmaps", "p_alpha[2]", "generate")
// to the base resource ID (255..302). Not used to load shaders directly
// anymore — kept as the anchor every FP16 (+49) and FP32 (+98) offset below
// is computed from.
//
// Mirror of Extract::nameIdxTable in lsfg-vk-android/src/extract/extract.cpp.
uint32_t shader_name_to_resource_id(const std::string &name);

// Same lookup, but returning the SPIR-V FP16 resource ID (304..351). The FP16
// set is a parallel SPIR-V variant precompiled into Lossless.dll with the
// `OpCapability Float16` enabled and mixed FP16/FP32 ops. The mapping is a
// constant +49 offset over shader_name_to_resource_id(). Returns 0 if the
// name is unknown OR if the corresponding FP16 ID is not in the supported
// range (currently 304..351).
uint32_t shader_name_to_resource_id_fp16(const std::string &name);

// Returns true when the FP16 cache directory contains every shader in the
// 304..351 range. Used by the render loop to fall back to the FP32 SPIR-V
// path transparently when the user toggles FP16 on but the DLL extraction
// skipped the FP16 set (e.g. for an older DLL build that doesn't include them).
bool fp16_shaders_available(const std::string &cacheDir);

// Constant offset from a base resource id (255..302) to its precompiled
// FP32 SPIR-V counterpart (353..400). Exposed so callers that already have
// a base id in hand (e.g. android_vk_probe.cpp's device capability probe)
// don't need to duplicate this constant.
constexpr uint32_t kFp32SpirvIdOffset = 98;

// Same lookup as shader_name_to_resource_id but returning the FP32 SPIR-V
// resource ID (353..400). Constant +98 offset over the base id. Returns 0
// if the name is unknown OR the resulting id falls outside 353..400.
uint32_t shader_name_to_resource_id_fp32_spirv(const std::string &name);

// Returns true when the FP32 SPIR-V cache directory contains every shader in
// the 353..400 range. This is the default path — it uses `OpMemoryModel
// Logical GLSL450` with no VulkanMemoryModel capability, so it works across
// the widest range of devices (verified across the entire range in
// _analysis/*.dis and _analysis/fp32/).
bool fp32_spirv_shaders_available(const std::string &cacheDir);

} // namespace lsfg_android
