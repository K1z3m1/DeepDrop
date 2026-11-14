package com.prirai.android.lr.middleware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import com.prirai.android.lr.utils.FaviconCache

class FaviconMiddleware(
    private val faviconCache: FaviconCache,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction
    ) {
        next(action)
        
        when (action) {
            is ContentAction.UpdateIconAction -> {
                val tab = store.state.tabs.find { it.id == action.sessionId }
                if (tab != null && action.icon != null) {
                    scope.launch {
                        faviconCache.saveFavicon(tab.content.url, action.icon)
                    }
                }
            }
            else -> {
            }
        }
    }
}