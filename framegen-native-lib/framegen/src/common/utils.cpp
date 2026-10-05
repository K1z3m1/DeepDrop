#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "common/utils.hpp"
#include "core/buffer.hpp"
#include "core/image.hpp"
#include "core/device.hpp"
#include "core/commandpool.hpp"
#include "core/fence.hpp"
#include "common/exception.hpp"

#include <cstdint>
#include <cerrno>
#include <cstdlib>
#include <fstream>
#include <string>
#include <ios>
#include <system_error>
#include <vector>

using namespace LSFG;
using namespace LSFG::Utils;

BarrierBuilder& BarrierBuilder::addR2W(Core::Image& image) {
    this->barriers.emplace_back(VkImageMemoryBarrier2 {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2,
        .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
        .srcAccessMask = VK_ACCESS_2_SHADER_READ_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
        .dstAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT,
        .oldLayout = image.getLayout(),
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .image = image.handle(),
        .subresourceRange = {
            .aspectMask = image.getAspectFlags(),
            .levelCount = 1,
            .layerCount = 1
        }
    });
    image.setLayout(VK_IMAGE_LAYOUT_GENERAL);

    return *this;
}

BarrierBuilder& BarrierBuilder::addW2R(Core::Image& image) {
    this->barriers.emplace_back(VkImageMemoryBarrier2 {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2,
        .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
        .srcAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
        .dstAccessMask = VK_ACCESS_2_SHADER_READ_BIT,
        .oldLayout = image.getLayout(),
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .image = image.handle(),
        .subresourceRange = {
            .aspectMask = image.getAspectFlags(),
            .levelCount = 1,
            .layerCount = 1
        }
    });
    image.setLayout(VK_IMAGE_LAYOUT_GENERAL);

    return *this;
}

void BarrierBuilder::build() const {
    Utils::cmdImageBarriers(this->commandBuffer->handle(),
        this->barriers.data(), static_cast<uint32_t>(this->barriers.size()));
}

void Utils::cmdImageBarriers(VkCommandBuffer commandBuffer,
        const VkImageMemoryBarrier2* barriers, uint32_t count) {
    if (count == 0)
        return;

    // Reused scratch buffer: this runs several times per generated frame.
    static thread_local std::vector<VkImageMemoryBarrier> converted;
    converted.clear();
    converted.reserve(count);

    VkPipelineStageFlags srcStages = 0;
    VkPipelineStageFlags dstStages = 0;
    for (uint32_t i = 0; i < count; ++i) {
        const VkImageMemoryBarrier2& b = barriers[i];
        // The stage/access bits used here (compute shader, transfer, top/bottom of
        // pipe, shader read/write, transfer write) have identical values in the
        // 32-bit and 64-bit flag types.
        srcStages |= static_cast<VkPipelineStageFlags>(b.srcStageMask);
        dstStages |= static_cast<VkPipelineStageFlags>(b.dstStageMask);
        converted.push_back(VkImageMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = static_cast<VkAccessFlags>(b.srcAccessMask),
            .dstAccessMask = static_cast<VkAccessFlags>(b.dstAccessMask),
            .oldLayout = b.oldLayout,
            .newLayout = b.newLayout,
            .srcQueueFamilyIndex = b.srcQueueFamilyIndex,
            .dstQueueFamilyIndex = b.dstQueueFamilyIndex,
            .image = b.image,
            .subresourceRange = b.subresourceRange
        });
    }
    // Synchronization2 allows an empty ("none") stage mask; the classic call needs one.
    if (srcStages == 0) srcStages = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    if (dstStages == 0) dstStages = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;

    vkCmdPipelineBarrier(commandBuffer, srcStages, dstStages, 0,
        0, nullptr, 0, nullptr, count, converted.data());
}

void Utils::uploadImage(const Core::Device& device, const Core::CommandPool& commandPool,
        Core::Image& image, const std::string& path) {
    // read image bytecode
    std::ifstream file(path.data(), std::ios::binary | std::ios::ate);
    if (!file.is_open())
        throw std::system_error(errno, std::generic_category(), "Failed to open image: " + path);

    std::streamsize size = file.tellg();
    size -= 124 + 4; // dds header and magic bytes
    std::vector<char> code(static_cast<size_t>(size));

    file.seekg(124 + 4, std::ios::beg);
    if (!file.read(code.data(), size))
        throw std::system_error(errno, std::generic_category(), "Failed to read image: " + path);

    file.close();

    // copy data to buffer
    const Core::Buffer stagingBuffer(
        device, code.data(), static_cast<uint32_t>(code.size()),
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT
    );

    // perform the upload
    Core::CommandBuffer commandBuffer(device, commandPool);
    commandBuffer.begin();

    const VkImageMemoryBarrier barrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_NONE,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout = image.getLayout(),
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .image = image.handle(),
        .subresourceRange = {
            .aspectMask = image.getAspectFlags(),
            .levelCount = 1,
            .layerCount = 1
        }
    };
    image.setLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    vkCmdPipelineBarrier(
        commandBuffer.handle(),
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &barrier
    );

    auto extent = image.getExtent();
    const VkBufferImageCopy region{
        .bufferImageHeight = 0,
        .imageSubresource = {
            .aspectMask = image.getAspectFlags(),
            .layerCount = 1
        },
        .imageExtent = { extent.width, extent.height, 1 }
    };
    vkCmdCopyBufferToImage(
        commandBuffer.handle(),
        stagingBuffer.handle(), image.handle(),
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region
    );

    commandBuffer.end();

    Core::Fence fence(device);
    commandBuffer.submit(device.getComputeQueue(), fence);

    // wait for the upload to complete
    if (!fence.wait(device))
        throw LSFG::vulkan_error(VK_TIMEOUT, "Upload operation timed out");
}

void Utils::clearImage(const Core::Device& device, Core::Image& image, bool white) {
    Core::Fence fence(device);
    const Core::CommandPool cmdPool(device);
    Core::CommandBuffer cmdBuf(device, cmdPool);
    cmdBuf.begin();

    const VkImageMemoryBarrier2 barrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2,
        .dstStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
        .dstAccessMask = VK_ACCESS_2_TRANSFER_WRITE_BIT,
        .oldLayout = image.getLayout(),
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .image = image.handle(),
        .subresourceRange = {
            .aspectMask = image.getAspectFlags(),
            .levelCount = 1,
            .layerCount = 1
        }
    };
    image.setLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    Utils::cmdImageBarriers(cmdBuf.handle(), &barrier, 1);

    const float clearValue = white ? 1.0F : 0.0F;
    const VkClearColorValue clearColor = {{ clearValue, clearValue, clearValue, clearValue }};
    const VkImageSubresourceRange subresourceRange = {
        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
        .levelCount = 1,
        .layerCount = 1
    };
    vkCmdClearColorImage(cmdBuf.handle(),
        image.handle(), image.getLayout(),
        &clearColor,
        1, &subresourceRange);

    cmdBuf.end();

    cmdBuf.submit(device.getComputeQueue(), fence);
    if (!fence.wait(device))
        throw LSFG::vulkan_error(VK_TIMEOUT, "Failed to wait for clearing fence.");
}
