#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "fx_present.h"

#define FX_FN(name) Java_app_fluxa_FluxaNative_##name

static char* fx_copy_utf(JNIEnv* env, jstring value) {
    if (!value) return NULL;
    const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!chars) return NULL;
    char* copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

JNIEXPORT jlong JNICALL FX_FN(nativeCreate)(JNIEnv* env, jclass clazz, jobject context,
                                            jobject output, jint width, jint height,
                                            jint outWidth, jint outHeight,
                                            jstring driverName, jint multiplier,
                                            jint flowSide, jfloat refreshRate,
                                            jfloat sourceRate, jint presentMode,
                                            jint upscaleMode, jint upscaleSharp,
                                            jlong upscaleLook) {
    (void)clazz;
    if (!output || width <= 0 || height <= 0) return 0;

    ANativeWindow* window = ANativeWindow_fromSurface(env, output);
    if (!window) return 0;

    char* driver = fx_copy_utf(env, driverName);
    const uint32_t dis_min_side = flowSide > 0 ? (uint32_t)flowSide : FX_DIS_MIN_SIDE_DEFAULT;

    FxPresenter* fg = fx_create(env, context, driver, window, (uint32_t)width, (uint32_t)height,
                                outWidth > 0 ? (uint32_t)outWidth : 0u,
                                outHeight > 0 ? (uint32_t)outHeight : 0u, (uint32_t)multiplier, refreshRate, sourceRate, dis_min_side,
                                (uint32_t)presentMode, upscaleMode < 0 ? 1u : (uint32_t)upscaleMode,
                                upscaleSharp < 0 ? 0u : (uint32_t)upscaleSharp,
                                upscaleLook < 0 ? 0ull : (uint64_t)upscaleLook);

    ANativeWindow_release(window);
    free(driver);
    return (jlong)(intptr_t)fg;
}

JNIEXPORT jobject JNICALL FX_FN(nativeProducerSurface)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)clazz;
    FxPresenter* fg = (FxPresenter*)(intptr_t)handle;
    ANativeWindow* window = fx_producer_window(fg);
    if (!window) return NULL;
    return ANativeWindow_toSurface(env, window);
}

JNIEXPORT void JNICALL FX_FN(nativeConfigure)(JNIEnv* env, jclass clazz, jlong handle,
                                              jint multiplier, jint flowSide,
                                              jfloat refreshRate, jfloat sourceRate,
                                              jint presentMode, jint upscaleMode,
                                              jint upscaleSharp, jlong upscaleLook) {
    (void)env;
    (void)clazz;
    FxPresenter* fg = (FxPresenter*)(intptr_t)handle;
    const uint32_t dis_min_side = flowSide > 0 ? (uint32_t)flowSide : FX_DIS_MIN_SIDE_DEFAULT;
    fx_configure(fg, (uint32_t)multiplier, refreshRate, sourceRate, dis_min_side,
                 (uint32_t)presentMode, upscaleMode < 0 ? 1u : (uint32_t)upscaleMode,
                 upscaleSharp < 0 ? 0u : (uint32_t)upscaleSharp,
                 upscaleLook < 0 ? 0ull : (uint64_t)upscaleLook);
}

JNIEXPORT void JNICALL FX_FN(nativeSetGenMode)(JNIEnv* env, jclass clazz, jlong handle,
                                               jint mode) {
    (void)env;
    (void)clazz;
    fx_set_gen_mode((FxPresenter*)(intptr_t)handle, mode < 0 ? 0u : (uint32_t)mode);
}

JNIEXPORT void JNICALL FX_FN(nativeSetTargetFps)(JNIEnv* env, jclass clazz, jlong handle,
                                                 jint fps) {
    (void)env;
    (void)clazz;
    fx_set_target_fps((FxPresenter*)(intptr_t)handle, fps < 0 ? 0u : (uint32_t)fps);
}

JNIEXPORT jint JNICALL FX_FN(nativeCapabilities)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    return (jint)fx_capabilities((FxPresenter*)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL FX_FN(nativeRealFrames)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    uint64_t real = 0;
    fx_stats((FxPresenter*)(intptr_t)handle, &real, NULL);
    return (jlong)real;
}

JNIEXPORT jlong JNICALL FX_FN(nativeGeneratedFrames)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    uint64_t generated = 0;
    fx_stats((FxPresenter*)(intptr_t)handle, NULL, &generated);
    return (jlong)generated;
}

JNIEXPORT jint JNICALL FX_FN(nativeFrameTimes)(JNIEnv* env, jclass clazz, jlong handle,
                                               jintArray out) {
    (void)clazz;
    if (!out) return 0;
    jsize cap = (*env)->GetArrayLength(env, out);
    if (cap <= 0) return 0;
    if (cap > 256) cap = 256;
    uint32_t tmp[256];
    const uint32_t got = fx_frametimes((FxPresenter*)(intptr_t)handle, tmp, (uint32_t)cap);
    if (got == 0) return 0;
    jint vals[256];
    for (uint32_t i = 0; i < got; i++) vals[i] = (jint)(tmp[i] > 0x7FFFFFFFu ? 0x7FFFFFFF : tmp[i]);
    (*env)->SetIntArrayRegion(env, out, 0, (jsize)got, vals);
    return (jint)got;
}

JNIEXPORT jboolean JNICALL FX_FN(nativeRecordStart)(JNIEnv* env, jclass clazz, jlong handle,
                                                    jobject surface, jint width, jint height) {
    (void)clazz;
    FxPresenter* fg = (FxPresenter*)(intptr_t)handle;
    if (!fg || !surface || width <= 0 || height <= 0) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    const bool live = fx_record_start(fg, window, (uint32_t)width, (uint32_t)height);
    ANativeWindow_release(window); /* fx_record_start holds its own reference */
    return live ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FX_FN(nativeRecordStop)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    fx_record_stop((FxPresenter*)(intptr_t)handle);
}

JNIEXPORT void JNICALL FX_FN(nativeRecordHud)(JNIEnv* env, jclass clazz, jlong handle,
                                              jbyteArray pixels, jint width, jint height,
                                              jint marginX, jint marginY) {
    (void)clazz;
    FxPresenter* fg = (FxPresenter*)(intptr_t)handle;
    if (!fg) return;
    if (!pixels || width <= 0 || height <= 0) {
        fx_record_hud(fg, NULL, 0, 0, 0, 0);
        return;
    }
    const jsize len = (*env)->GetArrayLength(env, pixels);
    if ((int64_t)len < (int64_t)width * height * 4) {
        fx_record_hud(fg, NULL, 0, 0, 0, 0);
        return;
    }
    jbyte* data = (*env)->GetByteArrayElements(env, pixels, NULL);
    if (!data) return;
    fx_record_hud(fg, (const uint8_t*)data, (uint32_t)width, (uint32_t)height, marginX, marginY);
    (*env)->ReleaseByteArrayElements(env, pixels, data, JNI_ABORT);
}

JNIEXPORT jlong JNICALL FX_FN(nativeRecordedFrames)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    uint64_t frames = 0;
    fx_record_stats((FxPresenter*)(intptr_t)handle, &frames, NULL);
    return (jlong)frames;
}

JNIEXPORT void JNICALL FX_FN(nativeDestroy)(JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    fx_destroy((FxPresenter*)(intptr_t)handle);
}
