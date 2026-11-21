#pragma once

#include <android/native_window.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FxPresenter FxPresenter;

#define FX_DIS_MIN_SIDE_DEFAULT 180u

/* Swapchain present mode: 0 = FIFO (vsync, always available), 1 = MAILBOX (falls back to FIFO). */
#define FX_PRESENT_FIFO 0u
#define FX_PRESENT_MAILBOX 1u

FxPresenter* fx_create(JNIEnv* env, jobject context, const char* driver_name,
                       ANativeWindow* output, uint32_t width, uint32_t height,
                       uint32_t out_width, uint32_t out_height, uint32_t multiplier, float refresh_rate, float source_rate,
                       uint32_t dis_min_side, uint32_t present_mode,
                       uint32_t upscale_mode /* FX_UP_* */,
                       uint32_t upscale_sharp /* strength 0..100 | FX_SHARP_* << 8, see fx_upscale.h */,
                       uint64_t upscale_look /* colour grade, FX_LOOK_* in fx_upscale.h */);

ANativeWindow* fx_producer_window(FxPresenter* fg);

void fx_configure(FxPresenter* fg, uint32_t multiplier, float refresh_rate, float source_rate,
                  uint32_t dis_min_side, uint32_t present_mode, uint32_t upscale_mode,
                  uint32_t upscale_sharp, uint64_t upscale_look);

/* GPU cost of the frame generation, live: 0 = full, 1 = balanced, 2 = lite, 3 = ultra-lite
 * (see VKR_DIS_MODE_* in vkr_dis.h). */
void fx_set_gen_mode(FxPresenter* fg, uint32_t mode);

/* Adaptive output rate, live: 0 = off (fixed xN from the multiplier); >0 = generate only as many
 * frames as needed to reach this fps from the measured source rate. */
void fx_set_target_fps(FxPresenter* fg, uint32_t fps);

/* Bit mask of optional features this device/format supports. */
#define FX_CAP_CUBIC 1u
uint32_t fx_capabilities(const FxPresenter* fg);

void fx_stats(FxPresenter* fg, uint64_t* real_frames, uint64_t* generated_frames);

/* Newest up-to `max` intervals between consecutive real (source) frames, in microseconds, oldest
 * first. Returns the count (<= 256). Safe to call from any thread. */
uint32_t fx_frametimes(FxPresenter* fg, uint32_t* out, uint32_t max);

/* Screen recording: also blit every shown frame (generated + real) into `window` (a MediaCodec
 * input surface) at width x height, aspect-fit with black bars. Blocks briefly until the presenter
 * thread has attached; returns true when the video feed is live. */
bool fx_record_start(FxPresenter* fg, ANativeWindow* window, uint32_t width, uint32_t height);
void fx_record_stop(FxPresenter* fg);
/* HUD burned into the recording: `rgba` is a width x height opaque RGBA8 bitmap (NULL = hide),
 * placed margin_x/margin_y pixels from the top-left corner of the shown frame inside the video.
 * Safe to call from any thread, at any time. */
void fx_record_hud(FxPresenter* fg, const uint8_t* rgba, uint32_t width, uint32_t height,
                   int32_t margin_x, int32_t margin_y);
void fx_record_stats(FxPresenter* fg, uint64_t* frames, uint64_t* dropped);

void fx_destroy(FxPresenter* fg);

#ifdef __cplusplus
}
#endif
