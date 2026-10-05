#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "core/memorypool.hpp"
#include "core/commandbuffer.hpp"
#include "core/device.hpp"
#include "core/image.hpp"
#include "common/exception.hpp"
#include "lsfg_memory.hpp"

#include <algorithm>
#include <cstdint>
#include <memory>

using namespace LSFG;
using namespace LSFG::Core;

namespace {
    MemoryStats globalStats;

    VkDeviceSize alignUp(VkDeviceSize value, VkDeviceSize alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    constexpr VkImageUsageFlags kUsage =
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
}

// ---- public switches ---------------------------------------------------------

Core::MemoryStats LSFG::getMemoryStats() {
    return globalStats;
}

// ---- Block -------------------------------------------------------------------

struct MemoryPool::Block {
    VkDevice device{};
    VkDeviceMemory handle{};

    Block() = default;
    Block(const Block&) = delete;
    Block& operator=(const Block&) = delete;
    ~Block() {
        if (handle != VK_NULL_HANDLE)
            vkFreeMemory(device, handle, nullptr);
    }
};

// ---- ScratchScope ------------------------------------------------------------

void ScratchScope::begin(const CommandBuffer& buffer) {
    if (!this->aliased)
        return;

    // Another stage may have written this memory since we last used it, so the
    // old contents are garbage: drop the tracked layout (the stage's own barriers
    // then transition from UNDEFINED) ...
    for (auto& image : this->images)
        image.setLayout(VK_IMAGE_LAYOUT_UNDEFINED);

    // ... and order this stage after everything before it in submission order,
    // including earlier submissions on the same queue that touched the same bytes.
    const VkMemoryBarrier barrier{
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
    };
    vkCmdPipelineBarrier(buffer.handle(),
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &barrier, 0, nullptr, 0, nullptr);
}

// ---- MemoryPool --------------------------------------------------------------

void MemoryPool::beginContext() {
    this->pending.clear();
    this->groups.clear();
    this->currentScope.reset();
    this->scratchUnaliased = 0;
}

std::shared_ptr<ScratchScope> MemoryPool::beginScope() {
    this->epoch++;
    this->currentScope = std::make_shared<ScratchScope>(this->options.aliasScratch);
    return this->currentScope;
}

Image MemoryPool::persistent(const Device& device, VkExtent2D extent, VkFormat format) {
    if (!this->options.pooled)
        return Image(device, extent, format);
    return Image(device, extent, format, kUsage, VK_IMAGE_ASPECT_COLOR_BIT,
        *this, MemoryPlacement::Persistent);
}

Image MemoryPool::scratch(const Device& device, VkExtent2D extent, VkFormat format) {
    if (!this->options.aliasScratch)
        return this->persistent(device, extent, format);

    Image image(device, extent, format, kUsage, VK_IMAGE_ASPECT_COLOR_BIT,
        *this, MemoryPlacement::Scratch);
    if (this->currentScope)
        this->currentScope->add(image);
    return image;
}

std::shared_ptr<VkDeviceMemory> MemoryPool::reserve(VkImage image,
        const VkMemoryRequirements& requirements, MemoryPlacement placement) {
    auto& group = this->groups[requirements.memoryTypeBits];
    const VkDeviceSize alignment = std::max<VkDeviceSize>(requirements.alignment, 1);

    std::shared_ptr<Block> block;
    VkDeviceSize offset{};
    if (placement == MemoryPlacement::Scratch) {
        // A new scope starts again at the beginning of the shared region.
        if (group.scratchEpoch != this->epoch) {
            group.scratchCursor = 0;
            group.scratchEpoch = this->epoch;
        }
        offset = alignUp(group.scratchCursor, alignment);
        group.scratchCursor = offset + requirements.size;
        group.scratchSize = std::max(group.scratchSize, group.scratchCursor);
        this->scratchUnaliased += requirements.size;
        if (!group.scratch)
            group.scratch = std::make_shared<Block>();
        block = group.scratch;
    } else {
        offset = alignUp(group.persistentCursor, alignment);
        group.persistentCursor = offset + requirements.size;
        if (!group.persistent)
            group.persistent = std::make_shared<Block>();
        block = group.persistent;
    }

    this->pending.push_back(Pending{ .image = image, .block = block, .offset = offset });
    // Aliasing constructor: the Image's memory pointer keeps the whole block
    // alive and dereferences to the VkDeviceMemory once finalize() has made it.
    return std::shared_ptr<VkDeviceMemory>(block, &block->handle);
}

void MemoryPool::finalize(const Device& device) {
    if (this->pending.empty()) {
        this->groups.clear();
        this->currentScope.reset();
        return;
    }

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), &memProps);

    MemoryStats stats;
    auto allocate = [&](Block& block, VkDeviceSize size, uint32_t typeBits) {
        uint32_t typeIndex = UINT32_MAX;
        for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
            if ((typeBits & (1U << i)) &&
                    (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                typeIndex = i;
                break;
            }
        }
        if (typeIndex == UINT32_MAX)
            throw LSFG::vulkan_error(VK_ERROR_UNKNOWN, "Unable to find memory type for pooled images");

        const VkMemoryAllocateInfo info{
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = size,
            .memoryTypeIndex = typeIndex
        };
        block.device = device.handle();
        const auto res = vkAllocateMemory(device.handle(), &info, nullptr, &block.handle);
        if (res != VK_SUCCESS || block.handle == VK_NULL_HANDLE)
            throw LSFG::vulkan_error(res, "Failed to allocate pooled image memory");
        stats.allocationCount++;
    };

    for (auto& [typeBits, group] : this->groups) {
        if (group.persistent && group.persistentCursor > 0) {
            allocate(*group.persistent, group.persistentCursor, typeBits);
            stats.persistentBytes += group.persistentCursor;
        }
        if (group.scratch && group.scratchSize > 0) {
            allocate(*group.scratch, group.scratchSize, typeBits);
            stats.scratchBytes += group.scratchSize;
        }
    }
    stats.scratchUnaliasedBytes = this->scratchUnaliased;

    for (const auto& item : this->pending) {
        const auto res = vkBindImageMemory(device.handle(), item.image,
            item.block->handle, item.offset);
        if (res != VK_SUCCESS)
            throw LSFG::vulkan_error(res, "Failed to bind pooled memory to Vulkan image");
    }

    globalStats = stats;
    this->pending.clear();
    this->groups.clear();
    this->currentScope.reset();
    this->scratchUnaliased = 0;
}
