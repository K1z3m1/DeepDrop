package com.firstt175.novaframe.ui.screens
import com.firstt175.novaframe.ui.produceConfigState
import com.firstt175.novaframe.ui.refreshConfigState

import com.firstt175.novaframe.ui.Routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.FramegenBackend
import com.firstt175.novaframe.prefs.NovaPreferences
import com.firstt175.novaframe.session.NativeBridge
import com.firstt175.novaframe.ui.components.CollapsibleSection
import com.firstt175.novaframe.ui.components.IconBadge
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.NovaSecondaryButton
import com.firstt175.novaframe.ui.components.NovaTopBar
import com.firstt175.novaframe.ui.components.SectionHeader
import com.firstt175.novaframe.ui.components.StatusPill
import com.firstt175.novaframe.ui.components.StatusTone
import com.firstt175.novaframe.ui.components.ToggleRow
import com.firstt175.novaframe.ui.components.ValueSlider
import com.firstt175.novaframe.ui.theme.NovaPrimary
import com.firstt175.novaframe.ui.theme.NovaStatusGood

/**
 * Dedicated frame-generation settings page.
 *
 * Keeping frame-generation controls on their own destination makes the launcher
 * much lighter while preserving the same live preferences and controls.
 */
@Composable
fun FrameGenScreen(nav: NavHostController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NovaTopBar(
            title = stringResource(R.string.nav_framegen_pacing),
            onBack = { nav.popBackStack() },
        )
        FrameGenSettingsSection(nav)
    }
}

@Composable
fun FrameGenSettingsSection(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { NovaPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()
    // The FP16 toggle is only meaningful when (a) the GPU advertises shaderFloat16
    // and (b) the FP16 SPIR-V cache from Lossless.dll has been populated. The
    // native probe ANDs both. Cached for the screen's lifetime — recomputed on
    // re-entry, which covers the post-DLL-pick / post-extract case naturally.
    val fp16Available = remember {
        val cacheDir = java.io.File(ctx.filesDir, "spirv").absolutePath
        runCatching { NativeBridge.isFramegenFp16Supported(cacheDir) }.getOrDefault(false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Status summary: what will run when the next session starts.
        NovaCard(accent = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.section_frame_generation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${if (state.framegenBackend == FramegenBackend.NCNN_AI) 2 else state.multiplier}\u00D7",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (state.framegenBackend == FramegenBackend.NCNN_AI) "AI (ncnn)" else "Lossless.dll (LSFG)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    label = if (state.lsfgEnabled) "เปิดอยู่" else "ปิดอยู่",
                    tone = if (state.lsfgEnabled) StatusTone.Good else StatusTone.Neutral,
                )
            }
        }

        // Picking the DLL (or AI model) used to live as its own step on the
        // settings home screen. It now lives here, at the top of Frame Gen,
        // since the two are really the same setting.
        NovaCard(onClick = {
            if (!state.legalAccepted) nav.navigate(Routes.LEGAL) else nav.navigate(Routes.DLL)
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = if (state.shadersReady) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.InsertDriveFile,
                    tint = if (state.shadersReady) NovaStatusGood else NovaPrimary,
                    size = 36.dp,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.nav_dll),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.dllDisplayName ?: stringResource(R.string.dll_status_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(8.dp))
                StatusPill(
                    label = if (state.shadersReady) "พร้อมใช้งาน" else if (state.dllDisplayName != null) "รอดำเนินการ" else "ต้องเลือก",
                    tone = if (state.shadersReady) StatusTone.Good
                    else if (state.dllDisplayName != null) StatusTone.Warn
                    else StatusTone.Neutral,
                )
            }
        }

        // ---- Frame generation -------------------------------------------------------
        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.section_frame_generation), title = null)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.framegenBackend == FramegenBackend.NCNN_AI) {
                    "Backend: AI (ncnn). Multiplier, flow scale and render resolution below apply " +
                        "to it too — the LSFG-shader-only options (performance mode, HDR, FP16) are " +
                        "hidden since they don't do anything on this backend. Switch backend and " +
                        "pick the compute path on the Frame-gen source screen."
                } else {
                    "Backend: Lossless.dll (LSFG). Switch backend on the Frame-gen source screen."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            ToggleRow(
                icon = Icons.Filled.FlashOn,
                title = "NovaFrame Frame Gen",
                description = "Master toggle for frame generation. Off = raw capture passthrough.",
                checked = state.lsfgEnabled,
                onCheckedChange = {
                    prefs.setNovaEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // The AI (ncnn) backend is locked to ×2. RIFE/IFRNet were only ever exported
            // and validated for the single-midpoint (k=1, multiplier=2) case — nothing
            // above ×2 has been trained/tested for these models, so the slider is
            // disabled and forced back to 2 rather than letting it silently run an
            // unvalidated higher multiplier. Only the LSFG_DLL shader backend supports
            // the full 2..8 range.
            if (state.framegenBackend == FramegenBackend.NCNN_AI) {
                if (state.multiplier != 2) {
                    LaunchedEffect(Unit) {
                        prefs.setMultiplier(2)
                        refreshConfigState(prefs)
                    }
                }
                ValueSlider(
                    title = stringResource(R.string.param_multiplier),
                    valueDisplay = "2× (ล็อกไว้)",
                    description = "แบ็กเอนด์ AI ถูกล็อกไว้ที่ ×2 — RIFE/IFRNet ได้รับการยืนยันเฉพาะ " +
                        "จุดกึ่งกลางเฟรมเดียวเท่านั้น สลับไปใช้แบ็กเอนด์ Lossless.dll " +
                        "เพื่อใช้ ×3–×8",
                    value = 2f,
                    range = 2f..2f,
                    steps = 0,
                    leadingIcon = Icons.Filled.Timeline,
                    enabled = false,
                    onValueChange = {},
                )
            } else {
                ValueSlider(
                    title = stringResource(R.string.param_multiplier),
                    valueDisplay = "${state.multiplier}×",
                    description = stringResource(R.string.param_multiplier_desc),
                    value = state.multiplier.toFloat(),
                    range = 2f..8f,
                    steps = 5,
                    leadingIcon = Icons.Filled.Timeline,
                    onValueChange = {
                        prefs.setMultiplier(it.toInt().coerceIn(2, 8))
                        refreshConfigState(prefs)
                    },
                )
            }

            // Like the multiplier slider above, flow scale only affects the LSFG_DLL
            // shader chain's motion-estimation pass (see resourcepool.cpp). The AI
            // (ncnn) backend's RIFE/IFRNet nets are single-pass with no separate
            // low-res flow stage to downscale — flowScale is accepted by
            // NcnnInterpolator::interpolate()/IfrnetInterpolator::interpolate() only
            // for call-site compatibility and is explicitly unused (see the
            // `/*flowScale — unused*/` parameter comment in both .cpp files).
            // Leaving the slider live here made it look broken: dragging it changed
            // the stored pref but had no visible effect on AI-backend output.
            if (state.framegenBackend == FramegenBackend.NCNN_AI) {
                ValueSlider(
                    title = stringResource(R.string.param_flow_scale),
                    valueDisplay = "ไม่มีข้อมูล",
                    description = "โมเดล RIFE/IFRNet ของแบ็กเอนด์ AI ไม่มีขั้นตอนสเกล flow แยกต่างหาก " +
                        "ตัวเลือกนี้มีผลเฉพาะกับแบ็กเอนด์เชเดอร์ Lossless.dll เท่านั้น",
                    value = 1.0f,
                    range = 1.0f..1.0f,
                    steps = 0,
                    leadingIcon = Icons.AutoMirrored.Filled.ShowChart,
                    enabled = false,
                    onValueChange = {},
                )
            } else {
                ValueSlider(
                    title = stringResource(R.string.param_flow_scale),
                    // Keep the original 0.1.3 representation: Flow scale is the
                    // raw 0.25..1.0 value, not a percentage and not a preset.
                    valueDisplay = "%.2f".format(state.flowScale),
                    description = stringResource(R.string.param_flow_scale_desc),
                    value = state.flowScale,
                    range = 0.25f..1.0f,
                    steps = 0,
                    leadingIcon = Icons.AutoMirrored.Filled.ShowChart,
                    onValueChange = {
                        prefs.setFlowScale(it)
                        refreshConfigState(prefs)
                    },
                )
            }

            ValueSlider(
                title = "Generation Deadline",
                valueDisplay = if (state.generationDeadlineMs == 0) "Off" else "${state.generationDeadlineMs} ms",
                description = "กำหนดเวลาสูงสุดตั้งแต่รับเฟรมจริงจนเฟรมเจนเสร็จ ถ้าเกินเวลาจะทิ้งเฟรมเจนและแสดงเฟรมจริงทันที เพื่อลดอาการกระตุกจากเฟรมเจนที่มาช้า",
                value = state.generationDeadlineMs.toFloat(),
                range = 0f..50f,
                steps = 49,
                leadingIcon = Icons.Filled.Speed,
                onValueChange = {
                    val value = it.toInt().coerceIn(0, 50)
                    prefs.setGenerationDeadlineMs(value)
                    runCatching { NativeBridge.setGenerationDeadlineMs(value) }
                    refreshConfigState(prefs)
                },
            )

            ValueSlider(
                title = "BypassGen Deadline",
                valueDisplay = if (state.bypassGenDeadlineMs == 0) "Off" else "${state.bypassGenDeadlineMs} ms",
                description = "ถ้าเฟรมจริงค้างเกินเวลานี้ จะไม่เริ่มงาน GPU generation และแสดงเฟรมจริงแทน เพื่อลดการใช้ GPU",
                value = state.bypassGenDeadlineMs.toFloat(),
                range = 0f..50f,
                steps = 49,
                leadingIcon = Icons.Filled.Speed,
                onValueChange = {
                    val value = it.toInt().coerceIn(0, 50)
                    prefs.setBypassGenDeadlineMs(value)
                    runCatching { NativeBridge.setBypassGenDeadlineMs(value) }
                    refreshConfigState(prefs)
                },
            )

            ValueSlider(
                title = "BypassGen Resume Delay",
                valueDisplay = "${state.bypassGenResumeDelayMs} ms",
                description = "หลังเข้า BypassGen ให้พัก GPU ตามเวลานี้ แล้วเริ่มจับคู่เฟรมใหม่จาก REAL frame ปัจจุบัน",
                value = state.bypassGenResumeDelayMs.toFloat(),
                range = 0f..500f,
                steps = 49,
                leadingIcon = Icons.Filled.Timeline,
                onValueChange = {
                    val value = it.toInt().coerceIn(0, 500)
                    prefs.setBypassGenResumeDelayMs(value)
                    runCatching { NativeBridge.setBypassGenResumeDelayMs(value) }
                    refreshConfigState(prefs)
                },
            )

            ValueSlider(
                title = stringResource(R.string.param_render_resolution_scale),
                valueDisplay = "${(state.renderResolutionScale * 100f).toInt()}%",
                description = stringResource(R.string.param_render_resolution_scale_desc),
                value = state.renderResolutionScale,
                range = 0f..1f,
                steps = 19,
                leadingIcon = Icons.Filled.AspectRatio,
                onValueChange = {
                    prefs.setRenderResolutionScale(it)
                    refreshConfigState(prefs)
                },
            )

            // Input FPS cap: throttles how many incoming capture frames are
            // admitted into the pipeline at all. Frames arriving faster than
            // the cap are dropped before capture handling, blit, and
            // generation — the whole pipeline runs at the capped rate, not
            // just the AI/LSFG step, e.g. capping a 60fps game to 30 means
            // ~30fps actually gets processed and fed into frame generation.
            // Backend-agnostic, so this stays visible for both Lossless.dll
            // and the AI (ncnn) backend.
            // Everything below this point only takes effect on the LSFG_3_1/3_1P
            // shader chain — the AI (ncnn) backend never reads performanceMode,
            // hdrMode or framegenFp16 at all (see lsfg_render_loop.cpp's
            // initRenderLoop: when cfg.aiBackend loads successfully,
            // initFramegen/createFramegenContext — the only code that consults
            // these three — is skipped entirely). Showing them while the AI
            // backend is selected would suggest they do something they don't.
            if (state.framegenBackend == FramegenBackend.LSFG_DLL) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    icon = Icons.Filled.Speed,
                    title = stringResource(R.string.param_performance_mode),
                    description = stringResource(R.string.param_performance_mode_desc),
                    checked = state.performanceMode,
                    onCheckedChange = {
                        prefs.setPerformance(it)
                        refreshConfigState(prefs)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    icon = Icons.Filled.HdrOn,
                    title = stringResource(R.string.param_hdr),
                    description = stringResource(R.string.param_hdr_desc),
                    checked = state.hdrMode,
                    onCheckedChange = {
                        prefs.setHdr(it)
                        refreshConfigState(prefs)
                    },
                )
                if (fp16Available) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow(
                        icon = Icons.Filled.Memory,
                        title = stringResource(R.string.param_framegen_fp16),
                        description = stringResource(R.string.param_framegen_fp16_desc),
                        checked = state.framegenFp16,
                        onCheckedChange = {
                            prefs.setFramegenFp16(it)
                            refreshConfigState(prefs)
                        },
                    )
                }
            }
        }

        // ---- Advanced (from SettingsDrawerOverlay) -----------------------------------
        // Frame-scheduling / queue / device-lost controls that used to be reachable
        // only from the in-game drawer. Same prefs + NativeBridge calls as the drawer,
        // so a value set here is identical to setting it live mid-session.
        CollapsibleSection(
            title = "ADVANCED",
            subtitle = if (state.waitForBusyGeneration || state.allowGenerationWhenBusy || state.losslessQueue) {
                "กำหนดเอง"
            } else {
                "ค่าเริ่มต้น — แตะเพื่อตั้งค่า"
            },
        ) {
            ToggleRow(
                icon = Icons.Filled.HourglassEmpty,
                title = "Wait for busy generation",
                description = "บล็อก frame loop จนกว่าการ generate ที่ค้างอยู่จะเสร็จ แทนที่จะไปต่อทันที",
                checked = state.waitForBusyGeneration,
                onCheckedChange = {
                    prefs.setWaitForBusyGeneration(it)
                    runCatching { NativeBridge.setWaitForBusyGeneration(it) }
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.PlaylistAdd,
                title = "Allow generation while busy",
                description = "อนุญาตให้เริ่ม generate ใหม่แม้ generate ก่อนหน้ายังไม่เสร็จ แทนที่จะข้ามไป",
                checked = state.allowGenerationWhenBusy,
                onCheckedChange = {
                    prefs.setAllowGenerationWhenBusy(it)
                    runCatching { NativeBridge.setAllowGenerationWhenBusy(it) }
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.AllInclusive,
                title = "Lossless queue",
                description = "เก็บทุกเฟรมจริงที่จับได้ไว้ในคิว แทนที่จะจำกัดตาม queue depth ด้านล่าง",
                checked = state.losslessQueue,
                onCheckedChange = {
                    prefs.setLosslessQueue(it)
                    runCatching { NativeBridge.setLosslessQueue(it) }
                    refreshConfigState(prefs)
                },
            )

            var maxQueued by remember { mutableStateOf(prefs.getMaxQueuedRealFrames()) }
            ValueSlider(
                title = "Queue depth",
                valueDisplay = "$maxQueued",
                description = "จำนวนเฟรมจริงสูงสุดที่รอในคิวก่อนที่เฟรมเก่าสุดจะถูกทิ้ง",
                value = maxQueued.toFloat(),
                range = 1f..8f,
                steps = 6,
                leadingIcon = Icons.Filled.Layers,
                onValueChange = {
                    val value = it.toInt().coerceIn(1, 8)
                    maxQueued = value
                    prefs.setMaxQueuedRealFrames(value)
                    runCatching { NativeBridge.setMaxQueuedRealFrames(value) }
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            var autoDisable by remember { mutableStateOf(prefs.isAutoDisableOnDeviceLostEnabled()) }
            ToggleRow(
                icon = Icons.Filled.WarningAmber,
                title = "Auto-disable on device lost",
                description = "ปิดเฟรมเจนอัตโนมัติชั่วคราวเมื่อเจอ VK_ERROR_DEVICE_LOST แทนที่จะปล่อยให้เซสชันล่ม",
                checked = autoDisable,
                onCheckedChange = {
                    autoDisable = it
                    prefs.setAutoDisableOnDeviceLostEnabled(it)
                    runCatching { NativeBridge.setAutoDisableOnDeviceLostEnabled(it) }
                },
            )

            Spacer(Modifier.height(4.dp))
            var resumeLabel by remember { mutableStateOf("RESUME FRAMEGEN") }
            NovaSecondaryButton(
                text = resumeLabel,
                onClick = {
                    val wasLatched = runCatching { NativeBridge.resumeFramegenAfterAutoDisable() }
                        .getOrDefault(false)
                    resumeLabel = if (wasLatched) "RESUMED" else "NOT LATCHED"
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FrameGenTailNote()
    }
}

@Composable
private fun FrameGenTailNote() {
    Text(
        text = "Changes apply on the next session start. During an active session, open the in-game drawer to tweak values live.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
