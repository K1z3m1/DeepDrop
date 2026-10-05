package com.firstt175.novaframe.prefs

import android.content.Context
import android.content.SharedPreferences

data class NovaConfig(
    val dllUri: String?,
    val dllDisplayName: String?,
    val shadersReady: Boolean,
    val lsfgEnabled: Boolean,
    val multiplier: Int,
    val flowScale: Float,
    val performanceMode: Boolean,
    val hdrMode: Boolean,
    /**
     * When true, the render loop loads the precompiled SPIR-V FP16 shader
     * variants from Lossless.dll (resource IDs 304..351) instead of the
     * default FP32 SPIR-V set (resource IDs 353..400). Requires
     * `VK_KHR_shader_float16_int8` + `shaderFloat16` on the GPU. The UI
     * gates this via [NativeBridge.isFramegenFp16Supported] so unsupported
     * hardware never sees the toggle.
     */
    val framegenFp16: Boolean,
    /**
     * Fraction of the display's native resolution to capture and render at,
     * from 1.0 (100%, native) down to 0.0 (0%, capture disabled — floor-clamped
     * to [MIN_RENDER_RESOLUTION_SCALE] everywhere it drives an actual buffer
     * size so we never allocate a 0x0 surface). Lower values shrink the
     * VirtualDisplay/ImageReader capture size and the native context's
     * width/height, cutting GPU cost across capture, frame-gen and every
     * post-processing pass — at the cost of a softer final image.
     */
    val renderResolutionScale: Float,
    /** Maximum capture-to-generation latency allowed before a generated frame is discarded. 0 disables the deadline. */
    val generationDeadlineMs: Int,
    /** Automatically bypass frame generation when a REAL frame reaches this age. 0 disables. */
    val bypassGenDeadlineMs: Int,
    /** Time to remain in automatic BypassGen before generation may resume. */
    val bypassGenResumeDelayMs: Int,
    val legalAccepted: Boolean,
    /** Single on/off for the whole HUD: fps line, frame pacing graph, CPU/GPU/RAM line. */
    val hudEnabled: Boolean,
    /** Separate on/off for the HUD's frame pacing graph (independent of [hudEnabled]). */
    val frameGraphEnabled: Boolean,
    /** Whether HUD positioning is unlocked for touch dragging. Locked by default. */
    val hudPositionUnlocked: Boolean,
    val hudPositionX: Float,
    val hudPositionY: Float,
    val overlayMode: OverlayMode,
    /** Vulkan presentation mode used consistently by every render-loop swapchain. */
    val presentMode: PresentMode,
    /** Block the frame loop until an in-flight generation finishes instead of proceeding immediately. */
    val waitForBusyGeneration: Boolean,
    /** Admit a new generation even while a previous one is still busy, instead of skipping it. */
    val allowGenerationWhenBusy: Boolean,
    /** Keep every captured real frame queued instead of bounding the queue at maxQueuedRealFrames. */
    val losslessQueue: Boolean,
    val autoEnabledApps: Set<String>,
    val framegenBackend: FramegenBackend,
    val aiModelUri: String?,
    val aiModelDisplayName: String?,
    val aiModelReady: Boolean,
    val aiModelPrecision: String?,
    val aiModelGraphs: List<String>,
    val activeModelDir: String?,
    val activeModelName: String?,
    val activeModelEngine: Int?,
    /** SAF tree URI containing the user-managed NovaFrameModels folder. */
    val myModelsRootUri: String?,
    /** Which ncnn graph the NCNN_AI backend runs — see [AiEngine]. */
    val aiEngine: AiEngine,
    val rifeModel: RifeModel,
    val ifrnetModel: IfrnetModel,
    val imageEnhancementEnabled: Boolean,
    val imageEnhancementContrast: Float,
    val imageEnhancementSaturation: Float,
    val upscaleEnabled: Boolean,
    val upscaleFilter: UpscaleFilter,
) {
}

/**
 * Which frame-generation pipeline drives the render loop:
 *  - [LSFG_DLL]: the existing Vulkan pipeline built from Lossless.dll's SPIR-V shaders.
 *  - [NCNN_AI]: the ncnn-based interpolator (FlowNetLite + RefineNetLite) loaded from a bundled
 *    APK asset (see BundledRifeModel / BundledIfrnetModel) or a user-imported model folder
 *    (see MyModelFolderScanner).
 */
enum class FramegenBackend(val prefValue: String) {
    LSFG_DLL("lsfg_dll"),
    NCNN_AI("ncnn_ai");

    companion object {
        fun fromPref(value: String?): FramegenBackend =
            values().firstOrNull { it.prefValue == value } ?: LSFG_DLL
    }
}

/**
 * Which ncnn graph the [FramegenBackend.NCNN_AI] path runs. Both engines share
 * the exact same native call shape (load/unload/isLoaded/interpolate — see
 * IfrnetInterpolator.hpp's "intentionally interchangeable" note) so this only
 * changes which bundled asset gets extracted ([com.firstt175.novaframe.session.models.BundledRifeModel]
 * vs [com.firstt175.novaframe.session.models.BundledIfrnetModel]) and which `aiEngine` int
 * (0/1) is passed down to NativeBridge.initContext/initAiInterpolator.
 * KEEP prefValue/ordinal mapping in sync with lsfg_render_loop.hpp's
 * RenderLoopConfig::aiEngine doc comment.
 */
enum class AiEngine(val prefValue: String, val nativeValue: Int) {
    RIFE("rife", 0),
    IFRNET("ifrnet", 1);

    companion object {
        fun fromPref(value: String?): AiEngine =
            values().firstOrNull { it.prefValue == value } ?: RIFE
    }
}

/** Small bundled RIFE models. All entries are single-file flownet graphs and run on Vulkan GPU. */
enum class RifeModel(val prefValue: String, val assetDir: String, val label: String, val sizeMb: Float) {
    V4_17_LITE("v4.17-lite", "rife-v4.17_lite_ensembleFalse", "RIFE v4.17 Lite", 10.3f),
    V4_7("v4.7", "rife-v4.7_ensembleFalse", "RIFE v4.7", 10.6f);

    companion object {
        fun fromPref(value: String?): RifeModel =
            values().firstOrNull { it.prefValue == value } ?: V4_17_LITE
    }
}

/** Lightweight IFRNet models bundled from ifrnet-ncnn-vulkan. S variants are the smallest. */
enum class IfrnetModel(val prefValue: String, val assetDir: String, val label: String, val sizeMb: Float) {
    S_GOPRO("s-gopro", "IFRNet_S_GoPro", "IFRNet S GoPro", 5.9f),
    S_VIMEO90K("s-vimeo90k", "IFRNet_S_Vimeo90K", "IFRNet S Vimeo90K", 5.9f);

    companion object {
        fun fromPref(value: String?): IfrnetModel =
            values().firstOrNull { it.prefValue == value } ?: S_VIMEO90K
    }
}

/** Filter used when blitting the framegen output to the swapchain if their
 *  resolutions differ. NEAREST=0, BILINEAR=1 (native side ordinal). */
enum class UpscaleFilter(val prefValue: String) {
    NEAREST("nearest"),
    BILINEAR("bilinear");

    companion object {
        fun fromPref(value: String?): UpscaleFilter =
            entries.firstOrNull { it.prefValue == value } ?: BILINEAR
    }
}

/**
 * In-game settings overlay entry affordance.
 *
 *  - [ICON_BUTTON] (default): a small draggable circular icon floats on top of the
 *    target app. Tapping it opens the same settings panel from the right edge.
 *  - [DRAWER]: legacy edge-swipe handle. Reliable on most devices but a few OEM
 *    skins clip TYPE_APPLICATION_OVERLAY edges so the user can end up unable to
 *    drag the drawer back closed — see the warning shown in the UI.
 */
enum class OverlayMode(val prefValue: String) {
    ICON_BUTTON("icon_button"),
    DRAWER("drawer");

    companion object {
        fun fromPref(value: String?): OverlayMode =
            values().firstOrNull { it.prefValue == value } ?: ICON_BUTTON
    }
}

/** Swapchain presentation mode. vkValue is the raw VkPresentModeKHR value
 *  passed straight through to NativeBridge.setPresentMode() — keep it in
 *  sync with the native side's IMMEDIATE=0/MAILBOX=1/FIFO=2 mapping.
 *  MAILBOX (low-latency triple buffering) remains the default. FIFO is
 *  vsync'd (no tearing, higher latency); IMMEDIATE may tear but has the
 *  lowest latency. No fallback: the render loop disables presentation
 *  natively for the session if the chosen mode isn't advertised by the surface. */
enum class PresentMode(val prefValue: String, val vkValue: Int) {
    IMMEDIATE("immediate", 0),
    MAILBOX("mailbox", 1),
    FIFO("fifo", 2);

    companion object {
        fun fromPref(value: String?): PresentMode =
            entries.firstOrNull { it.prefValue == value } ?: MAILBOX
    }
}

class NovaPreferences(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): NovaConfig = NovaConfig(
        dllUri = prefs.getString(KEY_DLL_URI, null),
        dllDisplayName = prefs.getString(KEY_DLL_NAME, null),
        shadersReady = prefs.getBoolean(KEY_SHADERS_READY, false),
        lsfgEnabled = prefs.getBoolean(KEY_LSFG_ENABLED, true),
        multiplier = prefs.getInt(KEY_MULTIPLIER, 2).coerceIn(2, 8),
        flowScale = prefs.getFloat(KEY_FLOW_SCALE, 1.0f).coerceIn(0.25f, 1.0f),
        performanceMode = prefs.getBoolean(KEY_PERF, true),
        hdrMode = prefs.getBoolean(KEY_HDR, false),
        framegenFp16 = prefs.getBoolean(KEY_FRAMEGEN_FP16, true),
        renderResolutionScale = prefs.getFloat(KEY_RENDER_RESOLUTION_SCALE, 0.9f).coerceIn(0.0f, 1.0f),
        generationDeadlineMs = prefs.getInt(KEY_GENERATION_DEADLINE_MS, 0).coerceIn(0, 100),
        bypassGenDeadlineMs = prefs.getInt(KEY_BYPASS_GEN_DEADLINE_MS, 0).coerceIn(0, 100),
        bypassGenResumeDelayMs = prefs.getInt(KEY_BYPASS_GEN_RESUME_DELAY_MS, 50).coerceIn(0, 2000),
        legalAccepted = prefs.getBoolean(KEY_LEGAL, false),
        // The HUD used to be separate toggles; if the single HUD switch was never
        // written, treat the HUD as on when any of the old readout toggles was on.
        hudEnabled = if (prefs.contains(KEY_HUD)) {
            prefs.getBoolean(KEY_HUD, false)
        } else {
            prefs.getBoolean(KEY_FPS_COUNTER, false) ||
                prefs.getBoolean(KEY_CPU_STAT, false) ||
                prefs.getBoolean(KEY_GPU_STAT, false) ||
                prefs.getBoolean(KEY_RAM_STAT, false)
        },
        frameGraphEnabled = prefs.getBoolean(KEY_FRAME_GRAPH, false),
        hudPositionUnlocked = prefs.getBoolean(KEY_HUD_POSITION_UNLOCKED, false),
        hudPositionX = prefs.getFloat(KEY_HUD_POSITION_X, 0.02f).coerceIn(0f, 1f),
        hudPositionY = prefs.getFloat(KEY_HUD_POSITION_Y, 0.02f).coerceIn(0f, 1f),
        overlayMode = OverlayMode.DRAWER,
        presentMode = PresentMode.fromPref(prefs.getString(KEY_PRESENT_MODE, null)),
        waitForBusyGeneration = prefs.getBoolean(KEY_WAIT_FOR_BUSY_GENERATION, false),
        allowGenerationWhenBusy = prefs.getBoolean(KEY_ALLOW_GENERATION_WHEN_BUSY, false),
        losslessQueue = prefs.getBoolean(KEY_LOSSLESS_QUEUE, false),
        autoEnabledApps = decodeAutoEnabledApps(prefs.getString(KEY_AUTO_ENABLED_APPS, null)),
        framegenBackend = FramegenBackend.fromPref(prefs.getString(KEY_FRAMEGEN_BACKEND, null)),
        aiModelUri = prefs.getString(KEY_AI_MODEL_URI, null),
        aiModelDisplayName = prefs.getString(KEY_AI_MODEL_NAME, null),
        aiModelReady = prefs.getBoolean(KEY_AI_MODEL_READY, false),
        aiModelPrecision = prefs.getString(KEY_AI_MODEL_PRECISION, null),
        aiModelGraphs = decodeAiModelGraphs(prefs.getString(KEY_AI_MODEL_GRAPHS, null)),
        activeModelDir = prefs.getString(KEY_ACTIVE_MODEL_DIR, null),
        activeModelName = prefs.getString(KEY_ACTIVE_MODEL_NAME, null),
        activeModelEngine = if (prefs.contains(KEY_ACTIVE_MODEL_ENGINE)) prefs.getInt(KEY_ACTIVE_MODEL_ENGINE, 0) else null,
        myModelsRootUri = prefs.getString(KEY_MY_MODELS_ROOT_URI, null),
        aiEngine = AiEngine.fromPref(prefs.getString(KEY_AI_ENGINE, null)),
        rifeModel = RifeModel.fromPref(prefs.getString(KEY_RIFE_MODEL, null)),
        ifrnetModel = IfrnetModel.fromPref(prefs.getString(KEY_IFRNET_MODEL, null)),
        imageEnhancementEnabled = prefs.getBoolean(KEY_IMAGE_ENHANCEMENT_ENABLED, false),
        imageEnhancementContrast = prefs.getFloat(KEY_IMAGE_ENHANCEMENT_CONTRAST, 1.0f).coerceIn(0.5f, 1.5f),
        imageEnhancementSaturation = prefs.getFloat(KEY_IMAGE_ENHANCEMENT_SATURATION, 1.0f).coerceIn(0f, 2f),
        upscaleEnabled = prefs.getBoolean(KEY_UPSCALE_ENABLED, true),
        upscaleFilter = UpscaleFilter.fromPref(prefs.getString(KEY_UPSCALE_FILTER, null)),
    )

    fun getAutoEnabledApps(): Set<String> =
        decodeAutoEnabledApps(prefs.getString(KEY_AUTO_ENABLED_APPS, null))

    fun setAutoEnabledApps(value: Set<String>) {
        prefs.edit()
            .putString(KEY_AUTO_ENABLED_APPS, value.joinToString("\n"))
            .apply()
    }

    // --- Sound tuner (global Equalizer/BassBoost/Virtualizer/LoudnessEnhancer, session 0) ---
    // Kept as standalone get/set pairs (like getAutoEnabledApps above) rather than fields on
    // NovaConfig — these don't feed the native render loop, so there's no reason to make every
    // NovaConfig construction site aware of them.

    fun isSoundTunerEnabled(): Boolean = prefs.getBoolean(KEY_SOUND_TUNER_ENABLED, false)
    fun setSoundTunerEnabled(value: Boolean) =
        prefs.edit().putBoolean(KEY_SOUND_TUNER_ENABLED, value).apply()

    fun getSoundTunerBass(): Int = prefs.getInt(KEY_SOUND_TUNER_BASS, 0).coerceIn(0, 1000)
    fun setSoundTunerBass(value: Int) =
        prefs.edit().putInt(KEY_SOUND_TUNER_BASS, value.coerceIn(0, 1000)).apply()

    fun getSoundTunerVirtualizer(): Int = prefs.getInt(KEY_SOUND_TUNER_VIRTUALIZER, 0).coerceIn(0, 1000)
    fun setSoundTunerVirtualizer(value: Int) =
        prefs.edit().putInt(KEY_SOUND_TUNER_VIRTUALIZER, value.coerceIn(0, 1000)).apply()

    /** Millibels, 0..2000 (0..20 dB) — see [com.firstt175.novaframe.session.diagnostics.SoundTunerController.setLoudnessGain]. */
    fun getSoundTunerLoudness(): Int = prefs.getInt(KEY_SOUND_TUNER_LOUDNESS, 0).coerceIn(0, 2000)
    fun setSoundTunerLoudness(value: Int) =
        prefs.edit().putInt(KEY_SOUND_TUNER_LOUDNESS, value.coerceIn(0, 2000)).apply()

    /** Per-band levels in millibels, indexed by band. Empty if never customized. */
    fun getSoundTunerEqBands(): List<Int> = decodeIntList(prefs.getString(KEY_SOUND_TUNER_EQ_BANDS, null))
    fun setSoundTunerEqBands(levels: List<Int>) = prefs.edit()
        .putString(KEY_SOUND_TUNER_EQ_BANDS, levels.joinToString(","))
        .apply()

    // --- "real-frame-first" backlog policy — OFF by default. Not part of
    // NovaConfig/initContext: it's hot-applied via a NativeBridge setter
    // (like pacing params), so a standalone get/set pair is enough here.

    /** How many REAL captures may sit queued before the oldest are evicted. Default 1 (original behaviour). */
    fun getMaxQueuedRealFrames(): Int =
        prefs.getInt(KEY_MAX_QUEUED_REAL_FRAMES, 1).coerceIn(1, 8)
    fun setMaxQueuedRealFrames(value: Int) = prefs.edit()
        .putInt(KEY_MAX_QUEUED_REAL_FRAMES, value.coerceIn(1, 8))
        .apply()

    /** Auto-disable framegen on VK_ERROR_DEVICE_LOST: ON by default (crash-recovery safety net). */
    fun isAutoDisableOnDeviceLostEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_DISABLE_ON_DEVICE_LOST, true)
    fun setAutoDisableOnDeviceLostEnabled(value: Boolean) =
        prefs.edit().putBoolean(KEY_AUTO_DISABLE_ON_DEVICE_LOST, value).apply()

    fun setDll(uri: String, displayName: String) = prefs.edit()
        .putString(KEY_DLL_URI, uri)
        .putString(KEY_DLL_NAME, displayName)
        .putBoolean(KEY_SHADERS_READY, false)
        .apply()

    fun setShadersReady(ready: Boolean) = prefs.edit()
        .putBoolean(KEY_SHADERS_READY, ready)
        .apply()

    fun setNovaEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_LSFG_ENABLED, value).apply()
    fun setMultiplier(value: Int) = prefs.edit().putInt(KEY_MULTIPLIER, value).apply()
    fun setFlowScale(value: Float) = prefs.edit().putFloat(KEY_FLOW_SCALE, value).apply()
    fun setPerformance(value: Boolean) = prefs.edit().putBoolean(KEY_PERF, value).apply()
    fun setHdr(value: Boolean) = prefs.edit().putBoolean(KEY_HDR, value).apply()
    fun setFramegenFp16(value: Boolean) = prefs.edit().putBoolean(KEY_FRAMEGEN_FP16, value).apply()
    fun setRenderResolutionScale(value: Float) = prefs.edit()
        .putFloat(KEY_RENDER_RESOLUTION_SCALE, value.coerceIn(0.0f, 1.0f))
        .apply()
    fun setLegalAccepted(value: Boolean) = prefs.edit().putBoolean(KEY_LEGAL, value).apply()
    fun setHudEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_HUD, value).apply()
    fun setFrameGraphEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_FRAME_GRAPH, value).apply()
    fun setHudPositionUnlocked(value: Boolean) = prefs.edit().putBoolean(KEY_HUD_POSITION_UNLOCKED, value).apply()
    fun setHudPosition(x: Float, y: Float) = prefs.edit()
        .putFloat(KEY_HUD_POSITION_X, x.coerceIn(0f, 1f))
        .putFloat(KEY_HUD_POSITION_Y, y.coerceIn(0f, 1f))
        .apply()
    fun setOverlayMode(value: OverlayMode) = prefs.edit()
        .putString(KEY_OVERLAY_MODE, value.prefValue)
        .apply()

    fun isWaitForBusyGenerationEnabled(): Boolean =
        prefs.getBoolean(KEY_WAIT_FOR_BUSY_GENERATION, false)
    fun setWaitForBusyGeneration(value: Boolean) = prefs.edit()
        .putBoolean(KEY_WAIT_FOR_BUSY_GENERATION, value)
        .apply()

    fun isAllowGenerationWhenBusyEnabled(): Boolean =
        prefs.getBoolean(KEY_ALLOW_GENERATION_WHEN_BUSY, false)
    fun setAllowGenerationWhenBusy(value: Boolean) = prefs.edit()
        .putBoolean(KEY_ALLOW_GENERATION_WHEN_BUSY, value)
        .apply()

    fun isLosslessQueueEnabled(): Boolean =
        prefs.getBoolean(KEY_LOSSLESS_QUEUE, false)
    fun setLosslessQueue(value: Boolean) = prefs.edit()
        .putBoolean(KEY_LOSSLESS_QUEUE, value)
        .apply()

    fun setFramegenBackend(value: FramegenBackend) = prefs.edit()
        .putString(KEY_FRAMEGEN_BACKEND, value.prefValue)
        .apply()

    fun setRifeModel(model: RifeModel) = prefs.edit()
        .putString(KEY_RIFE_MODEL, model.prefValue)
        .putBoolean(KEY_AI_MODEL_READY, false)
        .apply()

    fun setIfrnetModel(model: IfrnetModel) = prefs.edit()
        .putString(KEY_IFRNET_MODEL, model.prefValue)
        .putBoolean(KEY_AI_MODEL_READY, false)
        .apply()

    fun setAiModel(uri: String, displayName: String) = prefs.edit()
        .putString(KEY_AI_MODEL_URI, uri)
        .putString(KEY_AI_MODEL_NAME, displayName)
        .putBoolean(KEY_AI_MODEL_READY, false)
        .putString(KEY_AI_MODEL_PRECISION, null)
        .putString(KEY_AI_MODEL_GRAPHS, null)
        .apply()

    fun setMyModelsRootUri(uri: String?) = prefs.edit()
        .putString(KEY_MY_MODELS_ROOT_URI, uri)
        .apply()

    fun setActiveModel(dir: String, name: String, engine: Int) = prefs.edit()
        .putString(KEY_ACTIVE_MODEL_DIR, dir)
        .putString(KEY_ACTIVE_MODEL_NAME, name)
        .putInt(KEY_ACTIVE_MODEL_ENGINE, engine)
        .putString(KEY_AI_MODEL_URI, null)
        .putBoolean(KEY_AI_MODEL_READY, true)
        .putString(KEY_AI_MODEL_PRECISION, "fp16")
        .putString(KEY_AI_MODEL_GRAPHS, if (engine == 1) "ifrnet" else "flownet")
        .apply()

    fun setAiModelReady(ready: Boolean, precision: String?, graphs: List<String>) = prefs.edit()
        .putBoolean(KEY_AI_MODEL_READY, ready)
        .putString(KEY_AI_MODEL_PRECISION, precision)
        .putString(KEY_AI_MODEL_GRAPHS, graphs.joinToString("\n"))
        .apply()

    /** Switches the ncnn engine and clears its cached load status. */

    fun setImageEnhancementEnabled(value: Boolean) = prefs.edit()
        .putBoolean(KEY_IMAGE_ENHANCEMENT_ENABLED, value).apply()
    fun setImageEnhancementContrast(value: Float) = prefs.edit()
        .putFloat(KEY_IMAGE_ENHANCEMENT_CONTRAST, value.coerceIn(0.5f, 1.5f)).apply()
    fun setImageEnhancementSaturation(value: Float) = prefs.edit()
        .putFloat(KEY_IMAGE_ENHANCEMENT_SATURATION, value.coerceIn(0f, 2f)).apply()
    fun setUpscaleEnabled(value: Boolean) = prefs.edit()
        .putBoolean(KEY_UPSCALE_ENABLED, value).apply()
    fun setUpscaleFilter(value: UpscaleFilter) = prefs.edit()
        .putString(KEY_UPSCALE_FILTER, value.prefValue).apply()

    /** Maximum capture-to-generation latency in milliseconds. 0 disables the deadline. */
    fun getGenerationDeadlineMs(): Int = prefs.getInt(KEY_GENERATION_DEADLINE_MS, 0).coerceIn(0, 100)
    fun setGenerationDeadlineMs(value: Int) = prefs.edit()
        .putInt(KEY_GENERATION_DEADLINE_MS, value.coerceIn(0, 100)).apply()

    /** Automatic GPU-saving bypass deadline. 0 disables BypassGen. */
    fun getBypassGenDeadlineMs(): Int = prefs.getInt(KEY_BYPASS_GEN_DEADLINE_MS, 0).coerceIn(0, 100)
    fun setBypassGenDeadlineMs(value: Int) = prefs.edit()
        .putInt(KEY_BYPASS_GEN_DEADLINE_MS, value.coerceIn(0, 100)).apply()

    /** Time spent in automatic bypass before frame generation can resume. */
    fun getBypassGenResumeDelayMs(): Int = prefs.getInt(KEY_BYPASS_GEN_RESUME_DELAY_MS, 50).coerceIn(0, 2000)
    fun setBypassGenResumeDelayMs(value: Int) = prefs.edit()
        .putInt(KEY_BYPASS_GEN_RESUME_DELAY_MS, value.coerceIn(0, 2000)).apply()

    fun setPresentMode(value: PresentMode) = prefs.edit()
        .putString(KEY_PRESENT_MODE, value.prefValue).apply()

    fun setAiEngine(value: AiEngine) = prefs.edit()
        .putString(KEY_AI_ENGINE, value.prefValue)
        .putBoolean(KEY_AI_MODEL_READY, false)
        .putString(KEY_AI_MODEL_PRECISION, null)
        .putString(KEY_AI_MODEL_GRAPHS, null)
        .apply()

    companion object {
        private const val FILE = "lsfg_prefs"
        private const val KEY_DLL_URI = "dll_uri"
        private const val KEY_DLL_NAME = "dll_name"
        private const val KEY_SHADERS_READY = "shaders_ready"
        private const val KEY_LSFG_ENABLED = "lsfg_enabled"
        private const val KEY_MULTIPLIER = "multiplier"
        private const val KEY_FLOW_SCALE = "flow_scale"
        private const val KEY_PERF = "performance"
        private const val KEY_HDR = "hdr"
        private const val KEY_FRAMEGEN_FP16 = "framegen_fp16"
        private const val KEY_GENERATION_DEADLINE_MS = "generation_deadline_ms"
        private const val KEY_BYPASS_GEN_DEADLINE_MS = "bypass_gen_deadline_ms"
        private const val KEY_BYPASS_GEN_RESUME_DELAY_MS = "bypass_gen_resume_delay_ms"
        private const val KEY_RENDER_RESOLUTION_SCALE = "render_resolution_scale"
        /**
         * Never let the *effective* capture/render size collapse to 0 pixels even
         * if the user drags the slider all the way to 0%. Applied only where the
         * scale multiplies an actual buffer dimension (VirtualDisplay/ImageReader
         * size, native context width/height) — the stored preference and the UI
         * slider itself still range down to a true 0.0.
         */
        const val MIN_RENDER_RESOLUTION_SCALE = 0.1f
        private const val KEY_LEGAL = "legal_accepted"
        private const val KEY_HUD = "hud_enabled"
        // Legacy per-part HUD keys (except KEY_FRAME_GRAPH, which is the live graph switch).
        private const val KEY_FPS_COUNTER = "fps_counter"
        private const val KEY_FRAME_GRAPH = "frame_graph"
        private const val KEY_CPU_STAT = "hud_cpu_stat"
        private const val KEY_GPU_STAT = "hud_gpu_stat"
        private const val KEY_RAM_STAT = "hud_ram_stat"
        private const val KEY_HUD_POSITION_UNLOCKED = "hud_position_unlocked"
        private const val KEY_HUD_POSITION_X = "hud_position_x"
        private const val KEY_HUD_POSITION_Y = "hud_position_y"
        private const val KEY_OVERLAY_MODE = "overlay_mode"
        private const val KEY_PRESENT_MODE = "present_mode"
        private const val KEY_WAIT_FOR_BUSY_GENERATION = "wait_for_busy_generation"
        private const val KEY_ALLOW_GENERATION_WHEN_BUSY = "allow_generation_when_busy"
        private const val KEY_LOSSLESS_QUEUE = "lossless_queue"
        private const val KEY_AUTO_ENABLED_APPS = "auto_enabled_apps"
        private const val KEY_FRAMEGEN_BACKEND = "framegen_backend"
        private const val KEY_RIFE_MODEL = "rife_model"
        private const val KEY_IFRNET_MODEL = "ifrnet_model"
        private const val KEY_AI_MODEL_URI = "ai_model_uri"
        private const val KEY_AI_MODEL_NAME = "ai_model_name"
        private const val KEY_AI_MODEL_READY = "ai_model_ready"
        private const val KEY_AI_MODEL_PRECISION = "ai_model_precision"
        private const val KEY_AI_MODEL_GRAPHS = "ai_model_graphs"
        private const val KEY_ACTIVE_MODEL_DIR = "active_model_dir"
        private const val KEY_ACTIVE_MODEL_NAME = "active_model_name"
        private const val KEY_ACTIVE_MODEL_ENGINE = "active_model_engine"
        private const val KEY_MY_MODELS_ROOT_URI = "my_models_root_uri"
        private const val KEY_SOUND_TUNER_ENABLED = "sound_tuner_enabled"
        private const val KEY_SOUND_TUNER_BASS = "sound_tuner_bass"
        private const val KEY_SOUND_TUNER_VIRTUALIZER = "sound_tuner_virtualizer"
        private const val KEY_SOUND_TUNER_LOUDNESS = "sound_tuner_loudness"
        private const val KEY_SOUND_TUNER_EQ_BANDS = "sound_tuner_eq_bands"
        private const val KEY_MAX_QUEUED_REAL_FRAMES = "max_queued_real_frames"
        private const val KEY_AUTO_DISABLE_ON_DEVICE_LOST = "auto_disable_on_device_lost"
        /**
         * Legacy pre-split keys. Kept read-only (never written by current code) so an
         * existing install's prior single RIFE/IFRNet-shared setting seeds both engines'
         * new per-engine keys the first time [load] runs after this update, instead of
         * silently resetting everyone to the hardcoded defaults.
         */
        private const val KEY_IMAGE_ENHANCEMENT_ENABLED = "image_enhancement_enabled"
        private const val KEY_IMAGE_ENHANCEMENT_CONTRAST = "image_enhancement_contrast"
        private const val KEY_IMAGE_ENHANCEMENT_SATURATION = "image_enhancement_saturation"
        private const val KEY_UPSCALE_ENABLED = "upscale_enabled"
        private const val KEY_UPSCALE_FILTER = "upscale_filter"
        private const val KEY_AI_ENGINE = "ai_engine"

        private fun decodeAutoEnabledApps(raw: String?): Set<String> {
            if (raw.isNullOrEmpty()) return emptySet()
            return raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        }

        private fun decodeAiModelGraphs(raw: String?): List<String> {
            if (raw.isNullOrEmpty()) return emptyList()
            return raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        }

        private fun decodeIntList(raw: String?): List<Int> {
            if (raw.isNullOrEmpty()) return emptyList()
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        }
    }
}
