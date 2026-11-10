package app.fluxa

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Network details + speed test for the Network detail page.
 *
 * The test talks to Cloudflare's public speed-test endpoints (speed.cloudflare.com): a download is a
 * stream of zero bytes of a chosen size, an upload is a POST whose body is discarded. The public-IP lookup uses
 * Cloudflare's /cdn-cgi/trace and ipinfo.io. Nothing is sent except the test bytes and a normal HTTPS request.
 */
object NetTools {
    private const val HOST = "speed.cloudflare.com"
    private const val DOWN = "https://$HOST/__down?bytes="
    private const val UP = "https://$HOST/__up"
    private const val TRACE = "https://$HOST/cdn-cgi/trace"

    // ------------------------------------------------------------------ connection info

    class Link(
        val kind: String,            // "Wi-Fi", "มือถือ", "อีเทอร์เน็ต", "VPN", "ออฟไลน์"
        val metered: Boolean,
        val rows: List<Pair<String, String>>,
    )

    @Suppress("DEPRECATION")
    fun link(ctx: Context): Link {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val props = net?.let { cm.getLinkProperties(it) }
        if (net == null || caps == null) return Link("ออฟไลน์", false, emptyList())

        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "มือถือ"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "อีเทอร์เน็ต"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "อื่นๆ"
        }
        val rows = ArrayList<Pair<String, String>>()

        if (kind == "Wi-Fi") {
            runCatching {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                if (info != null) {
                    if (info.linkSpeed > 0) rows += "ความเร็วลิงก์" to "${info.linkSpeed} Mbps"
                    if (info.frequency > 0) {
                        val band = when {
                            info.frequency >= 5925 -> "6 GHz"
                            info.frequency >= 4900 -> "5 GHz"
                            else -> "2.4 GHz"
                        }
                        rows += "ความถี่" to "$band (${info.frequency} MHz)"
                    }
                    if (info.rssi in -127..0) {
                        val lvl = WifiManager.calculateSignalLevel(info.rssi, 5)
                        rows += "สัญญาณ" to "${info.rssi} dBm ($lvl/4)"
                    }
                }
            }
        }
        if (kind == "มือถือ") {
            runCatching {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { rows += "ผู้ให้บริการ" to it }
            }
        }
        val down = caps.linkDownstreamBandwidthKbps
        val up = caps.linkUpstreamBandwidthKbps
        if (down > 0) rows += "แบนด์วิดท์ที่ระบบประเมิน (ลง)" to mbps(down / 1000.0)
        if (up > 0) rows += "แบนด์วิดท์ที่ระบบประเมิน (ขึ้น)" to mbps(up / 1000.0)

        if (props != null) {
            props.interfaceName?.let { rows += "อินเทอร์เฟซ" to it }
            val v4 = props.linkAddresses.map { it.address }.filter { it is java.net.Inet4Address }
            val v6 = props.linkAddresses.map { it.address }.filter { it is java.net.Inet6Address && !it.isLinkLocalAddress }
            v4.firstOrNull()?.hostAddress?.let { rows += "IP ในเครือข่าย (IPv4)" to it }
            v6.firstOrNull()?.hostAddress?.substringBefore('%')?.let { rows += "IP ในเครือข่าย (IPv6)" to it }
            props.routes.firstOrNull { it.isDefaultRoute && it.gateway != null && it.gateway is java.net.Inet4Address }
                ?.gateway?.hostAddress?.let { rows += "เกตเวย์" to it }
            val dns = props.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }
            if (dns.isNotEmpty()) rows += "DNS" to dns.joinToString(", ")
            if (props.mtu > 0) rows += "MTU" to props.mtu.toString()
            props.domains?.takeIf { it.isNotBlank() }?.let { rows += "โดเมน" to it }
        }
        rows += "VPN" to if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "เปิดอยู่" else "ไม่ได้ใช้"
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return Link(kind, metered, rows)
    }

    /** Names and addresses of every up network interface, for the "all interfaces" fallback. */
    fun interfaces(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.map { ni ->
            ni.name to ni.inetAddresses.toList().mapNotNull { it.hostAddress?.substringBefore('%') }.joinToString(", ")
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ public IP

    class PublicInfo(val rows: List<Pair<String, String>>)

    /** Public IP, ISP and location. Blocking; call off the main thread. Null when offline. */
    fun publicInfo(): PublicInfo? {
        val rows = ArrayList<Pair<String, String>>()
        // Cloudflare trace: "key=value" lines, works over IPv4 or IPv6 whichever the phone prefers
        val trace = runCatching { get(TRACE) }.getOrNull()
        val t = HashMap<String, String>()
        trace?.lineSequence()?.forEach { l ->
            val i = l.indexOf('=')
            if (i > 0) t[l.substring(0, i)] = l.substring(i + 1)
        }
        t["ip"]?.let { rows += "IP สาธารณะ" to it }
        // ipinfo adds ISP / city
        val j = runCatching { JSONObject(get("https://ipinfo.io/json")) }.getOrNull()
        if (j != null) {
            if (t["ip"] == null) j.optString("ip").takeIf { it.isNotEmpty() }?.let { rows += "IP สาธารณะ" to it }
            j.optString("org").takeIf { it.isNotEmpty() }?.let { rows += "ผู้ให้บริการ (ISP)" to it }
            val place = listOf(j.optString("city"), j.optString("region"), j.optString("country"))
                .filter { it.isNotEmpty() }.joinToString(", ")
            if (place.isNotEmpty()) rows += "ตำแหน่งโดยประมาณ" to place
            j.optString("timezone").takeIf { it.isNotEmpty() }?.let { rows += "เขตเวลา" to it }
        } else {
            t["loc"]?.let { rows += "ประเทศ" to it }
        }
        t["colo"]?.let { rows += "เซิร์ฟเวอร์ทดสอบ" to "Cloudflare $it" }
        t["http"]?.let { rows += "HTTP" to it }
        t["tls"]?.let { rows += "TLS" to it }
        t["warp"]?.takeIf { it != "off" }?.let { rows += "WARP" to it }
        return if (rows.isEmpty()) null else PublicInfo(rows)
    }

    /** Opens an HTTPS connection; anything that is not https:// is refused. */
    private fun httpsConn(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "https only" }
        return URL(url).openConnection() as HttpURLConnection
    }

    private fun get(url: String): String {
        val c = httpsConn(url)
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.instanceFollowRedirects = false // a redirect could send the request somewhere else (or to plain http)
        c.setRequestProperty("User-Agent", "Fluxa")
        try {
            // small text answers only: cap what is read so a bad server cannot fill memory
            return c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(8192)
                val sb = StringBuilder()
                while (sb.length < 65_536) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ speed test

    class Result(
        val pingMs: Int?, val jitterMs: Int?, val downMbps: Double?, val upMbps: Double?, val bytes: Long,
        val cancelled: Boolean = false,
    )

    interface Listener {
        /** [phase]: 0 latency, 1 download, 2 upload. [mbps] is the live reading (0 during latency). */
        fun onProgress(phase: Int, mbps: Double, progress: Float)
        fun onDone(r: Result)
    }

    class Handle(private val cancelFlag: AtomicBoolean) {
        fun cancel() = cancelFlag.set(true)
    }

    private const val THREADS = 4
    private const val PHASE_MS = 8000L
    private const val WARMUP_MS = 1200L

    /** Runs latency → download → upload. Listener callbacks come from a worker thread. */
    fun runTest(l: Listener): Handle {
        val stop = AtomicBoolean(false)
        thread(name = "speedtest") {
            val bytes = AtomicLong(0)
            var ping: Int? = null
            var jitter: Int? = null
            var down: Double? = null
            var up: Double? = null
            runCatching {
                l.onProgress(0, 0.0, 0f)
                val p = latency(stop)
                ping = p?.first; jitter = p?.second
                if (!stop.get()) down = transfer(false, stop, bytes) { mbps, f -> l.onProgress(1, mbps, f) }
                if (!stop.get()) up = transfer(true, stop, bytes) { mbps, f -> l.onProgress(2, mbps, f) }
            }
            l.onDone(Result(ping, jitter, down, up, bytes.get(), stop.get()))
        }
        return Handle(stop)
    }

    /** Average TCP connect time and jitter (mean change between samples) over 8 handshakes. */
    private fun latency(stop: AtomicBoolean): Pair<Int, Int>? {
        val s = ArrayList<Long>()
        repeat(8) {
            if (stop.get()) return@repeat
            val ms = runCatching {
                Socket().use { sk ->
                    val t0 = System.nanoTime()
                    sk.connect(InetSocketAddress(HOST, 443), 2000)
                    (System.nanoTime() - t0) / 1000L // µs
                }
            }.getOrNull()
            if (ms != null) s += ms
            Thread.sleep(80)
        }
        if (s.size < 3) return null
        val body = s.drop(1) // the first handshake also pays for DNS
        val avg = body.average() / 1000.0
        var j = 0.0
        for (i in 1 until body.size) j += Math.abs(body[i] - body[i - 1]) / 1000.0
        j /= (body.size - 1).coerceAtLeast(1)
        return Math.round(avg).toInt().coerceAtLeast(1) to Math.round(j).toInt()
    }

    /**
     * Parallel transfer for [PHASE_MS]. The speed is measured over a sliding window after a short warm-up,
     * so TCP slow start does not drag the result down. Returns the final Mbps, or null on failure.
     */
    private fun transfer(
        upload: Boolean,
        stop: AtomicBoolean,
        total: AtomicLong,
        report: (Double, Float) -> Unit,
    ): Double? {
        val counted = AtomicLong(0)
        val failed = AtomicLong(0)
        val end = System.currentTimeMillis() + PHASE_MS
        val workers = (0 until THREADS).map {
            thread(name = if (upload) "up-$it" else "down-$it") {
                val buf = ByteArray(64 * 1024)
                while (!stop.get() && System.currentTimeMillis() < end) {
                    val ok = runCatching {
                        if (upload) uploadOnce(buf, end, stop, counted, total)
                        else downloadOnce(buf, end, stop, counted, total)
                    }.isSuccess
                    if (!ok) { failed.incrementAndGet(); Thread.sleep(200) }
                }
            }
        }
        val start = System.currentTimeMillis()
        val ts = ArrayList<Long>()
        val bs = ArrayList<Long>()
        var warm = -1
        var last = 0.0
        while (!stop.get() && System.currentTimeMillis() < end) {
            Thread.sleep(250)
            val now = System.currentTimeMillis()
            val b = counted.get()
            ts += now; bs += b
            val el = now - start
            if (el >= WARMUP_MS && warm < 0) warm = ts.size - 1
            last = if (warm >= 0 && ts.size - 1 > warm) {
                var i = warm
                while (i < ts.size - 1 && now - ts[i] > 2000) i++
                (b - bs[i]) * 8.0 / 1_000_000.0 / ((now - ts[i]) / 1000.0)
            } else {
                // still warming up: a rough number so the dial moves
                b * 8.0 / 1_000_000.0 / (el.coerceAtLeast(1) / 1000.0)
            }
            report(last, (el.toFloat() / PHASE_MS).coerceIn(0f, 1f))
        }
        workers.forEach { runCatching { it.join(1500) } }
        val mbps = if (warm >= 0 && ts.size - 1 > warm) {
            (bs.last() - bs[warm]) * 8.0 / 1_000_000.0 / ((ts.last() - ts[warm]) / 1000.0)
        } else last
        return if (mbps > 0) mbps else null
    }

    private fun downloadOnce(buf: ByteArray, end: Long, stop: AtomicBoolean, counted: AtomicLong, total: AtomicLong) {
        val c = httpsConn(DOWN + 50_000_000)
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("User-Agent", "Fluxa")
        try {
            c.inputStream.use { ins ->
                while (!stop.get() && System.currentTimeMillis() < end) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    counted.addAndGet(n.toLong()); total.addAndGet(n.toLong())
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun uploadOnce(buf: ByteArray, end: Long, stop: AtomicBoolean, counted: AtomicLong, total: AtomicLong) {
        val size = 2L * 1024 * 1024
        val c = httpsConn(UP)
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(size)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("User-Agent", "Fluxa")
        try {
            c.outputStream.use { out ->
                var sent = 0L
                while (sent < size && !stop.get() && System.currentTimeMillis() < end) {
                    val n = minOf(buf.size.toLong(), size - sent).toInt()
                    out.write(buf, 0, n)
                    sent += n
                    counted.addAndGet(n.toLong()); total.addAndGet(n.toLong())
                }
                // a request cut short must still be well-formed, so pad the rest without counting it
                while (sent < size) {
                    val n = minOf(buf.size.toLong(), size - sent).toInt()
                    out.write(buf, 0, n)
                    sent += n
                }
            }
            runCatching { c.inputStream.use { it.readBytes() } }
        } finally {
            c.disconnect()
        }
    }

    fun mbps(v: Double): String = when {
        v >= 100 -> String.format("%.0f Mbps", v)
        v >= 10 -> String.format("%.1f Mbps", v)
        else -> String.format("%.2f Mbps", v)
    }
}
