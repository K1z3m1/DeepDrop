#pragma once

#include "android_vk_session.hpp"

#include <volk.h>
#include <cstdint>
#include <vector>

namespace lsfg_android {

/**
 * GPU-only image enhancement + upscale pass.
 *
 * The source image is read-only. The pass applies the selected enhancement
 * controls to source samples first, then performs the selected upscale filter
 * into the destination image. This is intentionally a single logical
 * post-process stage so REAL and GENERATED frames use exactly the same path
 * without modifying the framegen input buffers.
 */
class GpuImageEnhancement {
public:
    bool initialize(VulkanSession &vk);

    bool record(VulkanSession &vk, VkCommandBuffer cb,
                VkImage source, VkExtent2D sourceExtent,
                VkImage destination, VkExtent2D destinationExtent,
                int upscaleFilterMode,
                float contrast, float saturation);

    // Called after the device is idle and before a swapchain is destroyed.
    // Destination image views/descriptor sets belong to the old swapchain.
    void clearDestinationBindings(VulkanSession &vk);

    void destroy(VulkanSession &vk);
    bool initialized() const { return pipeline_ != VK_NULL_HANDLE; }

private:
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;

    // One descriptor set per destination image. The destination swapchain image
    // is acquired before record(), so its descriptor can be safely updated for
    // the current source without racing a previous present.
    struct DestinationBinding {
        VkImage image = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkDescriptorSet set = VK_NULL_HANDLE;
    };
    std::vector<DestinationBinding> destinationBindings_;

    // Persistent source views avoid creating/destroying an image view per frame.
    struct SourceView {
        VkImage image = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
    };
    std::vector<SourceView> sourceViews_;
};

} // namespace lsfg_android
