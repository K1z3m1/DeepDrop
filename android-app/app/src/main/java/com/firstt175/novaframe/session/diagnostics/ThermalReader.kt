package com.firstt175.novaframe.session.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.firstt175.novaframe.session.NovaLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Single source of truth for every temperature number in the app (settings screen + in-game HUD).
 *
 * Reading order, best first:
 *  1. **Shizuku → `dumpsys thermalservice`**: the Thermal HAL's own sensor list. Works on Android
 *     10+ where apps cannot read `/sys/class/thermal` (SELinux), and gives labelled CPU / GPU /
 *     BATTERY / SKIN / NPU / SOC values plus the system throttling status.
 *  2. **Shizuku → `/sys/class/thermal/thermal_zone*`**: raw kernel zones, used for any value the
 *     HAL did not report (some OEM HALs list only battery + skin).
 *  3. **Direct sysfs** (works on older / permissive devices without Shizuku).
 *  4. **BatteryManager** broadcast: always available, battery only.
 *
 * Shizuku is intentionally the *primary* path, not a rescue: the old code only reached for it when
 * the direct read returned nothing, but a partially readable sysfs often returns a wrong zone
 * instead of nothing, so the "fallback" never fired and the UI showed a misleading number.
 *
 * Shizuku shell calls fork a process, so [peek] never blocks: it returns the last snapshot and
 * kicks off a background refresh when the cache is older than [CACHE_TTL_MS]. Suspend callers that
 * can wait (the settings screen already polls on Dispatchers.IO) use [read].
 */
object ThermalReader {

    private const val TAG = "ThermalReader"
    private const val CACHE_TTL_MS = 2_000L

    enum class Source { SHIZUKU_HAL, SHIZUKU_SYSFS, SYSFS, BATTERY_ONLY, NONE }

    data class Snapshot(
        val cpuC: Float? = null,
        val gpuC: Float? = null,
        val batteryC: Float? = null,
        val skinC: Float? = null,
        val npuC: Float? = null,
        /** android.os.PowerManager THERMAL_STATUS_* (0 none … 6 shutdown) as reported by thermalservice. */
        val throttleStatus: Int? = null,
        val source: Source = Source.NONE,
        val takenAtMs: Long = 0L,
    ) {
        /** The single number the HUD shows: CPU, else SoC/GPU hot spot, else skin, else battery. */
        val headlineC: Float? get() = cpuC ?: gpuC ?: skinC ?: batteryC
        val viaShizuku: Boolean get() = source == Source.SHIZUKU_HAL || source == Source.SHIZUKU_SYSFS
    }

    @Volatile private var cached: Snapshot = Snapshot()
    private val refreshing = AtomicBoolean(false)

    /** Last known snapshot; schedules a refresh if stale. Never blocks — safe on the main thread. */
    fun peek(context: Context): Snapshot {
        val snap = cached
        if (SystemClock.elapsedRealtime() - snap.takenAtMs > CACHE_TTL_MS &&
            refreshing.compareAndSet(false, true)
        ) {
            val appCtx = context.applicationContext
            thread(name = "thermal-refresh", isDaemon = true) {
                try {
                    cached = kotlinx.coroutines.runBlocking { sampleNow(appCtx) }
                } catch (t: Throwable) {
                    NovaLog.e(TAG, "refresh failed", t)
                } finally {
                    refreshing.set(false)
                }
            }
        }
        return snap
    }

    /** Fresh snapshot (honours the cache TTL so two screens polling together do not double-fork). */
    suspend fun read(context: Context): Snapshot {
        val snap = cached
        if (SystemClock.elapsedRealtime() - snap.takenAtMs <= CACHE_TTL_MS && snap.source != Source.NONE) return snap
        val fresh = sampleNow(context.applicationContext)
        cached = fresh
        return fresh
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun sampleNow(context: Context): Snapshot {
        val now = SystemClock.elapsedRealtime()
        val batteryIntentC = readBatteryIntentC(context)

        if (ShizukuDisplayPermission.isShizukuAvailable()) {
            // One process for both sources: cheaper than two forks every poll.
            val script = "dumpsys thermalservice 2>/dev/null; echo '$ZONE_MARK'; " +
                "for z in /sys/class/thermal/thermal_zone*; do " +
                "echo \"\$(cat \"\$z/type\" 2>/dev/null):\$(cat \"\$z/temp\" 2>/dev/null)\"; done"
            val out = ShizukuDisplayPermission.exec(script, timeoutMs = 4_000L)?.takeIf { it.ok || it.stdout.isNotEmpty() }?.stdout
            if (out != null) {
                val parts = out.split(ZONE_MARK, limit = 2)
                val hal = parseThermalService(parts[0])
                val zones = parts.getOrNull(1)?.let { parseZones(it) }.orEmpty()
                val merged = merge(hal, zones, batteryIntentC)
                if (merged.headlineC != null) {
                    return merged.copy(
                        source = if (hal.hasTemps) Source.SHIZUKU_HAL else Source.SHIZUKU_SYSFS,
                        takenAtMs = now,
                    )
                }
            }
        }

        val direct = parseZones(readZonesDirect())
        if (direct.isNotEmpty()) {
            val merged = merge(Hal(), direct, batteryIntentC)
            if (merged.cpuC != null || merged.gpuC != null) return merged.copy(source = Source.SYSFS, takenAtMs = now)
        }
        return Snapshot(
            batteryC = batteryIntentC,
            source = if (batteryIntentC != null) Source.BATTERY_ONLY else Source.NONE,
            takenAtMs = now,
        )
    }

    // --- thermalservice parsing ---------------------------------------------------------------

    private class Hal(
        val temps: List<Triple<Int, String, Float>> = emptyList(),   // type, name, °C
        val status: Int? = null,
    ) { val hasTemps: Boolean get() = temps.isNotEmpty() }

    // android.hardware.thermal Temperature.mType values.
    private const val T_CPU = 0
    private const val T_GPU = 1
    private const val T_BATTERY = 2
    private const val T_SKIN = 3
    private const val T_NPU = 9
    private const val T_SOC = 13

    private val TEMP_RE = Regex("""Temperature\{mValue=([-0-9.eE]+|NaN),\s*mType=(\d+),\s*mName=([^,}]*)""")
    private val STATUS_RE = Regex("""Thermal Status:\s*(\d+)""")

    private fun parseThermalService(dump: String): Hal {
        if (dump.isBlank()) return Hal()
        // Prefer the live HAL block; "Cached temperatures" can be minutes old.
        val liveIdx = dump.indexOf("Current temperatures from HAL")
        val region = if (liveIdx >= 0) dump.substring(liveIdx) else dump
        val endIdx = region.indexOf("Current cooling devices").takeIf { it > 0 } ?: region.length
        val scoped = region.substring(0, endIdx)
        val temps = TEMP_RE.findAll(scoped).mapNotNull { m ->
            val v = m.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() && it in -20f..150f } ?: return@mapNotNull null
            Triple(m.groupValues[2].toInt(), m.groupValues[3].trim(), v)
        }.toList()
        val status = STATUS_RE.find(dump)?.groupValues?.get(1)?.toIntOrNull()
        return Hal(temps, status)
    }

    // --- sysfs parsing ------------------------------------------------------------------------

    private data class Zone(val type: String, val c: Float)

    private fun parseZones(text: String): List<Zone> = text.lineSequence().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val type = line.substring(0, idx).trim().lowercase()
        val raw = line.substring(idx + 1).trim().toFloatOrNull() ?: return@mapNotNull null
        val c = if (kotlin.math.abs(raw) > 1000f) raw / 1000f else raw
        if (c !in 0f..150f) return@mapNotNull null
        Zone(type, c)
    }.toList()

    private fun readZonesDirect(): String {
        val dirs = runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
        }.getOrNull() ?: return ""
        return dirs.joinToString("\n") { z ->
            val t = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: return@joinToString ""
            val v = runCatching { File(z, "temp").readText().trim() }.getOrNull() ?: return@joinToString ""
            "$t:$v"
        }
    }

    private fun List<Zone>.hottest(vararg needles: String): Float? =
        filter { z -> needles.any { z.type.contains(it) } }.maxOfOrNull { it.c }

    // --- merge --------------------------------------------------------------------------------

    private fun merge(hal: Hal, zones: List<Zone>, batteryIntentC: Float?): Snapshot {
        fun halMax(type: Int) = hal.temps.filter { it.first == type }.maxOfOrNull { it.third }

        // Most specific zone names first so a "cpu-1-0" wins over a generic "tsens" sensor.
        val cpu = halMax(T_CPU)
            ?: zones.hottest("cpu")
            ?: halMax(T_SOC)
            ?: zones.hottest("soc", "apss", "cluster", "tsens", "big", "little")
        val gpu = halMax(T_GPU) ?: zones.hottest("gpu", "kgsl", "mali", "g3d")
        val battery = halMax(T_BATTERY)
            ?: zones.firstOrNull { it.type == "battery" || it.type.contains("batt") }?.c
            ?: batteryIntentC
        val skin = halMax(T_SKIN) ?: zones.hottest("skin", "xo-therm", "quiet-therm", "case")
        val npu = halMax(T_NPU) ?: zones.hottest("npu", "nsp", "tpu")
        return Snapshot(cpuC = cpu, gpuC = gpu, batteryC = battery, skinC = skin, npuC = npu, throttleStatus = hal.status)
    }

    private fun readBatteryIntentC(context: Context): Float? {
        val intent: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (tenths != Int.MIN_VALUE) tenths / 10f else null
    }

    private const val ZONE_MARK = "@@ZONES@@"
}
