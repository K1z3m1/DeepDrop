package com.firstt175.novaframe.session.diagnostics

import com.firstt175.novaframe.session.service.NovaForegroundService

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * Lightweight, session-friendly CPU/GPU/RAM sampler for the live HUD overlay.
 *
 * This is deliberately separate from `DeviceProfileScreen`'s settings-screen
 * sampler: that one runs at a slow, UI-driven cadence and has room for a full
 * Shizuku-privileged fallback chain, richer per-cluster CPU topology, and
 * per-cluster CPU topology. The HUD needs none of that — just a few cheap
 * numbers polled every second or so while a session is active — so this
 * keeps its own minimal state instead of exposing DeviceProfileScreen's
 * private trackers. Temperature is the exception: it is shared with the
 * settings screen through [ThermalReader] (Shizuku-first).
 *
 * All three samples are best-effort: any read that fails (locked-down
 * /proc/stat, no vendor GPU-busy node, etc.) just returns null for that one
 * field rather than throwing, so the HUD line simply omits it.
 */
object SystemStatsSampler {

    data class Stats(
        val cpuPercent: Float?,
        val gpuPercent: Float?,
        val ramUsedMb: Long?,
        val ramTotalMb: Long?,
        /** Hottest relevant SoC sensor (CPU → GPU → skin → battery), read through [ThermalReader] (Shizuku first). */
        val tempC: Float? = null,
        /** PowerManager THERMAL_STATUS_* (0 = none … 6 = shutdown) when thermalservice reports it. */
        val throttleStatus: Int? = null,
    )

    // --- CPU load: delta of aggregate /proc/stat between polls -------------
    // Same technique as DeviceProfileScreen's CpuLoadTracker. System-wide, so
    // it reflects total device load (the target app + capture + frame-gen),
    // which is what a HUD reader actually wants to see.
    @Volatile private var lastCpuTotal: Long = -1
    @Volatile private var lastCpuIdle: Long = -1

    private fun readSystemCpuPercent(): Float? {
        val line = runCatching {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        }.getOrNull() ?: return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0] != "cpu") return null
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        // fields: user nice system idle iowait irq softirq steal ...
        val idle = nums[3] + nums.getOrElse(4) { 0L }
        val total = nums.sum()
        val prevTotal = lastCpuTotal
        val prevIdle = lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idle
        if (prevTotal < 0) return null
        val totalDelta = total - prevTotal
        val idleDelta = idle - prevIdle
        if (totalDelta <= 0) return null
        return ((1f - idleDelta.toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
    }

    // Fallback when /proc/stat is locked down by SELinux on an OEM build (same
    // situation DeviceProfileScreen's AppCpuTracker was written for): this
    // app's own CPU time via Process.getElapsedCpuTime() is never gated, but
    // it only reflects this process, not total system load.
    @Volatile private var lastAppCpuTimeMs: Long = -1
    @Volatile private var lastAppElapsedMs: Long = -1

    private fun readAppCpuPercentFallback(): Float? {
        val cpuTimeMs = Process.getElapsedCpuTime()
        val elapsedMs = SystemClock.elapsedRealtime()
        val prevCpu = lastAppCpuTimeMs
        val prevElapsed = lastAppElapsedMs
        lastAppCpuTimeMs = cpuTimeMs
        lastAppElapsedMs = elapsedMs
        if (prevCpu < 0) return null
        val cpuDelta = cpuTimeMs - prevCpu
        val elapsedDelta = elapsedMs - prevElapsed
        if (elapsedDelta <= 0) return null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return ((cpuDelta.toFloat() / (elapsedDelta.toFloat() * cores)) * 100f).coerceIn(0f, 100f)
    }

    // --- GPU utilization (busy %) -------------------------------------------
    // Same vendor node list as DeviceProfileScreen's readGpuUtilizationPercent.
    // No standard cross-vendor API exists for this, so it's best-effort only.
    private val GPU_BUSY_NODES = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/kernel/gpu/utilization",
        "/sys/kernel/gpu/gpu_busy",
    )

    private fun parseGpuBusyPercent(raw: String): Float? {
        val trimmed = raw.trim()
        trimmed.trimEnd('%').toFloatOrNull()?.let { return it.coerceIn(0f, 100f) }
        // Older Adreno "busy_cycles total_cycles" pair form.
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size >= 2) {
            val busy = parts[0].toLongOrNull()
            val total = parts[1].toLongOrNull()
            if (busy != null && total != null && total > 0) {
                return ((busy.toFloat() / total.toFloat()) * 100f).coerceIn(0f, 100f)
            }
        }
        return null
    }

    private fun readGpuPercent(): Float? {
        for (path in GPU_BUSY_NODES) {
            val raw = runCatching { File(path).readText() }.getOrNull() ?: continue
            parseGpuBusyPercent(raw)?.let { return it }
        }
        return null
    }

    /**
     * Resets the delta-based CPU trackers. Call this whenever a new polling
     * session starts (see NovaForegroundService.startStatsPollingIfNeeded) —
     * since this sampler is a singleton, without this a session that starts
     * long after the previous one's last poll would blend that idle gap into
     * its very first CPU% reading.
     */
    fun resetTrackers() {
        lastCpuTotal = -1
        lastCpuIdle = -1
        lastAppCpuTimeMs = -1
        lastAppElapsedMs = -1
    }

    /** Samples all three metrics once. Cheap enough to call on a ~1s HUD timer. */
    fun sample(context: Context): Stats {
        val cpu = readSystemCpuPercent() ?: readAppCpuPercentFallback()
        val gpu = readGpuPercent()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        val ramOk = am != null && runCatching { am.getMemoryInfo(memInfo) }.isSuccess
        val ramUsedMb: Long?
        val ramTotalMb: Long?
        if (ramOk) {
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val availMb = memInfo.availMem / (1024 * 1024)
            ramTotalMb = totalMb
            ramUsedMb = (totalMb - availMb).coerceAtLeast(0)
        } else {
            ramTotalMb = null
            ramUsedMb = null
        }

        // Non-blocking: returns the last snapshot and refreshes in the background, so the
        // 1 Hz HUD timer (main thread) never waits on a Shizuku shell call.
        val thermal = ThermalReader.peek(context)

        return Stats(
            cpuPercent = cpu,
            gpuPercent = gpu,
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
            tempC = thermal.headlineC,
            throttleStatus = thermal.throttleStatus,
        )
    }
}
