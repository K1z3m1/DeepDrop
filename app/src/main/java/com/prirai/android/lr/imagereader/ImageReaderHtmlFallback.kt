package com.prirai.android.lr.imagereader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Fallback used when the page-side extension can't answer: downloads the
 * page's raw HTML and pulls the images out of it.
 *
 * Limits (why the extension is still preferred): it only sees images present
 * in the server-sent HTML - nothing a script adds later - and it has no
 * access to the browser's cookies, so logged-in or bot-protected pages may
 * return a different (or empty) document.
 */
internal object ImageReaderHtmlFallback {

    private const val TAG = "LRImageReaderHtml"
    private const val MAX_HTML_BYTES = 4 * 1024 * 1024
    private const val TIMEOUT_MS = 12_000
    private const val MAX_ITEMS = 1500
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"

    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SOURCE_TAG = Regex("<source\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ANCHOR_TAG = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_BLOCK = Regex("<script\\b[^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCRIPT_IMG = Regex("https?://[^\"'\\s<>\\\\]+?\\.(?:jpe?g|png|webp|avif|gif)(?:\\?[^\"'\\s<>\\\\]*)?", RegexOption.IGNORE_CASE)
    private val BASE_TAG = Regex("<base\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ATTR = Regex(
        "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))"
    )
    private val IMG_EXT = Regex("\\.(?:jpe?g|png|gif|webp|avif|bmp|jfif)(?:$|[?#])", RegexOption.IGNORE_CASE)
    private val SKIP_NAME = Regex("(?:sprite|spacer|pixel|blank|loading|loader|1x1|favicon|logo|icon)", RegexOption.IGNORE_CASE)
    private val WP_SIZE = Regex("-\\d{2,5}x\\d{2,5}(\\.(?:jpe?g|png|gif|webp|avif))$", RegexOption.IGNORE_CASE)

    private val FULL_ATTRS = listOf(
        "data-original", "data-orig-file", "data-full", "data-full-url", "data-large-file",
        "data-zoom-src", "data-highres", "data-hi-res", "data-original-src", "data-big",
        "data-zoom-image", "data-original-image", "data-src-original", "data-fullsize", "data-hires", "data-image-original",
    )
    private val LAZY_ATTRS = listOf(
        "data-src", "data-lazy-src", "data-lazy", "data-url", "data-echo", "data-img",
        "data-actual-src", "data-ll-src", "data-image", "data-pagespeed-lazy-src",
        "data-lazy", "data-original-image", "data-src-original", "data-fullsize", "data-hires",
    )

    suspend fun load(pageUrl: String, userAgent: String?): ReaderPayload? =
        withContext(Dispatchers.IO) {
            try {
                val ua = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
                val html = download(pageUrl, ua) ?: return@withContext null
                parse(html, pageUrl, ua)
            } catch (t: Throwable) {
                Log.w(TAG, "html fallback failed", t)
                null
            }
        }

    private fun download(pageUrl: String, ua: String): String? {
        val conn = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.8")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val type = conn.contentType.orEmpty()
            if (type.isNotEmpty() && !type.contains("html", true) && !type.contains("xml", true)) return null
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(type)?.groupValues?.get(1)
                ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (out.size() < MAX_HTML_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toString(charset.name())
        } finally {
            conn.disconnect()
        }
    }

    private fun attrs(tag: String): Map<String, String> {
        val map = HashMap<String, String>()
        ATTR.findAll(tag).forEach { m ->
            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            map.putIfAbsent(m.groupValues[1].lowercase(), unescape(v))
        }
        return map
    }

    private fun unescape(s: String) =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&#x2F;", "/").replace("&lt;", "<").replace("&gt;", ">")

    private fun resolve(base: URI, raw: String?): String? {
        val u = raw?.trim().orEmpty()
        if (u.isEmpty()) return null
        if (u.startsWith("data:image/", true)) {
            return if (u.startsWith("data:image/svg", true) || u.length < 4000) null else u
        }
        return try {
            val abs = base.resolve(u.replace(" ", "%20"))
            if (abs.scheme == "http" || abs.scheme == "https") abs.toString() else null
        } catch (e: Exception) {
            null
        }
    }

    /** Largest candidate of a srcset value. */
    private fun bestOfSrcset(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        var best: String? = null
        var bestWeight = -1f
        for (part in srcset.split(Regex(",\\s+"))) {
            val tokens = part.trim().split(Regex("\\s+"))
            val url = tokens.firstOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            var weight = 1f
            tokens.getOrNull(1)?.let { d ->
                Regex("^([\\d.]+)([wx])$", RegexOption.IGNORE_CASE).find(d)?.let { m ->
                    weight = (m.groupValues[1].toFloatOrNull() ?: 1f) *
                        (if (m.groupValues[2].equals("x", true)) 1000f else 1f)
                }
            }
            if (weight >= bestWeight) { bestWeight = weight; best = url }
        }
        return best
    }

    private fun upgrades(url: String): List<String> {
        if (url.startsWith("data:")) return emptyList()
        val out = ArrayList<String>()
        val wp = WP_SIZE.replace(url.substringBefore('?'), "$1")
        if (wp != url.substringBefore('?')) out.add(wp)
        return out
    }

    private fun parse(html: String, pageUrl: String, ua: String): ReaderPayload {
        var base = URI(pageUrl)
        BASE_TAG.find(html)?.let { attrs(it.value)["href"] }?.let { href ->
            runCatching { base.resolve(href) }.getOrNull()?.let { base = it }
        }
        val title = TITLE_TAG.find(html)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()

        // <picture> sources are collected as a pool; they sit next to their <img>.
        val items = ArrayList<Pair<Int, List<String>>>() // position -> candidates
        val seen = HashSet<String>()

        fun add(pos: Int, cands: List<String>) {
            val list = cands.distinct()
            if (list.isEmpty() || items.size >= MAX_ITEMS) return
            val best = list.first()
            if (!seen.add(best)) return
            items.add(pos to list)
        }

        IMG_TAG.findAll(html).forEach { m ->
            val a = attrs(m.value)
            val full = ArrayList<String>()
            FULL_ATTRS.forEach { k -> resolve(base, a[k])?.let(full::add) }

            val bases = ArrayList<String>()
            // The <source> tags right before this <img> inside a <picture>.
            val before = html.substring(maxOf(0, m.range.first - 1200), m.range.first)
            val picStart = before.lastIndexOf("<picture", ignoreCase = true)
            if (picStart >= 0 && !before.substring(picStart).contains("</picture", true)) {
                SOURCE_TAG.findAll(before.substring(picStart)).forEach { s ->
                    val sa = attrs(s.value)
                    resolve(base, bestOfSrcset(sa["srcset"] ?: sa["data-srcset"]))?.let(bases::add)
                }
            }
            resolve(base, bestOfSrcset(a["srcset"] ?: a["data-srcset"] ?: a["data-lazy-srcset"]))?.let(bases::add)
            LAZY_ATTRS.forEach { k -> resolve(base, a[k])?.let(bases::add) }
            resolve(base, a["src"])?.let(bases::add)

            val content = (full + bases.flatMap { upgrades(it) } + bases).distinct()
            val last = content.lastOrNull() ?: return@forEach
            if (last.contains(".svg", true) && !last.startsWith("data:")) return@forEach
            if (SKIP_NAME.containsMatchIn(last.substringAfterLast('/').take(120))) return@forEach
            val w = a["width"]?.toIntOrNull() ?: 0
            val h = a["height"]?.toIntOrNull() ?: 0
            if (w in 1..99 && h in 1..99) return@forEach
            add(m.range.first, content)
        }

        // <a href="big.jpg"> links to full-size images that have no <img>
        // of their own in the markup (galleries).
        ANCHOR_TAG.findAll(html).forEach { m ->
            val href = resolve(base, attrs(m.value)["href"]) ?: return@forEach
            if (IMG_EXT.containsMatchIn(href)) add(m.range.first, listOf(href))
        }

        // Image lists that scripts render later usually ship inside the HTML
        // as data (JSON / JS arrays): scan <script> bodies for absolute image
        // URLs, undoing JSON escaping first.
        SCRIPT_BLOCK.findAll(html).forEach { sm ->
            val body = sm.groupValues[1]
                .replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\/", "/")
            SCRIPT_IMG.findAll(body).forEach { um ->
                val url = um.value
                if (SKIP_NAME.containsMatchIn(url.substringAfterLast('/').take(120))) return@forEach
                resolve(base, url)?.let { r -> add(sm.range.first + um.range.first, upgrades(r) + r) }
            }
        }

        // Single-image pages: og:image as a last resort.
        if (items.isEmpty()) {
            META_TAG.findAll(html).forEach { m ->
                val a = attrs(m.value)
                if (a["property"].equals("og:image", true) || a["name"].equals("twitter:image", true)) {
                    resolve(base, a["content"])?.let { add(m.range.first, listOf(it)) }
                }
            }
        }

        items.sortBy { it.first }
        return ReaderPayload(
            pageUrl = pageUrl,
            title = title.take(200),
            userAgent = ua.take(400),
            images = items.map { ReaderImage(candidates = it.second.take(12), width = 0, height = 0) },
        )
    }
}
