package app.fluxa

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import java.io.File

/**
 * Hardware details for the monitor detail pages: CPU core layout (big / mid / little), GPU identity,
 * memory breakdown. Everything is read from sysfs / procfs or public APIs, so any value a phone
 * hides from apps simply comes back null / empty and the page shows "--" for it.
 */
object SocInfo {
    private fun readText(path: String): String? =
        runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun readLong(path: String): Long = readText(path)?.toLongOrNull() ?: 0L

    // ------------------------------------------------------------------ CPU

    data class Core(
        val index: Int,
        val online: Boolean,
        val curKhz: Long,
        val minKhz: Long,
        val maxKhz: Long,
        val governor: String?,
        val part: Int?,
        val implementer: Int?,
    ) {
        val name: String get() = coreName(implementer, part)
    }

    /** A set of identical cores (same model, same max clock). */
    class Cluster(val label: String, val cores: List<Core>) {
        val maxKhz get() = cores.maxOf { it.maxKhz }
        val minKhz get() = cores.minOf { it.minKhz }
        val name get() = cores.first().name
    }

    /** ARM core model names by "CPU part"; only the common phone cores. */
    private val ARM_PARTS = mapOf(
        0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35", 0xd05 to "Cortex-A55", 0xd07 to "Cortex-A57",
        0xd08 to "Cortex-A72", 0xd09 to "Cortex-A73", 0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76",
        0xd0c to "Neoverse-N1", 0xd0d to "Cortex-A77", 0xd0e to "Cortex-A76AE", 0xd40 to "Neoverse-V1",
        0xd41 to "Cortex-A78", 0xd44 to "Cortex-X1", 0xd46 to "Cortex-A510", 0xd47 to "Cortex-A710",
        0xd48 to "Cortex-X2", 0xd4a to "Neoverse-E1", 0xd4b to "Cortex-A78C", 0xd4c to "Cortex-X1C",
        0xd4d to "Cortex-A715", 0xd4e to "Cortex-X3", 0xd80 to "Cortex-A520", 0xd81 to "Cortex-A720",
        0xd82 to "Cortex-X4", 0xd87 to "Cortex-A725", 0xd85 to "Cortex-X925",
    )

    fun coreName(implementer: Int?, part: Int?): String {
        if (part == null) return "--"
        ARM_PARTS[part]?.takeIf { implementer == null || implementer == 0x41 }?.let { return it }
        return when (implementer) {
            0x51 -> "Qualcomm 0x" + part.toString(16)
            0x41 -> "ARM 0x" + part.toString(16)
            else -> "0x" + part.toString(16)
        }
    }

    /** "CPU implementer" / "CPU part" per processor from /proc/cpuinfo. */
    private fun cpuInfoParts(): Map<Int, Pair<Int?, Int?>> {
        val out = HashMap<Int, Pair<Int?, Int?>>()
        val text = readText("/proc/cpuinfo") ?: return out
        var cur = -1
        var impl: Int? = null
        var part: Int? = null
        fun flush() { if (cur >= 0) out[cur] = impl to part }
        for (line in text.lineSequence()) {
            val k = line.substringBefore(':', "").trim().lowercase()
            val v = line.substringAfter(':', "").trim()
            when (k) {
                "processor" -> { flush(); cur = v.toIntOrNull() ?: -1; impl = null; part = null }
                "cpu implementer" -> impl = v.removePrefix("0x").toIntOrNull(16)
                "cpu part" -> part = v.removePrefix("0x").toIntOrNull(16)
            }
        }
        flush()
        return out
    }

    fun cores(): List<Core> {
        val n = Runtime.getRuntime().availableProcessors()
        val parts = cpuInfoParts()
        val raw = (0 until n).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i/"
            val f = base + "cpufreq/"
            val online = if (i == 0) true else (readText(base + "online") ?: "1") != "0"
            Core(
                index = i,
                online = online,
                curKhz = readLong(f + "scaling_cur_freq"),
                minKhz = maxOf(readLong(f + "cpuinfo_min_freq"), 0L),
                maxKhz = maxOf(readLong(f + "cpuinfo_max_freq"), readLong(f + "scaling_max_freq")),
                governor = readText(f + "scaling_governor"),
                part = parts[i]?.second,
                implementer = parts[i]?.first,
            )
        }
        // a parked core hides its cpufreq files; borrow the limits from a core of the same model
        val limits = raw.filter { it.maxKhz > 0 }.groupBy { it.part }
            .mapValues { (_, l) -> l.maxOf { it.maxKhz } to l.minOf { it.minKhz } }
        return raw.map { c ->
            if (c.maxKhz > 0) c else limits[c.part]?.let { c.copy(maxKhz = it.first, minKhz = it.second) } ?: c
        }
    }

    /**
     * Groups the cores by (model, max clock) and names the groups from the strongest to the weakest:
     * 2 groups = big / little, 3 = big / mid / little, 4 or more = prime / big / mid / little.
     */
    fun clusters(cores: List<Core>): List<Cluster> {
        val groups = cores.groupBy { (it.part ?: -1) to it.maxKhz }
            .values
            .sortedWith(compareByDescending<List<Core>> { it.first().maxKhz }.thenByDescending { it.first().part ?: -1 })
        val n = groups.size
        return groups.mapIndexed { i, g ->
            val label = when {
                n == 1 -> "ทุกคอร์"
                i == 0 && n >= 4 -> "ใหญ่พิเศษ (Prime)"
                i == n - 1 -> "เล็ก"
                n == 2 -> "ใหญ่"
                n == 3 -> if (i == 0) "ใหญ่" else "กลาง"
                else -> if (i <= (n - 1) / 2) "ใหญ่" else "กลาง"
            }
            Cluster(label, g)
        }
    }

    /** Chip / SoC name: Build.SOC_* on Android 12+, otherwise the "Hardware" line of /proc/cpuinfo. */
    fun socName(): String {
        if (Build.VERSION.SDK_INT >= 31) {
            val man = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            val model = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            if (model != null) return listOfNotNull(man, model).joinToString(" ")
        }
        val hw = readText("/proc/cpuinfo")?.lineSequence()
            ?.firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        return hw?.takeIf { it.isNotEmpty() } ?: Build.HARDWARE
    }

    /** Hottest readable thermal zone whose name looks like the CPU, in °C; null when the phone hides them. */
    fun cpuTempC(): Float? = thermal(listOf("cpu", "soc", "mtktscpu"))

    fun gpuTempC(): Float? = thermal(listOf("gpu", "kgsl", "mali"))

    private fun thermal(words: List<String>): Float? {
        var best: Float? = null
        runCatching {
            File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { z ->
                val type = readText(z.path + "/type")?.lowercase() ?: return@forEach
                if (words.none { type.contains(it) }) return@forEach
                val raw = readLong(z.path + "/temp")
                if (raw == 0L) return@forEach
                val c = if (raw > 1000 || raw < -1000) raw / 1000f else raw.toFloat()
                if (c in 5f..130f && (best == null || c > best!!)) best = c
            }
        }
        return best
    }

    /** Battery temperature in °C (always readable); used as a fallback when thermal zones are blocked. */
    fun batteryTempC(ctx: Context): Float? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    // ------------------------------------------------------------------ battery

    /**
     * Everything the system lets an app read about the battery. [currentMa] is always positive (the direction
     * is given by [charging]); [watts] = voltage x current. Any value the phone hides is null.
     */
    class Battery(
        val percent: Int,
        val charging: Boolean,
        val full: Boolean,
        val plugged: Int,
        val health: Int,
        val tempC: Float?,
        val voltV: Float?,
        val currentMa: Int?,
        val watts: Float?,
        val technology: String?,
        val chargeMah: Int?,
        val cycles: Int?,
        val soh: Int?,
    )

    /** Reads the battery state. Cheap, but call it off the main thread. */
    fun battery(ctx: Context): Battery? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level < 0) return null else (level * 100 / scale).coerceIn(0, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it / 10f }
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val volt = if (mv > 0) mv / 1000f else null
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // CURRENT_NOW is in microamps on almost every phone (a few report milliamps); the sign differs by brand
        val raw = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(Int.MIN_VALUE)
        val ma = if (raw == Int.MIN_VALUE || raw == 0) null
        else Math.abs(raw).let { if (it >= 20_000) it / 1000 else it }
        val watts = if (ma != null && volt != null) ma / 1000f * volt else null

        val counter = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(0)
        val chargeMah = if (counter > 0) counter / 1000 else null
        val cycles = if (Build.VERSION.SDK_INT >= 34)
            i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it > 0 } else null
        val soh = if (Build.VERSION.SDK_INT >= 34)
            runCatching { bm.getIntProperty(10 /* BATTERY_PROPERTY_STATE_OF_HEALTH */) }.getOrNull()?.takeIf { it in 1..100 } else null

        return Battery(
            percent = pct,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = plugged,
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            tempC = temp,
            voltV = volt,
            currentMa = ma,
            watts = watts,
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            chargeMah = chargeMah,
            cycles = cycles,
            soh = soh,
        )
    }

    // ------------------------------------------------------------------ all temperatures

    /** One temperature sensor. [group]: SKIN, CPU, GPU, BATTERY or OTHER; [name] is the raw zone name. */
    class TempReading(val group: Int, val name: String, val c: Float)

    const val T_SKIN = 0
    const val T_CPU = 1
    const val T_GPU = 2
    const val T_BATTERY = 3
    const val T_OTHER = 4

    private fun tempGroup(type: String): Int = when {
        listOf("skin", "case", "shell", "quiet", "xo-therm", "xo_therm", "back_cover", "front_cover").any { type.contains(it) } -> T_SKIN
        listOf("gpu", "kgsl", "mali").any { type.contains(it) } -> T_GPU
        listOf("batt", "bms").any { type.contains(it) } -> T_BATTERY
        listOf("cpu", "soc", "cluster", "mtktscpu").any { type.contains(it) } -> T_CPU
        else -> T_OTHER
    }

    /**
     * Every temperature the phone lets the app read: all readable thermal zones (sorted into skin / CPU /
     * GPU / battery / other by zone name) plus the battery temperature from the system. Call off the main thread.
     */
    fun allTemps(ctx: Context): List<TempReading> {
        val out = ArrayList<TempReading>()
        runCatching {
            File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }?.forEach { z ->
                    val type = readText(z.path + "/type")?.lowercase() ?: return@forEach
                    val raw = readText(z.path + "/temp")?.toLongOrNull() ?: return@forEach
                    if (raw == 0L) return@forEach
                    val c = if (raw > 1000 || raw < -1000) raw / 1000f else raw.toFloat()
                    if (c !in 5f..130f) return@forEach
                    out.add(TempReading(tempGroup(type), type, c))
                }
        }
        batteryTempC(ctx)?.let { b ->
            // the system value is the reliable one; drop zone duplicates of the battery only when none is readable
            out.add(0, TempReading(T_BATTERY, "battery (android)", b))
        }
        return out
    }

    /** Android's own overall heat level (0 = none ... 6 = shutdown), or null. */
    fun thermalStatus(ctx: Context): Int? = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
    }.getOrNull()

    // ------------------------------------------------------------------ GPU

    class Gpu(val renderer: String, val vendor: String, val glVersion: String)

    /** Asks OpenGL ES for the GPU name through a 1x1 off-screen context. Call off the main thread. */
    fun gpuIdentity(): Gpu? = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return@runCatching null
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(
            dpy,
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ),
            0, cfgs, 0, 1, num, 0,
        )
        val cfg = cfgs[0] ?: return@runCatching null
        val ctx = EGL14.eglCreateContext(
            dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        val surf = EGL14.eglCreatePbufferSurface(
            dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        try {
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return@runCatching null
            Gpu(
                GLES20.glGetString(GLES20.GL_RENDERER) ?: "--",
                GLES20.glGetString(GLES20.GL_VENDOR) ?: "--",
                GLES20.glGetString(GLES20.GL_VERSION) ?: "--",
            )
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
        }
    }.getOrNull()

    /** "Vulkan 1.3" from the system feature flag, or null when the phone has no Vulkan. */
    fun vulkanVersion(ctx: Context): String? {
        val pm = ctx.packageManager
        val v = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
            ?: return null
        return "Vulkan ${(v shr 22) and 0x7F}.${(v shr 12) and 0x3FF}"
    }

    // ------------------------------------------------------------------ memory

    class Mem(
        val totalKb: Long, val availKb: Long, val cachedKb: Long, val buffersKb: Long,
        val swapTotalKb: Long, val swapFreeKb: Long,
    )

    fun meminfo(): Mem? {
        val text = readText("/proc/meminfo") ?: return null
        val m = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            val k = line.substringBefore(':', "")
            val v = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
            m[k] = v
        }
        val total = m["MemTotal"] ?: return null
        return Mem(
            total, m["MemAvailable"] ?: m["MemFree"] ?: 0L, m["Cached"] ?: 0L, m["Buffers"] ?: 0L,
            m["SwapTotal"] ?: 0L, m["SwapFree"] ?: 0L,
        )
    }
}
