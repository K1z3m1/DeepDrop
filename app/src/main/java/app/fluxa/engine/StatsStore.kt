package app.fluxa

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Local play statistics shown on the profile page: playtime per day / per game and the fps that was
 * shown (min / average / max). Stored as one small JSON string on the device; nothing is uploaded.
 */
object StatsStore {
    private const val PREFS = "fluxa_stats"
    private const val KEY = "data"
    private const val KEEP_DAYS = 120

    /** Totals for one game (or "?" when the foreground app could not be told). */
    class App {
        var sec = 0L
        var samples = 0L
        var outSum = 0.0
        var realSum = 0.0
        var min = 0 // 0 = no sample yet
        var max = 0
        var sessions = 0
        val avgOut: Double get() = if (samples > 0) outSum / samples else 0.0
        val avgReal: Double get() = if (samples > 0) realSum / samples else 0.0
    }

    class Data {
        var sessions = 0
        var longest = 0L
        var generated = 0L
        val days = LinkedHashMap<String, Long>()
        val apps = LinkedHashMap<String, App>()

        val totalSec: Long get() = apps.values.sumOf { it.sec }
        private val samples: Long get() = apps.values.sumOf { it.samples }
        val avgOut: Double get() = samples.let { if (it > 0) apps.values.sumOf { a -> a.outSum } / it else 0.0 }
        val avgReal: Double get() = samples.let { if (it > 0) apps.values.sumOf { a -> a.realSum } / it else 0.0 }
        val minOut: Int get() = apps.values.filter { it.min > 0 }.minOfOrNull { it.min } ?: 0
        val maxOut: Int get() = apps.values.maxOfOrNull { it.max } ?: 0
        val hasFps: Boolean get() = samples > 0

        /** Seconds played on the day [daysAgo] days back (0 = today). */
        fun secOn(daysAgo: Int): Long = days[dayKey(daysAgo)] ?: 0L
        fun secLast(n: Int): Long = (0 until n).sumOf { secOn(it) }
        fun activeDays(n: Int): Int = (0 until n).count { secOn(it) > 0 }
        fun mostPlayed(): List<Pair<String, App>> =
            apps.entries.filter { it.key != "?" || apps.size == 1 }.sortedByDescending { it.value.sec }.map { it.key to it.value }
    }

    fun dayKey(daysAgo: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun load(ctx: Context): Data {
        val d = Data()
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return d
        runCatching {
            val o = JSONObject(raw)
            d.sessions = o.optInt("sessions")
            d.longest = o.optLong("longest")
            d.generated = o.optLong("gen")
            o.optJSONObject("days")?.let { j -> j.keys().forEach { k -> d.days[k] = j.getLong(k) } }
            o.optJSONObject("apps")?.let { j ->
                j.keys().forEach { k ->
                    val a = j.getJSONObject(k)
                    d.apps[k] = App().apply {
                        sec = a.optLong("sec"); samples = a.optLong("n")
                        outSum = a.optDouble("out"); realSum = a.optDouble("real")
                        min = a.optInt("min"); max = a.optInt("max"); sessions = a.optInt("s")
                    }
                }
            }
        }
        return d
    }

    private fun save(ctx: Context, d: Data) {
        val oldest = dayKey(KEEP_DAYS)
        d.days.keys.removeAll { it < oldest }
        val o = JSONObject()
        o.put("sessions", d.sessions); o.put("longest", d.longest); o.put("gen", d.generated)
        o.put("days", JSONObject().also { j -> d.days.forEach { (k, v) -> j.put(k, v) } })
        o.put("apps", JSONObject().also { j ->
            d.apps.forEach { (k, a) ->
                j.put(k, JSONObject().put("sec", a.sec).put("n", a.samples).put("out", a.outSum)
                    .put("real", a.realSum).put("min", a.min).put("max", a.max).put("s", a.sessions))
            }
        })
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    /** One capture session; the service calls [tick] once per second while frames are being shown. */
    class Session(private val ctx: Context) {
        private val data = load(ctx)
        private var sec = 0L
        private var dirty = 0
        private val seen = HashSet<String>()

        fun tick(pkg: String, realFps: Float, outFps: Float, generated: Long) {
            if (++sec == 1L) data.sessions++
            val a = data.apps.getOrPut(pkg) { App() }
            if (seen.add(pkg)) a.sessions++
            a.sec++
            val day = dayKey(0)
            data.days[day] = (data.days[day] ?: 0L) + 1
            data.generated += generated
            if (sec > data.longest) data.longest = sec
            // the first seconds are start-up, not representative of the game's fps
            if (sec >= 3 && outFps >= 1f) {
                a.samples++
                a.outSum += outFps
                a.realSum += realFps
                val o = outFps.roundToInt().coerceAtMost(1000)
                if (a.min == 0 || o < a.min) a.min = o
                if (o > a.max) a.max = o
            }
            if (++dirty >= 10) { dirty = 0; save(ctx, data) }
        }

        fun end() = save(ctx, data)
    }
}
