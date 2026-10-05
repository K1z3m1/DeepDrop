// Minimal Vulkan smoke test for the cached SPIR-V blobs.
//
// Phase 4 only validates that every cached precompiled FP32 SPIR-V shader
// is accepted by the device driver via vkCreateShaderModule. That is a
// surprisingly powerful end-to-end check — it catches bad headers, invalid
// magic numbers, unsupported decorations, and any Vulkan version mismatch
// between the shader and what the device actually speaks.
//
// Full pipeline creation (create/present/delete context) lives in later
// phases, once we also have MediaProjection-sourced VkImages to feed it.

#include "android_shader_loader.hpp"
#include "android_vk_probe.hpp"

#include <volk.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "crash_reporter.hpp"

#define LOG_TAG "lsfg-vk-probe"
#define LOGE(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO,  __VA_ARGS__)

namespace lsfg_android {

namespace {

// The runtime (host session and framegen) targets Vulkan 1.1 so it works on every
// GPU/driver that ships 1.1 or newer. The probes must run at that same level: a shader
// that only validates at 1.2/1.3 (newer SPIR-V version, promoted capabilities) would
// pass here and then be rejected at runtime. So negotiation is capped at 1.1.
constexpr uint32_t kMaxNegotiatedApiVersion = VK_API_VERSION_1_1;

constexpr uint32_t kAllResourceIds[] = {
    255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266,
    267, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279,
    280, 281, 282, 283, 284, 285, 286, 287, 288, 289,
    290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302,
};

struct VulkanState {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
};

bool create_instance_and_device(VulkanState &out) {
    if (volkInitialize() != VK_SUCCESS) {
        LOGE("volkInitialize failed — no Vulkan loader on this device?");
        return false;
    }

    // Negotiate instead of hardcoding 1.1: request whatever the loader can
    // actually give us, capped at kMaxNegotiatedApiVersion. Requesting an
    // apiVersion the driver doesn't support just makes vkCreateInstance
    // fail on some older loaders, so this must go through
    // vkEnumerateInstanceVersion (Vulkan 1.1+) rather than being probed by
    // trial and error.
    const uint32_t negotiated = query_negotiated_instance_api_version();
    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "lsfg-android",
        .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
        .pEngineName = "lsfg-vk",
        .engineVersion = VK_MAKE_VERSION(1, 0, 0),
        .apiVersion = negotiated != 0 ? negotiated : VK_API_VERSION_1_1,
    };

    const VkInstanceCreateInfo instInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
    };

    if (vkCreateInstance(&instInfo, nullptr, &out.instance) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed");
        return false;
    }
    volkLoadInstance(out.instance);

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(out.instance, &count, nullptr);
    if (count == 0) {
        LOGE("No Vulkan physical devices");
        return false;
    }
    std::vector<VkPhysicalDevice> phys(count);
    vkEnumeratePhysicalDevices(out.instance, &count, phys.data());
    out.physicalDevice = phys[0];

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(out.physicalDevice, &props);
    LOGI("Using GPU: %s (API %u.%u.%u)",
         props.deviceName,
         VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion),
         VK_VERSION_PATCH(props.apiVersion));

    uint32_t qCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(out.physicalDevice, &qCount, nullptr);
    std::vector<VkQueueFamilyProperties> qFams(qCount);
    vkGetPhysicalDeviceQueueFamilyProperties(out.physicalDevice, &qCount, qFams.data());

    uint32_t computeFamily = UINT32_MAX;
    for (uint32_t i = 0; i < qCount; ++i) {
        if (qFams[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            computeFamily = i;
            break;
        }
    }
    if (computeFamily == UINT32_MAX) {
        LOGE("No compute-capable queue family");
        return false;
    }

    const float prio = 1.0f;
    const VkDeviceQueueCreateInfo qInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = computeFamily,
        .queueCount = 1,
        .pQueuePriorities = &prio,
    };
    const VkDeviceCreateInfo devInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &qInfo,
    };
    if (vkCreateDevice(out.physicalDevice, &devInfo, nullptr, &out.device) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        return false;
    }
    volkLoadDevice(out.device);
    return true;
}

void destroy(VulkanState &s) {
    if (s.device) {
        vkDestroyDevice(s.device, nullptr);
        s.device = VK_NULL_HANDLE;
    }
    if (s.instance) {
        vkDestroyInstance(s.instance, nullptr);
        s.instance = VK_NULL_HANDLE;
    }
}

} // namespace

int probe_shaders_on_device(const std::string &cacheDir) {
    VulkanState vk{};
    if (!create_instance_and_device(vk)) {
        destroy(vk);
        return kProbeNoVulkan;
    }

    int loaded = 0;
    int rejected = 0;
    for (uint32_t baseId : kAllResourceIds) {
        const uint32_t id = baseId + kFp32SpirvIdOffset;
        auto spirv = load_cached_spirv(cacheDir, id, ShaderCache::Fp32Spirv);
        if (spirv.empty() || (spirv.size() % 4) != 0) {
            LOGE("SPIR-V resource %u missing or malformed (%zu bytes)", id, spirv.size());
            destroy(vk);
            return kProbeMissingSpirv;
        }

        const VkShaderModuleCreateInfo info{
            .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            .codeSize = spirv.size(),
            .pCode = reinterpret_cast<const uint32_t *>(spirv.data()),
        };
        VkShaderModule mod = VK_NULL_HANDLE;
        const VkResult r = vkCreateShaderModule(vk.device, &info, nullptr, &mod);
        if (r != VK_SUCCESS) {
            LOGE("vkCreateShaderModule rejected shader %u (VkResult=%d)", id, r);
            ++rejected;
        } else {
            vkDestroyShaderModule(vk.device, mod, nullptr);
            ++loaded;
        }
    }

    destroy(vk);

    LOGI("Probe complete: %d accepted, %d rejected", loaded, rejected);
    return rejected == 0 ? kOk : kProbeDriverRejected;
}

bool device_supports_float16() {
    // Minimal headless instance just to query a physical-device feature. We
    // intentionally do NOT request the FP16 device extension during creation
    // because we want a yes/no answer for the UI, not a working device. A
    // separate VkInstance is fine — the cost is one volkInitialize and one
    // vkEnumeratePhysicalDevices, both well under a frame.
    if (volkInitialize() != VK_SUCCESS) {
        return false;
    }
    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "lsfg-android",
        .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
        .pEngineName = "lsfg-vk",
        .engineVersion = VK_MAKE_VERSION(1, 0, 0),
        .apiVersion = VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo instInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
    };
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        return false;
    }
    volkLoadInstance(instance);

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (count == 0) {
        vkDestroyInstance(instance, nullptr);
        return false;
    }
    std::vector<VkPhysicalDevice> phys(count);
    vkEnumeratePhysicalDevices(instance, &count, phys.data());

    bool ok = false;
    VkPhysicalDeviceShaderFloat16Int8Features fp16{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES,
    };
    VkPhysicalDeviceFeatures2 feats2{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .pNext = &fp16,
    };
    // Walk every physical device — the FP16 toggle should be available if ANY
    // of the device's GPUs supports it (the framegen session picks the first
    // compute-capable one in the same order in create_instance_and_device).
    for (auto pd : phys) {
        fp16.shaderFloat16 = VK_FALSE;
        fp16.shaderInt8 = VK_FALSE;
        vkGetPhysicalDeviceFeatures2(pd, &feats2);
        if (fp16.shaderFloat16 == VK_TRUE) {
            ok = true;
            break;
        }
    }
    vkDestroyInstance(instance, nullptr);
    return ok;
}

bool device_supports_vulkan_memory_model() {
    // VulkanMemoryModel is core-promoted in 1.2 (queryable via
    // VkPhysicalDeviceVulkan12Features); on 1.1 it's gated by
    // VK_KHR_vulkan_memory_model. We accept either signal. Not currently
    // consumed by the render loop — see android_vk_probe.hpp.
    if (volkInitialize() != VK_SUCCESS) {
        return false;
    }
    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "lsfg-android",
        .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
        .pEngineName = "lsfg-vk",
        .engineVersion = VK_MAKE_VERSION(1, 0, 0),
        .apiVersion = VK_API_VERSION_1_2,
    };
    const VkInstanceCreateInfo instInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
    };
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        return false;
    }
    volkLoadInstance(instance);

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (count == 0) {
        vkDestroyInstance(instance, nullptr);
        return false;
    }
    std::vector<VkPhysicalDevice> phys(count);
    vkEnumeratePhysicalDevices(instance, &count, phys.data());

    bool ok = false;
    for (auto pd : phys) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(pd, &props);
        const bool api12 = props.apiVersion >= VK_API_VERSION_1_2;
        if (api12) {
            VkPhysicalDeviceVulkan12Features vk12{
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
            };
            VkPhysicalDeviceFeatures2 feats2{
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                .pNext = &vk12,
            };
            vkGetPhysicalDeviceFeatures2(pd, &feats2);
            if (vk12.vulkanMemoryModel == VK_TRUE) {
                ok = true;
                break;
            }
        } else {
            // Pre-1.2: VK_KHR_vulkan_memory_model presence is the gating signal.
            uint32_t extCount = 0;
            vkEnumerateDeviceExtensionProperties(pd, nullptr, &extCount, nullptr);
            std::vector<VkExtensionProperties> exts(extCount);
            vkEnumerateDeviceExtensionProperties(pd, nullptr, &extCount, exts.data());
            for (const auto &e : exts) {
                if (std::strcmp(e.extensionName,
                                VK_KHR_VULKAN_MEMORY_MODEL_EXTENSION_NAME) == 0) {
                    ok = true;
                    break;
                }
            }
            if (ok) break;
        }
    }
    vkDestroyInstance(instance, nullptr);
    return ok;
}

uint32_t query_negotiated_instance_api_version() {
    if (volkInitialize() != VK_SUCCESS) {
        return 0;
    }
    // vkEnumerateInstanceVersion itself requires a Vulkan 1.1+ loader to
    // even be resolvable; volk gives us a null pointer instead of a crash
    // if the loader is older than that, in which case the device is 1.0-only.
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion != nullptr) {
        if (vkEnumerateInstanceVersion(&loaderVersion) != VK_SUCCESS) {
            loaderVersion = VK_API_VERSION_1_0;
        }
    }
    return std::min(loaderVersion, kMaxNegotiatedApiVersion);
}

std::string format_vulkan_api_version(uint32_t apiVersion) {
    if (apiVersion == 0) {
        return "unknown";
    }
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u",
              VK_VERSION_MAJOR(apiVersion),
              VK_VERSION_MINOR(apiVersion),
              VK_VERSION_PATCH(apiVersion));
    return std::string(buf);
}

std::string get_vulkan_device_api_version_string() {
    // Cached: the driver's reported apiVersion can't change mid-session,
    // and probing it means a full VkInstance create/destroy. Before this
    // cache, the Device Info card and get_vulkan_gpu_info() below each did
    // their own separate probe — up to 5 VkInstance round-trips just to
    // paint one settings screen.
    static std::mutex mutex;
    static std::string cached;
    static bool haveCached = false;
    std::lock_guard<std::mutex> lock(mutex);
    if (haveCached) {
        return cached;
    }

    if (volkInitialize() != VK_SUCCESS) {
        cached = "unknown";
        haveCached = true;
        return cached;
    }
    const uint32_t negotiated = query_negotiated_instance_api_version();
    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "lsfg-android",
        .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
        .pEngineName = "lsfg-vk",
        .engineVersion = VK_MAKE_VERSION(1, 0, 0),
        .apiVersion = negotiated != 0 ? negotiated : VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo instInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
    };
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        cached = "unknown";
        haveCached = true;
        return cached;
    }
    volkLoadInstance(instance);

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (count == 0) {
        vkDestroyInstance(instance, nullptr);
        cached = "unknown";
        haveCached = true;
        return cached;
    }
    std::vector<VkPhysicalDevice> phys(count);
    vkEnumeratePhysicalDevices(instance, &count, phys.data());

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(phys[0], &props);
    vkDestroyInstance(instance, nullptr);
    cached = format_vulkan_api_version(props.apiVersion);
    haveCached = true;
    return cached;
}

namespace {

std::string decode_vendor(uint32_t vendorId) {
    // PCI vendor IDs — the same ones Vulkan reports on mobile GPUs even
    // though there's no literal PCI bus involved.
    switch (vendorId) {
        case 0x5143: return "Qualcomm (Adreno)";
        case 0x13B5: return "ARM (Mali)";
        case 0x1010: return "Imagination (PowerVR)";
        case 0x10DE: return "NVIDIA";
        case 0x1002: return "AMD";
        case 0x8086: return "Intel";
        case 0x1AE0: return "Google (Swiftshader/Angle)";
        default: {
            char buf[16];
            snprintf(buf, sizeof(buf), "0x%04X", vendorId);
            return std::string(buf);
        }
    }
}

std::string decode_device_type(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU:            return "cpu";
        default:                                      return "other";
    }
}

} // namespace

GpuInfo get_vulkan_gpu_info() {
    // Cached for the same reason as get_vulkan_device_api_version_string()
    // above: vendor/type/driver/VRAM are all static for the process
    // lifetime, but the Device Info card calls this once per field (4
    // separate JNI getters), which used to mean 4 separate VkInstance
    // create/destroy cycles just to render one card.
    static std::mutex mutex;
    static GpuInfo cached;
    static bool haveCached = false;
    std::lock_guard<std::mutex> lock(mutex);
    if (haveCached) {
        return cached;
    }

    GpuInfo info;
    if (volkInitialize() != VK_SUCCESS) {
        cached = info;
        haveCached = true;
        return cached;
    }
    const uint32_t negotiated = query_negotiated_instance_api_version();
    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "lsfg-android",
        .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
        .pEngineName = "lsfg-vk",
        .engineVersion = VK_MAKE_VERSION(1, 0, 0),
        .apiVersion = negotiated != 0 ? negotiated : VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo instInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
    };
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        cached = info;
        haveCached = true;
        return cached;
    }
    volkLoadInstance(instance);

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (count == 0) {
        vkDestroyInstance(instance, nullptr);
        cached = info;
        haveCached = true;
        return cached;
    }
    std::vector<VkPhysicalDevice> phys(count);
    vkEnumeratePhysicalDevices(instance, &count, phys.data());
    const VkPhysicalDevice pd = phys[0];

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(pd, &props);
    info.vendor = decode_vendor(props.vendorID);
    info.deviceType = decode_device_type(props.deviceType);
    // Driver version encoding is vendor-specific (NVIDIA packs differently
    // from everyone else); the generic VK_VERSION_* macros are correct for
    // the common case (Qualcomm/ARM/Imagination, all of Android's mobile
    // GPU vendors) and merely imprecise — not wrong — for NVIDIA/Intel.
    char driverBuf[32];
    snprintf(driverBuf, sizeof(driverBuf), "%u.%u.%u",
              VK_VERSION_MAJOR(props.driverVersion),
              VK_VERSION_MINOR(props.driverVersion),
              VK_VERSION_PATCH(props.driverVersion));
    info.driverVersion = driverBuf;

    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(pd, &memProps);
    VkDeviceSize largestDeviceLocalHeap = 0;
    for (uint32_t i = 0; i < memProps.memoryHeapCount; ++i) {
        if (memProps.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
            largestDeviceLocalHeap = std::max(largestDeviceLocalHeap, memProps.memoryHeaps[i].size);
        }
    }
    if (largestDeviceLocalHeap > 0) {
        info.deviceLocalHeapMb = static_cast<long long>(largestDeviceLocalHeap / (1024 * 1024));
    }

    vkDestroyInstance(instance, nullptr);
    cached = info;
    haveCached = true;
    return cached;
}

} // namespace lsfg_android
