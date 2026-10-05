#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "core/commandpool.hpp"
#include "core/device.hpp"
#include "common/exception.hpp"

#include <memory>

using namespace LSFG::Core;

CommandPool::CommandPool(const Core::Device& device) {
    // create command pool
    // RESET_COMMAND_BUFFER_BIT lets individual command buffers allocated
    // from this pool be reset (see CommandBuffer::reset()) instead of
    // having to be freed and reallocated every time they're reused — that
    // was previously the single biggest source of per-frame Vulkan object
    // churn (alloc/free every command buffer, create/destroy every fence).
    const VkCommandPoolCreateInfo desc{
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = device.getComputeFamilyIdx()
    };
    VkCommandPool commandPoolHandle{};
    auto res = vkCreateCommandPool(device.handle(), &desc, nullptr, &commandPoolHandle);
    if (res != VK_SUCCESS || commandPoolHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Unable to create command pool");

    // store command pool in shared ptr
    this->commandPool = std::shared_ptr<VkCommandPool>(
        new VkCommandPool(commandPoolHandle),
        [dev = device.handle()](VkCommandPool* commandPoolHandle) {
            vkDestroyCommandPool(dev, *commandPoolHandle, nullptr);
        }
    );
}
