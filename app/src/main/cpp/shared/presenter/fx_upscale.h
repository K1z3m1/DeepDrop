// Output upscaler / enhancer for the presenter: compute passes (SGSR-style edge upscale, FSR 1 EASU, Catmull-Rom,
// Lanczos-2/3, Spline36, EWA Lanczos (jinc), Mitchell, anime line push, NIS-style edge-directed upscale) plus an optional sharpen
// pass (RCAS, CAS, adaptive, luma or clarity) that also
// works after a plain blit (nearest / linear / cubic).
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "../vk_dispatch.h"

#define FX_UP_NEAREST 0u
#define FX_UP_LINEAR 1u
#define FX_UP_CUBIC 2u
#define FX_UP_SGSR 3u
#define FX_UP_FSR1 4u
#define FX_UP_LANCZOS 5u /* Lanczos-2, 4x4 taps, anti-ringing */
#define FX_UP_CATROM 6u  /* Catmull-Rom bicubic, 9 bilinear fetches */
#define FX_UP_NIS 7u     /* edge-directed, NVIDIA-Image-Scaling style */
#define FX_UP_ANIME 8u    /* line push for flat-shaded / anime content (Anime4K-style, no CNN) */
#define FX_UP_MITCHELL 9u /* Mitchell-Netravali bicubic (B = C = 1/3), 4x4 taps */
#define FX_UP_LANCZOS3 10u /* Lanczos-3, 6x6 taps, anti-ringing */
#define FX_UP_SPLINE36 11u /* Spline36, 6x6 taps, smooth roll-off, little ringing */
#define FX_UP_EWA 12u      /* EWA Lanczos (jinc), radial weights, 6x6 taps, no axis bias */
#define FX_UP_MAX_MODE FX_UP_EWA

/* Sharpen pass that follows the upscale when the strength is > 0. */
#define FX_SHARP_RCAS 0u
#define FX_SHARP_CAS 1u
#define FX_SHARP_ADAPTIVE 2u /* edge-adaptive high-pass, gated on local contrast */
#define FX_SHARP_LUMA 3u     /* luma-only soft-clipped high-pass, no colour fringes */
#define FX_SHARP_CLARITY 4u  /* wide-radius local contrast, midtone-weighted */
#define FX_SHARP_MAX_METHOD FX_SHARP_CLARITY

/* Strength / enhancement values run 0..200 (%): 100 = the old maximum, 101..200 = overdrive. */
#define FX_UP_STRENGTH_MAX 200u

/* The JNI layer carries strength and method in one int: bits 0..7 strength (0..200), bits 8..11
 * method (FX_SHARP_*). Plain old values (< 256) therefore still mean "RCAS at that strength". */
#define FX_UP_SHARP_VALUE(packed) \
    (((packed) & 0xFFu) > FX_UP_STRENGTH_MAX ? FX_UP_STRENGTH_MAX : ((packed) & 0xFFu))
#define FX_UP_SHARP_METHOD(packed) \
    ((((packed) >> 8) & 0xFu) > FX_SHARP_MAX_METHOD ? FX_SHARP_RCAS : (((packed) >> 8) & 0xFu))

/* Extra image enhancement carried in the same packed int, above the sharpen bits:
 *   bits 12..19 deband strength (0..200)      -> final pass at output size
 *   bits 20..27 denoise / deblock (0..200)    -> pre pass at source size, before the upscale
 *   bits 28..29 anti-aliasing (FX_AA_*)       -> pre pass at source size, after the denoise
 *   bit  30     dither (triangular noise before the 8-bit store, part of the final pass)
 * Old values (< 4096) therefore keep meaning "no extra enhancement". The layout moved up by one
 * bit per field when the strengths grew from 7 to 8 bits; Kotlin packs it the same way. */
#define FX_AA_OFF 0u
#define FX_AA_FXAA 1u
#define FX_AA_SMAA 2u /* "SMAA-lite": single-pass morphological AA, see shaders/up_smaa.comp */
#define FX_AA_MAX FX_AA_SMAA

#define FX_UP_DEBAND(packed) \
    ((((packed) >> 12) & 0xFFu) > FX_UP_STRENGTH_MAX ? FX_UP_STRENGTH_MAX : (((packed) >> 12) & 0xFFu))
#define FX_UP_DENOISE(packed) \
    ((((packed) >> 20) & 0xFFu) > FX_UP_STRENGTH_MAX ? FX_UP_STRENGTH_MAX : (((packed) >> 20) & 0xFFu))
#define FX_UP_AA(packed) ((((packed) >> 28) & 0x3u) > FX_AA_MAX ? FX_AA_OFF : (((packed) >> 28) & 0x3u))
#define FX_UP_DITHER(packed) (((packed) >> 30) & 0x1u)
/* any pass that needs the source-size scratch images */
#define FX_UP_WANT_PRE(packed) (FX_UP_DENOISE(packed) > 0u || FX_UP_AA(packed) != FX_AA_OFF)
/* any enhancement at all (the presenter then runs the chain even without upscaling) */
#define FX_UP_WANT_ENH(packed) \
    (FX_UP_DENOISE(packed) > 0u || FX_UP_AA(packed) != FX_AA_OFF || FX_UP_DEBAND(packed) > 0u \
     || FX_UP_DITHER(packed) != 0u)

/* Colour grade ("look"): its own 64-bit value next to the packed int above, part of
 * the same final pass as deband / dither. An all-zero value is the identity (no grading).
 *   bits  0..7   brightness  -100..100  (signed byte, two's complement)
 *   bits  8..15  contrast    -100..100
 *   bits 16..23  saturation  -100..100
 *   bits 24..31  vibrance    -100..100
 *   bits 32..39  temperature -100..100  (- cool, + warm)
 *   bits 40..47  tint        -100..100  (- green, + magenta)
 * Kotlin packs it the same way (DisplayTuner.packLook). */
#define FX_LOOK_COLOR_MASK 0x0000FFFFFFFFFFFFull

static inline int fx_look_signed(uint64_t look, unsigned shift) {
    int v = (int)((look >> shift) & 0xFFu);
    if (v > 127) v -= 256;
    return v < -100 ? -100 : (v > 100 ? 100 : v);
}
#define FX_LOOK_BRIGHT(look) fx_look_signed((look), 0)
#define FX_LOOK_CONTRAST(look) fx_look_signed((look), 8)
#define FX_LOOK_SAT(look) fx_look_signed((look), 16)
#define FX_LOOK_VIB(look) fx_look_signed((look), 24)
#define FX_LOOK_TEMP(look) fx_look_signed((look), 32)
#define FX_LOOK_TINT(look) fx_look_signed((look), 40)
/* any grade at all (the presenter then runs the final pass, also without upscaling) */
static inline bool fx_look_active(uint64_t look) {
    return (look & FX_LOOK_COLOR_MASK) != 0u;
}

#define FX_UP_SLOTS 2u    /* must equal FX_FRAMES_IN_FLIGHT */
#define FX_UP_MAX_SRC 16u /* must be >= FX_MAX_TARGETS */

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
} FxUpImage;

typedef struct FxUpscaler {
    VkDevice device;
    VkPhysicalDeviceMemoryProperties mem;

    /* created once per device */
    bool pipes_ready;
    VkSampler sampler;
    VkDescriptorSetLayout dsl;
    VkPipelineLayout layout;
    VkPipeline easu;
    VkPipeline sgsr;
    VkPipeline rcas;
    /* optional: a driver that rejects one of these only loses that mode (it falls back to a blit) */
    VkPipeline lanczos;
    VkPipeline catrom;
    VkPipeline nis;
    VkPipeline cas;
    VkPipeline anime;
    VkPipeline mitchell;
    VkPipeline lanczos3;
    VkPipeline adaptive;
    VkPipeline spline36;
    VkPipeline ewa;
    VkPipeline luma;    /* more sharpen passes: missing -> RCAS */
    VkPipeline clarity;
    VkPipeline denoise; /* pre passes: a missing one is simply skipped */
    VkPipeline fxaa;
    VkPipeline smaa;
    VkPipeline deband; /* final pass: deband + colour grade + dither */

    /* depend on the output size and on the target views; dropped whenever either changes */
    bool res_ready;
    uint32_t width;
    uint32_t height;
    uint32_t src_w; /* size of the source targets (the pre passes run at this size) */
    uint32_t src_h;
    bool has_pre;   /* the source-size scratch images + their descriptor sets exist */
    uint32_t frame_counter; /* dither seed */
    VkDescriptorPool pool;
    FxUpImage img[FX_UP_SLOTS][2]; /* [slot][0] = A (first pass), [slot][1] = B (sharpen) */
    FxUpImage pre[FX_UP_SLOTS][2]; /* [slot][0] = C0, [slot][1] = C1 (source size, pre passes) */
    VkDescriptorSet set_src[FX_UP_MAX_SRC][FX_UP_SLOTS];  /* source  -> A  */
    VkDescriptorSet set_rcas[FX_UP_SLOTS];                /* A       -> B  */
    VkDescriptorSet set_post[FX_UP_SLOTS];                /* B       -> A  */
    VkDescriptorSet set_pre0[FX_UP_MAX_SRC][FX_UP_SLOTS]; /* source  -> C0 */
    VkDescriptorSet set_pre1[FX_UP_SLOTS];                /* C0      -> C1 */
    VkDescriptorSet set_up0[FX_UP_SLOTS];                 /* C0      -> A  */
    VkDescriptorSet set_up1[FX_UP_SLOTS];                 /* C1      -> A  */
} FxUpscaler;

/* Pipelines, sampler, layouts. False leaves the upscaler unusable (caller falls back to a blit). */
bool fx_up_create_pipes(FxUpscaler* u, VkDevice device,
                        const VkPhysicalDeviceMemoryProperties* mem);

/* Output-size images + descriptor sets. `src_views` are the target views (GENERAL layout), all
 * src_w x src_h. `want_pre` also creates the source-size scratch images the denoise / AA passes
 * need (extra memory, so only when one of them is on). */
bool fx_up_create_resources(FxUpscaler* u, VkFormat format, uint32_t width, uint32_t height,
                            uint32_t src_w, uint32_t src_h, bool want_pre,
                            const VkImageView* src_views, uint32_t src_count);

/* Device must be idle. */
void fx_up_release_resources(FxUpscaler* u);
void fx_up_destroy(FxUpscaler* u);

/* True when `mode` is done by a compute pass (not by vkCmdBlitImage). */
static inline bool fx_up_is_shader_mode(uint32_t mode) {
    return mode == FX_UP_SGSR || mode == FX_UP_FSR1 || mode == FX_UP_LANCZOS
        || mode == FX_UP_CATROM || mode == FX_UP_NIS || mode == FX_UP_ANIME
        || mode == FX_UP_MITCHELL || mode == FX_UP_LANCZOS3 || mode == FX_UP_SPLINE36
        || mode == FX_UP_EWA;
}

/* Records one upscale of `src_image` (GENERAL layout, src_w x src_h) into the slot's images and
 * returns the image holding the result (out size, GENERAL layout, visible to transfer reads).
 * Chain: [denoise/deblock] -> [FXAA / SMAA-lite] -> upscale -> [sharpen] -> [deband + colour grade
 * + dither].
 * `mode` is FX_UP_*; `sharp` 0 = no sharpen pass, 1..200 = strength of the pass picked by
 * `sharp_method` (FX_SHARP_*); `enh` is the packed int with the FX_UP_DEBAND / DENOISE / AA /
 * DITHER fields (0 = none); `look` is the colour grade value (FX_LOOK_*, 0 = none).
 * `blit_filter` is used by the blit-based modes (and as the fallback when a mode's pipeline is
 * missing) before a sharpen pass follows. */
VkImage fx_up_record(FxUpscaler* u, VkCommandBuffer cmd, uint32_t mode, uint32_t sharp,
                     uint32_t sharp_method, uint32_t enh, uint64_t look, uint32_t src_index, uint32_t slot,
                     VkImage src_image, uint32_t src_w, uint32_t src_h, VkFilter blit_filter);
