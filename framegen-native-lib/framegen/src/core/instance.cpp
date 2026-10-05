#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "core/instance.hpp"
#include "common/exception.hpp"

#include <cstdint>
#include <memory>
#include <vector>

using namespace LSFG::Core;

const std::vector<const char*> requiredExtensions = {

};

Instance::Instance() {
    volkInitialize();

    // create Vulkan instance
    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "lsfg-vk-base",
        .applicationVersion = VK_MAKE_VERSION(0, 0, 1),
        .pEngineName = "lsfg-vk-base",
        .engineVersion = VK_MAKE_VERSION(0, 0, 1),
        // Vulkan 1.1 is the baseline so this runs on every GPU/driver that ships Vulkan
        // 1.1+. Nothing newer than 1.1 core is required: barriers use the classic
        // vkCmdPipelineBarrier (see Utils::cmdImageBarriers) and the optional features
        // (FP16 shaders, robustness2) come from their 1.1-era extensions.
        .apiVersion = VK_API_VERSION_1_1
    };
    const VkInstanceCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
        .enabledExtensionCount = static_cast<uint32_t>(requiredExtensions.size()),
        .ppEnabledExtensionNames = requiredExtensions.data()
    };
    VkInstance instanceHandle{};
    auto res = vkCreateInstance(&createInfo, nullptr, &instanceHandle);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to create Vulkan instance");

    volkLoadInstance(instanceHandle);

    // store in shared ptr
    this->instance = std::shared_ptr<VkInstance>(
        new VkInstance(instanceHandle),
        [](VkInstance* instance) {
            vkDestroyInstance(*instance, nullptr);
        }
    );
}
