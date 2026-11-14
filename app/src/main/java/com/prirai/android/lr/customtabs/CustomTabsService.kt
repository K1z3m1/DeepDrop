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

class CustomTabsService : AbstractCustomTabsService() {
    override val scope = MainScope()
    
    override val engine: Engine by lazy { components.engine }
    override val customTabsServiceStore by lazy { components.customTabsStore }
    
    override fun warmup(flags: Long): Boolean {
        scope.launch(Dispatchers.Main) {
            try {
                engine.warmUp()
            } catch (e: Exception) {
            }
        }
        return true
    }
    
    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?
    ): Boolean {
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
