package com.prirai.android.lr.customtabs

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsSessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.customtabs.AbstractCustomTabsService
import com.prirai.android.lr.ext.components

/**
 * Enhanced Custom Tabs Service with performance optimizations
 * - Supports warmup for faster tab loading
 * - Implements speculative connections for better performance
 * - Compatible with Trusted Web Activities (TWA)
 */
class CustomTabsService : AbstractCustomTabsService() {
    override val scope = MainScope()
    
    override val engine: Engine by lazy { components.engine }
    override val customTabsServiceStore by lazy { components.customTabsStore }
    
    /**
     * Warmup the browser engine for faster subsequent loads
     */
    override fun warmup(flags: Long): Boolean {
        scope.launch(Dispatchers.Main) {
            try {
                engine.warmUp()
            } catch (e: Exception) {
                // Warmup failed, but continue anyway
            }
        }
        return true
    }
    
    /**
     * Open speculative connections to likely URLs for faster loading
     * This is called when apps hint that a URL might be loaded soon
     */
    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?
    ): Boolean {
        // SECURITY: any app can call this service. Only http(s) hints are used
        // and malformed bundles must not crash the browser.
        val urls = buildList {
            url?.let { add(it) }
            otherLikelyBundles?.take(5)?.forEach { bundle ->
                runCatching { bundle.getParcelable<Uri>("url") }.getOrNull()?.let { add(it) }
            }
        }.filter { it.scheme == "http" || it.scheme == "https" }

        urls.forEach { uri ->
            scope.launch(Dispatchers.IO) {
                try {
                    engine.speculativeConnect(uri.toString())
                } catch (e: Exception) {
                    // Connection failed, but that's okay
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
