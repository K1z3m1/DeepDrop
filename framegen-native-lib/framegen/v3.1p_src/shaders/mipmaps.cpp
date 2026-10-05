#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "v3_1p/shaders/mipmaps.hpp"
#include "common/utils.hpp"
#include "core/image.hpp"
#include "core/commandbuffer.hpp"

#include <utility>
#include <cstddef>
#include <cstdint>

using namespace LSFG_3_1P::Shaders;

Mipmaps::Mipmaps(Vulkan& vk,
        Core::Image inImg_0, Core::Image inImg_1)
        : inImg_0(std::move(inImg_0)), inImg_1(std::move(inImg_1)) {
    // create resources
    this->shaderModule = vk.shaders.getShader(vk.device, "p_mipmaps",
        { { 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER },
          { 1, VK_DESCRIPTOR_TYPE_SAMPLER },
          { 1, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE },
          { 7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE } });
    this->pipeline = vk.shaders.getPipeline(vk.device, "p_mipmaps");
    this->buffer = vk.resources.getBuffer(vk.device);
    this->sampler = vk.resources.getSampler(vk.device);
    for (size_t slot = 0; slot < 8; slot++)
        for (size_t i = 0; i < 2; i++)
            this->descriptorSets.at(slot).at(i) = Core::DescriptorSet(vk.device, vk.descriptorPool, this->shaderModule);

    // create outputs
    const VkExtent2D flowExtent{
        .width = static_cast<uint32_t>(
            static_cast<float>(this->inImg_0.getExtent().width) / vk.flowScale),
        .height = static_cast<uint32_t>(
            static_cast<float>(this->inImg_0.getExtent().height) / vk.flowScale)
    };
    for (size_t i = 0; i < 7; i++)
        this->outImgs.at(i) = vk.memory.persistent(vk.device,
            { flowExtent.width >> i, flowExtent.height >> i },
            VK_FORMAT_R8_UNORM);

    // hook up shaders
    for (size_t slot = 0; slot < 8; slot++) {
        this->inputImg0Ring.at(slot) = this->inImg_0;
        this->inputImg1Ring.at(slot) = this->inImg_1;
        for (size_t fc = 0; fc < 2; fc++)
            this->descriptorSets.at(slot).at(fc).update(vk.device)
                .add(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, this->buffer)
                .add(VK_DESCRIPTOR_TYPE_SAMPLER, this->sampler)
                .add(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, (fc % 2 == 0) ? this->inImg_0 : this->inImg_1)
                .add(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, this->outImgs)
                .build();
    }
}

#ifdef __ANDROID__
void Mipmaps::bindExternalInputs(Vulkan& vk, size_t slot, AHardwareBuffer* in0, AHardwareBuffer* in1) {
    slot %= 8;
    const auto extent = this->inImg_0.getExtent();
    const auto format = this->inImg_0.getFormat();
    this->inputImg0Ring.at(slot) = Core::Image(vk.device, extent, format,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT, in0);
    this->inputImg1Ring.at(slot) = Core::Image(vk.device, extent, format,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT, in1);
    for (size_t fc = 0; fc < 2; fc++)
        this->descriptorSets.at(slot).at(fc).update(vk.device)
            .add(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, this->buffer)
            .add(VK_DESCRIPTOR_TYPE_SAMPLER, this->sampler)
            .add(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, (fc % 2 == 0) ? this->inputImg0Ring.at(slot) : this->inputImg1Ring.at(slot))
            .add(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, this->outImgs)
            .build();
}
#endif

Core::Image& Mipmaps::getInputImage(size_t slot, size_t parity) {
    return (parity % 2 == 0) ? this->inputImg0Ring.at(slot % 8) : this->inputImg1Ring.at(slot % 8);
}

void Mipmaps::Dispatch(const Core::CommandBuffer& buf, uint64_t frameCount, size_t inputSlot) {
    // first pass
    const auto flowExtent = this->outImgs.at(0).getExtent();
    const uint32_t threadsX = (flowExtent.width + 63) >> 6;
    const uint32_t threadsY = (flowExtent.height + 63) >> 6;

    const size_t slot = inputSlot % 8;
    Core::Image& current = (frameCount % 2 == 0) ? this->inputImg0Ring.at(slot) : this->inputImg1Ring.at(slot);
    Core::Image& other = (frameCount % 2 == 0) ? this->inputImg1Ring.at(slot) : this->inputImg0Ring.at(slot);
    Utils::BarrierBuilder(buf)
        .addW2R(current)
        .addW2R(other)
        .addR2W(this->outImgs)
        .build();

    this->pipeline.bind(buf);
    this->descriptorSets.at(slot).at(frameCount % 2).bind(buf, this->pipeline);
    buf.dispatch(threadsX, threadsY, 1);
}
