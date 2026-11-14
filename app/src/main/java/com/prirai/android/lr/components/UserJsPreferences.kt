package com.prirai.android.lr.components

import android.content.Context
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File
import com.prirai.android.lr.preferences.UserPreferences

object UserJsPreferences {
    private const val CONFIG_FILE_NAME = "gecko_privacy_config.json"

    private val privacyPrefs = mapOf<String, Any>(
        "beacon.enabled" to false,
        "privacy.donottrackheader.enabled" to true,
        "privacy.clearOnShutdown.cookies" to true,
        "privacy.clearOnShutdown.sessions" to true,
        "cookiebanners.service.mode" to 1,
        "cookiebanners.service.mode.privateBrowsing" to 1,
    )

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
        "network.http.referer.XOriginPolicy" to 0,
        "network.http.referer.XOriginTrimmingPolicy" to 0,
        "network.http.sendRefererHeader" to 1,
        "network.prefetch-next" to false,
        "network.dns.disablePrefetch" to true,
        "browser.urlbar.speculativeConnect.enabled" to false,
    )

    private fun speculativePrefs(enabled: Boolean): Map<String, Any> =
        mapOf("network.predictor.enabled" to enabled)

    private val securityPrefs = mapOf<String, Any>(
        "dom.security.https_first" to true,
        "dom.security.https_only_mode_send_http_background_request" to false,
        "dom.disable_open_during_load" to true,
        "dom.popup_allowed_events" to "click dblclick mousedown pointerdown",
        "dom.storage.next_gen" to true,
        "accessibility.force_disabled" to 1,
        "security.tls.enable_0rtt_data" to false,
        "network.IDN_show_punycode" to true,
        "browser.send_pings" to false,
        "dom.security.https_first_schemeless" to true,
        "media.peerconnection.ice.default_address_only" to true,
        "privacy.query_stripping.enabled" to true,
        "privacy.query_stripping.enabled.pbmode" to true,
        "pdfjs.enableScripting" to false,
        "security.pki.crlite_mode" to 2,
    )

    private val mediaPrefs = mapOf<String, Any>(
        "media.autoplay.blocking_policy" to 2,
        "media.autoplay.default" to 5,
    )

    private val performancePrefs = mapOf<String, Any>(
        "content.notify.interval" to 100000,
        "browser.sessionhistory.max_total_viewers" to 2,
        "network.http.pacing.requests.enabled" to false,
        "network.ssl_tokens_cache_enabled" to true,
        "network.early-hints.enabled" to true,
        "network.http.http3.enable" to true,
        "network.dnsCacheEntries" to 1000,
        "network.dnsCacheExpiration" to 600,
        "network.buffer.cache.size" to 262144,
        "network.buffer.cache.count" to 128,
        "webgl.force-enabled" to true,
        "webgl.disable-fail-if-major-performance-caveat" to true,
        "network.http.max-connections" to 1800,
        "network.http.max-persistent-connections-per-server" to 10,
        "network.http.max-urgent-start-excessive-connections-per-host" to 5,
        "network.http.request.max-start-delay" to 5,
        "network.ssl_tokens_cache_capacity" to 10240,
        "network.early-hints.preconnect.enabled" to true,
        "browser.cache.disk.enable" to true,
        "browser.cache.disk.smart_size.enabled" to false,
        "browser.cache.disk.capacity" to 262144,
        "browser.cache.disk.max_entry_size" to 32768,
        "browser.cache.jsbc_compression_level" to 3,
    )

    private val lowRamOverrides = mapOf<String, Any>(
        "browser.sessionhistory.max_total_viewers" to 1,
        "browser.cache.memory.capacity" to 8192,
        "dom.ipc.processPrelaunch.fission.number" to 0,
        "browser.cache.disk.capacity" to 131072,
    )

    private val lowRamDroppedKeys = setOf(
        "network.buffer.cache.size",
        "network.buffer.cache.count",
        "network.dnsCacheEntries",
    )

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

    fun applyTo(context: Context, builder: GeckoRuntimeSettings.Builder) {
        val rfpEnabled = UserPreferences(context).resistFingerprinting
        val configFile = File(context.filesDir, CONFIG_FILE_NAME)
        val lowRam = UserPreferences(context).sleepingTabsMode ==
            UserPreferences.SLEEPING_TABS_SINGLE
        val fastLoad = UserPreferences(context).fastPageLoad
        val desired = buildConfigJson(rfpEnabled, lowRam, fastLoad)
        val current = runCatching { if (configFile.exists()) configFile.readText() else null }.getOrNull()
        if (current != desired) {
            configFile.writeText(desired)
        }
        builder.configFilePath(configFile.absolutePath)
    }

    fun applyTypedSettings(context: Context, settings: GeckoRuntimeSettings) {
        if (UserPreferences(context).resistFingerprinting) {
            settings.setFingerprintingProtection(true)
            settings.setFingerprintingProtectionPrivateBrowsing(true)
        } else {
            settings.setFingerprintingProtection(false)
            settings.setFingerprintingProtectionPrivateBrowsing(false)
        }
    }
}
