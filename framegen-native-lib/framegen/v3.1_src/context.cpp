#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "v3_1/context.hpp"
#include "common/utils.hpp"
#include "common/exception.hpp"

#include <vector>
#include <cstddef>
#include <algorithm>
#include <optional>
#include <cstdint>

using namespace LSFG_3_1;

#ifdef __ANDROID__
namespace {

void add_external_acquire(std::vector<VkImageMemoryBarrier2>& barriers,
        Vulkan& vk, Core::Image& image, VkAccessFlags2 accessMask) {
    if (!image.isExternalShared())
        return;

    barriers.emplace_back(VkImageMemoryBarrier2{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2,
        .srcStageMask = VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
        .srcAccessMask = 0,
        .dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
        .dstAccessMask = accessMask,
        .oldLayout = image.getLayout(),
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL,
        .dstQueueFamilyIndex = vk.device.getComputeFamilyIdx(),
        .image = image.handle(),
        .subresourceRange = {
            .aspectMask = image.getAspectFlags(),
            .levelCount = 1,
            .layerCount = 1,
        },
    });
}

void add_external_release(std::vector<VkImageMemoryBarrier2>& barriers,
        Vulkan& vk, Core::Image& image, VkAccessFlags2 accessMask) {
    if (!image.isExternalShared())
        return;

    barriers.emplace_back(VkImageMemoryBarrier2{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2,
        .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
        .srcAccessMask = accessMask,
        .dstStageMask = VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT,
        .dstAccessMask = 0,
        .oldLayout = image.getLayout(),
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = vk.device.getComputeFamilyIdx(),
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL,
        .image = image.handle(),
        .subresourceRange = {
            .aspectMask = image.getAspectFlags(),
            .levelCount = 1,
            .layerCount = 1,
        },
    });
}

void emit_external_barriers(const Core::CommandBuffer& buf,
        const std::vector<VkImageMemoryBarrier2>& barriers) {
    if (barriers.empty())
        return;

    // Classic barrier call: works on plain Vulkan 1.1 (no synchronization2).
    LSFG::Utils::cmdImageBarriers(buf.handle(), barriers.data(),
        static_cast<uint32_t>(barriers.size()));
}

} // namespace
#endif

Context::Context(Vulkan& vk,
        int in0, int in1, const std::vector<int>& outN,
        VkExtent2D extent, VkFormat format) {
    vk.memory.beginContext();
    // import input images
    this->inImg_0 = Core::Image(vk.device, extent, format,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT, in0);
    this->inImg_1 = Core::Image(vk.device, extent, format,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT, in1);

    // prepare render data
    for (size_t i = 0; i < 8; i++) {
        auto& data = this->data.at(i);
        data.internalSemaphores.resize(vk.generationCount);
        data.outSemaphores.resize(vk.generationCount);
        data.completionFences.resize(vk.generationCount);
        data.cmdBuffers2.resize(vk.generationCount);
    }

    // create shader chains
    this->mipmaps = Shaders::Mipmaps(vk, this->inImg_0, this->inImg_1);
    for (size_t i = 0; i < 7; i++)
        this->alpha.at(i) = Shaders::Alpha(vk, this->mipmaps.getOutImages().at(i));
    this->beta = Shaders::Beta(vk, this->alpha.at(0).getOutImages());
    for (size_t i = 0; i < 7; i++) {
        this->gamma.at(i) = Shaders::Gamma(vk,
            this->alpha.at(6 - i).getOutImages(),
            this->beta.getOutImages().at(std::min<size_t>(6 - i, 5)),
            (i == 0) ? std::nullopt : std::make_optional(this->gamma.at(i - 1).getOutImage()));
        if (i < 4) continue;

        this->delta.at(i - 4) = Shaders::Delta(vk,
            this->alpha.at(6 - i).getOutImages(),
            this->beta.getOutImages().at(6 - i),
            (i == 4) ? std::nullopt : std::make_optional(this->gamma.at(i - 1).getOutImage()),
            (i == 4) ? std::nullopt : std::make_optional(this->delta.at(i - 5).getOutImage1()),
            (i == 4) ? std::nullopt : std::make_optional(this->delta.at(i - 5).getOutImage2()));
    }
    this->generate = Shaders::Generate(vk,
        this->inImg_0, this->inImg_1,
        this->gamma.at(6).getOutImage(),
        this->delta.at(2).getOutImage1(),
        this->delta.at(2).getOutImage2(),
        outN, format);

    // bind the pooled image memory now that every image of this context exists
    vk.memory.finalize(vk.device);
}

void Context::present(Vulkan& vk,
        int inSem, const std::vector<int>& outSem) {
    auto& data = this->data.at(this->frameIdx % 8);

    // 3. wait for completion of previous frame in this slot
    if (data.shouldWait)
        for (auto& fence : data.completionFences)
            if (!fence.wait(vk.device, UINT64_MAX))
                throw LSFG::vulkan_error(VK_TIMEOUT, "Fence wait timed out");
    data.shouldWait = true;

    // 1. create mipmaps and process input image
    if (inSem >= 0) data.inSemaphore = Core::Semaphore(vk.device, inSem);
    // Internal (non-exported) semaphores are plain signal-once/wait-once
    // binary semaphores — a wait operation implicitly returns them to the
    // unsignaled state, so they're safe to keep across frames instead of
    // being recreated every present(). Only allocate on first use of this
    // ring slot.
    for (size_t i = 0; i < vk.generationCount; i++)
        if (!data.internalSemaphores.at(i).isValid())
            data.internalSemaphores.at(i) = Core::Semaphore(vk.device);

    // Reuse this ring slot's command buffer (reset instead of reallocate)
    // — the wait above already guarantees its prior submission has
    // finished on the GPU.
    if (!data.cmdBuffer1.isValid())
        data.cmdBuffer1 = Core::CommandBuffer(vk.device, vk.commandPool);
    else
        data.cmdBuffer1.reset();
    data.cmdBuffer1.begin();

#ifdef __ANDROID__
    {
        std::vector<VkImageMemoryBarrier2> acquireBarriers;
        acquireBarriers.reserve(2);
        add_external_acquire(acquireBarriers, vk, this->inImg_0, VK_ACCESS_2_SHADER_READ_BIT);
        add_external_acquire(acquireBarriers, vk, this->inImg_1, VK_ACCESS_2_SHADER_READ_BIT);
        emit_external_barriers(data.cmdBuffer1, acquireBarriers);
    }
#endif

    this->mipmaps.Dispatch(data.cmdBuffer1, this->frameIdx, this->frameIdx % 8);
    for (size_t i = 0; i < 7; i++)
        this->alpha.at(6 - i).Dispatch(data.cmdBuffer1, this->frameIdx);
    this->beta.Dispatch(data.cmdBuffer1, this->frameIdx);

    data.cmdBuffer1.end();
    std::vector<Core::Semaphore> waits = { data.inSemaphore };
    if (inSem < 0) waits.clear();
    data.cmdBuffer1.submit(vk.device.getComputeQueue(), std::nullopt,
        waits, std::nullopt,
        data.internalSemaphores, std::nullopt);

    // 2. generate intermediary frames
    for (size_t pass = 0; pass < vk.generationCount; pass++) {
        auto& internalSemaphore = data.internalSemaphores.at(pass);
        auto& outSemaphore = data.outSemaphores.at(pass);
        if (!outSem.empty()) {
            if (pass >= outSem.size())
                throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "Not enough output semaphores");
            outSemaphore = Core::Semaphore(vk.device, outSem.at(pass));
        }
        auto& completionFence = data.completionFences.at(pass);
        // Reuse the fence across frames — the wait at the top of this
        // function already guarantees it's signaled (its prior submission
        // is done) before we get here, so a reset is valid.
        if (!completionFence.isValid())
            completionFence = Core::Fence(vk.device);
        else
            completionFence.reset(vk.device);

        auto& buf2 = data.cmdBuffers2.at(pass);
        if (!buf2.isValid())
            buf2 = Core::CommandBuffer(vk.device, vk.commandPool);
        else
            buf2.reset();
        buf2.begin();

#ifdef __ANDROID__
        {
            std::vector<VkImageMemoryBarrier2> acquireBarriers;
            acquireBarriers.reserve(1);
            add_external_acquire(acquireBarriers, vk, this->generate.getOutImages().at(pass),
                VK_ACCESS_2_SHADER_WRITE_BIT);
            emit_external_barriers(buf2, acquireBarriers);
        }
#endif

        for (size_t i = 0; i < 7; i++) {
            this->gamma.at(i).Dispatch(buf2, this->frameIdx, pass);
            if (i >= 4)
                this->delta.at(i - 4).Dispatch(buf2, this->frameIdx, pass);
        }
        this->generate.Dispatch(buf2, this->frameIdx, pass, this->frameIdx % 8);

#ifdef __ANDROID__
        {
            std::vector<VkImageMemoryBarrier2> releaseBarriers;
            releaseBarriers.reserve(pass + 1 == vk.generationCount ? 3 : 1);
            add_external_release(releaseBarriers, vk, this->generate.getOutImages().at(pass),
                VK_ACCESS_2_SHADER_WRITE_BIT);
            if (pass + 1 == vk.generationCount) {
                add_external_release(releaseBarriers, vk, this->inImg_0, VK_ACCESS_2_SHADER_READ_BIT);
                add_external_release(releaseBarriers, vk, this->inImg_1, VK_ACCESS_2_SHADER_READ_BIT);
            }
            emit_external_barriers(buf2, releaseBarriers);
        }
#endif

        buf2.end();
        std::vector<Core::Semaphore> signals;
        if (outSemaphore.isValid())
            signals.push_back(outSemaphore);
        buf2.submit(vk.device.getComputeQueue(), completionFence,
            { internalSemaphore }, std::nullopt,
            signals, std::nullopt);
    }

    this->frameIdx++;
}

#ifdef __ANDROID__
void Context::presentAHB(Vulkan& vk, AHardwareBuffer* current, AHardwareBuffer* previous,
        int inSem, const std::vector<int>& outSem) {
    const size_t inputSlot = this->frameIdx % 8;
    auto& data = this->data.at(inputSlot);

    // 3. wait for completion of previous frame in this slot
    if (data.shouldWait)
        for (auto& fence : data.completionFences)
            if (!fence.wait(vk.device, UINT64_MAX))
                throw LSFG::vulkan_error(VK_TIMEOUT, "Fence wait timed out");
    data.shouldWait = true;

    // The ring slot is now known to be idle. Import and bind the exact capture
    // AHBs for this submission. This is descriptor/image metadata only; the
    // pixel storage remains the original capture buffers (zero-copy).
    this->mipmaps.bindExternalInputs(vk, inputSlot, current, previous);
    const size_t parity = this->frameIdx % 2;
    Core::Image& currentImg = this->mipmaps.getInputImage(inputSlot, parity);
    Core::Image& previousImg = this->mipmaps.getInputImage(inputSlot, 1 - parity);
    this->generate.bindExternalInputImages(vk, inputSlot, currentImg, previousImg);

    // 1. create mipmaps and process input image
    if (inSem >= 0) data.inSemaphore = Core::Semaphore(vk.device, inSem);
    // Internal (non-exported) semaphores are plain signal-once/wait-once
    // binary semaphores — a wait operation implicitly returns them to the
    // unsignaled state, so they're safe to keep across frames instead of
    // being recreated every present(). Only allocate on first use of this
    // ring slot.
    for (size_t i = 0; i < vk.generationCount; i++)
        if (!data.internalSemaphores.at(i).isValid())
            data.internalSemaphores.at(i) = Core::Semaphore(vk.device);

    // Reuse this ring slot's command buffer (reset instead of reallocate)
    // — the wait above already guarantees its prior submission has
    // finished on the GPU.
    if (!data.cmdBuffer1.isValid())
        data.cmdBuffer1 = Core::CommandBuffer(vk.device, vk.commandPool);
    else
        data.cmdBuffer1.reset();
    data.cmdBuffer1.begin();

#ifdef __ANDROID__
    {
        std::vector<VkImageMemoryBarrier2> acquireBarriers;
        acquireBarriers.reserve(2);
        add_external_acquire(acquireBarriers, vk, this->mipmaps.getInputImage(inputSlot, this->frameIdx % 2), VK_ACCESS_2_SHADER_READ_BIT);
        add_external_acquire(acquireBarriers, vk, this->mipmaps.getInputImage(inputSlot, 1 - (this->frameIdx % 2)), VK_ACCESS_2_SHADER_READ_BIT);
        emit_external_barriers(data.cmdBuffer1, acquireBarriers);
    }
#endif

    this->mipmaps.Dispatch(data.cmdBuffer1, this->frameIdx, this->frameIdx % 8);
    for (size_t i = 0; i < 7; i++)
        this->alpha.at(6 - i).Dispatch(data.cmdBuffer1, this->frameIdx);
    this->beta.Dispatch(data.cmdBuffer1, this->frameIdx);

    data.cmdBuffer1.end();
    std::vector<Core::Semaphore> waits = { data.inSemaphore };
    if (inSem < 0) waits.clear();
    data.cmdBuffer1.submit(vk.device.getComputeQueue(), std::nullopt,
        waits, std::nullopt,
        data.internalSemaphores, std::nullopt);

    // 2. generate intermediary frames
    for (size_t pass = 0; pass < vk.generationCount; pass++) {
        auto& internalSemaphore = data.internalSemaphores.at(pass);
        auto& outSemaphore = data.outSemaphores.at(pass);
        if (!outSem.empty()) {
            if (pass >= outSem.size())
                throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "Not enough output semaphores");
            outSemaphore = Core::Semaphore(vk.device, outSem.at(pass));
        }
        auto& completionFence = data.completionFences.at(pass);
        // Reuse the fence across frames — the wait at the top of this
        // function already guarantees it's signaled (its prior submission
        // is done) before we get here, so a reset is valid.
        if (!completionFence.isValid())
            completionFence = Core::Fence(vk.device);
        else
            completionFence.reset(vk.device);

        auto& buf2 = data.cmdBuffers2.at(pass);
        if (!buf2.isValid())
            buf2 = Core::CommandBuffer(vk.device, vk.commandPool);
        else
            buf2.reset();
        buf2.begin();

#ifdef __ANDROID__
        {
            std::vector<VkImageMemoryBarrier2> acquireBarriers;
            acquireBarriers.reserve(1);
            add_external_acquire(acquireBarriers, vk, this->generate.getOutImages().at(pass),
                VK_ACCESS_2_SHADER_WRITE_BIT);
            emit_external_barriers(buf2, acquireBarriers);
        }
#endif

        for (size_t i = 0; i < 7; i++) {
            this->gamma.at(i).Dispatch(buf2, this->frameIdx, pass);
            if (i >= 4)
                this->delta.at(i - 4).Dispatch(buf2, this->frameIdx, pass);
        }
        this->generate.Dispatch(buf2, this->frameIdx, pass, this->frameIdx % 8);

#ifdef __ANDROID__
        {
            std::vector<VkImageMemoryBarrier2> releaseBarriers;
            releaseBarriers.reserve(pass + 1 == vk.generationCount ? 3 : 1);
            add_external_release(releaseBarriers, vk, this->generate.getOutImages().at(pass),
                VK_ACCESS_2_SHADER_WRITE_BIT);
            if (pass + 1 == vk.generationCount) {
                add_external_release(releaseBarriers, vk, this->generate.getInputImage(inputSlot, this->frameIdx % 2), VK_ACCESS_2_SHADER_READ_BIT);
                add_external_release(releaseBarriers, vk, this->generate.getInputImage(inputSlot, 1 - (this->frameIdx % 2)), VK_ACCESS_2_SHADER_READ_BIT);
            }
            emit_external_barriers(buf2, releaseBarriers);
        }
#endif

        buf2.end();
        std::vector<Core::Semaphore> signals;
        if (outSemaphore.isValid())
            signals.push_back(outSemaphore);
        buf2.submit(vk.device.getComputeQueue(), completionFence,
            { internalSemaphore }, std::nullopt,
            signals, std::nullopt);
    }

    this->frameIdx++;
}

#endif

void Context::waitForCompletion(Vulkan& vk) const {
    if (this->frameIdx == 0)
        return; // present() never called, nothing to wait on

    // The slot present() just finished writing into. This holds the fresh
    // completionFences from that submission (present() reassigns them each
    // call), not the older set that present() itself waits on internally to
    // recycle the slot 8 frames later.
    const auto& data = this->data.at((this->frameIdx - 1) % 8);
    for (const auto& fence : data.completionFences)
        if (!fence.wait(vk.device, UINT64_MAX))
            throw LSFG::vulkan_error(VK_TIMEOUT, "Fence wait timed out");
}

#ifdef __ANDROID__

#include <android/hardware_buffer.h>

Context::Context(Vulkan& vk,
        AHardwareBuffer* in0, AHardwareBuffer* in1,
        const std::vector<AHardwareBuffer*>& outN,
        VkExtent2D extent, VkFormat format) {
    vk.memory.beginContext();
    // import inputs from AHB
    this->inImg_0 = Core::Image(vk.device, extent, format,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT, in0);
    this->inImg_1 = Core::Image(vk.device, extent, format,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT, in1);

    // import outputs from AHB into Core::Image instances; we'll hand them to
    // Generate's from-images ctor below.
    std::vector<Core::Image> outImgs;
    outImgs.reserve(outN.size());
    for (auto* ahb : outN) {
        outImgs.emplace_back(vk.device, extent, format,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT, ahb);
    }

    // prepare render data
    for (size_t i = 0; i < 8; i++) {
        auto& data = this->data.at(i);
        data.internalSemaphores.resize(vk.generationCount);
        data.outSemaphores.resize(vk.generationCount);
        data.completionFences.resize(vk.generationCount);
        data.cmdBuffers2.resize(vk.generationCount);
    }

    // build the same shader chain as the FD ctor — only Generate differs
    // (it now takes pre-built outImgs instead of FDs).
    this->mipmaps = Shaders::Mipmaps(vk, this->inImg_0, this->inImg_1);
    for (size_t i = 0; i < 7; i++)
        this->alpha.at(i) = Shaders::Alpha(vk, this->mipmaps.getOutImages().at(i));
    this->beta = Shaders::Beta(vk, this->alpha.at(0).getOutImages());
    for (size_t i = 0; i < 7; i++) {
        this->gamma.at(i) = Shaders::Gamma(vk,
            this->alpha.at(6 - i).getOutImages(),
            this->beta.getOutImages().at(std::min<size_t>(6 - i, 5)),
            (i == 0) ? std::nullopt : std::make_optional(this->gamma.at(i - 1).getOutImage()));
        if (i < 4) continue;

        this->delta.at(i - 4) = Shaders::Delta(vk,
            this->alpha.at(6 - i).getOutImages(),
            this->beta.getOutImages().at(6 - i),
            (i == 4) ? std::nullopt : std::make_optional(this->gamma.at(i - 1).getOutImage()),
            (i == 4) ? std::nullopt : std::make_optional(this->delta.at(i - 5).getOutImage1()),
            (i == 4) ? std::nullopt : std::make_optional(this->delta.at(i - 5).getOutImage2()));
    }
    this->generate = Shaders::Generate(vk,
        this->inImg_0, this->inImg_1,
        this->gamma.at(6).getOutImage(),
        this->delta.at(2).getOutImage1(),
        this->delta.at(2).getOutImage2(),
        std::move(outImgs));

    // bind the pooled image memory now that every image of this context exists
    vk.memory.finalize(vk.device);
}

#endif // __ANDROID__
