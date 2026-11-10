#include "fx_present.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <errno.h>
#include <math.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <poll.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "../vk_dispatch.h"
#include "../vk_driver.h"
#include "fx_upscale.h"
#include "vkr_dis.h"

#define LOG_TAG "Fluxa"
#ifndef VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT
#define VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT 0x00002000
#endif

#define FX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define FX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define FX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define FX_FRAMES_IN_FLIGHT 2u

#define FX_MAX_GENERATIONS VKR_DIS_MAX_GENERATIONS

#define FX_MAX_TARGETS (FX_FRAMES_IN_FLIGHT + FX_MAX_GENERATIONS)
#define FX_MAX_SWAPCHAIN_IMAGES 8u
#define FX_READER_IMAGES 4
#define FX_IMPORT_CACHE 8u
#define FX_ACQUIRE_TIMEOUT_NS 4000000ULL
#define FX_ACQUIRE_TIMEOUT_MAX_NS 33000000ULL
#define FX_MAX_RETIRED 32u
#define FX_FENCE_WAIT_MS 250
#define FX_TELEMETRY_FRAMES 120ULL
/* ring of the last real-frame intervals (us) for the HUD frametime graph; power of two */
#define FX_FT_RING 256u
#define FX_ARRIVAL_WINDOW 0.5f
#define FX_ARRIVAL_TOLERANCE 0.08f

/* Screen recording: every frame that is shown (generated + real) is also blitted into a second
 * swapchain whose surface is a MediaCodec input surface. Same device, same queue, same command
 * buffer - no second capture path. */
#define FX_REC_SANITY_MAX_IMAGES 64u /* only a guard against a nonsense driver answer */
#define FX_REC_PER_FRAME (1u + FX_MAX_GENERATIONS)
#define FX_REC_ACQUIRE_TIMEOUT_NS 0ULL /* the encoder tee never blocks the display */
#define FX_REC_WAIT_MS 800
/* HUD burned into the recording: a small opaque RGBA bitmap from Java, blitted over the video. */
#define FX_HUD_MAX_W 640u
#define FX_HUD_MAX_H 160u

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
} FxTarget;

_Static_assert(FX_UP_SLOTS == FX_FRAMES_IN_FLIGHT, "upscaler slots follow the frames in flight");
_Static_assert(FX_UP_MAX_SRC >= FX_MAX_TARGETS, "upscaler descriptor table too small");

typedef struct {
    ANativeWindow* window; /* owned reference to the encoder input surface */
    VkSurfaceKHR surface;
    VkSwapchainKHR swapchain;
    VkExtent2D extent;
    /* sized from the real vkGetSwapchainImagesKHR count - never truncated */
    VkImage* images;
    VkSemaphore* ready;
    uint8_t* presented; /* image has been through PRESENT_SRC at least once */
    uint32_t image_count;
    VkSemaphore acquire[FX_FRAMES_IN_FLIGHT][FX_REC_PER_FRAME];
    bool broken; /* present/acquire reported a lost surface: torn down on the next sync */
    uint64_t frames;
    uint64_t dropped;
    /* HUD overlay resources (presenter thread only) */
    bool hud_tried;       /* creation attempted (never retried within one recording) */
    bool hud_ok;
    bool hud_has_content; /* hud_image holds a bitmap and sits in TRANSFER_SRC_OPTIMAL */
    VkImage hud_image;
    VkDeviceMemory hud_memory;
    VkBuffer hud_buf[FX_FRAMES_IN_FLIGHT];
    VkDeviceMemory hud_buf_mem[FX_FRAMES_IN_FLIGHT];
    uint8_t* hud_map[FX_FRAMES_IN_FLIGHT];
    uint32_t hud_version; /* last mailbox version handled */
    uint32_t hud_w;       /* size/position currently in hud_image (0 = hidden) */
    uint32_t hud_h;
    int32_t hud_mx;
    int32_t hud_my;
} FxRec;

typedef struct {
    AHardwareBuffer* buffer;
    VkImage image;
    VkDeviceMemory memory;
    uint32_t width;
    uint32_t height;
} FxImport;

typedef struct {
    VkCommandBuffer cmd;
    VkFence fence;
    VkSemaphore acquire;
    VkSemaphore acquire_gen[FX_MAX_GENERATIONS];
    AImage* held;
    bool submitted;
} FxFrame;

struct FxPresenter {
    void* vulkan_handle;
    VkInstance instance;
    VkPhysicalDevice physical_device;
    VkDevice device;
    VkQueue queue;
    uint32_t queue_family;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_ahb_props;
    VkPhysicalDeviceMemoryProperties memory_properties;

    ANativeWindow* output;
    VkSurfaceKHR surface;
    VkSwapchainKHR swapchain;
    VkFormat swapchain_format;
    VkExtent2D extent;     /* source / generation size: reader, targets, DIS */
    VkExtent2D out_extent; /* swapchain size (== extent unless upscaling) */
    bool upscale;
    /* output upscaler: mode is FX_UP_*, sharp 0..100 (0 = no sharpen pass), sharp_method FX_SHARP_*.
     * All three are switched live from the JNI thread and read with relaxed atomics by the
     * presenter thread. */
    uint32_t up_mode;
    uint32_t up_sharp;
    uint32_t up_sharp_method; /* FX_SHARP_*: which pass the strength drives */
    uint32_t up_enh;          /* packed deband / denoise / AA / dither (see fx_upscale.h) */
    uint64_t up_look;         /* colour grade (FX_LOOK_*, 0 = none) */
    FxUpscaler up;
    bool up_failed;  /* pipelines or resources could not be built: fall back to a blit */
    bool up_pre_failed; /* the source-size scratch images could not be made: denoise / AA stay off */
    bool cubic_ext;  /* VK_EXT_filter_cubic enabled on the device */
    bool cubic_ok;   /* ... and the target format can be sampled with the cubic filter */
    VkImage swapchain_images[FX_MAX_SWAPCHAIN_IMAGES];
    VkSemaphore swapchain_ready[FX_MAX_SWAPCHAIN_IMAGES];
    uint32_t swapchain_image_count;

    VkCommandPool command_pool;
    FxFrame frames[FX_FRAMES_IN_FLIGHT];
    VkFence flush_fence;
    uint32_t frame_index;

    FxTarget targets[FX_MAX_TARGETS];
    VkFormat target_format;
    bool targets_built;

    FxImport imports[FX_IMPORT_CACHE];

    AImageReader* reader;
    AImageReader_ImageListener listener;
    ANativeWindow* producer;

    VkrDis* dis;

    pthread_t thread;
    pthread_mutex_t lock;
    pthread_cond_t signal;
    bool running;
    bool image_pending;

    uint32_t multiplier;
    uint32_t dis_min_side;
    uint32_t gen_mode;            // VKR_DIS_MODE_* (GPU cost of the frame generation), atomic
    uint32_t target_fps;          // 0 = fixed xN; >0 = adaptive, aim at this output rate, atomic
    uint32_t present_mode;        // requested (FX_PRESENT_*)
    uint32_t active_present_mode; // what the current swapchain really uses
    uint32_t built_present_mode;  // the request the current swapchain was built for
    float refresh_rate;
    float source_rate;
    bool config_dirty;

    VkSemaphore retired[FX_MAX_RETIRED];
    uint32_t retired_count;

    uint32_t source_divisor;
    uint32_t divisor_phase;
    uint64_t raw_frames;
    struct timespec raw_mark;
    float raw_rate;

    uint64_t source_frames;
    uint64_t real_frames;
    uint64_t generated_frames;
    uint64_t log_real;
    uint64_t log_generated;
    uint64_t acquire_misses;

    /* frametime ring: written by the presenter thread only, read by JNI (relaxed, tear-tolerant) */
    uint32_t ft_ring[FX_FT_RING];
    uint64_t ft_count;   /* samples ever written */
    uint64_t ft_last_ns; /* CLOCK_MONOTONIC of the previous real frame, 0 = none yet */

    /* recording: `rec` belongs to the presenter thread; the rest is the JNI <-> presenter mailbox */
    FxRec rec;
    pthread_mutex_t rec_lock;
    pthread_cond_t rec_cond;
    ANativeWindow* rec_pending_window;
    uint32_t rec_pending_w;
    uint32_t rec_pending_h;
    bool rec_start_req;
    bool rec_stop_req;
    bool rec_live;
    uint64_t rec_req_seq;
    uint64_t rec_done_seq;
    /* HUD mailbox (guarded by rec_lock): newest bitmap from Java */
    uint8_t* hud_pixels;
    uint32_t hud_w;
    uint32_t hud_h;
    int32_t hud_mx;
    int32_t hud_my;
    uint32_t hud_version;
};

static void fx_reset_swapchain(FxPresenter* fg);
static void fx_rec_sync(FxPresenter* fg);
static void fx_rec_destroy(FxPresenter* fg);

static bool fx_has_extension(const VkExtensionProperties* list, uint32_t count, const char* name) {
    for (uint32_t i = 0; i < count; i++) {
        if (strcmp(list[i].extensionName, name) == 0) return true;
    }
    return false;
}

static uint32_t fx_find_memory_type(FxPresenter* fg, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < fg->memory_properties.memoryTypeCount; i++) {
        if (!(bits & (1u << i))) continue;
        if ((fg->memory_properties.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    return UINT32_MAX;
}

static void fx_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout,
                       VkImageLayout new_layout, VkPipelineStageFlags src_stage,
                       VkPipelineStageFlags dst_stage, VkAccessFlags src_access,
                       VkAccessFlags dst_access) {
    VkImageMemoryBarrier barrier = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &barrier);
}

static void fx_blit(VkCommandBuffer cmd, VkImage src, VkImageLayout src_layout, uint32_t src_w,
                    uint32_t src_h, VkImage dst, VkImageLayout dst_layout, uint32_t dst_w,
                    uint32_t dst_h, VkFilter filter) {
    VkImageBlit blit = {0};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = (int32_t)src_w;
    blit.srcOffsets[1].y = (int32_t)src_h;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[1].x = (int32_t)dst_w;
    blit.dstOffsets[1].y = (int32_t)dst_h;
    blit.dstOffsets[1].z = 1;
    vkCmdBlitImage(cmd, src, src_layout, dst, dst_layout, 1, &blit, filter);
}

static void fx_blit_rect(VkCommandBuffer cmd, VkImage src, VkImageLayout src_layout,
                         uint32_t src_w, uint32_t src_h, VkImage dst, VkImageLayout dst_layout,
                         int32_t x0, int32_t y0, int32_t x1, int32_t y1) {
    VkImageBlit blit = {0};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = (int32_t)src_w;
    blit.srcOffsets[1].y = (int32_t)src_h;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[0].x = x0;
    blit.dstOffsets[0].y = y0;
    blit.dstOffsets[1].x = x1;
    blit.dstOffsets[1].y = y1;
    blit.dstOffsets[1].z = 1;
    vkCmdBlitImage(cmd, src, src_layout, dst, dst_layout, 1, &blit, VK_FILTER_LINEAR);
}

/* What to blit into a swapchain image for one shown frame. */
typedef struct {
    VkImage image; /* GENERAL layout */
    uint32_t w;
    uint32_t h;
    VkFilter filter;
} FxScaled;

static bool fx_up_ensure(FxPresenter* fg, bool want_pre) {
    if (fg->up_failed || !fg->up.pipes_ready) return false;
    if (fg->up.res_ready && fg->up.width == fg->out_extent.width
        && fg->up.height == fg->out_extent.height && fg->up.src_w == fg->extent.width
        && fg->up.src_h == fg->extent.height
        && (!want_pre || fg->up.has_pre || fg->up_pre_failed)) {
        return true;
    }
    vkDeviceWaitIdle(fg->device);
    VkImageView views[FX_MAX_TARGETS];
    for (uint32_t i = 0; i < FX_MAX_TARGETS; i++) views[i] = fg->targets[i].view;
    if (!fg->targets_built
        || !fx_up_create_resources(&fg->up, fg->target_format, fg->out_extent.width,
                                   fg->out_extent.height, fg->extent.width, fg->extent.height,
                                   want_pre && !fg->up_pre_failed, views, FX_MAX_TARGETS)) {
        fg->up_failed = true;
        FX_LOGW("upscaler unavailable; using a plain blit");
        return false;
    }
    if (want_pre && !fg->up.has_pre) fg->up_pre_failed = true;
    return true;
}

/* Runs the configured upscale + enhancement chain for `src` (an extent-sized target in GENERAL
 * layout) and says what is left to blit 1:1 / filtered into the swapchain image. Records only
 * compute / transfer work on its own images, so it can sit before the swapchain image's barrier.
 * Without upscaling the chain still runs when denoise / AA / deband / dither / grade is on. */
static FxScaled fx_scale_source(FxPresenter* fg, VkCommandBuffer cmd, VkImage src,
                                uint32_t src_index) {
    FxScaled out = {src, fg->extent.width, fg->extent.height, VK_FILTER_LINEAR};
    const uint32_t enh = __atomic_load_n(&fg->up_enh, __ATOMIC_RELAXED);
    const uint64_t look = __atomic_load_n(&fg->up_look, __ATOMIC_RELAXED);
    const bool enhance = FX_UP_WANT_ENH(enh) || fx_look_active(look);
    if (!fg->upscale && !enhance) return out;

    uint32_t mode = __atomic_load_n(&fg->up_mode, __ATOMIC_RELAXED);
    uint32_t sharp = __atomic_load_n(&fg->up_sharp, __ATOMIC_RELAXED);
    const uint32_t sharp_method = __atomic_load_n(&fg->up_sharp_method, __ATOMIC_RELAXED);
    if (!fg->upscale) {
        mode = FX_UP_LINEAR; /* 1:1: only the enhancement passes run, the upscale / sharpen settings do not apply */
        sharp = 0;
    }
    if (mode == FX_UP_CUBIC && !fg->cubic_ok) mode = FX_UP_LINEAR;

    const bool shader = fx_up_is_shader_mode(mode);
    VkFilter filter = VK_FILTER_LINEAR;
    if (mode == FX_UP_NEAREST) filter = VK_FILTER_NEAREST;
    else if (mode == FX_UP_CUBIC) filter = VK_FILTER_CUBIC_EXT;

    if ((shader || sharp > 0 || enhance) && fx_up_ensure(fg, FX_UP_WANT_PRE(enh))) {
        out.image = fx_up_record(&fg->up, cmd, mode, sharp, sharp_method, enh, look, src_index,
                                 fg->frame_index, src, fg->extent.width, fg->extent.height, filter);
        out.w = fg->out_extent.width;
        out.h = fg->out_extent.height;
        out.filter = VK_FILTER_NEAREST; /* same size: a plain copy */
        return out;
    }
    out.filter = shader ? VK_FILTER_LINEAR : filter;
    return out;
}

static bool fx_create_instance(FxPresenter* fg) {
    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(NULL, &count, NULL);
    VkExtensionProperties* exts = calloc(count ? count : 1, sizeof(VkExtensionProperties));
    if (!exts) return false;
    vkEnumerateInstanceExtensionProperties(NULL, &count, exts);

    const char* required[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    for (uint32_t i = 0; i < 2; i++) {
        if (!fx_has_extension(exts, count, required[i])) {
            FX_LOGE("missing instance extension %s", required[i]);
            free(exts);
            return false;
        }
    }
    free(exts);

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Fluxa";
    app.pEngineName = "Fluxa";
    app.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = 2;
    ici.ppEnabledExtensionNames = required;

    VkResult res = vkCreateInstance(&ici, NULL, &fg->instance);
    if (res != VK_SUCCESS) {
        app.apiVersion = VK_API_VERSION_1_1;
        res = vkCreateInstance(&ici, NULL, &fg->instance);
    }
    if (res != VK_SUCCESS) {
        FX_LOGE("vkCreateInstance -> %d", res);
        return false;
    }
    return vkd_load_instance(fg->instance);
}

static bool fx_pick_physical_device(FxPresenter* fg) {
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(fg->instance, &count, NULL) != VK_SUCCESS || count == 0) {
        return false;
    }
    if (count > 8) count = 8;
    VkPhysicalDevice devices[8];
    if (vkEnumeratePhysicalDevices(fg->instance, &count, devices) != VK_SUCCESS) return false;

    for (uint32_t i = 0; i < count; i++) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(devices[i], &props);
        if (props.apiVersion < VK_API_VERSION_1_3) {
            FX_LOGW("%s reports Vulkan %u.%u; frame generation needs 1.3", props.deviceName,
                    VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion));
            continue;
        }

        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, NULL);
        if (families == 0) continue;
        if (families > 16) families = 16;
        VkQueueFamilyProperties family_props[16];
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, family_props);

        for (uint32_t q = 0; q < families; q++) {
            const VkQueueFlags need = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
            if (family_props[q].queueCount == 0) continue;
            if ((family_props[q].queueFlags & need) != need) continue;
            VkBool32 supported = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(devices[i], q, fg->surface, &supported);
            if (!supported) continue;
            fg->physical_device = devices[i];
            fg->queue_family = q;
            vkGetPhysicalDeviceMemoryProperties(devices[i], &fg->memory_properties);
            FX_LOGI("frame generation device %s queue family %u", props.deviceName, q);
            return true;
        }
    }
    return false;
}

static bool fx_create_device(FxPresenter* fg) {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(fg->physical_device, NULL, &count, NULL);
    VkExtensionProperties* exts = calloc(count ? count : 1, sizeof(VkExtensionProperties));
    if (!exts) return false;
    vkEnumerateDeviceExtensionProperties(fg->physical_device, NULL, &count, exts);

    const char* required[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
        VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
    };
    const char* enable[10];
    uint32_t enable_n = 0;
    for (uint32_t i = 0; i < sizeof(required) / sizeof(required[0]); i++) {
        if (!fx_has_extension(exts, count, required[i])) {
            FX_LOGE("missing device extension %s", required[i]);
            free(exts);
            return false;
        }
        enable[enable_n++] = required[i];
    }
    if (fx_has_extension(exts, count, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        enable[enable_n++] = VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME;
    }
    if (fx_has_extension(exts, count, VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME)) {
        enable[enable_n++] = VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME;
    }
    if (fx_has_extension(exts, count, VK_EXT_FILTER_CUBIC_EXTENSION_NAME)) {
        enable[enable_n++] = VK_EXT_FILTER_CUBIC_EXTENSION_NAME;
        fg->cubic_ext = true;
    }
    free(exts);

    VkPhysicalDeviceVulkanMemoryModelFeatures memory_model = {
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES
    };
    VkPhysicalDeviceFeatures2 features = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    features.pNext = &memory_model;
    vkGetPhysicalDeviceFeatures2(fg->physical_device, &features);

    if (!memory_model.vulkanMemoryModel || !features.features.shaderStorageImageWriteWithoutFormat
        || !features.features.shaderStorageImageExtendedFormats) {
        FX_LOGE("device lacks the shader features frame generation requires");
        return false;
    }

    VkPhysicalDeviceFeatures enabled = {0};
    enabled.shaderStorageImageWriteWithoutFormat = VK_TRUE;
    enabled.shaderStorageImageExtendedFormats = VK_TRUE;

    VkPhysicalDeviceVulkanMemoryModelFeatures enable_memory_model = {
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES
    };
    enable_memory_model.vulkanMemoryModel = VK_TRUE;
    enable_memory_model.vulkanMemoryModelDeviceScope = memory_model.vulkanMemoryModelDeviceScope;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = fg->queue_family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;

    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &enable_memory_model;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = enable_n;
    dci.ppEnabledExtensionNames = enable;
    dci.pEnabledFeatures = &enabled;

    VkResult res = vkCreateDevice(fg->physical_device, &dci, NULL, &fg->device);
    if (res != VK_SUCCESS) {
        FX_LOGE("vkCreateDevice -> %d", res);
        return false;
    }
    vkGetDeviceQueue(fg->device, fg->queue_family, 0, &fg->queue);

    fg->get_ahb_props = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(
        fg->device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!fg->get_ahb_props) {
        FX_LOGE("vkGetAndroidHardwareBufferPropertiesANDROID unavailable");
        return false;
    }
    return true;
}

static void fx_destroy_swapchain(FxPresenter* fg) {
    for (uint32_t i = 0; i < FX_MAX_SWAPCHAIN_IMAGES; i++) {
        if (fg->swapchain_ready[i]) {
            vkDestroySemaphore(fg->device, fg->swapchain_ready[i], NULL);
            fg->swapchain_ready[i] = VK_NULL_HANDLE;
        }
        fg->swapchain_images[i] = VK_NULL_HANDLE;
    }
    fg->swapchain_image_count = 0;
    if (fg->swapchain) {
        vkDestroySwapchainKHR(fg->device, fg->swapchain, NULL);
        fg->swapchain = VK_NULL_HANDLE;
    }
}

static uint32_t fx_wanted_images(const FxPresenter* fg) {
    uint32_t generations = fg->multiplier > 1 ? fg->multiplier - 1 : 1;
    const uint32_t target = __atomic_load_n(&fg->target_fps, __ATOMIC_RELAXED);
    if (target > 0) {
        // Adaptive mode: room for the most frames it may ever ask for at the nominal source rate.
        const float src = fg->source_rate > 10.0f ? fg->source_rate : 10.0f;
        const uint32_t need = (uint32_t)ceilf((float)target / src);
        generations = need > 1 ? need - 1 : 1;
    }
    if (generations < 3) generations = 3; // headroom for live x2 -> x4 switching
    if (generations > FX_MAX_GENERATIONS) generations = FX_MAX_GENERATIONS;
    return (generations + 1) * 2;
}

static VkPresentModeKHR fx_pick_present_mode(const FxPresenter* fg) {
    if (fg->present_mode != FX_PRESENT_MAILBOX) return VK_PRESENT_MODE_FIFO_KHR;

    uint32_t count = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(fg->physical_device, fg->surface, &count, NULL);
    if (count == 0) return VK_PRESENT_MODE_FIFO_KHR;
    if (count > 16) count = 16;
    VkPresentModeKHR modes[16];
    vkGetPhysicalDeviceSurfacePresentModesKHR(fg->physical_device, fg->surface, &count, modes);
    for (uint32_t i = 0; i < count; i++) {
        if (modes[i] == VK_PRESENT_MODE_MAILBOX_KHR) return VK_PRESENT_MODE_MAILBOX_KHR;
    }
    FX_LOGW("MAILBOX present mode unsupported by this surface; using FIFO");
    return VK_PRESENT_MODE_FIFO_KHR;
}

static bool fx_create_swapchain(FxPresenter* fg) {
    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(fg->physical_device, fg->surface, &caps)
        != VK_SUCCESS) {
        return false;
    }

    uint32_t format_count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(fg->physical_device, fg->surface, &format_count, NULL);
    if (format_count == 0) return false;
    if (format_count > 32) format_count = 32;
    VkSurfaceFormatKHR formats[32];
    vkGetPhysicalDeviceSurfaceFormatsKHR(fg->physical_device, fg->surface, &format_count, formats);

    VkSurfaceFormatKHR chosen = formats[0];
    for (uint32_t i = 0; i < format_count; i++) {
        if (formats[i].format == VK_FORMAT_R8G8B8A8_UNORM
            && formats[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = formats[i];
            break;
        }
        if (formats[i].format == VK_FORMAT_B8G8R8A8_UNORM) chosen = formats[i];
    }

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) extent = fg->upscale ? fg->out_extent : fg->extent;
    if (extent.width == 0 || extent.height == 0) return false;

    uint32_t images = fx_wanted_images(fg);
    if (images < caps.minImageCount + 1) images = caps.minImageCount + 1;
    if (caps.maxImageCount != 0 && images > caps.maxImageCount) images = caps.maxImageCount;
    if (images > FX_MAX_SWAPCHAIN_IMAGES) images = FX_MAX_SWAPCHAIN_IMAGES;

    VkSwapchainCreateInfoKHR sci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = fg->surface;
    sci.minImageCount = images;
    sci.imageFormat = chosen.format;
    sci.imageColorSpace = chosen.colorSpace;
    sci.imageExtent = extent;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                           ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                           : caps.currentTransform;
    sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    sci.presentMode = fx_pick_present_mode(fg);
    fg->built_present_mode = fg->present_mode;
    fg->active_present_mode =
        sci.presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? FX_PRESENT_MAILBOX : FX_PRESENT_FIFO;
    sci.clipped = VK_TRUE;

    if (vkCreateSwapchainKHR(fg->device, &sci, NULL, &fg->swapchain) != VK_SUCCESS) {
        FX_LOGE("vkCreateSwapchainKHR failed");
        return false;
    }

    uint32_t got = 0;
    vkGetSwapchainImagesKHR(fg->device, fg->swapchain, &got, NULL);
    if (got > FX_MAX_SWAPCHAIN_IMAGES) got = FX_MAX_SWAPCHAIN_IMAGES;
    vkGetSwapchainImagesKHR(fg->device, fg->swapchain, &got, fg->swapchain_images);
    fg->swapchain_image_count = got;
    fg->swapchain_format = chosen.format;
    fg->out_extent = extent;
    if (!fg->upscale) fg->extent = extent;

    VkSemaphoreCreateInfo sem = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < got; i++) {
        if (vkCreateSemaphore(fg->device, &sem, NULL, &fg->swapchain_ready[i]) != VK_SUCCESS) {
            return false;
        }
    }

    FX_LOGI("swapchain %ux%u format=%d images=%u currentTransform=0x%x preTransform=0x%x present=%s",
            extent.width, extent.height, (int)chosen.format, got, caps.currentTransform,
            sci.preTransform, fg->active_present_mode == FX_PRESENT_MAILBOX ? "MAILBOX" : "FIFO");
    return true;
}

static void fx_destroy_targets(FxPresenter* fg) {
    fx_up_release_resources(&fg->up); /* its descriptors point at the target views */
    for (uint32_t i = 0; i < FX_MAX_TARGETS; i++) {
        FxTarget* t = &fg->targets[i];
        if (t->view) vkDestroyImageView(fg->device, t->view, NULL);
        if (t->image) vkDestroyImage(fg->device, t->image, NULL);
        if (t->memory) vkFreeMemory(fg->device, t->memory, NULL);
        memset(t, 0, sizeof(*t));
    }
    fg->targets_built = false;
}

static VkFormat fx_pick_target_format(FxPresenter* fg) {
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
                                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                                    | VK_FORMAT_FEATURE_BLIT_SRC_BIT
                                    | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    const VkFormat candidates[2] = {fg->swapchain_format, VK_FORMAT_R8G8B8A8_UNORM};
    for (uint32_t i = 0; i < 2; i++) {
        if (candidates[i] == VK_FORMAT_UNDEFINED) continue;
        VkFormatProperties props;
        memset(&props, 0, sizeof(props));
        vkGetPhysicalDeviceFormatProperties(fg->physical_device, candidates[i], &props);
        if ((props.optimalTilingFeatures & need) == need) return candidates[i];
    }
    return VK_FORMAT_UNDEFINED;
}

static bool fx_create_targets(FxPresenter* fg) {
    fx_destroy_targets(fg);

    fg->target_format = fx_pick_target_format(fg);
    if (fg->target_format == VK_FORMAT_UNDEFINED) {
        FX_LOGE("no storage-capable colour format for the composite ring");
        return false;
    }

    {
        VkFormatProperties props;
        memset(&props, 0, sizeof(props));
        vkGetPhysicalDeviceFormatProperties(fg->physical_device, fg->target_format, &props);
        fg->cubic_ok = fg->cubic_ext
            && (props.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT);
    }

    for (uint32_t i = 0; i < FX_MAX_TARGETS; i++) {
        FxTarget* t = &fg->targets[i];

        VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format = fg->target_format;
        ici.extent.width = fg->extent.width;
        ici.extent.height = fg->extent.height;
        ici.extent.depth = 1;
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;
        ici.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                  | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(fg->device, &ici, NULL, &t->image) != VK_SUCCESS) goto fail;

        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(fg->device, t->image, &req);
        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex =
            fx_find_memory_type(fg, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (mai.memoryTypeIndex == UINT32_MAX) goto fail;
        if (vkAllocateMemory(fg->device, &mai, NULL, &t->memory) != VK_SUCCESS) goto fail;
        if (vkBindImageMemory(fg->device, t->image, t->memory, 0) != VK_SUCCESS) goto fail;

        VkImageViewCreateInfo vci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vci.image = t->image;
        vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vci.format = fg->target_format;
        vci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        vci.subresourceRange.levelCount = 1;
        vci.subresourceRange.layerCount = 1;
        if (vkCreateImageView(fg->device, &vci, NULL, &t->view) != VK_SUCCESS) goto fail;
    }

    fg->targets_built = true;
    return true;

fail:
    fx_destroy_targets(fg);
    return false;
}

static bool fx_create_frames(FxPresenter* fg) {
    VkCommandPoolCreateInfo cpi = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpi.queueFamilyIndex = fg->queue_family;
    if (vkCreateCommandPool(fg->device, &cpi, NULL, &fg->command_pool) != VK_SUCCESS) return false;

    VkFenceCreateInfo ffi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(fg->device, &ffi, NULL, &fg->flush_fence) != VK_SUCCESS) return false;

    for (uint32_t i = 0; i < FX_FRAMES_IN_FLIGHT; i++) {
        FxFrame* f = &fg->frames[i];

        VkCommandBufferAllocateInfo cbi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cbi.commandPool = fg->command_pool;
        cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbi.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(fg->device, &cbi, &f->cmd) != VK_SUCCESS) return false;

        VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateFence(fg->device, &fci, NULL, &f->fence) != VK_SUCCESS) return false;

        VkSemaphoreCreateInfo sci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if (vkCreateSemaphore(fg->device, &sci, NULL, &f->acquire) != VK_SUCCESS) return false;
        for (uint32_t g = 0; g < FX_MAX_GENERATIONS; g++) {
            if (vkCreateSemaphore(fg->device, &sci, NULL, &f->acquire_gen[g]) != VK_SUCCESS) {
                return false;
            }
        }
    }
    return true;
}

static void fx_release_imports(FxPresenter* fg) {
    for (uint32_t i = 0; i < FX_IMPORT_CACHE; i++) {
        FxImport* imp = &fg->imports[i];
        if (imp->image) vkDestroyImage(fg->device, imp->image, NULL);
        if (imp->memory) vkFreeMemory(fg->device, imp->memory, NULL);
        if (imp->buffer) AHardwareBuffer_release(imp->buffer);
        memset(imp, 0, sizeof(*imp));
    }
}

static FxImport* fx_import_buffer(FxPresenter* fg, AHardwareBuffer* buffer) {
    for (uint32_t i = 0; i < FX_IMPORT_CACHE; i++) {
        if (fg->imports[i].buffer == buffer) return &fg->imports[i];
    }

    FxImport* slot = NULL;
    for (uint32_t i = 0; i < FX_IMPORT_CACHE; i++) {
        if (!fg->imports[i].buffer) {
            slot = &fg->imports[i];
            break;
        }
    }
    if (!slot) {
        vkDeviceWaitIdle(fg->device);
        fx_release_imports(fg);
        slot = &fg->imports[0];
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID format_props = {
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID
    };
    VkAndroidHardwareBufferPropertiesANDROID props = {
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID
    };
    props.pNext = &format_props;
    if (fg->get_ahb_props(fg->device, buffer, &props) != VK_SUCCESS) {
        FX_LOGW("vkGetAndroidHardwareBufferPropertiesANDROID failed");
        return NULL;
    }
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    AHardwareBuffer_describe(buffer, &desc);

    if (format_props.format == VK_FORMAT_UNDEFINED) {
        FX_LOGW("producer buffer format %u is external-only; frame generation cannot sample it",
                desc.format);
        return NULL;
    }

    VkExternalMemoryImageCreateInfo emi = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.pNext = &emi;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = format_props.format;
    ici.extent.width = desc.width;
    ici.extent.height = desc.height;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImage image = VK_NULL_HANDLE;
    if (vkCreateImage(fg->device, &ici, NULL, &image) != VK_SUCCESS) {
        FX_LOGW("AHB vkCreateImage failed");
        return NULL;
    }

    VkImportAndroidHardwareBufferInfoANDROID import = {
        VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID
    };
    import.buffer = buffer;

    VkMemoryDedicatedAllocateInfo dedicated = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = image;
    dedicated.pNext = &import;

    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.pNext = &dedicated;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = fx_find_memory_type(fg, props.memoryTypeBits, 0);

    VkDeviceMemory memory = VK_NULL_HANDLE;
    if (mai.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(fg->device, &mai, NULL, &memory) != VK_SUCCESS) {
        FX_LOGW("AHB memory import failed");
        vkDestroyImage(fg->device, image, NULL);
        return NULL;
    }
    if (vkBindImageMemory(fg->device, image, memory, 0) != VK_SUCCESS) {
        FX_LOGW("AHB vkBindImageMemory failed");
        vkFreeMemory(fg->device, memory, NULL);
        vkDestroyImage(fg->device, image, NULL);
        return NULL;
    }

    FX_LOGI("imported producer buffer %ux%u ahb format %u as vk format %d", desc.width,
            desc.height, desc.format, (int)format_props.format);
    AHardwareBuffer_acquire(buffer);
    slot->buffer = buffer;
    slot->image = image;
    slot->memory = memory;
    slot->width = desc.width;
    slot->height = desc.height;
    return slot;
}

static void fx_await_fence_fd(int fence_fd) {
    if (fence_fd < 0) return;
    struct pollfd pfd = {fence_fd, POLLIN, 0};
    int rc;
    do {
        rc = poll(&pfd, 1, FX_FENCE_WAIT_MS);
    } while (rc < 0 && errno == EINTR);
    close(fence_fd);
}

static void fx_image_available(void* context, AImageReader* reader) {
    (void)reader;
    FxPresenter* fg = (FxPresenter*)context;
    pthread_mutex_lock(&fg->lock);
    fg->image_pending = true;
    pthread_cond_signal(&fg->signal);
    pthread_mutex_unlock(&fg->lock);
}

/* Source rate the game really delivers (arrivals per second, repeats folded out). 0 = unknown. */
static float fx_measured_source_rate(const FxPresenter* fg) {
    if (fg->raw_rate <= 1.0f) return 0.0f;
    const uint32_t divisor = fg->source_divisor ? fg->source_divisor : 1u;
    return fg->raw_rate / (float)divisor;
}

static void fx_dis_configure(FxPresenter* fg, uint32_t multiplier, uint32_t dis_min_side) {
    if (!fg->dis) return;
    vkr_dis_configure(fg->dis, dis_min_side ? dis_min_side : FX_DIS_MIN_SIDE_DEFAULT, multiplier,
                      0.0f /* no panel-rate cap: generate as many frames as asked */);
    vkr_dis_set_mode(fg->dis, __atomic_load_n(&fg->gen_mode, __ATOMIC_RELAXED));
    vkr_dis_set_target_fps(fg->dis, __atomic_load_n(&fg->target_fps, __ATOMIC_RELAXED));
}

static void fx_dis_release(FxPresenter* fg) {
    if (fg->dis) {
        vkr_dis_destroy(fg->dis);
        fg->dis = NULL;
    }
}

static bool fx_dis_acquire(FxPresenter* fg) {
    if (fg->dis) return true;
    fg->dis = vkr_dis_create(fg->device, fg->physical_device);
    if (!fg->dis) {
        FX_LOGW("DIS optical flow is unavailable on this device");
        return false;
    }
    vkr_dis_set_mode(fg->dis, __atomic_load_n(&fg->gen_mode, __ATOMIC_RELAXED));
    vkr_dis_set_target_fps(fg->dis, __atomic_load_n(&fg->target_fps, __ATOMIC_RELAXED));
    return true;
}

static bool fx_dis_ready(const FxPresenter* fg) {
    return fg->dis != NULL;
}

static void fx_apply_config(FxPresenter* fg) {
    fx_rec_sync(fg); /* recording start/stop requests ride along with the config poll */

    pthread_mutex_lock(&fg->lock);
    bool dirty = fg->config_dirty;
    uint32_t multiplier = fg->multiplier;
    uint32_t dis_min_side = fg->dis_min_side;
    uint32_t present_mode = fg->present_mode;
    fg->config_dirty = false;
    pthread_mutex_unlock(&fg->lock);

    if (!dirty) return;

    if (present_mode != fg->built_present_mode && fg->swapchain) {
        // present mode is baked into the swapchain: rebuild it (waits for the device to idle)
        fx_reset_swapchain(fg);
        FX_LOGI("present mode -> %s", fg->active_present_mode == FX_PRESENT_MAILBOX ? "MAILBOX" : "FIFO");
    }

    fx_dis_configure(fg, multiplier, dis_min_side);
}

static void fx_renew_semaphore(FxPresenter* fg, VkSemaphore* handle) {
    if (fg->retired_count >= FX_MAX_RETIRED) return;
    VkSemaphoreCreateInfo sci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkSemaphore fresh = VK_NULL_HANDLE;
    if (vkCreateSemaphore(fg->device, &sci, NULL, &fresh) != VK_SUCCESS) return;
    if (*handle) fg->retired[fg->retired_count++] = *handle;
    *handle = fresh;
}

// VkrDisFlushFn: DIS needs this frame's pixels on the CPU mid-frame for the hardware motion
// estimator. Everything recorded so far - the source blit and DIS's copies - touches no
// swapchain image and waits on no semaphore, so it goes out on its own; the same buffer is then
// begun again for the rest of the frame, which keeps every semaphore on the final submit.
static VkCommandBuffer fx_dis_flush(void* user, VkCommandBuffer cmd) {
    FxPresenter* fg = (FxPresenter*)user;
    vkEndCommandBuffer(cmd);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    vkResetFences(fg->device, 1, &fg->flush_fence);
    if (vkQueueSubmit(fg->queue, 1, &si, fg->flush_fence) == VK_SUCCESS) {
        vkWaitForFences(fg->device, 1, &fg->flush_fence, VK_TRUE, UINT64_MAX);
    } else {
        FX_LOGW("DIS mid-frame submit failed");
        vkDeviceWaitIdle(fg->device);
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &bi);
    return cmd;
}

/* ------------------------------------------------------------------ screen recording */

/* Presenter thread (or destroy, after the thread is gone). Tears the recording swapchain down. */
static void fx_rec_destroy(FxPresenter* fg) {
    FxRec* r = &fg->rec;
    if (fg->device) {
        vkDeviceWaitIdle(fg->device);
        for (uint32_t i = 0; i < r->image_count && r->ready; i++) {
            if (r->ready[i]) vkDestroySemaphore(fg->device, r->ready[i], NULL);
        }
        for (uint32_t f = 0; f < FX_FRAMES_IN_FLIGHT; f++) {
            for (uint32_t k = 0; k < FX_REC_PER_FRAME; k++) {
                if (r->acquire[f][k]) vkDestroySemaphore(fg->device, r->acquire[f][k], NULL);
            }
        }
        for (uint32_t f = 0; f < FX_FRAMES_IN_FLIGHT; f++) {
            if (r->hud_map[f]) vkUnmapMemory(fg->device, r->hud_buf_mem[f]);
            if (r->hud_buf[f]) vkDestroyBuffer(fg->device, r->hud_buf[f], NULL);
            if (r->hud_buf_mem[f]) vkFreeMemory(fg->device, r->hud_buf_mem[f], NULL);
        }
        if (r->hud_image) vkDestroyImage(fg->device, r->hud_image, NULL);
        if (r->hud_memory) vkFreeMemory(fg->device, r->hud_memory, NULL);
        if (r->swapchain) vkDestroySwapchainKHR(fg->device, r->swapchain, NULL);
    }
    if (r->surface && fg->instance) vkDestroySurfaceKHR(fg->instance, r->surface, NULL);
    if (r->window) ANativeWindow_release(r->window);
    free(r->images);
    free(r->ready);
    free(r->presented);
    memset(r, 0, sizeof(*r));
}

/* Takes ownership of `window`. */
static bool fx_rec_create(FxPresenter* fg, ANativeWindow* window, uint32_t width,
                          uint32_t height) {
    FxRec* r = &fg->rec;
    memset(r, 0, sizeof(*r));
    r->window = window;

    VkAndroidSurfaceCreateInfoKHR asi;
    memset(&asi, 0, sizeof(asi));
    asi.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    asi.window = window;
    if (vkCreateAndroidSurfaceKHR(fg->instance, &asi, NULL, &r->surface) != VK_SUCCESS) {
        FX_LOGE("record: vkCreateAndroidSurfaceKHR failed");
        goto fail;
    }

    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(fg->physical_device, fg->queue_family, r->surface,
                                         &supported);
    if (!supported) {
        FX_LOGE("record: queue family cannot present to the encoder surface");
        goto fail;
    }

    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(fg->physical_device, r->surface, &caps)
        != VK_SUCCESS) {
        goto fail;
    }

    uint32_t format_count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(fg->physical_device, r->surface, &format_count, NULL);
    if (format_count == 0) goto fail;
    if (format_count > 32) format_count = 32;
    VkSurfaceFormatKHR formats[32];
    vkGetPhysicalDeviceSurfaceFormatsKHR(fg->physical_device, r->surface, &format_count, formats);
    VkSurfaceFormatKHR chosen = formats[0];
    for (uint32_t i = 0; i < format_count; i++) {
        if (formats[i].format == VK_FORMAT_R8G8B8A8_UNORM
            && formats[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = formats[i];
            break;
        }
        if (formats[i].format == VK_FORMAT_B8G8R8A8_UNORM) chosen = formats[i];
    }

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX || extent.height == UINT32_MAX) {
        extent.width = width;
        extent.height = height;
    }
    if (extent.width < caps.minImageExtent.width) extent.width = caps.minImageExtent.width;
    if (extent.height < caps.minImageExtent.height) extent.height = caps.minImageExtent.height;
    if (caps.maxImageExtent.width && extent.width > caps.maxImageExtent.width) {
        extent.width = caps.maxImageExtent.width;
    }
    if (caps.maxImageExtent.height && extent.height > caps.maxImageExtent.height) {
        extent.height = caps.maxImageExtent.height;
    }
    if (extent.width == 0 || extent.height == 0) goto fail;

    uint32_t images = caps.minImageCount + 1;
    if (caps.maxImageCount != 0 && images > caps.maxImageCount) images = caps.maxImageCount;
    if (images < 2) images = 2;

    VkSwapchainCreateInfoKHR sci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = r->surface;
    sci.minImageCount = images;
    sci.imageFormat = chosen.format;
    sci.imageColorSpace = chosen.colorSpace;
    sci.imageExtent = extent;
    sci.imageArrayLayers = 1;
    /* The encoder feed is only ever a blit/clear destination. Asking for COLOR_ATTACHMENT too makes
     * Mali (Mali-G68 on Exynos) tag these images for CRC / transaction elimination, and
     * vkCmdPipelineBarrier then crashes in find_crc_invalidate_transitions (null deref). */
    if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
        FX_LOGE("record: encoder surface does not support TRANSFER_DST");
        goto fail;
    }
    sci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                           ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                           : caps.currentTransform;
    sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                             ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                             : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sci.presentMode = VK_PRESENT_MODE_FIFO_KHR; /* encoder queue: never drop frames silently */
    sci.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(fg->device, &sci, NULL, &r->swapchain) != VK_SUCCESS) {
        FX_LOGE("record: vkCreateSwapchainKHR failed");
        goto fail;
    }

    uint32_t got = 0;
    vkGetSwapchainImagesKHR(fg->device, r->swapchain, &got, NULL);
    if (got == 0 || got > FX_REC_SANITY_MAX_IMAGES) {
        FX_LOGE("record: swapchain reports %u images", got);
        goto fail;
    }
    r->images = (VkImage*)calloc(got, sizeof(VkImage));
    r->ready = (VkSemaphore*)calloc(got, sizeof(VkSemaphore));
    r->presented = (uint8_t*)calloc(got, sizeof(uint8_t));
    if (!r->images || !r->ready || !r->presented) goto fail;
    vkGetSwapchainImagesKHR(fg->device, r->swapchain, &got, r->images);
    r->image_count = got;
    if (got == 0) goto fail;
    for (uint32_t i = 0; i < got; i++) {
        if (r->images[i] == VK_NULL_HANDLE) {
            FX_LOGE("record: swapchain image %u is null", i);
            goto fail;
        }
    }
    r->extent = extent;

    VkSemaphoreCreateInfo sem = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < got; i++) {
        if (vkCreateSemaphore(fg->device, &sem, NULL, &r->ready[i]) != VK_SUCCESS) goto fail;
    }
    for (uint32_t f = 0; f < FX_FRAMES_IN_FLIGHT; f++) {
        for (uint32_t k = 0; k < FX_REC_PER_FRAME; k++) {
            if (vkCreateSemaphore(fg->device, &sem, NULL, &r->acquire[f][k]) != VK_SUCCESS) {
                goto fail;
            }
        }
    }

    FX_LOGI("record swapchain %ux%u format=%d images=%u", extent.width, extent.height,
            (int)chosen.format, got);
    return true;

fail:
    fx_rec_destroy(fg);
    return false;
}

/* Presenter thread: applies a pending start/stop request (and rebuilds after a lost surface). */
static void fx_rec_sync(FxPresenter* fg) {
    pthread_mutex_lock(&fg->rec_lock);
    const bool stop = fg->rec_stop_req;
    const bool start = fg->rec_start_req;
    ANativeWindow* window = fg->rec_pending_window;
    const uint32_t width = fg->rec_pending_w;
    const uint32_t height = fg->rec_pending_h;
    const uint64_t seq = fg->rec_req_seq;
    fg->rec_stop_req = false;
    fg->rec_start_req = false;
    fg->rec_pending_window = NULL;
    pthread_mutex_unlock(&fg->rec_lock);

    if (!stop && !start && !fg->rec.broken) return;

    if (fg->rec.window || fg->rec.swapchain || fg->rec.surface) fx_rec_destroy(fg);
    fg->rec.broken = false;

    if (start && window && fg->device) {
        if (!fx_rec_create(fg, window, width, height)) {
            FX_LOGW("record: could not attach to the encoder surface");
        }
    } else if (window) {
        ANativeWindow_release(window);
    }

    pthread_mutex_lock(&fg->rec_lock);
    fg->rec_live = fg->rec.swapchain != VK_NULL_HANDLE;
    if (seq > fg->rec_done_seq) fg->rec_done_seq = seq;
    pthread_cond_broadcast(&fg->rec_cond);
    pthread_mutex_unlock(&fg->rec_lock);
}

/* Where the shown frame lands inside the (fixed-orientation) video: aspect-fit, black bars.
 * Returns true when it fills the whole video. */
static bool fx_rec_rect(const FxPresenter* fg, int32_t* x0, int32_t* y0, int32_t* x1,
                        int32_t* y1) {
    const uint32_t rw = fg->rec.extent.width;
    const uint32_t rh = fg->rec.extent.height;
    const uint32_t sw = fg->extent.width;
    const uint32_t sh = fg->extent.height;
    *x0 = 0;
    *y0 = 0;
    *x1 = (int32_t)rw;
    *y1 = (int32_t)rh;
    if (!sw || !sh || !rw || !rh) return true;

    uint32_t dw;
    uint32_t dh;
    if ((uint64_t)rw * sh <= (uint64_t)rh * sw) {
        dw = rw;
        dh = (uint32_t)(((uint64_t)sh * rw + sw / 2) / sw);
    } else {
        dh = rh;
        dw = (uint32_t)(((uint64_t)sw * rh + sh / 2) / sh);
    }
    if (dw > rw) dw = rw;
    if (dh > rh) dh = rh;
    if (dw < 2) dw = 2;
    if (dh < 2) dh = 2;
    if (rw - dw <= 2 && rh - dh <= 2) return true; /* near-equal aspect: just stretch */

    *x0 = (int32_t)((rw - dw) / 2);
    *y0 = (int32_t)((rh - dh) / 2);
    *x1 = *x0 + (int32_t)dw;
    *y1 = *y0 + (int32_t)dh;
    return false;
}

/* Presenter thread: builds the HUD image + one host-visible staging buffer per frame in flight. */
static void fx_rec_hud_create(FxPresenter* fg) {
    FxRec* r = &fg->rec;
    r->hud_tried = true;
    const VkDeviceSize bytes = (VkDeviceSize)FX_HUD_MAX_W * FX_HUD_MAX_H * 4u;

    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_R8G8B8A8_UNORM;
    ici.extent.width = FX_HUD_MAX_W;
    ici.extent.height = FX_HUD_MAX_H;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(fg->device, &ici, NULL, &r->hud_image) != VK_SUCCESS) goto fail;
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(fg->device, r->hud_image, &req);
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex =
        fx_find_memory_type(fg, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) goto fail;
    if (vkAllocateMemory(fg->device, &mai, NULL, &r->hud_memory) != VK_SUCCESS) goto fail;
    if (vkBindImageMemory(fg->device, r->hud_image, r->hud_memory, 0) != VK_SUCCESS) goto fail;

    for (uint32_t i = 0; i < FX_FRAMES_IN_FLIGHT; i++) {
        VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = bytes;
        bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(fg->device, &bci, NULL, &r->hud_buf[i]) != VK_SUCCESS) goto fail;
        vkGetBufferMemoryRequirements(fg->device, r->hud_buf[i], &req);
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = fx_find_memory_type(
            fg, req.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (mai.memoryTypeIndex == UINT32_MAX) goto fail;
        if (vkAllocateMemory(fg->device, &mai, NULL, &r->hud_buf_mem[i]) != VK_SUCCESS) goto fail;
        if (vkBindBufferMemory(fg->device, r->hud_buf[i], r->hud_buf_mem[i], 0) != VK_SUCCESS) {
            goto fail;
        }
        void* map = NULL;
        if (vkMapMemory(fg->device, r->hud_buf_mem[i], 0, VK_WHOLE_SIZE, 0, &map) != VK_SUCCESS ||
            !map) {
            goto fail;
        }
        r->hud_map[i] = (uint8_t*)map;
    }
    r->hud_ok = true;
    return;

fail:
    FX_LOGW("record: HUD overlay unavailable, video is recorded without it");
    for (uint32_t i = 0; i < FX_FRAMES_IN_FLIGHT; i++) {
        if (r->hud_map[i]) vkUnmapMemory(fg->device, r->hud_buf_mem[i]);
        if (r->hud_buf[i]) vkDestroyBuffer(fg->device, r->hud_buf[i], NULL);
        if (r->hud_buf_mem[i]) vkFreeMemory(fg->device, r->hud_buf_mem[i], NULL);
        r->hud_map[i] = NULL;
        r->hud_buf[i] = VK_NULL_HANDLE;
        r->hud_buf_mem[i] = VK_NULL_HANDLE;
    }
    if (r->hud_image) vkDestroyImage(fg->device, r->hud_image, NULL);
    if (r->hud_memory) vkFreeMemory(fg->device, r->hud_memory, NULL);
    r->hud_image = VK_NULL_HANDLE;
    r->hud_memory = VK_NULL_HANDLE;
    r->hud_ok = false;
}

/* Once per frame, before the first fx_rec_blit: when Java sent a new HUD bitmap, copy it into this
 * frame's staging buffer and record the upload into the HUD image. */
static void fx_rec_hud_prepare(FxPresenter* fg, VkCommandBuffer cmd) {
    FxRec* r = &fg->rec;
    pthread_mutex_lock(&fg->rec_lock);
    if (fg->hud_version == r->hud_version) {
        pthread_mutex_unlock(&fg->rec_lock);
        return;
    }
    r->hud_version = fg->hud_version;
    const uint32_t w = fg->hud_w;
    const uint32_t h = fg->hud_h;
    if (!w || !h || !fg->hud_pixels) { /* HUD switched off */
        r->hud_w = 0;
        r->hud_h = 0;
        pthread_mutex_unlock(&fg->rec_lock);
        return;
    }
    if (!r->hud_tried) fx_rec_hud_create(fg);
    if (!r->hud_ok) {
        r->hud_w = 0;
        r->hud_h = 0;
        pthread_mutex_unlock(&fg->rec_lock);
        return;
    }
    const uint32_t slot = fg->frame_index;
    memcpy(r->hud_map[slot], fg->hud_pixels, (size_t)w * h * 4u);
    r->hud_w = w;
    r->hud_h = h;
    r->hud_mx = fg->hud_mx;
    r->hud_my = fg->hud_my;
    pthread_mutex_unlock(&fg->rec_lock);

    fx_barrier(cmd, r->hud_image,
               r->hud_has_content ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
    VkBufferImageCopy region = {0};
    region.bufferRowLength = w;
    region.bufferImageHeight = h;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent.width = w;
    region.imageExtent.height = h;
    region.imageExtent.depth = 1;
    vkCmdCopyBufferToImage(cmd, r->hud_buf[slot], r->hud_image,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    fx_barrier(cmd, r->hud_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
               VK_ACCESS_TRANSFER_READ_BIT);
    r->hud_has_content = true;
}

/* Burns the HUD into recording image `dst` (already in TRANSFER_DST_OPTIMAL), 1:1, no scaling. */
static void fx_rec_hud_blit(FxPresenter* fg, VkCommandBuffer cmd, VkImage dst, int32_t x0,
                            int32_t y0) {
    const FxRec* r = &fg->rec;
    if (!r->hud_ok || !r->hud_has_content || !r->hud_w || !r->hud_h) return;
    int32_t dx = x0 + r->hud_mx;
    int32_t dy = y0 + r->hud_my;
    if (dx < 0) dx = 0;
    if (dy < 0) dy = 0;
    const int32_t rw = (int32_t)r->extent.width;
    const int32_t rh = (int32_t)r->extent.height;
    int32_t w = (int32_t)r->hud_w;
    int32_t h = (int32_t)r->hud_h;
    if (dx >= rw || dy >= rh) return;
    if (dx + w > rw) w = rw - dx;
    if (dy + h > rh) h = rh - dy;
    VkImageBlit blit = {0};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = w;
    blit.srcOffsets[1].y = h;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[0].x = dx;
    blit.dstOffsets[0].y = dy;
    blit.dstOffsets[1].x = dx + w;
    blit.dstOffsets[1].y = dy + h;
    blit.dstOffsets[1].z = 1;
    vkCmdBlitImage(cmd, r->hud_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
}

/* Records "copy this shown frame into recording image `dst`" into the frame's command buffer. */
static void fx_rec_blit(FxPresenter* fg, VkCommandBuffer cmd, VkImage src,
                        VkImageLayout src_layout, uint32_t dst_index) {
    if (dst_index >= fg->rec.image_count) return;
    const VkImage dst = fg->rec.images[dst_index];
    if (dst == VK_NULL_HANDLE) return;
    /* like a normal WSI client: an image that was already presented comes back in PRESENT_SRC */
    const bool was_presented = fg->rec.presented[dst_index] != 0;
    int32_t x0, y0, x1, y1;
    const bool full = fx_rec_rect(fg, &x0, &y0, &x1, &y1);

    fx_barrier(cmd, dst, was_presented ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED,
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, was_presented ? VK_ACCESS_MEMORY_READ_BIT : 0,
               VK_ACCESS_TRANSFER_WRITE_BIT);
    if (!full) {
        VkClearColorValue black;
        memset(&black, 0, sizeof(black));
        black.float32[3] = 1.0f;
        VkImageSubresourceRange range;
        memset(&range, 0, sizeof(range));
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        vkCmdClearColorImage(cmd, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);
        fx_barrier(cmd, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT);
    }
    fx_blit_rect(cmd, src, src_layout, fg->extent.width, fg->extent.height, dst,
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, x0, y0, x1, y1);
    fx_rec_hud_blit(fg, cmd, dst, x0, y0);
    fx_barrier(cmd, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
               VK_ACCESS_TRANSFER_WRITE_BIT, 0);
}

static void fx_present_frame(FxPresenter* fg, FxImport* source, AImage* image) {
    FxFrame* f = &fg->frames[fg->frame_index];

    uint32_t capacity = 0;
    if (fx_dis_ready(fg) && fg->swapchain_image_count > 2) {
        capacity = fg->swapchain_image_count - 2;
        if (capacity > FX_MAX_GENERATIONS) capacity = FX_MAX_GENERATIONS;
    }

    uint32_t planned = 0;
    if (fg->dis) planned = vkr_dis_plan(fg->dis, capacity, fg->source_frames);

    uint32_t image_index = 0;
    VkResult acq = vkAcquireNextImageKHR(fg->device, fg->swapchain, UINT64_MAX, f->acquire,
                                         VK_NULL_HANDLE, &image_index);
    if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) {
        AImage_delete(image);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR) fx_reset_swapchain(fg);
        return;
    }

    uint64_t gen_timeout = FX_ACQUIRE_TIMEOUT_MAX_NS;
    if (fg->refresh_rate > 1.0f) {
        gen_timeout = (uint64_t)(2000000000.0f / fg->refresh_rate);
        if (gen_timeout < FX_ACQUIRE_TIMEOUT_NS) gen_timeout = FX_ACQUIRE_TIMEOUT_NS;
        if (gen_timeout > FX_ACQUIRE_TIMEOUT_MAX_NS) gen_timeout = FX_ACQUIRE_TIMEOUT_MAX_NS;
    }

    uint32_t gen_count = 0;
    uint32_t gen_index[FX_MAX_GENERATIONS] = {0};
    for (uint32_t g = 0; g < planned; g++) {
        uint32_t index = 0;
        VkResult ga = vkAcquireNextImageKHR(fg->device, fg->swapchain, gen_timeout,
                                            f->acquire_gen[g], VK_NULL_HANDLE, &index);
        if (ga != VK_SUCCESS && ga != VK_SUBOPTIMAL_KHR) {
            if (fg->acquire_misses++ % 240 == 0) {
                FX_LOGW("generated frame %u/%u dropped: acquire -> %d", g + 1, planned, (int)ga);
            }
            fx_renew_semaphore(fg, &f->acquire_gen[g]);
            break;
        }
        gen_index[gen_count++] = index;
    }

    /* Recording: one video image per shown frame (generated ones first, the real one last).
     * Never block the display for the encoder - a frame without a free image is just skipped. */
    uint32_t rec_index[FX_REC_PER_FRAME];
    VkSemaphore* rec_acquire = fg->rec.acquire[fg->frame_index];
    const uint32_t rec_want = gen_count + 1;
    for (uint32_t k = 0; k < FX_REC_PER_FRAME; k++) rec_index[k] = UINT32_MAX;
    if (fg->rec.swapchain && !fg->rec.broken) {
        for (uint32_t k = 0; k < rec_want && k < FX_REC_PER_FRAME; k++) {
            uint32_t index = 0;
            VkResult ra = vkAcquireNextImageKHR(fg->device, fg->rec.swapchain,
                                                FX_REC_ACQUIRE_TIMEOUT_NS, rec_acquire[k],
                                                VK_NULL_HANDLE, &index);
            if ((ra == VK_SUCCESS || ra == VK_SUBOPTIMAL_KHR) && index < fg->rec.image_count) {
                rec_index[k] = index;
            } else if (ra == VK_SUCCESS || ra == VK_SUBOPTIMAL_KHR) {
                FX_LOGW("record: acquire gave image %u but the swapchain has %u, stopping", index,
                        fg->rec.image_count);
                fg->rec.dropped += rec_want - k;
                fg->rec.broken = true;
                break;
            } else {
                /* encoder is behind: skip the rest of this frame's video instead of stalling
                 * the display for FX_REC_ACQUIRE_TIMEOUT_NS per remaining image */
                fg->rec.dropped += rec_want - k;
                if (ra != VK_TIMEOUT && ra != VK_NOT_READY) {
                    FX_LOGW("record: acquire -> %d, stopping the video feed", (int)ra);
                    fg->rec.broken = true;
                }
                break;
            }
        }
    }

    vkResetFences(fg->device, 1, &f->fence);
    vkResetCommandBuffer(f->cmd, 0);

    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(f->cmd, &bi);
    if (fg->rec.swapchain && !fg->rec.broken) fx_rec_hud_prepare(fg, f->cmd);

    FxTarget* composite = &fg->targets[fg->frame_index];

    fx_barrier(f->cmd, source->image, VK_IMAGE_LAYOUT_UNDEFINED,
               VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_READ_BIT);
    fx_barrier(f->cmd, composite->image, VK_IMAGE_LAYOUT_UNDEFINED,
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
    fx_blit(f->cmd, source->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, source->width,
            source->height, composite->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            fg->extent.width, fg->extent.height, VK_FILTER_LINEAR);
    fx_barrier(f->cmd, composite->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                   | VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_ACCESS_TRANSFER_WRITE_BIT,
               VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);

    if (fg->dis) {
        vkr_dis_process_ex(fg->dis, f->cmd, composite->image, fg->extent.width,
                           fg->extent.height, gen_count, fx_dis_flush, fg);
    }

    for (uint32_t g = 0; g < gen_count; g++) {
        FxTarget* generated = &fg->targets[FX_FRAMES_IN_FLIGHT + g];
        vkr_dis_generate_into(fg->dis, f->cmd, g, FX_FRAMES_IN_FLIGHT + g, generated->image,
                              generated->view, fg->extent.width, fg->extent.height,
                              VK_NULL_HANDLE);
        fx_barrier(f->cmd, generated->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                   VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        const FxScaled gen_scaled =
            fx_scale_source(fg, f->cmd, generated->image, FX_FRAMES_IN_FLIGHT + g);
        fx_barrier(f->cmd, fg->swapchain_images[gen_index[g]], VK_IMAGE_LAYOUT_UNDEFINED,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                   VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
        fx_blit(f->cmd, gen_scaled.image, VK_IMAGE_LAYOUT_GENERAL, gen_scaled.w, gen_scaled.h,
                fg->swapchain_images[gen_index[g]], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                fg->out_extent.width, fg->out_extent.height, gen_scaled.filter);
        fx_barrier(f->cmd, fg->swapchain_images[gen_index[g]],
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                   VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        if (rec_index[g] != UINT32_MAX) {
            fx_rec_blit(fg, f->cmd, generated->image, VK_IMAGE_LAYOUT_GENERAL, rec_index[g]);
        }
    }

    fx_barrier(f->cmd, composite->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
               VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
               VK_ACCESS_TRANSFER_READ_BIT);
    const FxScaled real_scaled = fx_scale_source(fg, f->cmd, composite->image, fg->frame_index);
    fx_barrier(f->cmd, fg->swapchain_images[image_index], VK_IMAGE_LAYOUT_UNDEFINED,
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
    fx_blit(f->cmd, real_scaled.image, VK_IMAGE_LAYOUT_GENERAL, real_scaled.w, real_scaled.h,
            fg->swapchain_images[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            fg->out_extent.width, fg->out_extent.height, real_scaled.filter);
    fx_barrier(f->cmd, fg->swapchain_images[image_index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
    if (rec_index[gen_count] != UINT32_MAX) {
        fx_rec_blit(fg, f->cmd, composite->image, VK_IMAGE_LAYOUT_GENERAL, rec_index[gen_count]);
    }

    vkEndCommandBuffer(f->cmd);

    VkSemaphore wait[4 * (1 + FX_MAX_GENERATIONS)];
    VkPipelineStageFlags stages[4 * (1 + FX_MAX_GENERATIONS)];
    VkSemaphore signal[4 * (1 + FX_MAX_GENERATIONS)];
    uint32_t wait_count = 0;
    uint32_t signal_count = 0;

    wait[wait_count] = f->acquire;
    stages[wait_count++] = VK_PIPELINE_STAGE_TRANSFER_BIT;
    signal[signal_count++] = fg->swapchain_ready[image_index];
    for (uint32_t g = 0; g < gen_count; g++) {
        wait[wait_count] = f->acquire_gen[g];
        stages[wait_count++] = VK_PIPELINE_STAGE_TRANSFER_BIT;
        signal[signal_count++] = fg->swapchain_ready[gen_index[g]];
    }
    for (uint32_t k = 0; k < rec_want && k < FX_REC_PER_FRAME; k++) {
        if (rec_index[k] == UINT32_MAX) continue;
        wait[wait_count] = rec_acquire[k];
        stages[wait_count++] = VK_PIPELINE_STAGE_TRANSFER_BIT;
        signal[signal_count++] = fg->rec.ready[rec_index[k]];
    }

    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = wait_count;
    si.pWaitSemaphores = wait;
    si.pWaitDstStageMask = stages;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &f->cmd;
    si.signalSemaphoreCount = signal_count;
    si.pSignalSemaphores = signal;

    if (vkQueueSubmit(fg->queue, 1, &si, f->fence) != VK_SUCCESS) {
        FX_LOGE("vkQueueSubmit failed; dropping this frame");
        vkDeviceWaitIdle(fg->device);
        if (fg->rec.swapchain) fg->rec.broken = true; /* its acquired images were never presented */
        AImage_delete(image);
        return;
    }
    f->submitted = true;
    f->held = image;
    for (uint32_t k = 0; k < rec_want && k < FX_REC_PER_FRAME; k++) {
        if (rec_index[k] != UINT32_MAX) fg->rec.presented[rec_index[k]] = 1;
    }

    bool out_of_date = false;
    for (uint32_t g = 0; g < gen_count; g++) {
        VkPresentInfoKHR gpi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        gpi.waitSemaphoreCount = 1;
        gpi.pWaitSemaphores = &fg->swapchain_ready[gen_index[g]];
        gpi.swapchainCount = 1;
        gpi.pSwapchains = &fg->swapchain;
        gpi.pImageIndices = &gen_index[g];
        VkResult pr = vkQueuePresentKHR(fg->queue, &gpi);
        if (pr == VK_ERROR_OUT_OF_DATE_KHR) out_of_date = true;
    }

    VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &fg->swapchain_ready[image_index];
    pi.swapchainCount = 1;
    pi.pSwapchains = &fg->swapchain;
    pi.pImageIndices = &image_index;
    VkResult pr = vkQueuePresentKHR(fg->queue, &pi);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR) out_of_date = true;

    /* same order as on screen: generated frames first, the real frame last */
    for (uint32_t k = 0; k < rec_want && k < FX_REC_PER_FRAME; k++) {
        if (rec_index[k] == UINT32_MAX) continue;
        VkPresentInfoKHR rpi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        rpi.waitSemaphoreCount = 1;
        rpi.pWaitSemaphores = &fg->rec.ready[rec_index[k]];
        rpi.swapchainCount = 1;
        rpi.pSwapchains = &fg->rec.swapchain;
        rpi.pImageIndices = &rec_index[k];
        VkResult rr = vkQueuePresentKHR(fg->queue, &rpi);
        if (rr == VK_SUCCESS || rr == VK_SUBOPTIMAL_KHR) {
            fg->rec.frames++;
        } else {
            FX_LOGW("record: present -> %d, stopping the video feed", (int)rr);
            fg->rec.broken = true;
        }
    }

    {
        struct timespec ft_now;
        clock_gettime(CLOCK_MONOTONIC, &ft_now);
        const uint64_t ns = (uint64_t)ft_now.tv_sec * 1000000000ull + (uint64_t)ft_now.tv_nsec;
        if (fg->ft_last_ns != 0 && ns > fg->ft_last_ns) {
            uint64_t us = (ns - fg->ft_last_ns) / 1000ull;
            if (us > 0x7FFFFFFFull) us = 0x7FFFFFFFull;
            const uint64_t n = fg->ft_count;
            __atomic_store_n(&fg->ft_ring[n % FX_FT_RING], (uint32_t)us, __ATOMIC_RELAXED);
            __atomic_store_n(&fg->ft_count, n + 1, __ATOMIC_RELEASE);
        }
        fg->ft_last_ns = ns;
    }
    fg->real_frames++;
    fg->generated_frames += gen_count;
    if ((fg->real_frames % FX_TELEMETRY_FRAMES) == 0) {
        const uint64_t d_real = fg->real_frames - fg->log_real;
        const uint64_t d_gen = fg->generated_frames - fg->log_generated;
        fg->log_real = fg->real_frames;
        fg->log_generated = fg->generated_frames;
        FX_LOGI("fluxa real=%llu made=%llu ratio=%.2f planned=%u got=%u images=%u div=%u misses=%llu",
                (unsigned long long)fg->real_frames, (unsigned long long)fg->generated_frames,
                d_real ? (double)(d_real + d_gen) / (double)d_real : 0.0, planned, gen_count,
                fg->swapchain_image_count, fg->source_divisor,
                (unsigned long long)fg->acquire_misses);
    }

    fg->frame_index = (fg->frame_index + 1) % FX_FRAMES_IN_FLIGHT;

    if (out_of_date) fx_reset_swapchain(fg);
}

static void fx_reset_swapchain(FxPresenter* fg) {
    vkDeviceWaitIdle(fg->device);
    const VkExtent2D previous = fg->extent;
    fx_destroy_swapchain(fg);
    if (!fx_create_swapchain(fg)) return;
    if (previous.width != fg->extent.width || previous.height != fg->extent.height) {
        fx_destroy_targets(fg);
    }
}

static bool fx_prepare_chain(FxPresenter* fg) {
    if (!fg->dis) return false;
    const VkrDisContentRect content = {0, 0, fg->extent.width, fg->extent.height};
    if (!vkr_dis_needs_rebuild(fg->dis, fg->extent.width, fg->extent.height, fg->target_format,
                               content)) {
        return true;
    }
    vkDeviceWaitIdle(fg->device);
    vkr_dis_forget_targets(fg->dis);
    return vkr_dis_prepare(fg->dis, fg->extent.width, fg->extent.height, fg->target_format,
                           content);
}

static float fx_elapsed_since(struct timespec* mark) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (mark->tv_sec == 0 && mark->tv_nsec == 0) {
        *mark = now;
        return 0.0f;
    }
    return (float)(now.tv_sec - mark->tv_sec) + (float)(now.tv_nsec - mark->tv_nsec) / 1.0e9f;
}

static void fx_track_arrivals(FxPresenter* fg) {
    fg->raw_frames++;
    const float elapsed = fx_elapsed_since(&fg->raw_mark);
    if (elapsed < FX_ARRIVAL_WINDOW) return;
    clock_gettime(CLOCK_MONOTONIC, &fg->raw_mark);
    const float rate = (float)fg->raw_frames / elapsed;
    fg->raw_frames = 0;
    fg->raw_rate = fg->raw_rate > 0.0f ? fg->raw_rate + (rate - fg->raw_rate) * 0.5f : rate;

    uint32_t divisor = 1;
    const float guest = fg->source_rate;
    if (guest > 1.0f && fg->raw_rate > guest) {
        const long ratio = lroundf(fg->raw_rate / guest);
        if (ratio > 1 && ratio <= (long)FX_MAX_GENERATIONS + 1) {
            const float folded = fg->raw_rate / (float)ratio;
            if (fabsf(folded - guest) <= guest * FX_ARRIVAL_TOLERANCE) divisor = (uint32_t)ratio;
        }
    }
    if (divisor != fg->source_divisor) {
        fg->source_divisor = divisor;
        fg->divisor_phase = 0;
        FX_LOGI("producer repeats every frame %u times at %.1f fps for a %.1f fps game",
                divisor, fg->raw_rate, guest);
    }
}

static void* fx_thread(void* arg) {
    FxPresenter* fg = (FxPresenter*)arg;

    while (true) {
        pthread_mutex_lock(&fg->lock);
        while (fg->running && !fg->image_pending) {
            pthread_cond_wait(&fg->signal, &fg->lock);
        }
        bool running = fg->running;
        fg->image_pending = false;
        pthread_mutex_unlock(&fg->lock);
        if (!running) break;

        if (!vkd_bind(fg->vulkan_handle, fg->instance)) break;
        fx_apply_config(fg);

        while (true) {
            fx_apply_config(fg);

            AImage* image = NULL;
            int fence_fd = -1;
            media_status_t status =
                AImageReader_acquireNextImageAsync(fg->reader, &image, &fence_fd);
            if (status != AMEDIA_OK || !image) break;

            fx_track_arrivals(fg);
            if (fg->source_divisor > 1) {
                fg->divisor_phase = (fg->divisor_phase + 1) % fg->source_divisor;
                if (fg->divisor_phase != 0) {
                    if (fence_fd >= 0) close(fence_fd);
                    AImage_delete(image);
                    continue;
                }
            }

            AHardwareBuffer* buffer = NULL;
            if (AImage_getHardwareBuffer(image, &buffer) != AMEDIA_OK || !buffer) {
                if (fence_fd >= 0) close(fence_fd);
                AImage_delete(image);
                continue;
            }

            fx_await_fence_fd(fence_fd);

            FxFrame* f = &fg->frames[fg->frame_index];
            if (f->submitted) {
                vkWaitForFences(fg->device, 1, &f->fence, VK_TRUE, UINT64_MAX);
            }
            if (f->held) {
                AImage_delete(f->held);
                f->held = NULL;
            }

            if (!fg->swapchain && !fx_create_swapchain(fg)) {
                AImage_delete(image);
                break;
            }
            if (!fg->targets_built && !fx_create_targets(fg)) {
                AImage_delete(image);
                break;
            }
            if (!fx_prepare_chain(fg)) {
                AImage_delete(image);
                break;
            }

            FxImport* source = fx_import_buffer(fg, buffer);
            if (!source) {
                AImage_delete(image);
                continue;
            }

            fg->source_frames++;
            fx_present_frame(fg, source, image);
        }
    }

    return NULL;
}

FxPresenter* fx_create(JNIEnv* env, jobject context, const char* driver_name,
                       ANativeWindow* output, uint32_t width, uint32_t height,
                       uint32_t out_width, uint32_t out_height, uint32_t multiplier,
                       float refresh_rate, float source_rate,
                       uint32_t dis_min_side, uint32_t present_mode, uint32_t upscale_mode,
                       uint32_t upscale_sharp, uint64_t upscale_look) {
    if (!output || width == 0 || height == 0) return NULL;

    FxPresenter* fg = calloc(1, sizeof(FxPresenter));
    if (!fg) return NULL;

    pthread_mutex_init(&fg->lock, NULL);
    pthread_cond_init(&fg->signal, NULL);
    pthread_mutex_init(&fg->rec_lock, NULL);
    {
        pthread_condattr_t attr;
        pthread_condattr_init(&attr);
        pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
        pthread_cond_init(&fg->rec_cond, &attr);
        pthread_condattr_destroy(&attr);
    }
    fg->output = output;
    fg->extent.width = width;
    fg->extent.height = height;
    fg->upscale = out_width > 0 && out_height > 0 && (out_width != width || out_height != height);
    fg->up_mode = upscale_mode <= FX_UP_MAX_MODE ? upscale_mode : FX_UP_LINEAR;
    fg->up_sharp = FX_UP_SHARP_VALUE(upscale_sharp);
    fg->up_sharp_method = FX_UP_SHARP_METHOD(upscale_sharp);
    fg->up_enh = upscale_sharp;
    fg->up_look = upscale_look;
    fg->out_extent.width = fg->upscale ? out_width : width;
    fg->out_extent.height = fg->upscale ? out_height : height;
    fg->multiplier = multiplier;
    fg->dis_min_side = dis_min_side ? dis_min_side : FX_DIS_MIN_SIDE_DEFAULT;
    fg->present_mode = present_mode == FX_PRESENT_MAILBOX ? FX_PRESENT_MAILBOX : FX_PRESENT_FIFO;
    fg->refresh_rate = refresh_rate;
    fg->source_rate = source_rate;
    fg->source_divisor = 1;
    ANativeWindow_acquire(output);

    VkAndroidSurfaceCreateInfoKHR asi;
    memset(&asi, 0, sizeof(asi));
    asi.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    asi.window = output;

    fg->vulkan_handle = fluxa_open_vulkan(env, context, driver_name);
    if (!fg->vulkan_handle) {
        FX_LOGE("no Vulkan driver for frame generation");
        goto fail;
    }
    if (!vkd_init(fg->vulkan_handle)) goto fail;
    if (!fx_create_instance(fg)) goto fail;

    if (vkCreateAndroidSurfaceKHR(fg->instance, &asi, NULL, &fg->surface) != VK_SUCCESS) {
        FX_LOGE("vkCreateAndroidSurfaceKHR failed");
        goto fail;
    }

    if (!fx_pick_physical_device(fg)) {
        FX_LOGE("no device supports frame generation");
        goto fail;
    }
    if (!fx_create_device(fg)) goto fail;
    if (!fx_create_frames(fg)) goto fail;
    if (!fx_create_swapchain(fg)) goto fail;
    if (!fx_create_targets(fg)) goto fail;
    /* always built: the denoise / AA / deband / grade passes also run without upscaling */
    vkGetPhysicalDeviceMemoryProperties(fg->physical_device, &fg->memory_properties);
    if (!fx_up_create_pipes(&fg->up, fg->device, &fg->memory_properties)) {
        fg->up_failed = true;
        FX_LOGW("upscaler pipelines unavailable; upscaling uses a plain blit");
    }

    if (!fx_dis_acquire(fg)) goto fail;
    fx_dis_configure(fg, multiplier, fg->dis_min_side);

    const uint64_t reader_usage =
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AImageReader_newWithUsage((int32_t)fg->extent.width, (int32_t)fg->extent.height,
                                  AIMAGE_FORMAT_PRIVATE, reader_usage, FX_READER_IMAGES,
                                  &fg->reader) != AMEDIA_OK) {
        FX_LOGE("AImageReader_newWithUsage failed");
        goto fail;
    }
    if (AImageReader_getWindow(fg->reader, &fg->producer) != AMEDIA_OK || !fg->producer) {
        FX_LOGE("AImageReader_getWindow failed");
        goto fail;
    }
    ANativeWindow_acquire(fg->producer);

    fg->listener.context = fg;
    fg->listener.onImageAvailable = fx_image_available;
    AImageReader_setImageListener(fg->reader, &fg->listener);

    fg->running = true;
    if (pthread_create(&fg->thread, NULL, fx_thread, fg) != 0) {
        FX_LOGE("presenter thread could not start");
        fg->running = false;
        goto fail;
    }

    FX_LOGI("frame generation presenter ready %ux%u -> %ux%u multiplier=%u dis_min_side=%u "
            "upscale=%u sharp=%u method=%u cubic=%d",
            fg->extent.width, fg->extent.height, fg->out_extent.width, fg->out_extent.height,
            multiplier, fg->dis_min_side, fg->up_mode, fg->up_sharp, fg->up_sharp_method,
            (int)fg->cubic_ok);
    return fg;

fail:
    fx_destroy(fg);
    return NULL;
}

ANativeWindow* fx_producer_window(FxPresenter* fg) {
    return fg ? fg->producer : NULL;
}

void fx_configure(FxPresenter* fg, uint32_t multiplier, float refresh_rate, float source_rate,
                  uint32_t dis_min_side, uint32_t present_mode, uint32_t upscale_mode,
                  uint32_t upscale_sharp, uint64_t upscale_look) {
    if (!fg) return;
    __atomic_store_n(&fg->up_mode, upscale_mode <= FX_UP_MAX_MODE ? upscale_mode : FX_UP_LINEAR,
                     __ATOMIC_RELAXED);
    __atomic_store_n(&fg->up_sharp, FX_UP_SHARP_VALUE(upscale_sharp), __ATOMIC_RELAXED);
    __atomic_store_n(&fg->up_sharp_method, FX_UP_SHARP_METHOD(upscale_sharp), __ATOMIC_RELAXED);
    __atomic_store_n(&fg->up_enh, upscale_sharp, __ATOMIC_RELAXED);
    __atomic_store_n(&fg->up_look, upscale_look, __ATOMIC_RELAXED);
    pthread_mutex_lock(&fg->lock);
    fg->multiplier = multiplier;
    fg->dis_min_side = dis_min_side ? dis_min_side : FX_DIS_MIN_SIDE_DEFAULT;
    fg->present_mode = present_mode == FX_PRESENT_MAILBOX ? FX_PRESENT_MAILBOX : FX_PRESENT_FIFO;
    fg->refresh_rate = refresh_rate;
    fg->source_rate = source_rate;
    fg->config_dirty = true;
    pthread_mutex_unlock(&fg->lock);
}

void fx_set_gen_mode(FxPresenter* fg, uint32_t mode) {
    if (!fg) return;
    if (mode > VKR_DIS_MODE_ULTRA) mode = VKR_DIS_MODE_ULTRA;
    __atomic_store_n(&fg->gen_mode, mode, __ATOMIC_RELAXED);
    pthread_mutex_lock(&fg->lock);
    fg->config_dirty = true;
    pthread_mutex_unlock(&fg->lock);
}

void fx_set_target_fps(FxPresenter* fg, uint32_t fps) {
    if (!fg) return;
    if (fps > 480u) fps = 480u;
    __atomic_store_n(&fg->target_fps, fps, __ATOMIC_RELAXED);
    pthread_mutex_lock(&fg->lock);
    fg->config_dirty = true;
    pthread_mutex_unlock(&fg->lock);
}

uint32_t fx_capabilities(const FxPresenter* fg) {
    return fg && fg->cubic_ok ? FX_CAP_CUBIC : 0u;
}

void fx_stats(FxPresenter* fg, uint64_t* real_frames, uint64_t* generated_frames) {
    if (!fg) return;
    if (real_frames) *real_frames = __atomic_load_n(&fg->real_frames, __ATOMIC_RELAXED);
    if (generated_frames) {
        *generated_frames = __atomic_load_n(&fg->generated_frames, __ATOMIC_RELAXED);
    }
}

/* Copies the newest up-to `max` real-frame intervals (us), oldest first; returns how many. */
uint32_t fx_frametimes(FxPresenter* fg, uint32_t* out, uint32_t max) {
    if (!fg || !out || max == 0) return 0;
    const uint64_t n = __atomic_load_n(&fg->ft_count, __ATOMIC_ACQUIRE);
    uint32_t k = n < FX_FT_RING ? (uint32_t)n : FX_FT_RING;
    if (k > max) k = max;
    for (uint32_t i = 0; i < k; i++) {
        const uint64_t idx = n - k + i;
        out[i] = __atomic_load_n(&fg->ft_ring[idx % FX_FT_RING], __ATOMIC_RELAXED);
    }
    return k;
}

void fx_destroy(FxPresenter* fg) {
    if (!fg) return;

    if (fg->running) {
        pthread_mutex_lock(&fg->lock);
        fg->running = false;
        pthread_cond_broadcast(&fg->signal);
        pthread_mutex_unlock(&fg->lock);
        pthread_join(fg->thread, NULL);
    }

    if (fg->reader) AImageReader_setImageListener(fg->reader, NULL);

    if (fg->device) {
        vkd_bind(fg->vulkan_handle, fg->instance);
        vkDeviceWaitIdle(fg->device);
    }

    for (uint32_t i = 0; i < FX_FRAMES_IN_FLIGHT; i++) {
        FxFrame* f = &fg->frames[i];
        if (f->held) {
            AImage_delete(f->held);
            f->held = NULL;
        }
    }

    fx_dis_release(fg);
    if (fg->reader) {
        AImageReader_delete(fg->reader);
        fg->reader = NULL;
    }
    if (fg->producer) ANativeWindow_release(fg->producer);

    if (fg->rec_pending_window) {
        ANativeWindow_release(fg->rec_pending_window);
        fg->rec_pending_window = NULL;
    }
    if (fg->device) {
        fx_rec_destroy(fg); /* disconnects the encoder surface so a new presenter can reuse it */
        fx_release_imports(fg);
        fx_destroy_targets(fg);
        fx_up_destroy(&fg->up);
        fx_destroy_swapchain(fg);
        for (uint32_t i = 0; i < FX_FRAMES_IN_FLIGHT; i++) {
            FxFrame* f = &fg->frames[i];
            if (f->fence) vkDestroyFence(fg->device, f->fence, NULL);
            if (f->acquire) vkDestroySemaphore(fg->device, f->acquire, NULL);
            for (uint32_t g = 0; g < FX_MAX_GENERATIONS; g++) {
                if (f->acquire_gen[g]) vkDestroySemaphore(fg->device, f->acquire_gen[g], NULL);
            }
        }
        for (uint32_t i = 0; i < fg->retired_count; i++) {
            vkDestroySemaphore(fg->device, fg->retired[i], NULL);
        }
        fg->retired_count = 0;
        if (fg->flush_fence) vkDestroyFence(fg->device, fg->flush_fence, NULL);
        if (fg->command_pool) vkDestroyCommandPool(fg->device, fg->command_pool, NULL);
        vkDestroyDevice(fg->device, NULL);
    }
    if (fg->surface) vkDestroySurfaceKHR(fg->instance, fg->surface, NULL);
    if (fg->instance) vkDestroyInstance(fg->instance, NULL);
    if (fg->output) ANativeWindow_release(fg->output);

    pthread_mutex_destroy(&fg->lock);
    pthread_cond_destroy(&fg->signal);
    free(fg->hud_pixels);
    fg->hud_pixels = NULL;
    pthread_mutex_destroy(&fg->rec_lock);
    pthread_cond_destroy(&fg->rec_cond);
    free(fg);
}

/* ------------------------------------------------------------------ recording (JNI side) */

static void fx_wake_presenter(FxPresenter* fg) {
    pthread_mutex_lock(&fg->lock);
    fg->image_pending = true;
    pthread_cond_signal(&fg->signal);
    pthread_mutex_unlock(&fg->lock);
}

/* Waits (bounded) until the presenter thread has handled request `seq`. */
static bool fx_rec_wait(FxPresenter* fg, uint64_t seq) {
    struct timespec deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec += FX_REC_WAIT_MS / 1000;
    deadline.tv_nsec += (long)(FX_REC_WAIT_MS % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec += 1;
        deadline.tv_nsec -= 1000000000L;
    }
    pthread_mutex_lock(&fg->rec_lock);
    while (fg->rec_done_seq < seq) {
        if (pthread_cond_timedwait(&fg->rec_cond, &fg->rec_lock, &deadline) == ETIMEDOUT) break;
    }
    const bool live = fg->rec_live;
    pthread_mutex_unlock(&fg->rec_lock);
    return live;
}

bool fx_record_start(FxPresenter* fg, ANativeWindow* window, uint32_t width, uint32_t height) {
    if (!fg || !window || width == 0 || height == 0) return false;
    ANativeWindow_acquire(window);

    pthread_mutex_lock(&fg->rec_lock);
    if (fg->rec_pending_window) ANativeWindow_release(fg->rec_pending_window);
    fg->rec_pending_window = window;
    fg->rec_pending_w = width;
    fg->rec_pending_h = height;
    fg->rec_start_req = true;
    fg->rec_stop_req = false;
    const uint64_t seq = ++fg->rec_req_seq;
    pthread_mutex_unlock(&fg->rec_lock);

    fx_wake_presenter(fg);
    return fx_rec_wait(fg, seq);
}

void fx_record_stop(FxPresenter* fg) {
    if (!fg) return;
    pthread_mutex_lock(&fg->rec_lock);
    if (fg->rec_pending_window) {
        ANativeWindow_release(fg->rec_pending_window);
        fg->rec_pending_window = NULL;
    }
    fg->rec_start_req = false;
    fg->rec_stop_req = true;
    const uint64_t seq = ++fg->rec_req_seq;
    pthread_mutex_unlock(&fg->rec_lock);

    fx_wake_presenter(fg);
    fx_rec_wait(fg, seq);
}

void fx_record_hud(FxPresenter* fg, const uint8_t* rgba, uint32_t width, uint32_t height,
                   int32_t margin_x, int32_t margin_y) {
    if (!fg) return;
    pthread_mutex_lock(&fg->rec_lock);
    if (!rgba || !width || !height || width > FX_HUD_MAX_W || height > FX_HUD_MAX_H) {
        fg->hud_w = 0;
        fg->hud_h = 0;
    } else {
        if (!fg->hud_pixels) fg->hud_pixels = malloc((size_t)FX_HUD_MAX_W * FX_HUD_MAX_H * 4u);
        if (fg->hud_pixels) {
            memcpy(fg->hud_pixels, rgba, (size_t)width * height * 4u);
            fg->hud_w = width;
            fg->hud_h = height;
            fg->hud_mx = margin_x;
            fg->hud_my = margin_y;
        } else {
            fg->hud_w = 0;
            fg->hud_h = 0;
        }
    }
    fg->hud_version++;
    pthread_mutex_unlock(&fg->rec_lock);
}

void fx_record_stats(FxPresenter* fg, uint64_t* frames, uint64_t* dropped) {
    if (!fg) return;
    if (frames) *frames = __atomic_load_n(&fg->rec.frames, __ATOMIC_RELAXED);
    if (dropped) *dropped = __atomic_load_n(&fg->rec.dropped, __ATOMIC_RELAXED);
}
