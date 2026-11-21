// Runs the presenter's real output upscaler (fx_upscale.c: same compute shaders, same blit modes,
// same RCAS / CAS pass) on a single still image. Used by the in-app before/after comparison so the
// preview matches what the overlay shows during a session.
//
// Own Vulkan instance + device (no surface), created lazily and kept until nativeStillRelease().
// All entry points are serialised by one mutex. Not meant to run next to a live presenter: the
// dispatch table in vk_dispatch.c is shared, so the Java side refuses while a session is active.

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "../vk_dispatch.h"
#include "../vk_driver.h"
#include "fx_upscale.h"

#define ST_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "FluxaStill", __VA_ARGS__)
#define ST_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FluxaStill", __VA_ARGS__)

#ifndef VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT
#define VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT 0x00002000
#endif

#define ST_FORMAT VK_FORMAT_R8G8B8A8_UNORM
#define ST_MAX_SIDE 8192u

#define ST_CAP_READY 1
#define ST_CAP_CUBIC 2

typedef struct {
    bool tried;
    bool ok;
    bool cubic;
    void* lib;
    VkInstance instance;
    VkPhysicalDevice phys;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    VkPhysicalDeviceMemoryProperties mem;
    FxUpscaler up;
    bool up_ready;
} StillCtx;

static StillCtx g_st;
static pthread_mutex_t g_st_lock = PTHREAD_MUTEX_INITIALIZER;

static bool st_has_ext(const VkExtensionProperties* list, uint32_t n, const char* name) {
    for (uint32_t i = 0; i < n; i++) {
        if (strcmp(list[i].extensionName, name) == 0) return true;
    }
    return false;
}

static uint32_t st_find_memory(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_st.mem.memoryTypeCount; i++) {
        if (!(bits & (1u << i))) continue;
        if ((g_st.mem.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    return UINT32_MAX;
}

static void st_teardown(void) {
    StillCtx* s = &g_st;
    if (s->device) vkDeviceWaitIdle(s->device);
    if (s->up_ready) fx_up_destroy(&s->up);
    if (s->device) vkDestroyDevice(s->device, NULL);
    if (s->instance) vkDestroyInstance(s->instance, NULL);
    if (s->lib) vkd_unload(); /* the libvulkan handle stays loaded on purpose */
    memset(s, 0, sizeof(*s));
}

static bool st_init(void) {
    StillCtx* s = &g_st;
    if (s->ok) return true;
    if (s->tried) return false;
    s->tried = true;

    s->lib = fluxa_open_vulkan(NULL, NULL, NULL);
    if (!s->lib || !vkd_init(s->lib)) {
        ST_LOGE("libvulkan unavailable");
        goto fail;
    }

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Fluxa still";
    app.pEngineName = "Fluxa";
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    VkResult res = vkCreateInstance(&ici, NULL, &s->instance);
    if (res != VK_SUCCESS) {
        app.apiVersion = VK_API_VERSION_1_1;
        res = vkCreateInstance(&ici, NULL, &s->instance);
    }
    if (res != VK_SUCCESS || !vkd_load_instance(s->instance)) {
        ST_LOGE("vkCreateInstance -> %d", res);
        goto fail;
    }

    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(s->instance, &count, NULL) != VK_SUCCESS || count == 0) goto fail;
    if (count > 8) count = 8;
    VkPhysicalDevice devices[8];
    if (vkEnumeratePhysicalDevices(s->instance, &count, devices) != VK_SUCCESS) goto fail;

    for (uint32_t i = 0; i < count && !s->phys; i++) {
        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, NULL);
        if (families == 0) continue;
        if (families > 16) families = 16;
        VkQueueFamilyProperties props[16];
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, props);
        for (uint32_t q = 0; q < families; q++) {
            const VkQueueFlags need = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
            if (props[q].queueCount && (props[q].queueFlags & need) == need) {
                s->phys = devices[i];
                s->family = q;
                break;
            }
        }
    }
    if (!s->phys) {
        ST_LOGE("no graphics+compute queue");
        goto fail;
    }
    vkGetPhysicalDeviceMemoryProperties(s->phys, &s->mem);

    VkPhysicalDeviceFeatures2 features = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    vkGetPhysicalDeviceFeatures2(s->phys, &features);
    if (!features.features.shaderStorageImageWriteWithoutFormat) {
        ST_LOGE("shaderStorageImageWriteWithoutFormat missing");
        goto fail;
    }
    VkPhysicalDeviceFeatures enabled = {0};
    enabled.shaderStorageImageWriteWithoutFormat = VK_TRUE;

    uint32_t ext_count = 0;
    vkEnumerateDeviceExtensionProperties(s->phys, NULL, &ext_count, NULL);
    VkExtensionProperties* exts = calloc(ext_count ? ext_count : 1, sizeof(VkExtensionProperties));
    if (!exts) goto fail;
    vkEnumerateDeviceExtensionProperties(s->phys, NULL, &ext_count, exts);
    const char* enable[1];
    uint32_t enable_n = 0;
    bool cubic_ext = st_has_ext(exts, ext_count, VK_EXT_FILTER_CUBIC_EXTENSION_NAME);
    if (cubic_ext) enable[enable_n++] = VK_EXT_FILTER_CUBIC_EXTENSION_NAME;
    free(exts);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = s->family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = enable_n;
    dci.ppEnabledExtensionNames = enable;
    dci.pEnabledFeatures = &enabled;
    res = vkCreateDevice(s->phys, &dci, NULL, &s->device);
    if (res != VK_SUCCESS) {
        ST_LOGE("vkCreateDevice -> %d", res);
        goto fail;
    }
    vkGetDeviceQueue(s->device, s->family, 0, &s->queue);

    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(s->phys, ST_FORMAT, &fp);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
                                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                                    | VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    if ((fp.optimalTilingFeatures & need) != need) {
        ST_LOGE("RGBA8 lacks storage/sampled/blit support");
        goto fail;
    }
    s->cubic = cubic_ext
        && (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT);

    if (!fx_up_create_pipes(&s->up, s->device, &s->mem)) goto fail;
    s->up_ready = true;
    s->ok = true;
    ST_LOGI("still upscaler ready (cubic=%d)", s->cubic);
    return true;

fail:
    st_teardown();
    g_st.tried = true; /* do not retry a device that already failed */
    return false;
}

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkBuffer up_buf, down_buf;
    VkDeviceMemory up_mem, down_mem;
    void* up_map;
    void* down_map;
    VkCommandPool pool;
    VkFence fence;
} StillJob;

static bool st_make_buffer(VkDeviceSize size, VkBufferUsageFlags usage, VkBuffer* buf,
                           VkDeviceMemory* mem, void** map) {
    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = size;
    bci.usage = usage;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(g_st.device, &bci, NULL, buf) != VK_SUCCESS) return false;
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(g_st.device, *buf, &req);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = st_find_memory(req.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) return false;
    if (vkAllocateMemory(g_st.device, &mai, NULL, mem) != VK_SUCCESS) return false;
    if (vkBindBufferMemory(g_st.device, *buf, *mem, 0) != VK_SUCCESS) return false;
    return vkMapMemory(g_st.device, *mem, 0, VK_WHOLE_SIZE, 0, map) == VK_SUCCESS;
}

static void st_job_free(StillJob* j) {
    VkDevice d = g_st.device;
    if (g_st.up.res_ready) fx_up_release_resources(&g_st.up);
    if (j->fence) vkDestroyFence(d, j->fence, NULL);
    if (j->pool) vkDestroyCommandPool(d, j->pool, NULL);
    if (j->up_map) vkUnmapMemory(d, j->up_mem);
    if (j->down_map) vkUnmapMemory(d, j->down_mem);
    if (j->up_buf) vkDestroyBuffer(d, j->up_buf, NULL);
    if (j->down_buf) vkDestroyBuffer(d, j->down_buf, NULL);
    if (j->up_mem) vkFreeMemory(d, j->up_mem, NULL);
    if (j->down_mem) vkFreeMemory(d, j->down_mem, NULL);
    if (j->view) vkDestroyImageView(d, j->view, NULL);
    if (j->image) vkDestroyImage(d, j->image, NULL);
    if (j->memory) vkFreeMemory(d, j->memory, NULL);
    memset(j, 0, sizeof(*j));
}

static void st_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to,
                       VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
                       VkAccessFlags src_access, VkAccessFlags dst_access) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = src_access;
    b.dstAccessMask = dst_access;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &b);
}

/* in: sw*sh RGBA8. out: ow*oh RGBA8 (caller allocated). */
static bool st_run(const uint8_t* in, uint32_t sw, uint32_t sh, uint8_t* out, uint32_t ow,
                   uint32_t oh, uint32_t mode, uint32_t packed_sharp, uint64_t look) {
    StillJob j;
    memset(&j, 0, sizeof(j));
    VkDevice d = g_st.device;
    const VkDeviceSize in_bytes = (VkDeviceSize)sw * sh * 4u;
    const VkDeviceSize out_bytes = (VkDeviceSize)ow * oh * 4u;

    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = ST_FORMAT;
    ici.extent.width = sw;
    ici.extent.height = sh;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
              | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(d, &ici, NULL, &j.image) != VK_SUCCESS) goto fail;
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(d, j.image, &req);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = st_find_memory(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) goto fail;
    if (vkAllocateMemory(d, &mai, NULL, &j.memory) != VK_SUCCESS) goto fail;
    if (vkBindImageMemory(d, j.image, j.memory, 0) != VK_SUCCESS) goto fail;
    VkImageViewCreateInfo vci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = j.image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = ST_FORMAT;
    vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vci.subresourceRange.levelCount = 1;
    vci.subresourceRange.layerCount = 1;
    if (vkCreateImageView(d, &vci, NULL, &j.view) != VK_SUCCESS) goto fail;

    if (!st_make_buffer(in_bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, &j.up_buf, &j.up_mem, &j.up_map)) goto fail;
    if (!st_make_buffer(out_bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, &j.down_buf, &j.down_mem, &j.down_map)) goto fail;
    memcpy(j.up_map, in, (size_t)in_bytes);

    if (!fx_up_create_resources(&g_st.up, ST_FORMAT, ow, oh, sw, sh, FX_UP_WANT_PRE(packed_sharp), &j.view, 1)) goto fail;

    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.queueFamilyIndex = g_st.family;
    if (vkCreateCommandPool(d, &pci, NULL, &j.pool) != VK_SUCCESS) goto fail;
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = j.pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    VkCommandBuffer cmd;
    if (vkAllocateCommandBuffers(d, &cai, &cmd) != VK_SUCCESS) goto fail;
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(d, &fci, NULL, &j.fence) != VK_SUCCESS) goto fail;

    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &bi);

    /* upload */
    st_barrier(cmd, j.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
               VK_ACCESS_TRANSFER_WRITE_BIT);
    VkBufferImageCopy copy;
    memset(&copy, 0, sizeof(copy));
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent.width = sw;
    copy.imageExtent.height = sh;
    copy.imageExtent.depth = 1;
    vkCmdCopyBufferToImage(cmd, j.up_buf, j.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    st_barrier(cmd, j.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
               VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);

    /* the presenter's own upscale path */
    if (mode > FX_UP_MAX_MODE) mode = FX_UP_LINEAR;
    if (mode == FX_UP_CUBIC && !g_st.cubic) mode = FX_UP_LINEAR;
    VkFilter filter = VK_FILTER_LINEAR;
    if (mode == FX_UP_NEAREST) filter = VK_FILTER_NEAREST;
    else if (mode == FX_UP_CUBIC) filter = VK_FILTER_CUBIC_EXT;
    VkImage result = fx_up_record(&g_st.up, cmd, mode, FX_UP_SHARP_VALUE(packed_sharp),
                                  FX_UP_SHARP_METHOD(packed_sharp), packed_sharp, look, 0, 0, j.image, sw, sh, filter);

    /* readback (result is GENERAL and visible to transfer reads) */
    VkBufferImageCopy back;
    memset(&back, 0, sizeof(back));
    back.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    back.imageSubresource.layerCount = 1;
    back.imageExtent.width = ow;
    back.imageExtent.height = oh;
    back.imageExtent.depth = 1;
    vkCmdCopyImageToBuffer(cmd, result, VK_IMAGE_LAYOUT_GENERAL, j.down_buf, 1, &back);
    VkMemoryBarrier host = {VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1,
                         &host, 0, NULL, 0, NULL);
    vkEndCommandBuffer(cmd);

    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    if (vkQueueSubmit(g_st.queue, 1, &si, j.fence) != VK_SUCCESS) goto fail;
    if (vkWaitForFences(d, 1, &j.fence, VK_TRUE, 5000000000ULL) != VK_SUCCESS) {
        vkDeviceWaitIdle(d);
        goto fail;
    }
    memcpy(out, j.down_map, (size_t)out_bytes);
    st_job_free(&j);
    return true;

fail:
    ST_LOGE("still upscale failed (%ux%u -> %ux%u mode %u)", sw, sh, ow, oh, mode);
    vkDeviceWaitIdle(d);
    st_job_free(&j);
    return false;
}

#define ST_FN(name) Java_app_fluxa_FluxaNative_##name

/* -1 = Vulkan unusable, otherwise ST_CAP_* bits. */
JNIEXPORT jint JNICALL ST_FN(nativeStillInfo)(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_st_lock);
    jint r = -1;
    if (st_init()) r = ST_CAP_READY | (g_st.cubic ? ST_CAP_CUBIC : 0);
    pthread_mutex_unlock(&g_st_lock);
    return r;
}

/* rgba: sw*sh*4 bytes (R,G,B,A order). Returns ow*oh*4 bytes, or null on failure. */
JNIEXPORT jbyteArray JNICALL ST_FN(nativeStillUpscale)(JNIEnv* env, jclass clazz, jbyteArray rgba,
                                                      jint sw, jint sh, jint ow, jint oh,
                                                      jint mode, jint sharp, jlong look) {
    (void)clazz;
    if (!rgba || sw <= 0 || sh <= 0 || ow <= 0 || oh <= 0) return NULL;
    if ((uint32_t)sw > ST_MAX_SIDE || (uint32_t)sh > ST_MAX_SIDE || (uint32_t)ow > ST_MAX_SIDE
        || (uint32_t)oh > ST_MAX_SIDE) {
        return NULL;
    }
    const jsize in_len = (*env)->GetArrayLength(env, rgba);
    if ((int64_t)in_len < (int64_t)sw * sh * 4) return NULL;

    const size_t out_len = (size_t)ow * (size_t)oh * 4u;
    uint8_t* out = malloc(out_len);
    jbyte* in = (*env)->GetByteArrayElements(env, rgba, NULL);
    if (!out || !in) {
        free(out);
        if (in) (*env)->ReleaseByteArrayElements(env, rgba, in, JNI_ABORT);
        return NULL;
    }

    pthread_mutex_lock(&g_st_lock);
    bool ok = st_init() && st_run((const uint8_t*)in, (uint32_t)sw, (uint32_t)sh, out, (uint32_t)ow,
                                  (uint32_t)oh, mode < 0 ? FX_UP_LINEAR : (uint32_t)mode,
                                  sharp < 0 ? 0u : (uint32_t)sharp,
                                  look < 0 ? 0ull : (uint64_t)look);
    pthread_mutex_unlock(&g_st_lock);
    (*env)->ReleaseByteArrayElements(env, rgba, in, JNI_ABORT);

    jbyteArray result = NULL;
    if (ok) {
        result = (*env)->NewByteArray(env, (jsize)out_len);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_len, (const jbyte*)out);
    }
    free(out);
    return result;
}

JNIEXPORT void JNICALL ST_FN(nativeStillRelease)(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_st_lock);
    if (g_st.tried) st_teardown();
    pthread_mutex_unlock(&g_st_lock);
}
