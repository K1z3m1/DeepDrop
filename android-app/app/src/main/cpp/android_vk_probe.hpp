#pragma once

#include <cstdint>
#include <string>

namespace lsfg_android {

constexpr int kProbeNoVulkan = -10;
constexpr int kProbeMissingSpirv = -11;
constexpr int kProbeDriverRejected = -12;

// Creates a headless Vulkan device and runs vkCreateShaderModule over every
// cached SPIR-V blob. Returns kOk iff all shaders are accepted.
int probe_shaders_on_device(const std::string &cacheDir);

// Reports whether the first physical Vulkan device on this system advertises
// VK_KHR_shader_float16_int8 with shaderFloat16=VK_TRUE. Used by the UI to
// grey out the "FP16 frame-gen shaders" toggle on hardware that can't run
// the OpCapability Float16 SPIR-V variants. Headless: creates a temporary
// VkInstance/VkDevice, checks the feature, and tears down. Returns false on
// any error (no Vulkan loader, no device, etc.) — the UI must treat false
// as "FP16 not available" rather than a failure to probe.
bool device_supports_float16();

// Reports whether the device advertises vulkanMemoryModel. Not currently
// consumed by the render loop (both the FP16 and FP32 SPIR-V paths avoid
// VulkanMemoryModel entirely — verified in _analysis/*.dis), but kept
// available as a general Vulkan capability probe. Returns false on any
// probe error, same defensive default as device_supports_float16().
bool device_supports_vulkan_memory_model();

// Highest Vulkan instance API version this device's loader reports via
// vkEnumerateInstanceVersion, capped to kMaxNegotiatedApiVersion (see .cpp).
// This is what create_instance_and_device() requests. The runtime targets Vulkan 1.1
// (so it runs on every GPU with 1.1+), hence the cap: probes run at the same level
// the render path does. Returns 0 if there's no Vulkan loader at all.
uint32_t query_negotiated_instance_api_version();

// Human-readable "major.minor.patch" for [apiVersion] (a packed
// VK_MAKE_API_VERSION-style uint32, as returned by
// query_negotiated_instance_api_version() or read from
// VkPhysicalDeviceProperties::apiVersion). Used by the Device Info card.
std::string format_vulkan_api_version(uint32_t apiVersion);

// Convenience for the Device Info card: negotiates an instance the same
// way create_instance_and_device() does, reads the first physical
// device's VkPhysicalDeviceProperties::apiVersion (the version the actual
// GPU driver supports, which is what matters for "what can this device
// run" — the instance-level negotiated version above is only an upper
// bound), formats it, and tears the temporary instance down. Returns
// "unknown" on any probe failure (no loader, no device, etc.).
std::string get_vulkan_device_api_version_string();

// GPU facts beyond just the name (already covered by getVulkanGpuName in
// the ncnn path) — vendor, discrete/integrated, driver version, and a
// VRAM estimate. All defaulted so a partial probe failure still returns
// something sane instead of garbage.
struct GpuInfo {
    std::string vendor = "unknown";       // decoded from VkPhysicalDeviceProperties::vendorID
    std::string deviceType = "unknown";   // "integrated" / "discrete" / "virtual" / "cpu" / "other"
    std::string driverVersion = "unknown";
    // Largest DEVICE_LOCAL heap size, in MiB. On the near-universal
    // unified-memory mobile SoC this is NOT dedicated video RAM — it's
    // the GPU-addressable slice of shared system RAM Vulkan reports as
    // device-local, same as what desktop tools call "VRAM" on an iGPU.
    // There is no portable Vulkan query for actual discrete VRAM vs.
    // shared UMA, so the UI must label this as an estimate.
    long long deviceLocalHeapMb = -1;
    // Vulkan has no portable query for ALU/shader-core count (unlike CPU
    // core count, which sysfs exposes directly) — vendors gate that behind
    // proprietary extensions or don't expose it at all. Left unset
    // (-1) rather than guessed from the device name string, which would
    // be unreliable across driver/SKU revisions.
    int shaderCoreCount = -1;
};

// Probes the first Vulkan physical device for the fields above. Returns a
// default-constructed GpuInfo (all "unknown"/-1) if there's no Vulkan
// loader or no device.
GpuInfo get_vulkan_gpu_info();

} // namespace lsfg_android
