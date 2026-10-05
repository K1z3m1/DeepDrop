#include "android_vk_session.hpp"
#include "android_shader_loader.hpp"

#include <volk.h>

#include <android/log.h>

#include <cstring>
#include <vector>

#include "crash_reporter.hpp"

#define LOG_TAG "lsfg-vk-session"
#define LOGE(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGW(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_WARN,  __VA_ARGS__)
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO,  __VA_ARGS__)

namespace lsfg_android {

namespace {

// Required for the AHB <-> VkImage <-> opaque-FD bridge that feeds framegen.
// The session targets Vulkan 1.1 (see make_instance), so the extensions that were
// promoted to core in 1.1 are NOT listed here — see kPromotedDeviceExt.
constexpr const char *kRequiredDeviceExt[] = {
    VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,                       // export OPAQUE_FD
    VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,                    // semaphore FDs for framegen
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
};

// Promoted to core in Vulkan 1.1, so a 1.1 device has them without being asked. Some
// drivers still list them and some don't; requiring them by name would lock out GPUs
// that can run everything else. Enabled when listed, ignored otherwise.
constexpr const char *kPromotedDeviceExt[] = {
    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
    VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
    VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
    VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
    VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
    VK_KHR_MAINTENANCE1_EXTENSION_NAME,
};

// Optional. Framegen uses null descriptors when robustness2 exists; when it is
// missing, the Android framegen fork binds a fallback image for optional shader
// inputs instead.
// queue_family_foreign lets us use FOREIGN_EXT in barriers; if absent we fall
// back to QUEUE_FAMILY_EXTERNAL.
// swapchain is optional: when available we present generated frames through
// the Vulkan WSI path (zero-copy GPU blit) instead of ANativeWindow_lock +
// memcpy; if missing we fall back to the CPU-blit path transparently.
constexpr const char *kOptionalDeviceExt[] = {
    VK_EXT_ROBUSTNESS_2_EXTENSION_NAME,
    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    VK_KHR_SWAPCHAIN_EXTENSION_NAME,
};

// Optional instance extensions for the WSI path. Both are required to present
// on an ANativeWindow via Vulkan. If either is missing at instance level we
// disable the WSI path entirely (hasSwapchain stays false).
constexpr const char *kOptionalInstanceExt[] = {
    VK_KHR_SURFACE_EXTENSION_NAME,
    VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
};

bool has_extension(const std::vector<VkExtensionProperties> &avail, const char *name) {
    for (const auto &e : avail) {
        if (std::strcmp(e.extensionName, name) == 0) return true;
    }
    return false;
}

bool has_instance_extension(const std::vector<VkExtensionProperties> &avail, const char *name) {
    for (const auto &e : avail) {
        if (std::strcmp(e.extensionName, name) == 0) return true;
    }
    return false;
}

// Build the instance with the WSI extensions when they're available. Falling
// back silently is the right move here — the worst case is we miss the
// swapchain fast path, and the CPU blit still works.
VkInstance make_instance(bool &hasSurfaceExts) {
    hasSurfaceExts = false;
    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "lsfg-android",
        .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
        .pEngineName = "lsfg-vk",
        .engineVersion = VK_MAKE_VERSION(1, 0, 0),
        // Vulkan 1.1 is the baseline so this works on every GPU/driver with Vulkan 1.1+.
        // Nothing here needs anything newer: barriers use vkCmdPipelineBarrier, no
        // timeline semaphores, no synchronization2.
        .apiVersion = VK_API_VERSION_1_1,
    };

    uint32_t extCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> avail(extCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, avail.data());

    std::vector<const char *> enabled;
    bool surfaceAll = true;
    for (const char *opt : kOptionalInstanceExt) {
        if (has_instance_extension(avail, opt)) {
            enabled.push_back(opt);
        } else {
            surfaceAll = false;
        }
    }
    hasSurfaceExts = surfaceAll;

    VkInstanceCreateInfo info{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
        .enabledExtensionCount = static_cast<uint32_t>(enabled.size()),
        .ppEnabledExtensionNames = enabled.data(),
    };
    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&info, nullptr, &inst) != VK_SUCCESS) return VK_NULL_HANDLE;
    return inst;
}

VkPhysicalDevice pick_physical_device(VkInstance inst) {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(inst, &count, nullptr);
    if (count == 0) return VK_NULL_HANDLE;
    std::vector<VkPhysicalDevice> devs(count);
    vkEnumeratePhysicalDevices(inst, &count, devs.data());
    // First device is the only one on a typical Android phone. If we ever
    // run on a system with a real discrete + integrated split, prefer the
    // one that exposes all required extensions.
    for (auto d : devs) {
        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(d, nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> exts(extCount);
        vkEnumerateDeviceExtensionProperties(d, nullptr, &extCount, exts.data());

        bool ok = true;
        for (const char *req : kRequiredDeviceExt) {
            if (!has_extension(exts, req)) { ok = false; break; }
        }
        if (ok) return d;
    }
    return VK_NULL_HANDLE;
}

uint32_t find_compute_family(VkPhysicalDevice phys) {
    uint32_t qCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &qCount, nullptr);
    std::vector<VkQueueFamilyProperties> q(qCount);
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &qCount, q.data());
    for (uint32_t i = 0; i < qCount; ++i) {
        if (q[i].queueFlags & VK_QUEUE_COMPUTE_BIT) return i;
    }
    return UINT32_MAX;
}

} // namespace

int create_session(VulkanSession &out) {
    if (out.initialized()) return kSessionAlreadyInitialized;

    if (volkInitialize() != VK_SUCCESS) {
        LOGE("volkInitialize failed");
        return kSessionNoVulkan;
    }

    bool hasSurfaceExts = false;
    out.instance = make_instance(hasSurfaceExts);
    if (out.instance == VK_NULL_HANDLE) {
        LOGE("vkCreateInstance failed");
        return kSessionNoVulkan;
    }
    volkLoadInstance(out.instance);

    // Resolve vkCreateAndroidSurfaceKHR against OUR instance and stash the
    // pointer in the session. Two reasons we can't rely on the volk global:
    //   1. On some drivers (observed on Adreno 840 / Vulkan 1.4 and various
    //      Mali firmwares, also HyperOS Adreno builds) volk's normal load
    //      path doesn't populate the symbol even when VK_KHR_android_surface
    //      was accepted by vkCreateInstance — vkGetInstanceProcAddr resolves
    //      it manually.
    //   2. Even if volkLoadInstance loaded it correctly, framegen's
    //      Instance::Instance() will later call volkLoadInstance() against
    //      its own VkInstance (created without surface extensions), which
    //      overwrites the global pointer to NULL. The render loop reads the
    //      pointer at swapchain-creation time, after framegen init, so the
    //      global is unreliable.
    // The render loop reads out.pfnCreateAndroidSurfaceKHR instead.
    if (hasSurfaceExts) {
        out.pfnCreateAndroidSurfaceKHR = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
            vkGetInstanceProcAddr(out.instance, "vkCreateAndroidSurfaceKHR"));
        out.pfnDestroySurfaceKHR = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
            vkGetInstanceProcAddr(out.instance, "vkDestroySurfaceKHR"));
        out.pfnGetPhysicalDeviceSurfaceCapabilitiesKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
            vkGetInstanceProcAddr(out.instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"));
        out.pfnGetPhysicalDeviceSurfaceFormatsKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
            vkGetInstanceProcAddr(out.instance, "vkGetPhysicalDeviceSurfaceFormatsKHR"));
        out.pfnGetPhysicalDeviceSurfaceSupportKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
            vkGetInstanceProcAddr(out.instance, "vkGetPhysicalDeviceSurfaceSupportKHR"));
        out.pfnGetPhysicalDeviceSurfacePresentModesKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
            vkGetInstanceProcAddr(out.instance, "vkGetPhysicalDeviceSurfacePresentModesKHR"));

        if (out.pfnCreateAndroidSurfaceKHR != nullptr &&
            out.pfnDestroySurfaceKHR != nullptr &&
            out.pfnGetPhysicalDeviceSurfaceCapabilitiesKHR != nullptr &&
            out.pfnGetPhysicalDeviceSurfaceFormatsKHR != nullptr &&
            out.pfnGetPhysicalDeviceSurfaceSupportKHR != nullptr &&
            out.pfnGetPhysicalDeviceSurfacePresentModesKHR != nullptr) {
            LOGI("vkCreateAndroidSurfaceKHR and related WSI functions resolved and cached on session");
        } else {
            LOGW("WSI surface functions unresolvable on this driver — disabling WSI");
            hasSurfaceExts = false;
        }
    }

    out.physicalDevice = pick_physical_device(out.instance);
    if (out.physicalDevice == VK_NULL_HANDLE) {
        LOGE("No physical device with all required extensions");
        return kSessionMissingExtension;
    }

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(out.physicalDevice, &props);
    out.deviceUuid =
        (static_cast<uint64_t>(props.vendorID) << 32) | props.deviceID;
    LOGI("Using GPU: %s (API %u.%u.%u, vendor=0x%x device=0x%x uuid=0x%llx)",
         props.deviceName,
         VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion),
         VK_VERSION_PATCH(props.apiVersion),
         props.vendorID, props.deviceID,
         (unsigned long long)out.deviceUuid);

    out.computeFamilyIdx = find_compute_family(out.physicalDevice);
    if (out.computeFamilyIdx == UINT32_MAX) {
        LOGE("No compute queue family");
        return kSessionNoSuitableGpu;
    }

    // Probe optional extensions and build the final list.
    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(out.physicalDevice, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> avail(extCount);
    vkEnumerateDeviceExtensionProperties(out.physicalDevice, nullptr, &extCount, avail.data());

    // Log key extension availability for diagnostics.
    {
        static const char* kDiagExts[] = {
            "VK_KHR_timeline_semaphore",
            "VK_KHR_synchronization2",
            "VK_KHR_vulkan_memory_model",
            VK_EXT_ROBUSTNESS_2_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
            VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
            VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        };
        LOGI("Device extension availability (%u total):", extCount);
        for (const char* ext : kDiagExts) {
            LOGI("  [%s] %s", has_extension(avail, ext) ? "Y" : "N", ext);
        }
    }

    std::vector<const char *> enabledExts;
    for (const char *req : kRequiredDeviceExt) enabledExts.push_back(req);
    for (const char *promoted : kPromotedDeviceExt) {
        if (has_extension(avail, promoted)) enabledExts.push_back(promoted);
    }
    bool hasSwapchainDevExt = false;
    for (const char *opt : kOptionalDeviceExt) {
        if (has_extension(avail, opt)) {
            enabledExts.push_back(opt);
            if (std::strcmp(opt, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME) == 0) {
                out.hasRobustness2 = true;
            } else if (std::strcmp(opt, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) == 0) {
                out.hasQueueFamilyForeign = true;
            } else if (std::strcmp(opt, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0) {
                hasSwapchainDevExt = true;
            }
        } else {
            // Robustness2 has a dedicated INFO message below; keep this unrelated
            // optional-extension diagnostic separate from presentation mode.
            if (std::strcmp(opt, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME) != 0) {
                LOGW("Optional extension %s not available", opt);
            }
        }
    }
    out.hasSwapchain = hasSurfaceExts && hasSwapchainDevExt;
    if (!out.hasSwapchain) {
        LOGE("MAILBOX WSI unavailable (surface=%d, swapchain=%d); presentation path is disabled",
             (int)hasSurfaceExts, (int)hasSwapchainDevExt);
    }

    // Nothing beyond Vulkan 1.1 core is required, so no timeline semaphores,
    // synchronization2 or memory-model features are enabled here.

    if (!out.hasRobustness2) {
        // Mali (most revisions), older Adreno, and PowerVR don't expose
        // VK_EXT_robustness2. Framegen handles this by binding a tiny 1x1
        // fallback descriptor image instead of relying on the nullDescriptor
        // feature — visually identical, ~zero perf cost. This is informational,
        // not an error.
        LOGI("VK_EXT_robustness2 absent — using 1x1 fallback descriptor image (no functional impact)");
    }

    // Probe whether OPAQUE_FD is actually an *exportable* handle type for a
    // plain binary semaphore on this physical device. The VK_KHR_external_semaphore_fd
    // extension being present (checked above, and required) only means the
    // driver implements *some* fd-based handle type — on Android that is
    // frequently SYNC_FD only (Mali, and historically Adreno/PowerVR), used
    // for native fence interop, not OPAQUE_FD (the desktop-Linux-style opaque
    // payload that framegen's own Semaphore(fd) import path requires). Doing
    // this check once here — instead of discovering it via a failed
    // vkCreateSemaphore/vkGetSemaphoreFdKHR call on every single frame — lets
    // the render loop pick the right synchronization strategy up front.
    {
        const VkPhysicalDeviceExternalSemaphoreInfo extSemInfo{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT,
        };
        VkExternalSemaphoreProperties extSemProps{
            .sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES,
        };
        vkGetPhysicalDeviceExternalSemaphoreProperties(out.physicalDevice, &extSemInfo, &extSemProps);
        out.hasExportableOpaqueFdSemaphore =
            (extSemProps.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;

        const VkPhysicalDeviceExternalSemaphoreInfo syncExtSemInfo{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        };
        VkExternalSemaphoreProperties syncExtSemProps{
            .sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES,
        };
        vkGetPhysicalDeviceExternalSemaphoreProperties(out.physicalDevice, &syncExtSemInfo, &syncExtSemProps);
        out.hasExportableSyncFdSemaphore =
            (syncExtSemProps.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;

        LOGI("Cross-device semaphore export: OPAQUE_FD=%s SYNC_FD=%s",
             out.hasExportableOpaqueFdSemaphore ? "supported" : "no",
             out.hasExportableSyncFdSemaphore ? "supported" : "no");
    }

    // Query feature support before requesting anything in vkCreateDevice: a 1.1 device
    // may expose the sampler-YCbCr / robustness2 extensions without every feature bit.
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrQuery{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
    };
    VkPhysicalDeviceFeatures2 features2Query{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .pNext = &ycbcrQuery,
    };
    vkGetPhysicalDeviceFeatures2(out.physicalDevice, &features2Query);

    // Feature chain (1.1 core / extension structs only).
    VkPhysicalDeviceRobustness2FeaturesEXT robustness2{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT,
        .nullDescriptor = VK_TRUE,
    };
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
        .pNext = out.hasRobustness2 ? static_cast<void *>(&robustness2) : nullptr,
        .samplerYcbcrConversion = ycbcrQuery.samplerYcbcrConversion,
    };

    const float prio = 1.0f;
    const VkDeviceQueueCreateInfo qInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = out.computeFamilyIdx,
        .queueCount = 1,
        .pQueuePriorities = &prio,
    };
    const VkDeviceCreateInfo devInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = &ycbcr,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &qInfo,
        .enabledExtensionCount = static_cast<uint32_t>(enabledExts.size()),
        .ppEnabledExtensionNames = enabledExts.data(),
    };
    if (vkCreateDevice(out.physicalDevice, &devInfo, nullptr, &out.device) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        return kSessionDeviceCreateFailed;
    }
    // Populate per-device table — these pointers stay valid for our device
    // even after framegen's volkLoadDevice() clobbers the globals.
    volkLoadDeviceTable(&out.fn, out.device);

    out.fn.vkGetDeviceQueue(out.device, out.computeFamilyIdx, 0, &out.computeQueue);

    const VkCommandPoolCreateInfo poolInfo{
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = out.computeFamilyIdx,
    };
    if (out.fn.vkCreateCommandPool(out.device, &poolInfo, nullptr, &out.commandPool) != VK_SUCCESS) {
        LOGE("vkCreateCommandPool failed");
        return kSessionDeviceCreateFailed;
    }

    // Pre-allocate the ring. A single vkAllocateCommandBuffers for all N slots
    // is cheaper than N individual calls, and since the pool has
    // RESET_COMMAND_BUFFER_BIT we can reset each CB independently in
    // acquireCommandRing().
    VkCommandBufferAllocateInfo cbai{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = out.commandPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = kCommandRingSize,
    };
    if (out.fn.vkAllocateCommandBuffers(out.device, &cbai,
            out.ringCommandBuffers.data()) != VK_SUCCESS) {
        LOGE("vkAllocateCommandBuffers(ring) failed");
        return kSessionDeviceCreateFailed;
    }
    for (uint32_t i = 0; i < kCommandRingSize; ++i) {
        const VkFenceCreateInfo fi{
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            // Unsignaled: first acquireCommandRing on this slot skips the wait.
        };
        if (out.fn.vkCreateFence(out.device, &fi, nullptr, &out.ringFences[i]) != VK_SUCCESS) {
            LOGE("vkCreateFence(ring %u) failed", i);
            return kSessionDeviceCreateFailed;
        }
        out.ringFenceArmed[i] = false;
    }
    out.ringNext = 0;

    LOGI("Vulkan session ready (compute family=%u, %zu extensions, robustness2=%s, swapchain=%s)",
         out.computeFamilyIdx, enabledExts.size(),
         out.hasRobustness2 ? "yes" : "no",
         out.hasSwapchain ? "yes" : "no");
    return kOk;
}

bool acquireCommandRing(VulkanSession &vk, VkCommandBuffer &outCb, VkFence &outFence) {
    if (vk.device == VK_NULL_HANDLE) return false;

    // Presentation is fire-and-forget from the CPU side: never block waiting for
    // a previous GPU submission. Probe the ring fences and take the first slot
    // that is already idle. If every slot is still in flight, skip this frame
    // instead of adding latency.
    for (uint32_t scan = 0; scan < kCommandRingSize; ++scan) {
        const uint32_t idx = (vk.ringNext + scan) % kCommandRingSize;
        VkFence fence = vk.ringFences[idx];
        VkCommandBuffer cb = vk.ringCommandBuffers[idx];

        if (vk.ringFenceArmed[idx]) {
            const VkResult fenceStatus = vk.fn.vkGetFenceStatus(vk.device, fence);
            if (fenceStatus != VK_SUCCESS) {
                continue;
            }
            if (vk.fn.vkResetFences(vk.device, 1, &fence) != VK_SUCCESS) {
                continue;
            }
            vk.ringFenceArmed[idx] = false;
        }

        if (vk.fn.vkResetCommandBuffer(cb, 0) != VK_SUCCESS) {
            continue;
        }

        vk.ringNext = (idx + 1) % kCommandRingSize;
        outCb = cb;
        outFence = fence;
        return true;
    }

    // No CPU wait, no sleep, no fallback: caller simply drops this frame.
    return false;
}

bool submitCommandRing(VulkanSession &vk, VkCommandBuffer cb, VkFence fence) {
    if (vk.device == VK_NULL_HANDLE || cb == VK_NULL_HANDLE) return false;
    const VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &cb,
    };
    const VkResult r = vk.fn.vkQueueSubmit(vk.computeQueue, 1, &si, fence);
    if (r != VK_SUCCESS) {
        LOGW("vkQueueSubmit failed rc=%d", (int)r);
        return false;
    }
    // Mark the slot whose fence we just armed. Find it by scanning — ring is
    // tiny (8 slots) so linear search is noise vs the submit cost itself.
    for (uint32_t i = 0; i < kCommandRingSize; ++i) {
        if (vk.ringFences[i] == fence) {
            vk.ringFenceArmed[i] = true;
            break;
        }
    }
    return true;
}

bool waitCommandRing(VulkanSession &vk, VkFence fence) {
    if (vk.device == VK_NULL_HANDLE || fence == VK_NULL_HANDLE) return true;
    // Look up whether this fence is actually armed; if not, the caller is
    // waiting on something that never got submitted (e.g. record failed and
    // no submit happened) — treat as trivially done.
    bool armed = false;
    for (uint32_t i = 0; i < kCommandRingSize; ++i) {
        if (vk.ringFences[i] == fence && vk.ringFenceArmed[i]) {
            armed = true;
            break;
        }
    }
    if (!armed) return true;

    const VkResult r = vk.fn.vkWaitForFences(vk.device, 1, &fence,
                                             VK_TRUE, 500ULL * 1'000'000ULL);
    if (r != VK_SUCCESS) {
        LOGW("waitCommandRing: vkWaitForFences returned %d", (int)r);
        return false;
    }
    return true;
}

void destroy_session(VulkanSession &s) {
    if (s.device != VK_NULL_HANDLE) {
        // Drain any in-flight ring work before freeing fences. Safe even if
        // no CBs were ever submitted: unarmed fences aren't waited on.
        if (s.fn.vkWaitForFences != nullptr) {
            for (uint32_t i = 0; i < kCommandRingSize; ++i) {
                if (s.ringFences[i] != VK_NULL_HANDLE && s.ringFenceArmed[i]) {
                    s.fn.vkWaitForFences(s.device, 1, &s.ringFences[i],
                                         VK_TRUE, 500ULL * 1'000'000ULL);
                    s.ringFenceArmed[i] = false;
                }
            }
        }
        if (s.fn.vkDestroyFence != nullptr) {
            for (uint32_t i = 0; i < kCommandRingSize; ++i) {
                if (s.ringFences[i] != VK_NULL_HANDLE) {
                    s.fn.vkDestroyFence(s.device, s.ringFences[i], nullptr);
                    s.ringFences[i] = VK_NULL_HANDLE;
                }
                s.ringCommandBuffers[i] = VK_NULL_HANDLE; // owned by the pool
            }
        }
        if (s.commandPool != VK_NULL_HANDLE && s.fn.vkDestroyCommandPool != nullptr) {
            s.fn.vkDestroyCommandPool(s.device, s.commandPool, nullptr);
            s.commandPool = VK_NULL_HANDLE;
        }
        if (s.fn.vkDeviceWaitIdle != nullptr) s.fn.vkDeviceWaitIdle(s.device);
        if (s.fn.vkDestroyDevice != nullptr) {
            s.fn.vkDestroyDevice(s.device, nullptr);
        } else {
            // Fallback to global if our table was never populated.
            vkDestroyDevice(s.device, nullptr);
        }
        s.device = VK_NULL_HANDLE;
    }
    if (s.instance != VK_NULL_HANDLE) {
        vkDestroyInstance(s.instance, nullptr);
        s.instance = VK_NULL_HANDLE;
    }
    s.physicalDevice = VK_NULL_HANDLE;
    s.computeQueue = VK_NULL_HANDLE;
    s.computeFamilyIdx = UINT32_MAX;
    s.deviceUuid = 0;
    s.hasRobustness2 = false;
    s.hasQueueFamilyForeign = false;
    s.hasSwapchain = false;
    s.ringNext = 0;
    s.ringCommandBuffers.fill(VK_NULL_HANDLE);
    s.ringFences.fill(VK_NULL_HANDLE);
    s.ringFenceArmed.fill(false);
    s.fn = {};
}

} // namespace lsfg_android
