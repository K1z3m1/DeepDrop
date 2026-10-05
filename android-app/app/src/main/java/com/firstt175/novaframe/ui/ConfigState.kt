package com.firstt175.novaframe.ui

import com.firstt175.novaframe.prefs.NovaConfig
import com.firstt175.novaframe.prefs.AiEngine
import com.firstt175.novaframe.prefs.FramegenBackend
import com.firstt175.novaframe.prefs.NovaPreferences
import com.firstt175.novaframe.prefs.OverlayMode
import com.firstt175.novaframe.prefs.PresentMode
import com.firstt175.novaframe.prefs.RifeModel
import com.firstt175.novaframe.prefs.IfrnetModel
import com.firstt175.novaframe.prefs.UpscaleFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Produces a StateFlow bound to [NovaPreferences] content. Each screen that mutates prefs
 * should call [refresh] after commit so the home screen reflects updates.
 *
 * This is intentionally simple (manual refresh) to avoid pulling in DataStore for Phase 1.
 */
private val shared: MutableStateFlow<NovaConfig> = MutableStateFlow(
    NovaConfig(
        dllUri = null,
        dllDisplayName = null,
        shadersReady = false,
        lsfgEnabled = true,
        multiplier = 2,
        flowScale = 1.0f,
        performanceMode = true,
        hdrMode = false,
        framegenFp16 = true,
        renderResolutionScale = 0.9f,
        generationDeadlineMs = 0,
        bypassGenDeadlineMs = 0,
        bypassGenResumeDelayMs = 50,
        legalAccepted = false,
        hudEnabled = false,
        frameGraphEnabled = false,
        hudPositionUnlocked = false,
        hudPositionX = 0.02f,
        hudPositionY = 0.02f,
        overlayMode = OverlayMode.DRAWER,
        presentMode = PresentMode.MAILBOX,
        waitForBusyGeneration = false,
        allowGenerationWhenBusy = false,
        losslessQueue = false,
        autoEnabledApps = emptySet(),
        framegenBackend = FramegenBackend.LSFG_DLL,
        aiModelUri = null,
        aiModelDisplayName = null,
        aiModelReady = false,
        aiModelPrecision = null,
        aiModelGraphs = emptyList(),
        activeModelDir = null,
        activeModelName = null,
        activeModelEngine = null,
        myModelsRootUri = null,
        aiEngine = AiEngine.RIFE,
        rifeModel = RifeModel.V4_17_LITE,
        ifrnetModel = IfrnetModel.S_VIMEO90K,
        imageEnhancementEnabled = false,
        imageEnhancementContrast = 1.0f,
        imageEnhancementSaturation = 1.0f,
        upscaleEnabled = true,
        upscaleFilter = UpscaleFilter.BILINEAR,
    )
)

fun produceConfigState(prefs: NovaPreferences): StateFlow<NovaConfig> {
    shared.value = prefs.load()
    return shared
}

fun refreshConfigState(prefs: NovaPreferences) {
    shared.value = prefs.load()
}
