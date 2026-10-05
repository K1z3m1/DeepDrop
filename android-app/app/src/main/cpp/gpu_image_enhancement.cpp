#include "gpu_image_enhancement.hpp"

#include <glslang/Public/ShaderLang.h>
#include <glslang/Public/ResourceLimits.h>
#include <glslang/SPIRV/GlslangToSpv.h>

#include <android/log.h>
#include <array>
#include <algorithm>
#include <cstddef>
#include <string>
#include <vector>

#define LOG_TAG "lsfg-gpu-enhance"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace lsfg_android {
namespace {

const char *kComputeShader = R"GLSL(
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0, rgba8) readonly uniform image2D srcImage;
layout(binding = 1, rgba8) writeonly uniform image2D dstImage;

layout(push_constant) uniform Params {
    float contrast;
    float saturation;
    float srcWidth;
    float srcHeight;
    float dstWidth;
    float dstHeight;
    float filterMode;
    float reserved;
} p;

vec4 enhance(vec4 c) {
    c.rgb = (c.rgb - 0.5) * p.contrast + 0.5;
    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    c.rgb = luma + (c.rgb - luma) * p.saturation;
    c.rgb = clamp(c.rgb, 0.0, 1.0);
    return c;
}

vec4 sampleSource(ivec2 q) {
    q = clamp(q, ivec2(0), ivec2(int(p.srcWidth) - 1, int(p.srcHeight) - 1));
    return enhance(imageLoad(srcImage, q));
}

void main() {
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    ivec2 dstSize = imageSize(dstImage);
    if (q.x >= dstSize.x || q.y >= dstSize.y) return;

    // Map destination pixel centers into source pixel space. Enhancement is
    // evaluated before interpolation, so the logical order is:
    // source -> enhancement -> selected upscale filter -> destination.
    vec2 srcPos = ((vec2(q) + vec2(0.5)) *
                   vec2(p.srcWidth, p.srcHeight) /
                   vec2(p.dstWidth, p.dstHeight)) - vec2(0.5);

    vec4 result;
    if (p.filterMode < 0.5) {
        result = sampleSource(ivec2(floor(srcPos + vec2(0.5))));
    } else {
        ivec2 base = ivec2(floor(srcPos));
        vec2 f = fract(srcPos);
        vec4 c00 = sampleSource(base);
        vec4 c10 = sampleSource(base + ivec2(1, 0));
        vec4 c01 = sampleSource(base + ivec2(0, 1));
        vec4 c11 = sampleSource(base + ivec2(1, 1));
        result = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
    }

    imageStore(dstImage, q, result);
}
)GLSL";

bool compileShader(std::vector<uint32_t> &spirv) {
    if (!glslang::InitializeProcess()) {
        LOGE("glslang InitializeProcess failed");
        return false;
    }

    glslang::TShader shader(EShLangCompute);
    shader.setStrings(&kComputeShader, 1);
    shader.setEntryPoint("main");
    shader.setSourceEntryPoint("main");
    shader.setEnvInput(glslang::EShSourceGlsl, EShLangCompute,
                       glslang::EShClientVulkan, 0);
    shader.setEnvClient(glslang::EShClientVulkan,
                        glslang::EShTargetVulkan_1_1);
    shader.setEnvTarget(glslang::EShTargetSpv,
                        glslang::EShTargetSpv_1_3);

    const TBuiltInResource *resources = GetDefaultResources();
    const EShMessages messages =
        static_cast<EShMessages>(EShMsgVulkanRules | EShMsgSpvRules);

    const bool parsed = shader.parse(resources, 450, false, messages);
    if (!parsed) {
        LOGE("GPU enhancement shader parse failed: %s", shader.getInfoLog());
        LOGE("GPU enhancement shader debug: %s", shader.getInfoDebugLog());
        glslang::FinalizeProcess();
        return false;
    }

    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(messages)) {
        LOGE("GPU enhancement shader link failed: %s", program.getInfoLog());
        LOGE("GPU enhancement shader debug: %s", program.getInfoDebugLog());
        glslang::FinalizeProcess();
        return false;
    }

    glslang::SpvOptions options;
    options.generateDebugInfo = false;
    options.disableOptimizer = false;
    options.optimizeSize = true;
    glslang::GlslangToSpv(
        *program.getIntermediate(EShLangCompute), spirv, &options);

    glslang::FinalizeProcess();
    return !spirv.empty();
}

// Max distinct destination VkImage handles (swapchain images across the
// session's swapchain rebuilds) cached at once. record() below evicts the
// least-recently-used entry once this is reached, so a long session that
// cycles through more handles than this degrades gracefully instead of the
// enhancement pass permanently falling back to a plain copy.
constexpr uint32_t kMaxDestinationBindings = 32;

} // namespace

bool GpuImageEnhancement::initialize(VulkanSession &vk) {
    if (pipeline_ != VK_NULL_HANDLE) return true;
    if (vk.device == VK_NULL_HANDLE) return false;

    std::vector<uint32_t> spirv;
    if (!compileShader(spirv)) return false;

    const VkShaderModuleCreateInfo smi{
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = spirv.size() * sizeof(uint32_t),
        .pCode = spirv.data(),
    };
    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vk.fn.vkCreateShaderModule(vk.device, &smi, nullptr, &shaderModule) != VK_SUCCESS) {
        LOGE("vkCreateShaderModule failed");
        return false;
    }

    const std::array<VkDescriptorSetLayoutBinding, 2> bindings{{
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
        {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
    }};
    const VkDescriptorSetLayoutCreateInfo dli{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = static_cast<uint32_t>(bindings.size()),
        .pBindings = bindings.data(),
    };
    if (vk.fn.vkCreateDescriptorSetLayout(vk.device, &dli, nullptr,
                                           &descriptorSetLayout_) != VK_SUCCESS) {
        vk.fn.vkDestroyShaderModule(vk.device, shaderModule, nullptr);
        return false;
    }

    const VkPushConstantRange push{
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = sizeof(float) * 8,
    };
    const VkPipelineLayoutCreateInfo pli{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &descriptorSetLayout_,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &push,
    };
    if (vk.fn.vkCreatePipelineLayout(vk.device, &pli, nullptr,
                                     &pipelineLayout_) != VK_SUCCESS) {
        vk.fn.vkDestroyShaderModule(vk.device, shaderModule, nullptr);
        return false;
    }

    const VkPipelineShaderStageCreateInfo stage{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
        .stage = VK_SHADER_STAGE_COMPUTE_BIT,
        .module = shaderModule,
        .pName = "main",
    };
    const VkComputePipelineCreateInfo pci{
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = stage,
        .layout = pipelineLayout_,
    };
    const VkResult pr = vk.fn.vkCreateComputePipelines(
        vk.device, VK_NULL_HANDLE, 1, &pci, nullptr, &pipeline_);
    vk.fn.vkDestroyShaderModule(vk.device, shaderModule, nullptr);
    if (pr != VK_SUCCESS) {
        LOGE("vkCreateComputePipelines failed (%d)", static_cast<int>(pr));
        destroy(vk);
        return false;
    }

    const VkDescriptorPoolSize poolSize{
        .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        .descriptorCount = kMaxDestinationBindings * 2,
    };
    const VkDescriptorPoolCreateInfo dpi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        // Lets record() free a single evicted DestinationBinding's set (LRU
        // eviction below) instead of only being able to reset the whole pool.
        .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
        .maxSets = kMaxDestinationBindings,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
    };
    if (vk.fn.vkCreateDescriptorPool(vk.device, &dpi, nullptr,
                                      &descriptorPool_) != VK_SUCCESS) {
        destroy(vk);
        return false;
    }

    LOGI("Real Vulkan image-enhancement + upscale compute pipeline initialized");
    return true;
}

bool GpuImageEnhancement::record(VulkanSession &vk, VkCommandBuffer cb,
                                 VkImage source, VkExtent2D sourceExtent,
                                 VkImage destination, VkExtent2D destinationExtent,
                                 int upscaleFilterMode, float contrast,
                                 float saturation) {
    if (!initialize(vk) || cb == VK_NULL_HANDLE ||
        source == VK_NULL_HANDLE || destination == VK_NULL_HANDLE ||
        sourceExtent.width == 0 || sourceExtent.height == 0 ||
        destinationExtent.width == 0 || destinationExtent.height == 0) {
        return false;
    }

    VkImageView sourceView = VK_NULL_HANDLE;
    for (const auto &entry : sourceViews_) {
        if (entry.image == source) {
            sourceView = entry.view;
            break;
        }
    }
    if (sourceView == VK_NULL_HANDLE) {
        VkImageViewCreateInfo vci{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = source,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1
            },
        };
        if (vk.fn.vkCreateImageView(vk.device, &vci, nullptr, &sourceView) != VK_SUCCESS) {
            LOGW("GPU enhancement: source vkCreateImageView failed");
            return false;
        }
        sourceViews_.push_back({source, sourceView});
    }

    DestinationBinding *destinationBinding = nullptr;
    for (size_t i = 0; i < destinationBindings_.size(); ++i) {
        if (destinationBindings_[i].image == destination) {
            // Move the hit to the back so the front stays the
            // least-recently-used entry for the eviction below.
            if (i + 1 != destinationBindings_.size()) {
                const auto off = static_cast<std::ptrdiff_t>(i);
                std::rotate(destinationBindings_.begin() + off,
                            destinationBindings_.begin() + off + 1,
                            destinationBindings_.end());
            }
            destinationBinding = &destinationBindings_.back();
            break;
        }
    }
    if (destinationBinding == nullptr) {
        if (destinationBindings_.size() >= kMaxDestinationBindings) {
            // A long session can cycle through more distinct swapchain image
            // handles than the cap (repeated swapchain rebuilds: rotation,
            // HDR toggles, live settings changes, ...). Evict the
            // least-recently-used entry instead of refusing every new handle
            // from here on, which previously made the enhancement pass fall
            // back to a plain copy for the rest of the session.
            DestinationBinding &lru = destinationBindings_.front();
            if (lru.view != VK_NULL_HANDLE)
                vk.fn.vkDestroyImageView(vk.device, lru.view, nullptr);
            if (lru.set != VK_NULL_HANDLE)
                vk.fn.vkFreeDescriptorSets(vk.device, descriptorPool_, 1, &lru.set);
            destinationBindings_.erase(destinationBindings_.begin());
        }

        VkImageViewCreateInfo vci{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = destination,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .subresourceRange = {
                VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1
            },
        };
        VkImageView view = VK_NULL_HANDLE;
        if (vk.fn.vkCreateImageView(vk.device, &vci, nullptr, &view) != VK_SUCCESS) {
            LOGW("GPU enhancement: destination vkCreateImageView failed");
            return false;
        }

        const VkDescriptorSetAllocateInfo dai{
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = descriptorPool_,
            .descriptorSetCount = 1,
            .pSetLayouts = &descriptorSetLayout_,
        };
        VkDescriptorSet set = VK_NULL_HANDLE;
        if (vk.fn.vkAllocateDescriptorSets(vk.device, &dai, &set) != VK_SUCCESS) {
            vk.fn.vkDestroyImageView(vk.device, view, nullptr);
            LOGW("GPU enhancement: vkAllocateDescriptorSets failed");
            return false;
        }

        destinationBindings_.push_back({destination, view, set});
        destinationBinding = &destinationBindings_.back();
    }

    const VkDescriptorImageInfo srcInfo{
        .sampler = VK_NULL_HANDLE,
        .imageView = sourceView,
        .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
    };
    const VkDescriptorImageInfo dstInfo{
        .sampler = VK_NULL_HANDLE,
        .imageView = destinationBinding->view,
        .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
    };
    const std::array<VkWriteDescriptorSet, 2> writes{{
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = destinationBinding->set,
            .dstBinding = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &srcInfo,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = destinationBinding->set,
            .dstBinding = 1,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &dstInfo,
        },
    }};
    vk.fn.vkUpdateDescriptorSets(vk.device, static_cast<uint32_t>(writes.size()),
                                 writes.data(), 0, nullptr);

    vk.fn.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vk.fn.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  pipelineLayout_, 0, 1, &destinationBinding->set,
                                  0, nullptr);

    const std::array<float, 8> params{
        std::clamp(contrast, 0.5f, 1.5f),
        std::clamp(saturation, 0.0f, 2.0f),
        static_cast<float>(sourceExtent.width),
        static_cast<float>(sourceExtent.height),
        static_cast<float>(destinationExtent.width),
        static_cast<float>(destinationExtent.height),
        static_cast<float>(std::clamp(upscaleFilterMode, 0, 1)),
        0.0f,
    };
    vk.fn.vkCmdPushConstants(cb, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                             0, sizeof(params), params.data());

    vk.fn.vkCmdDispatch(cb, (destinationExtent.width + 7) / 8,
                        (destinationExtent.height + 7) / 8, 1);
    return true;
}

void GpuImageEnhancement::clearDestinationBindings(VulkanSession &vk) {
    if (vk.device == VK_NULL_HANDLE) return;
    for (const auto &entry : destinationBindings_) {
        if (entry.view != VK_NULL_HANDLE && vk.fn.vkDestroyImageView)
            vk.fn.vkDestroyImageView(vk.device, entry.view, nullptr);
    }
    // The destination descriptor sets were allocated from this pool and are never
    // freed individually, so without a reset every swapchain/stage rebuild (rotation,
    // present-mode change, recording start/stop) leaked its sets until the pool ran dry
    // and enhancement silently stopped working. The device is idle here.
    if (descriptorPool_ != VK_NULL_HANDLE && vk.fn.vkResetDescriptorPool != nullptr) {
        vk.fn.vkResetDescriptorPool(vk.device, descriptorPool_, 0);
    }
    destinationBindings_.clear();
}

void GpuImageEnhancement::destroy(VulkanSession &vk) {
    if (vk.device == VK_NULL_HANDLE) return;

    clearDestinationBindings(vk);

    for (const auto &entry : sourceViews_) {
        if (entry.view != VK_NULL_HANDLE && vk.fn.vkDestroyImageView)
            vk.fn.vkDestroyImageView(vk.device, entry.view, nullptr);
    }
    sourceViews_.clear();

    if (descriptorPool_ != VK_NULL_HANDLE && vk.fn.vkDestroyDescriptorPool)
        vk.fn.vkDestroyDescriptorPool(vk.device, descriptorPool_, nullptr);
    descriptorPool_ = VK_NULL_HANDLE;

    if (pipeline_ != VK_NULL_HANDLE && vk.fn.vkDestroyPipeline)
        vk.fn.vkDestroyPipeline(vk.device, pipeline_, nullptr);
    pipeline_ = VK_NULL_HANDLE;

    if (pipelineLayout_ != VK_NULL_HANDLE && vk.fn.vkDestroyPipelineLayout)
        vk.fn.vkDestroyPipelineLayout(vk.device, pipelineLayout_, nullptr);
    pipelineLayout_ = VK_NULL_HANDLE;

    if (descriptorSetLayout_ != VK_NULL_HANDLE && vk.fn.vkDestroyDescriptorSetLayout)
        vk.fn.vkDestroyDescriptorSetLayout(vk.device, descriptorSetLayout_, nullptr);
    descriptorSetLayout_ = VK_NULL_HANDLE;
}

} // namespace lsfg_android
