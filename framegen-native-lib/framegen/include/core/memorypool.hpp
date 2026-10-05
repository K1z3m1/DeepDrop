#pragma once

#include "core/image.hpp"

#include <vulkan/vulkan_core.h>

#include <cstdint>
#include <map>
#include <memory>
#include <vector>

namespace LSFG::Core {

    class Device;
    class CommandBuffer;

    ///
    /// Runtime switches for the image memory pool. Both default to off, so the
    /// library behaves exactly like before unless the host opts in.
    ///
    struct MemoryOptions {
        /// Sub-allocate the long-lived internal images out of one shared
        /// VkDeviceMemory block instead of one vkAllocateMemory per image.
        bool pooled{false};
        /// Let the per-stage scratch ("temp") images of different shader stages
        /// share the same memory, since only one stage uses its scratch at a time.
        bool aliasScratch{false};
    };

    /// Totals from the most recent MemoryPool::finalize(), in bytes.
    struct MemoryStats {
        uint64_t persistentBytes{0};   ///< pooled long-lived images
        uint64_t scratchBytes{0};      ///< scratch block actually allocated
        uint64_t scratchUnaliasedBytes{0}; ///< what scratch would cost without aliasing
        uint32_t allocationCount{0};   ///< vkAllocateMemory calls made by the pool
    };

    ///
    /// The scratch images belonging to one shader stage. A stage calls begin()
    /// at the start of its Dispatch(); when aliasing is active that emits the
    /// barrier which makes reusing another stage's memory safe.
    ///
    class ScratchScope {
    public:
        explicit ScratchScope(bool aliased) : aliased(aliased) {}

        /// Register a scratch image as part of this stage.
        void add(const Image& image) { this->images.push_back(image); }

        ///
        /// Call first thing inside a stage's Dispatch(). No-op unless aliasing.
        ///
        void begin(const CommandBuffer& buffer);
    private:
        bool aliased;
        std::vector<Image> images;
    };

    ///
    /// Sub-allocating / aliasing allocator for internal images.
    ///
    /// Images are created (and their views made) immediately, but memory is
    /// bound in finalize(), once the exact block sizes are known. Call
    /// finalize() after building a whole context and before the first submit.
    ///
    class MemoryPool {
    public:
        MemoryPool() = default;

        void configure(MemoryOptions newOptions) { this->options = newOptions; }
        [[nodiscard]] const MemoryOptions& getOptions() const { return this->options; }

        /// Call at the start of building a context. Drops anything left over from
        /// a context whose construction failed part-way.
        void beginContext();

        /// Start a new shader stage; scratch images created afterwards belong
        /// to it. Safe to call when aliasing is off (the scope is then a no-op).
        std::shared_ptr<ScratchScope> beginScope();

        /// Long-lived image (kept across frames).
        Image persistent(const Device& device, VkExtent2D extent,
            VkFormat format = VK_FORMAT_R8G8B8A8_UNORM);

        /// Stage-local scratch image. Falls back to persistent() when aliasing is off.
        Image scratch(const Device& device, VkExtent2D extent,
            VkFormat format = VK_FORMAT_R8G8B8A8_UNORM);

        /// Allocate the pooled blocks and bind every pending image.
        void finalize(const Device& device);

        /// Used by Image: reserve space for an already-created image.
        std::shared_ptr<VkDeviceMemory> reserve(VkImage image,
            const VkMemoryRequirements& requirements, MemoryPlacement placement);
    private:
        struct Block;
        struct Group {
            std::shared_ptr<Block> persistent;
            VkDeviceSize persistentCursor{0};
            std::shared_ptr<Block> scratch;
            VkDeviceSize scratchCursor{0};
            VkDeviceSize scratchSize{0};
            uint64_t scratchEpoch{UINT64_MAX};
        };
        struct Pending {
            VkImage image{};
            std::shared_ptr<Block> block;
            VkDeviceSize offset{0};
        };

        MemoryOptions options;
        std::map<uint32_t, Group> groups; // keyed by memoryTypeBits
        std::vector<Pending> pending;
        std::shared_ptr<ScratchScope> currentScope;
        uint64_t epoch{0};
        uint64_t scratchUnaliased{0};
    };

}
