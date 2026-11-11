#include "fx_upscale.h"

#include <android/log.h>
#include <math.h>
#include <string.h>

#include "up_adaptive_comp.spv.h"
#include "up_anime_comp.spv.h"
#include "up_cas_comp.spv.h"
#include "up_catrom_comp.spv.h"
#include "up_clarity_comp.spv.h"
#include "up_deband_comp.spv.h"
#include "up_denoise_comp.spv.h"
#include "up_easu_comp.spv.h"
#include "up_fxaa_comp.spv.h"
#include "up_lanczos3_comp.spv.h"
#include "up_lanczos_comp.spv.h"
#include "up_luma_comp.spv.h"
#include "up_ewa_comp.spv.h"
#include "up_mitchell_comp.spv.h"
#include "up_nis_comp.spv.h"
#include "up_rcas_comp.spv.h"
#include "up_sgsr_comp.spv.h"
#include "up_smaa_comp.spv.h"
#include "up_spline36_comp.spv.h"

#define UP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Fluxa", __VA_ARGS__)
#define UP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Fluxa", __VA_ARGS__)

typedef struct {
    float src_w;
    float src_h;
    float dst_w;
    float dst_h;
    float sharp;
    float p1; /* denoise / deband strength 0..2; sharpen overdrive 0..1 */
    float p2; /* dither amplitude (LSB), 0 = off */
    float p3; /* per-frame seed */
    /* colour grade, only read by the final pass (the other shaders declare just the
     * first 8 floats; the pipeline layout covers all 14 = 56 bytes, within the 128-byte minimum) */
    float bright;    /* -1..1 */
    float contrast;  /* -1..1 */
    float sat;       /* -1..1 */
    float vib;       /* -1..1 */
    float temp;      /* -1..1 (cool .. warm) */
    float tint;      /* -1..1 (green .. magenta) */
} UpPush;

static uint32_t up_find_memory(const FxUpscaler* u, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < u->mem.memoryTypeCount; i++) {
        if (!(bits & (1u << i))) continue;
        if ((u->mem.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    return UINT32_MAX;
}

static void up_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout,
                       VkImageLayout new_layout, VkPipelineStageFlags src_stage,
                       VkPipelineStageFlags dst_stage, VkAccessFlags src_access,
                       VkAccessFlags dst_access) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = src_access;
    b.dstAccessMask = dst_access;
    b.oldLayout = old_layout;
    b.newLayout = new_layout;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &b);
}

static VkPipeline up_make_pipeline(FxUpscaler* u, const uint32_t* code, size_t size) {
    VkShaderModuleCreateInfo smi = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smi.codeSize = size;
    smi.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(u->device, &smi, NULL, &module) != VK_SUCCESS) return VK_NULL_HANDLE;

    VkComputePipelineCreateInfo pci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pci.stage.module = module;
    pci.stage.pName = "main";
    pci.layout = u->layout;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult r = vkCreateComputePipelines(u->device, VK_NULL_HANDLE, 1, &pci, NULL, &pipeline);
    vkDestroyShaderModule(u->device, module, NULL);
    return r == VK_SUCCESS ? pipeline : VK_NULL_HANDLE;
}

bool fx_up_create_pipes(FxUpscaler* u, VkDevice device,
                        const VkPhysicalDeviceMemoryProperties* mem) {
    memset(u, 0, sizeof(*u));
    u->device = device;
    u->mem = *mem;

    VkSamplerCreateInfo sci = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(device, &sci, NULL, &u->sampler) != VK_SUCCESS) goto fail;

    VkDescriptorSetLayoutBinding bindings[2];
    memset(bindings, 0, sizeof(bindings));
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo dli = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dli.bindingCount = 2;
    dli.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &dli, NULL, &u->dsl) != VK_SUCCESS) goto fail;

    VkPushConstantRange range = {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(UpPush)};
    VkPipelineLayoutCreateInfo pli = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &u->dsl;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &range;
    if (vkCreatePipelineLayout(device, &pli, NULL, &u->layout) != VK_SUCCESS) goto fail;

    u->easu = up_make_pipeline(u, up_easu_comp, up_easu_comp_size);
    u->sgsr = up_make_pipeline(u, up_sgsr_comp, up_sgsr_comp_size);
    u->rcas = up_make_pipeline(u, up_rcas_comp, up_rcas_comp_size);
    if (!u->easu || !u->sgsr || !u->rcas) {
        UP_LOGE("upscaler pipelines failed (easu=%d sgsr=%d rcas=%d)", u->easu != 0, u->sgsr != 0,
                u->rcas != 0);
        goto fail;
    }
    /* newer modes are optional: a missing one only turns that mode into a plain blit */
    u->lanczos = up_make_pipeline(u, up_lanczos_comp, up_lanczos_comp_size);
    u->catrom = up_make_pipeline(u, up_catrom_comp, up_catrom_comp_size);
    u->nis = up_make_pipeline(u, up_nis_comp, up_nis_comp_size);
    u->cas = up_make_pipeline(u, up_cas_comp, up_cas_comp_size);
    u->anime = up_make_pipeline(u, up_anime_comp, up_anime_comp_size);
    u->mitchell = up_make_pipeline(u, up_mitchell_comp, up_mitchell_comp_size);
    u->lanczos3 = up_make_pipeline(u, up_lanczos3_comp, up_lanczos3_comp_size);
    u->adaptive = up_make_pipeline(u, up_adaptive_comp, up_adaptive_comp_size);
    u->spline36 = up_make_pipeline(u, up_spline36_comp, up_spline36_comp_size);
    u->ewa = up_make_pipeline(u, up_ewa_comp, up_ewa_comp_size);
    u->luma = up_make_pipeline(u, up_luma_comp, up_luma_comp_size);
    u->clarity = up_make_pipeline(u, up_clarity_comp, up_clarity_comp_size);
    u->denoise = up_make_pipeline(u, up_denoise_comp, up_denoise_comp_size);
    u->fxaa = up_make_pipeline(u, up_fxaa_comp, up_fxaa_comp_size);
    u->smaa = up_make_pipeline(u, up_smaa_comp, up_smaa_comp_size);
    u->deband = up_make_pipeline(u, up_deband_comp, up_deband_comp_size);
    if (!u->denoise || !u->fxaa || !u->smaa || !u->deband) {
        UP_LOGE("enhance pipelines missing (denoise=%d fxaa=%d smaa=%d deband=%d)", u->denoise != 0,
                u->fxaa != 0, u->smaa != 0, u->deband != 0);
    }
    if (!u->lanczos || !u->catrom || !u->nis || !u->cas || !u->anime || !u->mitchell
        || !u->lanczos3 || !u->adaptive || !u->spline36 || !u->ewa || !u->luma || !u->clarity) {
        UP_LOGE("optional upscaler pipelines missing (lanczos=%d catrom=%d nis=%d cas=%d anime=%d "
                "mitchell=%d lanczos3=%d adaptive=%d spline36=%d ewa=%d luma=%d clarity=%d)",
                u->lanczos != 0, u->catrom != 0, u->nis != 0, u->cas != 0, u->anime != 0,
                u->mitchell != 0, u->lanczos3 != 0, u->adaptive != 0, u->spline36 != 0,
                u->ewa != 0, u->luma != 0, u->clarity != 0);
    }
    u->pipes_ready = true;
    return true;

fail:
    fx_up_destroy(u);
    return false;
}

static void up_free_image(FxUpscaler* u, FxUpImage* im) {
    if (im->view) vkDestroyImageView(u->device, im->view, NULL);
    if (im->image) vkDestroyImage(u->device, im->image, NULL);
    if (im->memory) vkFreeMemory(u->device, im->memory, NULL);
    memset(im, 0, sizeof(*im));
}

void fx_up_release_resources(FxUpscaler* u) {
    if (!u->device) return;
    for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
        for (uint32_t k = 0; k < 2; k++) {
            up_free_image(u, &u->img[s][k]);
            up_free_image(u, &u->pre[s][k]);
        }
    }
    if (u->pool) vkDestroyDescriptorPool(u->device, u->pool, NULL); /* frees its sets */
    u->pool = VK_NULL_HANDLE;
    memset(u->set_src, 0, sizeof(u->set_src));
    memset(u->set_rcas, 0, sizeof(u->set_rcas));
    memset(u->set_post, 0, sizeof(u->set_post));
    memset(u->set_pre0, 0, sizeof(u->set_pre0));
    memset(u->set_pre1, 0, sizeof(u->set_pre1));
    memset(u->set_up0, 0, sizeof(u->set_up0));
    memset(u->set_up1, 0, sizeof(u->set_up1));
    u->res_ready = false;
    u->has_pre = false;
    u->width = 0;
    u->height = 0;
    u->src_w = 0;
    u->src_h = 0;
}

void fx_up_destroy(FxUpscaler* u) {
    if (!u->device) return;
    fx_up_release_resources(u);
    if (u->easu) vkDestroyPipeline(u->device, u->easu, NULL);
    if (u->sgsr) vkDestroyPipeline(u->device, u->sgsr, NULL);
    if (u->rcas) vkDestroyPipeline(u->device, u->rcas, NULL);
    if (u->lanczos) vkDestroyPipeline(u->device, u->lanczos, NULL);
    if (u->catrom) vkDestroyPipeline(u->device, u->catrom, NULL);
    if (u->nis) vkDestroyPipeline(u->device, u->nis, NULL);
    if (u->cas) vkDestroyPipeline(u->device, u->cas, NULL);
    if (u->anime) vkDestroyPipeline(u->device, u->anime, NULL);
    if (u->mitchell) vkDestroyPipeline(u->device, u->mitchell, NULL);
    if (u->lanczos3) vkDestroyPipeline(u->device, u->lanczos3, NULL);
    if (u->spline36) vkDestroyPipeline(u->device, u->spline36, NULL);
    if (u->ewa) vkDestroyPipeline(u->device, u->ewa, NULL);
    if (u->luma) vkDestroyPipeline(u->device, u->luma, NULL);
    if (u->clarity) vkDestroyPipeline(u->device, u->clarity, NULL);
    if (u->adaptive) vkDestroyPipeline(u->device, u->adaptive, NULL);
    if (u->denoise) vkDestroyPipeline(u->device, u->denoise, NULL);
    if (u->fxaa) vkDestroyPipeline(u->device, u->fxaa, NULL);
    if (u->smaa) vkDestroyPipeline(u->device, u->smaa, NULL);
    if (u->deband) vkDestroyPipeline(u->device, u->deband, NULL);
    if (u->layout) vkDestroyPipelineLayout(u->device, u->layout, NULL);
    if (u->dsl) vkDestroyDescriptorSetLayout(u->device, u->dsl, NULL);
    if (u->sampler) vkDestroySampler(u->device, u->sampler, NULL);
    u->easu = u->sgsr = u->rcas = VK_NULL_HANDLE;
    u->lanczos = u->catrom = u->nis = u->cas = VK_NULL_HANDLE;
    u->anime = u->mitchell = u->lanczos3 = u->adaptive = VK_NULL_HANDLE;
    u->spline36 = u->ewa = u->luma = u->clarity = VK_NULL_HANDLE;
    u->denoise = u->fxaa = u->smaa = u->deband = VK_NULL_HANDLE;
    u->layout = VK_NULL_HANDLE;
    u->dsl = VK_NULL_HANDLE;
    u->sampler = VK_NULL_HANDLE;
    u->pipes_ready = false;
}

static bool up_make_image(FxUpscaler* u, FxUpImage* im, VkFormat format, uint32_t w, uint32_t h) {
    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = format;
    ici.extent.width = w;
    ici.extent.height = h;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
              | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(u->device, &ici, NULL, &im->image) != VK_SUCCESS) return false;

    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(u->device, im->image, &req);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = up_find_memory(u, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) return false;
    if (vkAllocateMemory(u->device, &mai, NULL, &im->memory) != VK_SUCCESS) return false;
    if (vkBindImageMemory(u->device, im->image, im->memory, 0) != VK_SUCCESS) return false;

    VkImageViewCreateInfo vci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = im->image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = format;
    vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount = 1;
    vci.subresourceRange.layerCount = 1;
    return vkCreateImageView(u->device, &vci, NULL, &im->view) == VK_SUCCESS;
}

static void up_write_set(FxUpscaler* u, VkDescriptorSet set, VkImageView sampled, VkImageView storage) {
    VkDescriptorImageInfo in = {u->sampler, sampled, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo out = {VK_NULL_HANDLE, storage, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet w[2];
    memset(w, 0, sizeof(w));
    w[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w[0].dstSet = set;
    w[0].dstBinding = 0;
    w[0].descriptorCount = 1;
    w[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w[0].pImageInfo = &in;
    w[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w[1].dstSet = set;
    w[1].dstBinding = 1;
    w[1].descriptorCount = 1;
    w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w[1].pImageInfo = &out;
    vkUpdateDescriptorSets(u->device, 2, w, 0, NULL);
}

bool fx_up_create_resources(FxUpscaler* u, VkFormat format, uint32_t width, uint32_t height,
                            uint32_t src_w, uint32_t src_h, bool want_pre,
                            const VkImageView* src_views, uint32_t src_count) {
    if (!u->pipes_ready || src_count == 0 || src_count > FX_UP_MAX_SRC) return false;
    fx_up_release_resources(u);

    for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
        for (uint32_t k = 0; k < 2; k++) {
            if (!up_make_image(u, &u->img[s][k], format, width, height)) goto fail;
        }
    }
    if (want_pre) {
        /* denoise / AA scratch images at source size; without them the pre passes are skipped */
        bool ok = src_w > 0 && src_h > 0;
        for (uint32_t s = 0; s < FX_UP_SLOTS && ok; s++) {
            for (uint32_t k = 0; k < 2 && ok; k++) {
                ok = up_make_image(u, &u->pre[s][k], format, src_w, src_h);
            }
        }
        if (!ok) {
            UP_LOGE("source-size scratch images could not be created; denoise / AA stay off");
            for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
                for (uint32_t k = 0; k < 2; k++) up_free_image(u, &u->pre[s][k]);
            }
            want_pre = false;
        }
    }

    const uint32_t sets = src_count * FX_UP_SLOTS + 2 * FX_UP_SLOTS
                        + (want_pre ? src_count * FX_UP_SLOTS + 3 * FX_UP_SLOTS : 0);
    VkDescriptorPoolSize sizes[2] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sets},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, sets},
    };
    VkDescriptorPoolCreateInfo dpi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = sets;
    dpi.poolSizeCount = 2;
    dpi.pPoolSizes = sizes;
    if (vkCreateDescriptorPool(u->device, &dpi, NULL, &u->pool) != VK_SUCCESS) goto fail;

    VkDescriptorSetLayout layouts[(2 * FX_UP_MAX_SRC + 5) * FX_UP_SLOTS];
    VkDescriptorSet allocated[(2 * FX_UP_MAX_SRC + 5) * FX_UP_SLOTS];
    for (uint32_t i = 0; i < sets; i++) layouts[i] = u->dsl;
    VkDescriptorSetAllocateInfo dai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = u->pool;
    dai.descriptorSetCount = sets;
    dai.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(u->device, &dai, allocated) != VK_SUCCESS) goto fail;

    uint32_t n = 0;
    for (uint32_t i = 0; i < src_count; i++) {
        for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
            u->set_src[i][s] = allocated[n++];
            up_write_set(u, u->set_src[i][s], src_views[i], u->img[s][0].view);
        }
    }
    for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
        u->set_rcas[s] = allocated[n++];
        up_write_set(u, u->set_rcas[s], u->img[s][0].view, u->img[s][1].view);
    }
    for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
        u->set_post[s] = allocated[n++];
        up_write_set(u, u->set_post[s], u->img[s][1].view, u->img[s][0].view);
    }
    if (want_pre) {
        for (uint32_t i = 0; i < src_count; i++) {
            for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
                u->set_pre0[i][s] = allocated[n++];
                up_write_set(u, u->set_pre0[i][s], src_views[i], u->pre[s][0].view);
            }
        }
        for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
            u->set_pre1[s] = allocated[n++];
            up_write_set(u, u->set_pre1[s], u->pre[s][0].view, u->pre[s][1].view);
        }
        for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
            u->set_up0[s] = allocated[n++];
            up_write_set(u, u->set_up0[s], u->pre[s][0].view, u->img[s][0].view);
        }
        for (uint32_t s = 0; s < FX_UP_SLOTS; s++) {
            u->set_up1[s] = allocated[n++];
            up_write_set(u, u->set_up1[s], u->pre[s][1].view, u->img[s][0].view);
        }
    }

    u->width = width;
    u->height = height;
    u->src_w = src_w;
    u->src_h = src_h;
    u->has_pre = want_pre;
    u->res_ready = true;
    UP_LOGI("upscaler resources ready %ux%u (source %ux%u, pre=%d)", width, height, src_w, src_h,
            want_pre ? 1 : 0);
    return true;

fail:
    UP_LOGE("upscaler resources could not be created");
    fx_up_release_resources(u);
    return false;
}

static void up_dispatch(VkCommandBuffer cmd, VkPipeline pipeline, const FxUpscaler* u,
                        VkDescriptorSet set, const UpPush* pc) {
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, u->layout, 0, 1, &set, 0, NULL);
    vkCmdPushConstants(cmd, u->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(*pc), pc);
    vkCmdDispatch(cmd, ((uint32_t)pc->dst_w + 7u) / 8u, ((uint32_t)pc->dst_h + 7u) / 8u, 1);
}

/* The compute pipeline that implements `mode`; VK_NULL_HANDLE when the mode is blit-based or its
 * pipeline could not be created. */
static VkPipeline up_mode_pipeline(const FxUpscaler* u, uint32_t mode) {
    switch (mode) {
        case FX_UP_SGSR: return u->sgsr;
        case FX_UP_FSR1: return u->easu;
        case FX_UP_LANCZOS: return u->lanczos;
        case FX_UP_CATROM: return u->catrom;
        case FX_UP_NIS: return u->nis;
        case FX_UP_ANIME: return u->anime;
        case FX_UP_MITCHELL: return u->mitchell;
        case FX_UP_LANCZOS3: return u->lanczos3;
        case FX_UP_SPLINE36: return u->spline36;
        case FX_UP_EWA: return u->ewa;
        default: return VK_NULL_HANDLE;
    }
}

VkImage fx_up_record(FxUpscaler* u, VkCommandBuffer cmd, uint32_t mode, uint32_t sharp,
                     uint32_t sharp_method, uint32_t enh, uint64_t look, uint32_t src_index,
                     uint32_t slot, VkImage src_image, uint32_t src_w, uint32_t src_h,
                     VkFilter blit_filter) {
    const VkPipelineStageFlags kAll = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
    const VkAccessFlags kRW = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
    const VkImage a = u->img[slot][0].image;
    const VkImage b = u->img[slot][1].image;
    UpPush pc = {(float)src_w, (float)src_h, (float)u->width, (float)u->height, 0.0f, 0.0f, 0.0f, 0.0f};
    VkImage result = a;

    const VkPipeline first_pass = up_mode_pipeline(u, mode);
    /* CAS / adaptive / luma / clarity missing -> fall back to RCAS, which is always there */
    VkPipeline sharp_pass = u->rcas;
    if (sharp_method == FX_SHARP_CAS && u->cas) sharp_pass = u->cas;
    else if (sharp_method == FX_SHARP_ADAPTIVE && u->adaptive) sharp_pass = u->adaptive;
    else if (sharp_method == FX_SHARP_LUMA && u->luma) sharp_pass = u->luma;
    else if (sharp_method == FX_SHARP_CLARITY && u->clarity) sharp_pass = u->clarity;
    const bool use_amount = sharp_pass != u->rcas; /* the other passes take 0..1 directly */

    /* ---- pre passes at source size: denoise / deblock, then FXAA or SMAA-lite ---- */
    VkImage cur = src_image;                        /* what the upscale reads */
    VkDescriptorSet up_set = u->set_src[src_index][slot];
    const uint32_t dn_amt = FX_UP_DENOISE(enh);
    const uint32_t aa_mode = FX_UP_AA(enh);
    VkPipeline dn_pipe = dn_amt > 0 ? u->denoise : VK_NULL_HANDLE;
    VkPipeline aa_pipe = aa_mode == FX_AA_FXAA ? u->fxaa
                       : (aa_mode == FX_AA_SMAA ? u->smaa : VK_NULL_HANDLE);
    if (!u->has_pre || src_w != u->src_w || src_h != u->src_h) {
        dn_pipe = VK_NULL_HANDLE; /* no scratch images (yet): skip, the frame still shows */
        aa_pipe = VK_NULL_HANDLE;
    }
    if (dn_pipe || aa_pipe) {
        const VkImage c0 = u->pre[slot][0].image;
        const VkImage c1 = u->pre[slot][1].image;
        UpPush pre = {(float)src_w, (float)src_h, (float)src_w, (float)src_h, 0.0f, 0.0f, 0.0f, 0.0f};
        /* the source's earlier writes (blit / DIS compute / generation) must be visible to sampling */
        up_barrier(cmd, src_image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                   kAll | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                   VK_ACCESS_SHADER_READ_BIT);
        up_barrier(cmd, c0, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, kAll,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, kRW, VK_ACCESS_SHADER_WRITE_BIT);
        if (dn_pipe) {
            pre.p1 = (float)dn_amt / 100.0f; /* 0..2 */
            up_dispatch(cmd, dn_pipe, u, u->set_pre0[src_index][slot], &pre);
            cur = c0;
            up_set = u->set_up0[slot];
            if (aa_pipe) {
                up_barrier(cmd, c0, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
                up_barrier(cmd, c1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, kAll,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, kRW, VK_ACCESS_SHADER_WRITE_BIT);
                up_dispatch(cmd, aa_pipe, u, u->set_pre1[slot], &pre);
                cur = c1;
                up_set = u->set_up1[slot];
            }
        } else {
            up_dispatch(cmd, aa_pipe, u, u->set_pre0[src_index][slot], &pre);
            cur = c0;
            up_set = u->set_up0[slot];
        }
    }

    if (first_pass != VK_NULL_HANDLE) {
        /* make `cur`'s earlier writes (blit / DIS compute / generation / pre pass) visible to sampling */
        up_barrier(cmd, cur, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                   kAll | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                   VK_ACCESS_SHADER_READ_BIT);
        up_barrier(cmd, a, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, kAll,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, kRW, VK_ACCESS_SHADER_WRITE_BIT);
        up_dispatch(cmd, first_pass, u, up_set, &pc);
    } else {
        /* nearest / linear / cubic (or a shader mode whose pipeline is missing): blit into A,
         * optionally sharpened below */
        up_barrier(cmd, a, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, kAll,
                   VK_PIPELINE_STAGE_TRANSFER_BIT, kRW, VK_ACCESS_TRANSFER_WRITE_BIT);
        up_barrier(cmd, cur, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                   kAll | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                   VK_ACCESS_TRANSFER_READ_BIT);
        VkImageBlit blit;
        memset(&blit, 0, sizeof(blit));
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1].x = (int32_t)src_w;
        blit.srcOffsets[1].y = (int32_t)src_h;
        blit.srcOffsets[1].z = 1;
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[1].x = (int32_t)u->width;
        blit.dstOffsets[1].y = (int32_t)u->height;
        blit.dstOffsets[1].z = 1;
        vkCmdBlitImage(cmd, cur, VK_IMAGE_LAYOUT_GENERAL, a, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                       1, &blit, blit_filter);
        up_barrier(cmd, a, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                   VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    if (sharp > 0) {
        /* strength 0..200 %: the first 100 % map exactly as before, 101..200 % become `over`
         * (0..1), which the sharpen shaders turn into overdrive */
        const float total = (float)(sharp > FX_UP_STRENGTH_MAX ? FX_UP_STRENGTH_MAX : sharp) / 100.0f;
        const float amount = total > 1.0f ? 1.0f : total;
        pc.p1 = total > 1.0f ? total - 1.0f : 0.0f;
        if (use_amount) {
            /* CAS / adaptive / luma / clarity: 0 (gentle) .. 1 (strongest), then overdrive */
            pc.sharp = amount;
        } else {
            /* RCAS strength: 100 -> 1.0 (sharpest), 50 -> 0.5, 1 -> ~0.25 (FSR's 2 stops) */
            pc.sharp = exp2f(-2.0f * (1.0f - amount));
        }
        up_barrier(cmd, a, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        up_barrier(cmd, b, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, kAll,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, kRW, VK_ACCESS_SHADER_WRITE_BIT);
        pc.src_w = (float)u->width; /* RCAS / CAS read the already-upscaled image */
        pc.src_h = (float)u->height;
        up_dispatch(cmd, sharp_pass, u, u->set_rcas[slot], &pc);
        result = b;
    }

    /* ---- final pass at output size: deband -> colour grade -> dither (dither last,
     * right before the 8-bit store) ---- */
    const uint32_t deb = FX_UP_DEBAND(enh);
    const bool dith = FX_UP_DITHER(enh) != 0u;
    const bool looking = fx_look_active(look);
    if ((deb > 0 || dith || looking) && u->deband) {
        const VkImage in = result;
        const VkImage out = in == a ? b : a;
        VkDescriptorSet set = in == a ? u->set_rcas[slot] : u->set_post[slot];
        up_barrier(cmd, in, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        if (out == b) {
            up_barrier(cmd, b, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, kAll,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, kRW, VK_ACCESS_SHADER_WRITE_BIT);
        } else {
            /* A was read by the sharpen pass: wait for it before overwriting */
            up_barrier(cmd, a, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        }
        UpPush fin = {(float)u->width, (float)u->height, (float)u->width, (float)u->height, 0.0f,
                      (float)deb / 100.0f /* 0..2 */, dith ? 1.0f : 0.0f, (float)(u->frame_counter++ & 1023u)};
        if (looking) {
            fin.bright = (float)FX_LOOK_BRIGHT(look) / 100.0f;
            fin.contrast = (float)FX_LOOK_CONTRAST(look) / 100.0f;
            fin.sat = (float)FX_LOOK_SAT(look) / 100.0f;
            fin.vib = (float)FX_LOOK_VIB(look) / 100.0f;
            fin.temp = (float)FX_LOOK_TEMP(look) / 100.0f;
            fin.tint = (float)FX_LOOK_TINT(look) / 100.0f;
        }
        up_dispatch(cmd, u->deband, u, set, &fin);
        result = out;
    }

    up_barrier(cmd, result, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    return result;
}
