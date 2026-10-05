#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "core/device.hpp"
#include "core/image.hpp"
#include "core/instance.hpp"
#include "common/exception.hpp"

#include <cstdint>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

using namespace LSFG::Core;

// Extensions the device MUST have.
const std::vector<const char*> requiredExtensions = {
#ifndef __ANDROID__
    "VK_KHR_external_memory_fd",
    "VK_KHR_external_semaphore_fd",
#else
    // Android image sharing uses AHardwareBuffer, but frame completion is
    // synchronized across the host and framegen Vulkan devices with
    // exportable/importable opaque-FD binary semaphores.
    "VK_KHR_external_semaphore_fd",
    "VK_ANDROID_external_memory_android_hardware_buffer",
#endif
};

// Extensions that were promoted to core in Vulkan 1.1. The instance targets 1.1, so
// they are core here and NOT required by name: some 1.1 drivers stop listing promoted
// extensions, and failing on those would lock out GPUs that can run everything else.
// Enable them when listed (older drivers), skip them otherwise.
const std::vector<const char*> promotedExtensions = {
#ifdef __ANDROID__
    "VK_KHR_external_semaphore",
    "VK_KHR_external_memory",
    "VK_KHR_sampler_ycbcr_conversion",
    "VK_KHR_dedicated_allocation",
    "VK_KHR_get_memory_requirements2",
    "VK_KHR_bind_memory2",
    "VK_KHR_maintenance1",
#endif
};

namespace {

bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) return true;
    }
    return false;
}

} // namespace

const Image& Device::getFallbackDescriptorImage() const {
    return *this->fallbackDescriptorImage;
}

Device::Device(const Instance& instance, uint64_t deviceUUID) {
    // get all physical devices
    uint32_t deviceCount{};
    auto res = vkEnumeratePhysicalDevices(instance.handle(), &deviceCount, nullptr);
    if (res != VK_SUCCESS || deviceCount == 0)
        throw LSFG::vulkan_error(res, "Failed to enumerate physical devices");

    std::vector<VkPhysicalDevice> devices(deviceCount);
    res = vkEnumeratePhysicalDevices(instance.handle(), &deviceCount, devices.data());
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to get physical devices");

    // get device by uuid
    std::optional<VkPhysicalDevice> physicalDevice;
    for (const auto& device : devices) {
        VkPhysicalDeviceProperties properties;
        vkGetPhysicalDeviceProperties(device, &properties);

        const uint64_t uuid =
            static_cast<uint64_t>(properties.vendorID) << 32 | properties.deviceID;
        if (deviceUUID == uuid || deviceUUID == 0x1463ABAC) {
            physicalDevice = device;
            break;
        }
    }
    if (!physicalDevice)
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED,
            "Could not find physical device with UUID");

    // find queue family indices
    uint32_t familyCount{};
    vkGetPhysicalDeviceQueueFamilyProperties(*physicalDevice, &familyCount, nullptr);

    std::vector<VkQueueFamilyProperties> queueFamilies(familyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(*physicalDevice, &familyCount, queueFamilies.data());

    std::optional<uint32_t> computeFamilyIdx;
    for (uint32_t i = 0; i < familyCount; ++i) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT)
            computeFamilyIdx = i;
    }
    if (!computeFamilyIdx)
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "No compute queue family found");

    uint32_t extensionCount{};
    res = vkEnumerateDeviceExtensionProperties(*physicalDevice, nullptr, &extensionCount, nullptr);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to enumerate device extensions");
    std::vector<VkExtensionProperties> availableExtensions(extensionCount);
    res = vkEnumerateDeviceExtensionProperties(*physicalDevice, nullptr,
        &extensionCount, availableExtensions.data());
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to get device extensions");

    std::vector<const char*> enabledExtensions;
    enabledExtensions.reserve(requiredExtensions.size() + 1);
    for (const char* extension : requiredExtensions) {
        if (!hasExtension(availableExtensions, extension)) {
            throw LSFG::vulkan_error(VK_ERROR_EXTENSION_NOT_PRESENT,
                std::string("Missing required device extension: ") + extension);
        }
        enabledExtensions.push_back(extension);
    }

    for (const char* extension : promotedExtensions) {
        if (hasExtension(availableExtensions, extension))
            enabledExtensions.push_back(extension);
    }

    // robustness2 is optional (the fallback descriptor image covers its absence). The
    // extension being listed doesn't guarantee nullDescriptor, so probe the feature too.
    bool hasRobustness2 = false;
    if (hasExtension(availableExtensions, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME)) {
        VkPhysicalDeviceRobustness2FeaturesEXT robustProbe{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT,
        };
        VkPhysicalDeviceFeatures2 robustFeats{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            .pNext = &robustProbe,
        };
        vkGetPhysicalDeviceFeatures2(*physicalDevice, &robustFeats);
        hasRobustness2 = robustProbe.nullDescriptor == VK_TRUE;
    }
    if (hasRobustness2) {
        enabledExtensions.push_back(VK_EXT_ROBUSTNESS_2_EXTENSION_NAME);
    }

    // Probe FP16 support on this physical device. The LSFG-Android port can
    // load precompiled SPIR-V FP16 shader variants from Lossless.dll (resource
    // IDs 304..351) which carry `OpCapability Float16`. Vulkan rejects those at
    // vkCreateShaderModule time unless the device was created with the
    // shaderFloat16 feature explicitly enabled. On Vulkan 1.1 that feature lives in
    // VK_KHR_shader_float16_int8, so it is only used when that extension is listed and
    // reports the feature as supported; otherwise FP32 shaders are used.
    bool hasFloat16 = false;
    if (hasExtension(availableExtensions, "VK_KHR_shader_float16_int8")) {
        VkPhysicalDeviceShaderFloat16Int8FeaturesKHR fp16Probe{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR,
        };
        VkPhysicalDeviceFeatures2 featsProbe{
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            .pNext = &fp16Probe,
        };
        vkGetPhysicalDeviceFeatures2(*physicalDevice, &featsProbe);
        hasFloat16 = fp16Probe.shaderFloat16 == VK_TRUE;
    }
    if (hasFloat16) enabledExtensions.push_back("VK_KHR_shader_float16_int8");

    // create logical device — feature chain built from extension structs only, so it is
    // valid on a plain Vulkan 1.1 device (no Vulkan12/13 feature structs, no
    // synchronization2, no timeline semaphores, no vulkan memory model: the shaders
    // don't use them).
    const float queuePriority{1.0F}; // highest priority
    VkPhysicalDeviceRobustness2FeaturesEXT robustness2{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT,
        .nullDescriptor = VK_TRUE,
    };
    VkPhysicalDeviceShaderFloat16Int8FeaturesKHR fp16Enable{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES_KHR,
        .shaderFloat16 = VK_TRUE,
    };
    void* featureChain = nullptr;
    if (hasRobustness2) {
        robustness2.pNext = featureChain;
        featureChain = &robustness2;
    }
    if (hasFloat16) {
        fp16Enable.pNext = featureChain;
        featureChain = &fp16Enable;
    }
    const VkDeviceQueueCreateInfo computeQueueDesc{
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = *computeFamilyIdx,
        .queueCount = 1,
        .pQueuePriorities = &queuePriority
    };
    const VkDeviceCreateInfo deviceCreateInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = featureChain,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &computeQueueDesc,
        .enabledExtensionCount = static_cast<uint32_t>(enabledExtensions.size()),
        .ppEnabledExtensionNames = enabledExtensions.data()
    };
    VkDevice deviceHandle{};
    res = vkCreateDevice(*physicalDevice, &deviceCreateInfo, nullptr, &deviceHandle);
    if (res != VK_SUCCESS || deviceHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to create logical device");

    volkLoadDevice(deviceHandle);

    // get compute queue
    VkQueue queueHandle{};
    vkGetDeviceQueue(deviceHandle, *computeFamilyIdx, 0, &queueHandle);

    // store in shared ptr
    this->computeQueue = queueHandle;
    this->computeFamilyIdx = *computeFamilyIdx;
    this->physicalDevice = *physicalDevice;
    this->nullDescriptorSupported = hasRobustness2;
    this->device = std::shared_ptr<VkDevice>(
        new VkDevice(deviceHandle),
        [](VkDevice* device) {
            vkDestroyDevice(*device, nullptr);
        }
    );
    if (!this->nullDescriptorSupported) {
        this->fallbackDescriptorImage = std::make_shared<Core::Image>(*this,
            VkExtent2D{1, 1}, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
    }
}
