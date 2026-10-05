package com.firstt175.novaframe.ui.screens

import com.firstt175.novaframe.session.diagnostics.ThermalReader
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.novaframe.R
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.firstt175.novaframe.session.NativeBridge
import com.firstt175.novaframe.session.diagnostics.ShizukuDisplayPermission
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.NovaTopBar
import com.firstt175.novaframe.ui.components.SectionHeader
import com.firstt175.novaframe.ui.components.StatusPill
import com.firstt175.novaframe.ui.components.StatusTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------
// Device profile screen. Everything below (CPU load, temperature, RAM,
// current per-cluster clock speed) is only ever polled while this
// composable is actually on screen AND in the foreground: the poll loop
// runs inside repeatOnLifecycle(RESUMED), so pressing Home or switching
// away from the app suspends it immediately (no background timer), and
// navigating back to this screen is what restarts it — there is no
// polling anywhere else in the app.
// ---------------------------------------------------------------------

private const val POLL_INTERVAL_MS = 1000L

private data class CpuCluster(
    val label: String,
    val coreIndices: List<Int>,
    val maxFreqMhz: Int,
    val coreName: String?,
)

/** Facts that cannot change while the app is running — gathered once. */
private data class StaticProfileInfo(
    val modelName: String,
    val androidVersion: String,
    val soc: String,
    val abis: String,
    val cpuCoreCount: Int,
    val cpuClusters: List<CpuCluster>,
    val gpuName: String,
    val gpuVendor: String,
    val gpuDeviceType: String,
    val gpuDriverVersion: String,
    val gpuVramMb: Long,
    val vulkanApiVersion: String,
    val gpuFreqNode: GpuFreqNode?,
    val gpuMaxFreqMhz: Int?,
    val npu: NpuInfo,
)

/** Facts that change second to second — re-read on every poll tick. */
private data class LiveProfileInfo(
    val rootLabel: String,
    val rootTone: StatusTone,
    val shizukuLabel: String,
    val shizukuTone: StatusTone,
    val cpuLoadPercent: Float?,
    val thermal: ThermalReader.Snapshot,
    val clusterCurFreqMhz: Map<String, Int?>,
    val gpuCurFreqMhz: Int?,
    val gpuUtilizationPercent: Float?,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val battery: BatteryInfo,
)

@Composable
fun DeviceProfileScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val static = remember { captureStaticProfileInfo(context) }
    var live by remember { mutableStateOf<LiveProfileInfo?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // CpuLoadTracker needs two samples to produce a delta, so the very
            // first tick primes it and only the second one paints a real number.
            while (true) {
                live = withContext(Dispatchers.IO) {
                    captureLiveProfileInfo(context, static.cpuClusters, static.gpuFreqNode)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NovaTopBar(
            title = stringResource(R.string.profile_title),
            onBack = { nav.popBackStack() },
        )

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_device))
            Spacer(Modifier.height(10.dp))
            ProfileRow(stringResource(R.string.profile_model), static.modelName)
            ProfileRow(stringResource(R.string.profile_android), static.androidVersion)
            ProfileRow(stringResource(R.string.profile_soc), static.soc)
            ProfileRow(stringResource(R.string.profile_abi), static.abis)
        }

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_access))
            Spacer(Modifier.height(10.dp))
            ProfileStatusRow(stringResource(R.string.profile_root), live?.rootLabel ?: "…", live?.rootTone ?: StatusTone.Neutral)
            Spacer(Modifier.height(6.dp))
            ProfileStatusRow(stringResource(R.string.profile_shizuku), live?.shizukuLabel ?: "…", live?.shizukuTone ?: StatusTone.Neutral)
        }

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_temps))
            Spacer(Modifier.height(10.dp))
            val th = live?.thermal
            val na = stringResource(R.string.profile_value_na)
            ProfileRow(stringResource(R.string.profile_cpu_temp_label), th?.cpuC?.let { "%.1f°C".format(it) } ?: na)
            ProfileRow(stringResource(R.string.profile_gpu_temp), th?.gpuC?.let { "%.1f°C".format(it) } ?: na)
            ProfileRow(stringResource(R.string.profile_battery_temp), th?.batteryC?.let { "%.1f°C".format(it) } ?: na)
            ProfileRow(stringResource(R.string.profile_skin_temp), th?.skinC?.let { "%.1f°C".format(it) } ?: na)
            if (th?.npuC != null) {
                ProfileRow(stringResource(R.string.profile_npu_temp), "%.1f°C".format(th.npuC))
            }
            th?.throttleStatus?.let { st ->
                ProfileRow(stringResource(R.string.profile_thermal_status), thermalStatusLabel(st))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (th?.source) {
                    ThermalReader.Source.SHIZUKU_HAL -> stringResource(R.string.profile_temp_src_shizuku_hal)
                    ThermalReader.Source.SHIZUKU_SYSFS -> stringResource(R.string.profile_temp_src_shizuku_sysfs)
                    ThermalReader.Source.SYSFS -> stringResource(R.string.profile_temp_src_sysfs)
                    ThermalReader.Source.BATTERY_ONLY -> stringResource(R.string.profile_temp_src_battery)
                    else -> stringResource(R.string.profile_temp_src_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (th != null && !th.viaShizuku) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.profile_temp_need_shizuku),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_cpu))
            Spacer(Modifier.height(10.dp))
            ProfileRow(
                stringResource(R.string.profile_cpu_load),
                live?.cpuLoadPercent?.let { "%.0f%%".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_cpu_temp),
                live?.thermal?.cpuC?.let { "%.1f°C".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(stringResource(R.string.profile_cpu_cores), "${static.cpuCoreCount}")
            if (static.cpuClusters.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                static.cpuClusters.forEach { cluster ->
                    val curFreq = live?.clusterCurFreqMhz?.get(cluster.label)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (cluster.coreName != null) {
                                "${cluster.label} ×${cluster.coreIndices.size} (${cluster.coreName})"
                            } else {
                                "${cluster.label} ×${cluster.coreIndices.size}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (curFreq != null) {
                                stringResource(R.string.profile_cluster_freq_format, curFreq, cluster.maxFreqMhz)
                            } else {
                                stringResource(R.string.profile_cluster_freq_max_only_format, cluster.maxFreqMhz)
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_memory))
            Spacer(Modifier.height(10.dp))
            val used = live?.ramUsedMb
            val total = live?.ramTotalMb
            ProfileRow(
                stringResource(R.string.profile_ram_used),
                if (used != null) "%.1f GB".format(used / 1024f) else stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_ram_free),
                if (used != null && total != null) "%.1f GB".format((total - used) / 1024f) else stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_ram_total),
                if (total != null) "%.1f GB".format(total / 1024f) else stringResource(R.string.profile_value_na),
            )
        }

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_gpu))
            Spacer(Modifier.height(10.dp))
            ProfileRow(stringResource(R.string.profile_gpu_name), static.gpuName)
            ProfileRow(stringResource(R.string.profile_gpu_vendor), static.gpuVendor)
            ProfileRow(stringResource(R.string.profile_gpu_type), static.gpuDeviceType)
            ProfileRow(stringResource(R.string.profile_gpu_driver), static.gpuDriverVersion)
            run {
                val curGpuMhz = live?.gpuCurFreqMhz
                val maxGpuMhz = static.gpuMaxFreqMhz
                ProfileRow(
                    stringResource(R.string.profile_gpu_clock),
                    when {
                        curGpuMhz != null && maxGpuMhz != null ->
                            stringResource(R.string.profile_cluster_freq_format, curGpuMhz, maxGpuMhz)
                        maxGpuMhz != null ->
                            stringResource(R.string.profile_cluster_freq_max_only_format, maxGpuMhz)
                        else -> stringResource(R.string.profile_value_na)
                    },
                )
            }
            ProfileRow(
                stringResource(R.string.profile_gpu_load),
                live?.gpuUtilizationPercent?.let { "%.0f%%".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_gpu_temp),
                live?.thermal?.gpuC?.let { "%.1f°C".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_gpu_vram),
                if (static.gpuVramMb >= 0) "${static.gpuVramMb} MB" else stringResource(R.string.profile_value_na),
            )
            ProfileRow(stringResource(R.string.profile_vulkan_api), static.vulkanApiVersion)
        }

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_npu))
            Spacer(Modifier.height(10.dp))
            ProfileStatusRow(
                stringResource(R.string.profile_npu_present),
                if (static.npu.present) stringResource(R.string.profile_npu_yes) else stringResource(R.string.profile_npu_no),
                if (static.npu.present) StatusTone.Good else StatusTone.Neutral,
            )
            Spacer(Modifier.height(6.dp))
            if (static.npu.present) {
                ProfileRow(stringResource(R.string.profile_npu_name), static.npu.name)
                static.npu.detail?.let { ProfileRow(stringResource(R.string.profile_npu_vendor), it) }
                static.npu.source?.let { ProfileRow(stringResource(R.string.profile_npu_source), it) }
            }
        }

        NovaCard {
            SectionHeader(eyebrow = stringResource(R.string.profile_section_display))
            Spacer(Modifier.height(10.dp))
            val w = live?.screenWidth
            val h = live?.screenHeight
            ProfileRow(
                stringResource(R.string.profile_display_resolution),
                if (w != null && h != null) "$w × $h" else stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_display_refresh),
                live?.refreshRateHz?.takeIf { it > 0f }?.let { "%.0f Hz".format(it) } ?: stringResource(R.string.profile_value_na),
            )
            ProfileRow(
                stringResource(R.string.profile_display_density),
                live?.densityDpi?.let { "$it dpi" } ?: stringResource(R.string.profile_value_na),
            )
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    // Long values (full GPU driver strings, long SoC names, etc) used to be
    // clipped by the screen edge since neither Text could wrap or shrink.
    // Giving each side a weight lets the value wrap onto a second line
    // instead of being cut off.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1.2f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ProfileStatusRow(label: String, value: String, tone: StatusTone) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill(label = value, tone = tone)
    }
}

// -------------------------------------------------------------------
// Data gathering. Everything here is best-effort and wrapped so a
// single unreadable sysfs node (varies wildly across OEM/kernel
// builds) never crashes the screen — it just shows "N/A" for that row.
// -------------------------------------------------------------------

private fun captureStaticProfileInfo(context: Context): StaticProfileInfo {
    val modelName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    val androidVersion = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    val soc = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}".trim()
        } else {
            "${android.os.Build.HARDWARE} / ${android.os.Build.BOARD}"
        }
    }.getOrDefault("unknown")
    val abis = android.os.Build.SUPPORTED_ABIS.joinToString(", ")

    val coreIndices = detectCpuCoreIndices()
    val clusters = buildCpuClusters(coreIndices)

    val gpuName = runCatching { NativeBridge.getVulkanGpuName(0) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    val gpuVendor = runCatching { NativeBridge.getGpuVendor() }.getOrDefault("unknown")
    val gpuDeviceType = runCatching { NativeBridge.getGpuDeviceType() }.getOrDefault("unknown")
    val gpuDriverVersion = runCatching { NativeBridge.getGpuDriverVersion() }.getOrDefault("unknown")
    val gpuVramMb = runCatching { NativeBridge.getGpuVramMb() }.getOrDefault(-1L)
    val vulkanApiVersion = runCatching { NativeBridge.getVulkanApiVersion() }.getOrDefault("unknown")

    // Resolved once — which sysfs node (if any) actually exposes this
    // device's GPU frequency varies by vendor/kernel, so we probe for it
    // a single time and reuse the same node on every poll tick instead of
    // re-scanning /sys/class/devfreq every second.
    val gpuFreqNode = findGpuFreqNode()
    val gpuMaxFreqMhz = gpuFreqNode?.let { gpuFreqMhz(it, it.maxPath) }

    val npu = detectNpu()

    return StaticProfileInfo(
        modelName = modelName,
        androidVersion = androidVersion,
        soc = soc,
        abis = abis,
        cpuCoreCount = coreIndices.size,
        cpuClusters = clusters,
        gpuName = gpuName,
        gpuVendor = gpuVendor,
        gpuDeviceType = gpuDeviceType,
        gpuDriverVersion = gpuDriverVersion,
        gpuVramMb = gpuVramMb,
        vulkanApiVersion = vulkanApiVersion,
        gpuFreqNode = gpuFreqNode,
        gpuMaxFreqMhz = gpuMaxFreqMhz,
        npu = npu,
    )
}

private suspend fun captureLiveProfileInfo(
    context: Context,
    clusters: List<CpuCluster>,
    gpuFreqNode: GpuFreqNode?,
): LiveProfileInfo {
    val (rootLabel, rootTone) = readRootStatus()
    val (shizukuLabel, shizukuTone) = readShizukuStatus()

    val clusterFreqs = clusters.associate { cluster ->
        val freqs = cluster.coreIndices.mapNotNull { cpuCurFreqKhz(it) }
        cluster.label to if (freqs.isEmpty()) null else (freqs.average() / 1000.0).toInt()
    }
    val gpuCurFreqMhz = gpuFreqNode?.let { gpuFreqMhz(it, it.curPath) }
    val gpuUtilizationPercent = readGpuUtilizationPercent()
    val cpuLoadPercent = CpuLoadTracker.sample()
    val thermal = ThermalReader.read(context)

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    runCatching { am?.getMemoryInfo(memInfo) }
    val totalMb = memInfo.totalMem / (1024 * 1024)
    val availMb = memInfo.availMem / (1024 * 1024)
    val usedMb = (totalMb - availMb).coerceAtLeast(0)

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val metrics = DisplayMetrics()
    val refreshRate = runCatching {
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.let {
            it.getRealMetrics(metrics)
            it.refreshRate
        } ?: 0f
    }.getOrDefault(0f)

    val battery = captureBatteryInfo(context)

    return LiveProfileInfo(
        rootLabel = rootLabel,
        rootTone = rootTone,
        shizukuLabel = shizukuLabel,
        shizukuTone = shizukuTone,
        cpuLoadPercent = cpuLoadPercent,
        thermal = thermal,
        clusterCurFreqMhz = clusterFreqs,
        gpuCurFreqMhz = gpuCurFreqMhz,
        gpuUtilizationPercent = gpuUtilizationPercent,
        ramUsedMb = usedMb,
        ramTotalMb = totalMb,
        screenWidth = metrics.widthPixels,
        screenHeight = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        refreshRateHz = refreshRate,
        battery = battery,
    )
}

private fun readRootStatus(): Pair<String, StatusTone> {
    val granted = runCatching { com.topjohnwu.superuser.Shell.isAppGrantedRoot() }.getOrNull()
    return when (granted) {
        true -> "ได้รับสิทธิ์แล้ว" to StatusTone.Good
        false -> "ไม่พร้อมใช้งาน" to StatusTone.Bad
        else -> "ไม่ทราบ" to StatusTone.Neutral
    }
}

private fun readShizukuStatus(): Pair<String, StatusTone> {
    val running = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
    val granted = runCatching { ShizukuDisplayPermission.isShizukuAvailable() }.getOrDefault(false)
    return when {
        granted -> "เชื่อมต่อแล้ว" to StatusTone.Good
        running -> "ต้องการสิทธิ์" to StatusTone.Warn
        else -> "ยังไม่ทำงาน" to StatusTone.Neutral
    }
}

// --- CPU topology / frequency -----------------------------------------

private fun readLongFile(path: String): Long? =
    runCatching { File(path).readText().trim().toLong() }.getOrNull()

private fun parseCpuRange(spec: String): List<Int> {
    val result = mutableListOf<Int>()
    spec.split(",").forEach { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@forEach
        if (trimmed.contains("-")) {
            val bounds = trimmed.split("-")
            val a = bounds.getOrNull(0)?.trim()?.toIntOrNull()
            val b = bounds.getOrNull(1)?.trim()?.toIntOrNull()
            if (a != null && b != null) result.addAll(a..b)
        } else {
            trimmed.toIntOrNull()?.let { result.add(it) }
        }
    }
    return result
}

private fun detectCpuCoreIndices(): List<Int> {
    val possible = runCatching { File("/sys/devices/system/cpu/possible").readText().trim() }.getOrNull()
    val parsed = possible?.let { parseCpuRange(it) }
    return if (!parsed.isNullOrEmpty()) parsed else (0 until Runtime.getRuntime().availableProcessors()).toList()
}

private fun cpuMaxFreqKhz(cpu: Int): Int? =
    readLongFile("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")?.toInt()

private fun cpuCurFreqKhz(cpu: Int): Int? =
    readLongFile("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")?.toInt()
        ?: readLongFile("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_cur_freq")?.toInt()

private fun clusterLabels(n: Int): List<String> = when (n) {
    0 -> emptyList()
    1 -> listOf("ทุกคอร์")
    2 -> listOf("เล็ก", "ใหญ่")
    3 -> listOf("เล็ก", "กลาง", "ใหญ่")
    else -> List(n) { i ->
        when (i) {
            0 -> "เล็ก"
            n - 1 -> "หลัก"
            n - 2 -> "ใหญ่"
            else -> "กลาง ${i}"
        }
    }
}

private fun buildCpuClusters(coreIndices: List<Int>): List<CpuCluster> {
    val coreNames = readCpuCoreNames()
    val byMaxFreq = coreIndices
        .mapNotNull { cpu -> cpuMaxFreqKhz(cpu)?.let { cpu to it } }
        .groupBy({ it.second }, { it.first })
        .toSortedMap()
    if (byMaxFreq.isEmpty()) {
        return if (coreIndices.isEmpty()) {
            emptyList()
        } else {
            listOf(CpuCluster("ทุกคอร์", coreIndices, 0, coreNames[coreIndices.firstOrNull()]))
        }
    }
    val freqs = byMaxFreq.keys.toList()
    val labels = clusterLabels(freqs.size)
    return freqs.mapIndexed { i, freqKhz ->
        val cores = byMaxFreq.getValue(freqKhz)
        CpuCluster(labels[i], cores, freqKhz / 1000, coreNames[cores.firstOrNull()])
    }
}

// --- CPU core model name (ARM "CPU part" id, decoded per processor) ----

// ARM implementer 0x41 part-number -> marketing core name. Not exhaustive,
// just the cores actually seen in phones/tablets; unknown ids fall back to
// showing the raw hex so it's still informative instead of blank.
private val ARM_CORE_NAMES = mapOf(
    "0xd03" to "Cortex-A53",
    "0xd04" to "Cortex-A35",
    "0xd05" to "Cortex-A55",
    "0xd06" to "Cortex-A65",
    "0xd07" to "Cortex-A57",
    "0xd08" to "Cortex-A72",
    "0xd09" to "Cortex-A73",
    "0xd0a" to "Cortex-A75",
    "0xd0b" to "Cortex-A76",
    "0xd0c" to "Neoverse-N1",
    "0xd0d" to "Cortex-A77",
    "0xd0e" to "Cortex-A76AE",
    "0xd40" to "Neoverse-V1",
    "0xd41" to "Cortex-A78",
    "0xd42" to "Cortex-A78AE",
    "0xd44" to "Cortex-X1",
    "0xd46" to "Cortex-A510",
    "0xd47" to "Cortex-A710",
    "0xd48" to "Cortex-X2",
    "0xd49" to "Neoverse-N2",
    "0xd4a" to "Neoverse-E1",
    "0xd4b" to "Cortex-A78C",
    "0xd4c" to "Cortex-X1C",
    "0xd4d" to "Cortex-A715",
    "0xd4e" to "Cortex-X3",
    "0xd80" to "Cortex-A520",
    "0xd81" to "Cortex-A720",
    "0xd82" to "Cortex-X4",
)

// Qualcomm Kryo / Samsung / Apple etc. don't expose useful "CPU part" ids
// (Qualcomm reuses ARM stock ids for the underlying core, so those are
// already covered above via ARM_CORE_NAMES).
private fun decodeCoreName(implementer: String?, part: String?): String? {
    if (part == null) return null
    if (implementer == "0x41") return ARM_CORE_NAMES[part] ?: part
    return part
}

// Parses /proc/cpuinfo's per-processor blocks for "CPU implementer" and
// "CPU part", returning a map of cpu index -> decoded core name. This file
// is world-readable on every Android build we've seen (unlike /proc/stat),
// so no root/Shizuku is needed.
private fun readCpuCoreNames(): Map<Int, String> {
    val text = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return emptyMap()
    val result = mutableMapOf<Int, String>()
    var currentCpu: Int? = null
    var implementer: String? = null
    var part: String? = null

    fun flush() {
        val cpu = currentCpu ?: return
        decodeCoreName(implementer, part)?.let { result[cpu] = it }
    }

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            flush()
            currentCpu = null
            implementer = null
            part = null
            continue
        }
        val split = line.split(":", limit = 2)
        if (split.size != 2) continue
        val key = split[0].trim()
        val value = split[1].trim()
        when (key) {
            "processor" -> currentCpu = value.toIntOrNull()
            "CPU implementer" -> implementer = value
            "CPU part" -> part = value
        }
    }
    flush()
    return result
}

// --- GPU clock -----------------------------------------------------------
// Unlike CPU frequency, there's no single standard sysfs path for GPU
// clock — it varies by vendor/kernel (Adreno's kgsl node, generic devfreq,
// Samsung's Mali node, etc). This probes the common ones in priority order
// and falls back to scanning /sys/class/devfreq for anything that names
// itself as a GPU. Returns null (shown as N/A) if nothing readable is
// found, which is expected on plenty of devices (locked-down SELinux, no
// devfreq node exposed, etc) — same best-effort spirit as the CPU/temp
// readers above.

private data class GpuFreqNode(val curPath: String, val maxPath: String, val scaleDivisor: Long)

private val GPU_FREQ_CANDIDATE_PATHS = listOf(
    // Adreno (Qualcomm), modern kernels — devfreq-based governor.
    "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq" to "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
    // Adreno (Qualcomm), legacy kernels — direct gpuclk node.
    "/sys/class/kgsl/kgsl-3d0/gpuclk" to "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
    // Samsung Mali (Exynos) direct clock node.
    "/sys/kernel/gpu/gpu_clock" to "/sys/kernel/gpu/gpu_max_clock",
)

// Different vendors/kernels report this node in Hz, kHz, or already in MHz —
// this is what previously showed "312000 MHz" / "949000 MHz" on Samsung
// Exynos: the fixed Mali candidate assumed the node was already-MHz, but on
// this kernel it's actually kHz. Rather than re-guess the unit on every
// 1-second poll, read the max-frequency value once up front — it's static
// for the life of the device — work out which unit *this* node is reporting
// in (real mobile GPU clocks land in roughly the 50–4000 MHz band), and
// reuse that same divisor for every later cur_freq read.
private fun detectScaleDivisor(reference: Long): Long {
    var divisor = 1L
    var v = reference
    while (v > 4000L) {
        v /= 1000L
        divisor *= 1000L
    }
    return divisor
}

private fun resolveGpuFreqNode(curPath: String, maxPath: String): GpuFreqNode? {
    val rawCur = readLongFile(curPath)
    val rawMax = readLongFile(maxPath)
    // Prefer max as the reference: it doesn't move, so the unit it implies
    // can't be thrown off by catching the GPU mid-ramp on a busy poll.
    val reference = rawMax?.takeIf { it > 0L } ?: rawCur?.takeIf { it > 0L } ?: return null
    return GpuFreqNode(curPath, maxPath, detectScaleDivisor(reference))
}

private fun findGpuFreqNode(): GpuFreqNode? {
    GPU_FREQ_CANDIDATE_PATHS.firstOrNull { (curPath, _) -> readLongFile(curPath) != null }
        ?.let { (curPath, maxPath) -> resolveGpuFreqNode(curPath, maxPath)?.let { return it } }

    // Fallback: scan every registered devfreq device for one that
    // self-identifies as the GPU (MediaTek Mali, generic Mali devfreq
    // governors, etc all show up here under varying node names).
    val dirs = runCatching { File("/sys/class/devfreq").listFiles() }.getOrNull() ?: return null
    for (dir in dirs) {
        val name = runCatching { File(dir, "name").readText().trim().lowercase() }
            .getOrNull() ?: dir.name.lowercase()
        val looksLikeGpu = name.contains("gpu") || name.contains("mali") || name.contains("kgsl")
        if (!looksLikeGpu) continue
        val curPath = File(dir, "cur_freq").absolutePath
        val maxPath = File(dir, "max_freq").absolutePath
        if (readLongFile(curPath) != null) {
            resolveGpuFreqNode(curPath, maxPath)?.let { return it }
        }
    }
    return null
}

private fun gpuFreqMhz(node: GpuFreqNode, path: String): Int? {
    val raw = readLongFile(path) ?: return null
    if (raw <= 0L) return null
    return (raw / node.scaleDivisor).toInt()
}

// --- GPU utilization (busy %) --------------------------------------------
// Frequency alone doesn't tell you how hard the GPU is actually working (a
// GPU can be pinned to max clock while mostly idle waiting on vsync), so
// this is a separate node from GPU clock — vendors that expose one usually
// don't expose it at the same path as the frequency node.

private val GPU_LOAD_CANDIDATE_PATHS = listOf(
    // Adreno (Qualcomm), modern kernels — plain "NN %" or "NN".
    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
    // Adreno (Qualcomm), legacy kernels — "busy_cycles total_cycles" pair.
    "/sys/class/kgsl/kgsl-3d0/gpubusy",
    // Samsung Mali (Exynos) — naming varies by kernel version.
    "/sys/kernel/gpu/utilization",
    "/sys/kernel/gpu/gpu_busy",
)

private fun parseGpuBusyPercent(raw: String): Float? {
    val trimmed = raw.trim()
    val parts = trimmed.split(Regex("\\s+"))
    // "busy_cycles total_cycles" pair (older Adreno gpubusy node).
    if (parts.size == 2) {
        val busy = parts[0].toLongOrNull()
        val total = parts[1].toLongOrNull()
        if (busy != null && total != null && total > 0) {
            return ((busy.toFloat() / total.toFloat()) * 100f).coerceIn(0f, 100f)
        }
    }
    // Everything else: a single number, optionally with a trailing "%".
    return trimmed.removeSuffix("%").trim().toFloatOrNull()?.coerceIn(0f, 100f)
}

private fun readGpuUtilizationPercent(): Float? {
    for (path in GPU_LOAD_CANDIDATE_PATHS) {
        val raw = runCatching { File(path).readText() }.getOrNull() ?: continue
        parseGpuBusyPercent(raw)?.let { return it }
    }
    // Fallback: some vendors (MediaTek Mali in particular) expose a plain
    // "load" file right next to the devfreq node itself.
    val dirs = runCatching { File("/sys/class/devfreq").listFiles() }.getOrNull() ?: return null
    for (dir in dirs) {
        val name = runCatching { File(dir, "name").readText().trim().lowercase() }
            .getOrNull() ?: dir.name.lowercase()
        val looksLikeGpu = name.contains("gpu") || name.contains("mali") || name.contains("kgsl")
        if (!looksLikeGpu) continue
        val raw = runCatching { File(dir, "load").readText() }.getOrNull() ?: continue
        parseGpuBusyPercent(raw)?.let { return it }
    }
    return null
}

// --- NPU / AI accelerator detection ---------------------------------------
// Evidence only. No SoC/model lookup is used here. A missing file means only
// "not detected from accessible evidence"; it does NOT mean the physical NPU
// does not exist. Android/vendor sandboxing can hide driver files.

private data class NpuInfo(
    val present: Boolean,
    val name: String,
    val detail: String?,
    val source: String?,
)

private data class NpuEvidence(
    val source: String,
    val name: String,
    val path: String,
)

private val NPU_KEYWORDS = listOf(
    "npu", "neural", "apu", "mdla", "vpu", "hexagon", "cdsp", "adsp", "edgetpu", "dsp"
)

private fun looksLikeNpuName(value: String): Boolean {
    val s = value.lowercase()
    return NPU_KEYWORDS.any { s.contains(it) }
}

private fun findNpuEvidence(): NpuEvidence? {
    // 1) devfreq: a live accelerator clock domain is strong local evidence.
    val devfreqDirs = runCatching { File("/sys/class/devfreq").listFiles() }.getOrNull().orEmpty()
    for (dir in devfreqDirs) {
        val name = runCatching { File(dir, "name").readText().trim() }.getOrNull()
            ?: dir.name
        if (looksLikeNpuName(name)) {
            return NpuEvidence("sysfs devfreq", name, dir.absolutePath)
        }
    }

    // 2) Accessible device nodes. We do not claim a vendor from a filename;
    // the kernel-visible node itself is the evidence.
    val dev = runCatching { File("/dev").listFiles() }.getOrNull().orEmpty()
    dev.firstOrNull { looksLikeNpuName(it.name) }?.let {
        return NpuEvidence("device node", it.name, it.absolutePath)
    }

    // 3) Vendor libraries/HALs. Scan the actual filesystem instead of a
    // hard-coded SoC table. This catches OEM-specific filenames too.
    val roots = listOf("/vendor/lib", "/vendor/lib64", "/vendor/lib/hw", "/vendor/lib64/hw", "/system/lib", "/system/lib64")
    for (root in roots) {
        val dir = File(root)
        val files = runCatching { dir.listFiles() }.getOrNull().orEmpty()
        files.firstOrNull { it.isFile && looksLikeNpuName(it.name) }?.let {
            return NpuEvidence("filesystem library/HAL", it.name, it.absolutePath)
        }
    }

    // 4) Search accessible sysfs device names. Limit depth/work: this runs
    // once when the screen is created, never every second.
    val sysRoots = listOf(File("/sys/devices"), File("/sys/class"))
    for (root in sysRoots) {
        val evidence = runCatching {
            root.walkTopDown()
                .maxDepth(4)
                .filter { it.isDirectory }
                .firstOrNull { looksLikeNpuName(it.name) }
        }.getOrNull()
        if (evidence != null) {
            return NpuEvidence("sysfs device path", evidence.name, evidence.absolutePath)
        }
    }
    return null
}

private fun detectNpu(): NpuInfo {
    val evidence = findNpuEvidence() ?: return NpuInfo(
        present = false,
        name = "ไม่พบจากไฟล์/โหนดที่แอปเข้าถึงได้",
        detail = "ไม่ได้ใช้ฐานข้อมูล SoC",
        source = "local filesystem scan",
    )
    return NpuInfo(
        present = true,
        name = evidence.name,
        detail = evidence.path,
        source = evidence.source,
    )
}

// --- Battery: charge level, capacity, charging status, power flow --------
// Every value here is either read directly from an Android API or a
// sysfs node — nothing is estimated or guessed. If a real source isn't
// available, the field is null and the UI shows "N/A" rather than a
// computed/interpolated stand-in.

private data class CurrentInterpretation(
    val raw: Long,
    val microAmpMilliamp: Float?,
    val milliAmpMilliamp: Float?,
    val microAmpPowerW: Float?,
    val milliAmpPowerW: Float?,
    val selectedMa: Float?,
    val selectedPowerW: Float?,
    val source: String?,
    val reason: String?,
)

private data class BatteryInfo(
    val percent: Int?,
    val statusLabel: String,
    val healthLabel: String,
    val technology: String?,
    val temperatureC: Float?,
    val pluggedLabel: String,
    val voltageNowMv: Int?,
    val currentNowMa: Float?,
    val currentSource: String?,
    val currentReason: String?,
    val currentRaw: Long?,
    val powerNowW: Float?,
    val powerSource: String?,
    val chargeNowMah: Int?,
    val chargeFullMah: Int?,
    val chargeFullDesignMah: Int?,
    val healthPercent: Float?,
    val cycleCount: Int?,
    val bypassLabel: String,
    val bypassSource: String?,
)

// The kernel exposes exactly one power_supply node with type "Battery";
// its directory name varies by vendor ("battery", "bms", "fg", ...), so
// scan by declared type instead of hardcoding a name.
private fun findBatterySupplyDir(): File? {
    val dirs = runCatching { File("/sys/class/power_supply").listFiles() }.getOrNull() ?: return null
    for (dir in dirs) {
        val type = runCatching { File(dir, "type").readText().trim() }.getOrNull() ?: continue
        if (type.equals("Battery", ignoreCase = true)) return dir
    }
    return null
}

private fun readSysfsText(dir: File?, name: String): String? {
    if (dir == null) return null
    return runCatching { File(dir, name).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}

private fun readSysfsLong(dir: File?, name: String): Long? = readSysfsText(dir, name)?.toLongOrNull()

// Bypass/pass-through charging (power routed straight to the SoC while the
// battery itself isn't being charged, used to reduce heat/wear during
// sustained gaming) has no standard Android API and no universal sysfs
// node — every vendor that supports it names the flag differently. Rather
// than guess from current draw (which is exactly the kind of vague,
// unverified reading to avoid), this only reports "active"/"not active"
// when a known flag file actually exists and is readable; otherwise it
// says plainly that no bypass node was found on this device.
private val BYPASS_FLAG_NAMES = listOf(
    "charge_bypass",
    "bypass_charging",
    "battery_bypass",
    "usb_bypass",
    "charging_bypass",
)

private fun detectBypassCharging(): Pair<String, String?> {
    val supplies = runCatching { File("/sys/class/power_supply").listFiles() }.getOrNull()
        ?: return "ไม่สามารถตรวจสอบได้" to null
    for (dir in supplies) {
        for (flagName in BYPASS_FLAG_NAMES) {
            val file = File(dir, flagName)
            if (!file.exists()) continue
            val raw = runCatching { file.readText().trim() }.getOrNull() ?: continue
            val active = raw == "1" || raw.equals("enabled", ignoreCase = true) || raw.equals("true", ignoreCase = true)
            return (if (active) "กำลังใช้งาน" else "รองรับ แต่ปิดอยู่ตอนนี้") to "${dir.name}/$flagName"
        }
    }
    return "ไม่พบข้อมูล (เครื่องนี้ไม่มี node bypass ที่รู้จัก)" to null
}

private fun captureBatteryInfo(context: Context): BatteryInfo {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val intent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val supplyDir = findBatterySupplyDir()

    // Percent: BatteryManager's CAPACITY property is the canonical source
    // on modern Android; fall back to the sticky-intent level/scale pair
    // only if that property isn't implemented on this device.
    val percentFromProperty = bm
        ?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull() }
        ?.takeIf { it in 0..100 }
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percentFromIntent = if (level in 0..scale && scale > 0) (level * 100) / scale else null
    val percent = percentFromProperty ?: percentFromIntent

    val statusLabel = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "กำลังชาร์จ"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "กำลังใช้งาน (ไม่ชาร์จ)"
        BatteryManager.BATTERY_STATUS_FULL -> "เต็ม"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "เสียบไฟอยู่แต่ไม่ชาร์จ"
        else -> "ไม่ทราบสถานะ"
    }

    val healthLabel = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "ปกติ"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "ร้อนเกินไป"
        BatteryManager.BATTERY_HEALTH_DEAD -> "เสีย"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "แรงดันเกิน"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "ผิดพลาด (ไม่ระบุสาเหตุ)"
        BatteryManager.BATTERY_HEALTH_COLD -> "เย็นเกินไป"
        else -> "ไม่ทราบ"
    }

    val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

    val pluggedLabel = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "อะแดปเตอร์ไฟ (AC)"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "ไร้สาย (Wireless)"
        0 -> "ไม่ได้เสียบ"
        else -> "ไม่ทราบ"
    }

    val tempTenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    val temperatureC = if (tempTenthsC != Int.MIN_VALUE) tempTenthsC / 10f else null

    // Voltage: sysfs voltage_now is µV (highest precision on most kernels);
    // fall back to the intent's EXTRA_VOLTAGE, which Android defines in mV.
    val voltageNowMv = readSysfsLong(supplyDir, "voltage_now")?.let { (it / 1000f).toInt() }
        ?: intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }

    // Current unit detection: evaluate BOTH interpretations (µA and mA)
    // and use measured voltage to calculate both possible powers. This avoids
    // the old fixed threshold/unit guess. A candidate is physically plausible
    // only when its resulting phone power is within 0.01..150 W.
    val currentFromProperty = bm
        ?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull() }
        ?.takeIf { it != Int.MIN_VALUE && it != 0 }
    val sysfsCurrent = readSysfsLong(supplyDir, "current_now")?.takeIf { it != 0L }
    val rawCurrent = currentFromProperty?.toLong() ?: sysfsCurrent
    val currentSourceBase = when {
        currentFromProperty != null -> "BatteryManager.CURRENT_NOW"
        sysfsCurrent != null -> "sysfs current_now"
        else -> null
    }

    val voltageV = voltageNowMv?.div(1000f)
    val currentInterpretation = if (rawCurrent != null && voltageV != null && voltageV > 0f) {
        val uaMa = rawCurrent / 1000f
        val maMa = rawCurrent.toFloat()
        val uaPower = kotlin.math.abs(voltageV * (uaMa / 1000f))
        val maPower = kotlin.math.abs(voltageV * maMa)
        val uaValid = uaPower in 0.01f..150f
        val maValid = maPower in 0.01f..150f
        when {
            uaValid && !maValid -> CurrentInterpretation(
                rawCurrent, uaMa, maMa, uaPower, maPower, uaMa, uaPower,
                currentSourceBase?.plus(" (ตีความเป็น µA)"),
                "สมมติฐาน µA ให้ ${"%.2f".format(uaPower)} W อยู่ในช่วง 0.01–150 W; สมมติฐาน mA ให้ ${"%.2f".format(maPower)} W ซึ่งไม่สมเหตุสมผล"
            )
            !uaValid && maValid -> CurrentInterpretation(
                rawCurrent, uaMa, maMa, uaPower, maPower, maMa, maPower,
                currentSourceBase?.plus(" (ตีความเป็น mA)"),
                "สมมติฐาน mA ให้ ${"%.2f".format(maPower)} W อยู่ในช่วง 0.01–150 W; สมมติฐาน µA ให้ ${"%.2f".format(uaPower)} W ซึ่งไม่สมเหตุสมผล"
            )
            uaValid && maValid -> CurrentInterpretation(
                rawCurrent, uaMa, maMa, uaPower, maPower, uaMa, uaPower,
                currentSourceBase?.plus(" (µA: ambiguous)"),
                "ทั้ง µA (${"%.2f".format(uaPower)} W) และ mA (${"%.2f".format(maPower)} W) อยู่ในช่วงที่เป็นไปได้; เลือก µA ตามหน่วยของ Android/kernel ABI"
            )
            else -> CurrentInterpretation(
                rawCurrent, uaMa, maMa, uaPower, maPower, null, null,
                currentSourceBase,
                "ทั้ง µA (${"%.2f".format(uaPower)} W) และ mA (${"%.2f".format(maPower)} W) อยู่นอกช่วง 0.01–150 W"
            )
        }
    } else null

    // If the kernel exposes power_now, prefer that direct measurement. The
    // standard power_supply ABI uses µW, so convert only the unit, not the
    // value itself. Otherwise use V×I from the selected current interpretation.
    val powerNowRawUw = readSysfsLong(supplyDir, "power_now")
    val directPowerW = powerNowRawUw?.let { kotlin.math.abs(it / 1_000_000f) }
        ?.takeIf { it in 0.01f..150f }
    val powerNowW = directPowerW ?: currentInterpretation?.selectedPowerW
    val powerSource = when {
        directPowerW != null -> "sysfs power_now (µW) — direct kernel value"
        currentInterpretation?.selectedPowerW != null -> "V × I"
        else -> null
    }

    // charge_counter/charge_full/charge_full_design are all µAh from the
    // fuel gauge directly — no derivation. If charge_counter isn't exposed
    // on this kernel, "current capacity" is simply left null (N/A) rather
    // than backed into from percent × charge_full.
    val chargeNowMah = readSysfsLong(supplyDir, "charge_counter")?.let { (it / 1000).toInt() }
    val chargeFullMah = readSysfsLong(supplyDir, "charge_full")?.let { (it / 1000).toInt() }
    val chargeFullDesignMah = readSysfsLong(supplyDir, "charge_full_design")?.let { (it / 1000).toInt() }

    // Health % = actual full-charge capacity ÷ as-designed full-charge
    // capacity — a real ratio of two directly-read fuel-gauge values.
    val healthPercent = if (chargeFullMah != null && chargeFullDesignMah != null && chargeFullDesignMah > 0) {
        (chargeFullMah.toFloat() / chargeFullDesignMah.toFloat()) * 100f
    } else {
        null
    }

    val cycleCount = readSysfsLong(supplyDir, "cycle_count")?.toInt()

    val (bypassLabel, bypassSource) = detectBypassCharging()

    return BatteryInfo(
        percent = percent,
        statusLabel = statusLabel,
        healthLabel = healthLabel,
        technology = technology,
        temperatureC = temperatureC,
        pluggedLabel = pluggedLabel,
        voltageNowMv = voltageNowMv,
        currentNowMa = currentInterpretation?.selectedMa,
        currentSource = currentInterpretation?.source,
        currentReason = currentInterpretation?.reason,
        currentRaw = currentInterpretation?.raw,
        powerNowW = powerNowW,
        powerSource = powerSource,
        chargeNowMah = chargeNowMah,
        chargeFullMah = chargeFullMah,
        chargeFullDesignMah = chargeFullDesignMah,
        healthPercent = healthPercent,
        cycleCount = cycleCount,
        bypassLabel = bypassLabel,
        bypassSource = bypassSource,
    )
}

// --- CPU load (delta of /proc/stat between polls) ----------------------

private object CpuLoadTracker {
    @Volatile private var lastTotal: Long = -1
    @Volatile private var lastIdle: Long = -1
    private val WHITESPACE = Regex("\\s+")

    private fun parseAndTrack(line: String?): Float? {
        if (line == null) return null
        val parts = line.trim().split(WHITESPACE)
        if (parts.isEmpty() || parts[0] != "cpu") return null
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val idle = nums[3] + nums.getOrElse(4) { 0L }
        val total = nums.sum()

        val prevTotal = lastTotal
        val prevIdle = lastIdle
        lastTotal = total
        lastIdle = idle

        if (prevTotal < 0) return null
        val totalDelta = total - prevTotal
        val idleDelta = idle - prevIdle
        if (totalDelta <= 0) return null
        return (((totalDelta - idleDelta).toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
    }

    private fun readProcStatLine(): String? =
        runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull()

    // Some OEM builds (seen on Samsung/Android 15) deny app-level reads of
    // /proc/stat outright, which is what showed as permanent "N/A" CPU load.
    // /proc/loadavg is a different file with looser access rules on most of
    // those builds, and doesn't need Shizuku/root: it's the 1-minute run
    // queue average rather than an instantaneous busy delta, so it reacts
    // more slowly than /proc/stat, but it's a real, locally-measured number
    // instead of a permanent blank.
    private fun readLoadAvgPercent(): Float? {
        val text = runCatching { File("/proc/loadavg").readText() }.getOrNull() ?: return null
        val oneMinuteLoad = text.trim().split(WHITESPACE).firstOrNull()?.toFloatOrNull() ?: return null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return ((oneMinuteLoad / cores) * 100f).coerceIn(0f, 100f)
    }

    suspend fun sample(): Float? =
        parseAndTrack(readProcStatLine())
            ?: readLoadAvgPercent()
            ?: sampleViaShizuku()
            ?: AppCpuTracker.sample()

    // Some OEM builds (Samsung One UI on Android 15 in particular) deny
    // app-level reads of both /proc/stat and /proc/loadavg outright, so
    // when Shizuku is connected, read /proc/stat through its privileged
    // shell instead of giving up and showing a permanent "N/A".
    private suspend fun sampleViaShizuku(): Float? {
        val result = ShizukuDisplayPermission.exec("cat /proc/stat") ?: return null
        if (!result.ok) return null
        val line = result.stdout.lineSequence().firstOrNull { it.trim().startsWith("cpu ") }
        return parseAndTrack(line)
    }
}

// Absolute last resort when neither /proc reads nor Shizuku are available:
// this app's own CPU time isn't gated by SELinux the way system-wide files
// are, so we can still show *something* real instead of a blank "N/A". It's
// this app's usage, not whole-device usage, so it will read lower/different
// than a true system-wide figure, but it's a live, non-fake number.
private object AppCpuTracker {
    @Volatile private var lastCpuTimeMs: Long = -1
    @Volatile private var lastElapsedMs: Long = -1

    fun sample(): Float? {
        val cpuTimeMs = Process.getElapsedCpuTime()
        val elapsedMs = SystemClock.elapsedRealtime()

        val prevCpu = lastCpuTimeMs
        val prevElapsed = lastElapsedMs
        lastCpuTimeMs = cpuTimeMs
        lastElapsedMs = elapsedMs

        if (prevCpu < 0) return null
        val cpuDelta = cpuTimeMs - prevCpu
        val elapsedDelta = elapsedMs - prevElapsed
        if (elapsedDelta <= 0) return null

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return ((cpuDelta.toFloat() / (elapsedDelta.toFloat() * cores)) * 100f).coerceIn(0f, 100f)
    }
}

// --- Temperature -----------------------------------------------------------
// All reads live in ThermalReader (Shizuku first, sysfs / battery as fallbacks).

@Composable
private fun thermalStatusLabel(status: Int): String = stringResource(
    when (status) {
        0 -> R.string.profile_thermal_none
        1 -> R.string.profile_thermal_light
        2 -> R.string.profile_thermal_moderate
        3 -> R.string.profile_thermal_severe
        else -> R.string.profile_thermal_critical
    },
)
