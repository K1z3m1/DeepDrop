package com.prirai.android.lr.components

import android.content.Context
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

/**
 * Applies privacy-hardening GeckoView preferences inspired by arkenfox/betterfox.
 *
 * GeckoView 148 exposes only a limited set of typed setter methods on
 * [GeckoRuntimeSettings] / [GeckoRuntimeSettings.Builder]. For preferences that
 * don't have a typed API, we write a Gecko config JSON file
 * (`{"prefs": { "pref.name": value }}`) and pass its path to
 * [GeckoRuntimeSettings.Builder.configFilePath]. These become Gecko default prefs
 * that are still overridable by the user via about:config.
 *
 * Safe browsing is intentionally excluded — it is controlled by UserPreferences.safeBrowsing.
 *
 * Usage:
 * ```
 * // 1. Before builder.build()
 * UserJsPreferences.applyTo(applicationContext, builder)
 * val runtimeSettings = builder.<other methods>.build()
 *
 * // 2. After builder.build(), apply typed setters
 * UserJsPreferences.applyTypedSettings(runtimeSettings)
 * ```
 */
object UserJsPreferences {

    private const val CONFIG_FILE_NAME = "gecko_privacy_config.json"

    // ── Prefs applied via GeckoView config JSON file ──────────────────────────
    // (No typed builder/setter API exists for these in GeckoView 148.)

    private val privacyPrefs = mapOf<String, Any>(
        // Disables navigator.sendBeacon — prevents silent background data leakage
        "beacon.enabled" to false,
        // Send Do-Not-Track request header
        "privacy.donottrackheader.enabled" to true,
        // Clear cookies and HTTP sessions on browser shutdown
        "privacy.clearOnShutdown.cookies" to true,
        "privacy.clearOnShutdown.sessions" to true,
        // Auto-reject cookie consent banners (normal + private browsing)
        "cookiebanners.service.mode" to 1,
        "cookiebanners.service.mode.privateBrowsing" to 1,
    )

    // Classic fingerprint resistance (letterboxing, uniform UA, canvas
    // spoofing...). While RFP is enabled, Gecko forces content
    // `prefers-color-scheme: light` (a uniform fingerprint, bug 1535189) and
    // spoofs canvas/image readbacks, which breaks picture uploads/downloads
    // on some sites (red-line renderings) and the "Web theme" setting. It is
    // therefore user-controlled via Advanced settings and OFF by default.
    private fun rfpPrefs(enabled: Boolean): Map<String, Any> =
        if (enabled) {
            mapOf("privacy.resistFingerprinting" to true)
        } else {
            emptyMap()
        }

    private val telemetryPrefs = mapOf<String, Any>(
        "datareporting.healthreport.uploadEnabled" to false,
        "datareporting.policy.dataSubmissionEnabled" to false,
        "app.normandy.enabled" to false,
        "app.shield.optoutstudies.enabled" to false,
        "browser.ping-centre.telemetry" to false,
    )

    private val networkPrefs = mapOf<String, Any>(
        // Respect the page's referrer policy for cross-origin subresources.
        // Restricting this to same-origin breaks hotlink-protected images on CDNs.
        "network.http.referer.XOriginPolicy" to 0,
        // Do not force-trim cross-origin Referer: let the page's own referrer
        // policy decide (Gecko's default policy is already
        // strict-origin-when-cross-origin). hitomi.la sets
        // no-referrer-when-downgrade and its image CDN checks the Referer.
        "network.http.referer.XOriginTrimmingPolicy" to 0,
        "network.http.sendRefererHeader" to 1,
        // Page-content prefetch stays off: it spends mobile data and asks
        // for pages the user may never open. (The network predictor, which
        // only pre-connects to hosts a site is already known to use, is
        // controlled by the "Faster page loading" toggle: see
        // speculativePrefs below.)
        "network.prefetch-next" to false,
        "network.dns.disablePrefetch" to true,
        "browser.urlbar.speculativeConnect.enabled" to false,
    )

    // "Faster page loading" toggle. The predictor learns, per site, which
    // other hosts a page fetches from and opens those connections as soon as
    // the navigation starts on a repeat visit. The data is a small local
    // database, and it only reaches hosts that page requested before.
    private fun speculativePrefs(enabled: Boolean): Map<String, Any> =
        mapOf("network.predictor.enabled" to enabled)

    private val securityPrefs = mapOf<String, Any>(
        // HTTPS-first: try HTTPS before falling back to HTTP
        "dom.security.https_first" to true,
        // Do not probe HTTP while in HTTPS-only mode
        "dom.security.https_only_mode_send_http_background_request" to false,
        // Block pop-up windows opened while a page is loading
        "dom.disable_open_during_load" to true,
        // Restrict which events may trigger a pop-up
        "dom.popup_allowed_events" to "click dblclick mousedown pointerdown",
        // Enable next-gen storage (prevents cross-site storage tracking)
        "dom.storage.next_gen" to true,
        // Prevent sites from detecting whether assistive technology is active
        "accessibility.force_disabled" to 1,
        // Disable TLS 1.3 0-RTT early data: it is replayable by an attacker.
        // Costs one round trip on resumed connections.
        "security.tls.enable_0rtt_data" to false,
        // Always show punycode for internationalised domain names so
        // look-alike (homograph) hostnames cannot pass as the real site.
        "network.IDN_show_punycode" to true,
        // Disable hyperlink auditing (<a ping>) and navigator pings.
        "browser.send_pings" to false,
        // Typed addresses without a scheme try HTTPS first.
        "dom.security.https_first_schemeless" to true,
        // WebRTC: only expose the default route address, not every local IP.
        "media.peerconnection.ice.default_address_only" to true,
        // Strip known tracking parameters from URLs (normal + private tabs).
        "privacy.query_stripping.enabled" to true,
        "privacy.query_stripping.enabled.pbmode" to true,
        // No JavaScript execution inside PDF documents.
        "pdfjs.enableScripting" to false,
        // Enforce CRLite certificate revocation checks (local, no extra
        // round trip to an OCSP responder).
        "security.pki.crlite_mode" to 2,
    )

    private val mediaPrefs = mapOf<String, Any>(
        // Block all media autoplay by default
        "media.autoplay.blocking_policy" to 2,
        "media.autoplay.default" to 5,
    )

    private val performancePrefs = mapOf<String, Any>(
        // Reduce layout notification bursts for smoother rendering
        "content.notify.interval" to 100000,
        // Keep up to 2 pages in the back/forward cache so Back/Forward is
        // instant instead of a full reload. (Was 0 = bfcache disabled, which
        // made every history navigation re-fetch and re-render the page.)
        // Capped at 2 to bound memory on low-RAM devices.
        "browser.sessionhistory.max_total_viewers" to 2,
        // Do not throttle request starts: lets the browser fire parallel
        // sub-resource requests immediately (faster page load on fast links).
        "network.http.pacing.requests.enabled" to false,
        // TLS 1.3 session resumption tickets: skips full handshakes on
        // repeat visits. No cross-site identifier is exposed (tickets are
        // partitioned per top-level site).
        "network.ssl_tokens_cache_enabled" to true,
        // Honour 103 Early Hints so CSS/JS preloads start before the
        // main response finishes.
        "network.early-hints.enabled" to true,
        // HTTP/3 (QUIC): fewer round trips on supporting servers.
        "network.http.http3.enable" to true,
        // Larger resolver cache; entries still expire (10 min) so stale DNS
        // is bounded. Purely local - no extra network traffic.
        "network.dnsCacheEntries" to 1000,
        "network.dnsCacheExpiration" to 600,
        // Bigger socket buffers improve throughput on large responses.
        "network.buffer.cache.size" to 262144,
        "network.buffer.cache.count" to 128,
        // ── Best WebGL support ──
        // Never silently disable WebGL on blocklisted drivers - attempt
        // hardware WebGL everywhere rather than falling back to "no WebGL"
        "webgl.force-enabled" to true,
        // Do not fail context creation when only a software rasterizer is
        // available (the "major performance caveat") - sites get a working
        // context instead of none
        "webgl.disable-fail-if-major-performance-caveat" to true,
        // ── Page load speed ──
        // More parallel connections / earlier start of non-critical requests
        // (Betterfox values). Only affects connection counts, not memory.
        "network.http.max-connections" to 1800,
        "network.http.max-persistent-connections-per-server" to 10,
        "network.http.max-urgent-start-excessive-connections-per-host" to 5,
        "network.http.request.max-start-delay" to 5,
        // Bigger TLS session-ticket cache -> more resumed handshakes.
        "network.ssl_tokens_cache_capacity" to 10240,
        // Open the connection for a 103 Early Hints preconnect right away.
        "network.early-hints.preconnect.enabled" to true,
        // ── HTTP disk cache ──
        // The mobile Gecko default disk cache can be quite small, so scripts,
        // styles and images from the previous visit may already be evicted.
        // Set the size explicitly: 256 MB (value is in KB) lives in the app cache dir; the
        // OS reclaims it under storage pressure and "Clear cache" empties
        // it. Smart sizing is off so the explicit capacity is honoured.
        "browser.cache.disk.enable" to true,
        "browser.cache.disk.smart_size.enabled" to false,
        "browser.cache.disk.capacity" to 262144,
        // Allow larger single responses (JS bundles, hero images) into it.
        "browser.cache.disk.max_entry_size" to 32768,
        // Compress cached JavaScript bytecode: smaller entries, more of
        // them fit in the cache above.
        "browser.cache.jsbc_compression_level" to 3,
    )

    // Applied only in single-tab (low RAM) mode. These replace the
    // throughput-oriented performancePrefs entries that cost memory.
    private val lowRamOverrides = mapOf<String, Any>(
        // Keep at most 1 page in the back/forward cache (performancePrefs: 2).
        "browser.sessionhistory.max_total_viewers" to 1,
        // Cap the in-memory HTTP cache at 8 MB (value is in KB).
        "browser.cache.memory.capacity" to 8192,
        // Do not pre-launch a spare content process.
        "dom.ipc.processPrelaunch.fission.number" to 0,
        // Low-RAM phones tend to be low-storage too: half-size disk cache.
        "browser.cache.disk.capacity" to 131072,
    )

    // performancePrefs keys that trade RAM for speed; dropped in low RAM mode.
    private val lowRamDroppedKeys = setOf(
        "network.buffer.cache.size",
        "network.buffer.cache.count",
        "network.dnsCacheEntries",
    )

    // ── Config file writing ──────────────────────────────────────────────────

    private fun buildConfigJson(resistFingerprinting: Boolean, lowRam: Boolean, fastLoad: Boolean): String {
        val perf = if (lowRam) {
            (performancePrefs - lowRamDroppedKeys) + lowRamOverrides
        } else {
            performancePrefs
        }
        val allPrefs = privacyPrefs + rfpPrefs(resistFingerprinting) + telemetryPrefs +
                networkPrefs + speculativePrefs(fastLoad) + securityPrefs + mediaPrefs + perf

        return buildString {
            append("{\"prefs\":{")
            allPrefs.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":")
                when (value) {
                    is Boolean -> append(value)
                    is Int -> append(value)
                    is String -> {
                        append("\"")
                        append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                        append("\"")
                    }
                    else -> append(value)
                }
            }
            append("}}")
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Writes the privacy config JSON file and registers it with [builder] via
     * [GeckoRuntimeSettings.Builder.configFilePath]. Must be called **before**
     * [GeckoRuntimeSettings.Builder.build].
     *
     * The write is **content-gated**: we read the existing file (if any) and
     * only call [File.writeText] when its contents differ from the freshly
     * built JSON. Previously this was an unconditional write on every cold
     * start, which is a strict-mode-violating main-thread disk write (the
     * builder itself is constructed on the thread that first resolves the
     * `runtime` lazy, currently Main). The config is only ~800 bytes but on
     * slow flash the open + write + fsync can be 5-30 ms. Reading the file
     * first is cheap (same inode, same block, warm page cache after the first
     * read) and almost always finds the bytes already match, so the hot path
     * becomes read-only.
     */
    fun applyTo(context: Context, builder: GeckoRuntimeSettings.Builder) {
        val rfpEnabled = com.prirai.android.lr.preferences.UserPreferences(context).resistFingerprinting
        val configFile = File(context.filesDir, CONFIG_FILE_NAME)
        val lowRam = com.prirai.android.lr.preferences.UserPreferences(context).sleepingTabsMode ==
            com.prirai.android.lr.preferences.UserPreferences.SLEEPING_TABS_SINGLE
        val fastLoad = com.prirai.android.lr.preferences.UserPreferences(context).fastPageLoad
        val desired = buildConfigJson(rfpEnabled, lowRam, fastLoad)
        val current = runCatching { if (configFile.exists()) configFile.readText() else null }.getOrNull()
        if (current != desired) {
            configFile.writeText(desired)
        }
        builder.configFilePath(configFile.absolutePath)
    }

    /**
     * Applies privacy settings that have a typed setter API on [GeckoRuntimeSettings].
     * Must be called **after** [GeckoRuntimeSettings.Builder.build].
     *
     * These typed setters are belt-and-suspenders on top of the config file approach:
     * they set the live runtime preference value directly, not just the Gecko default.
     *
     * Fingerprinting protection follows the same user preference as the config-file
     * RFP pref; both are applied at engine construction, so toggling the setting in
     * Advanced takes effect on the next app start.
     */
    fun applyTypedSettings(context: Context, settings: GeckoRuntimeSettings) {
        // Enhanced fingerprinting protection for both normal and private browsing
        if (com.prirai.android.lr.preferences.UserPreferences(context).resistFingerprinting) {
            settings.setFingerprintingProtection(true)
            settings.setFingerprintingProtectionPrivateBrowsing(true)
        } else {
            settings.setFingerprintingProtection(false)
            settings.setFingerprintingProtectionPrivateBrowsing(false)
        }
    }
}
