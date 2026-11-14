package com.prirai.android.lr.browser.tabgroups

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class TabGroupMiddleware(
    private val lazyTabGroupManager: Lazy<UnifiedTabGroupManager>
) : Middleware<BrowserState, BrowserAction> {
    companion object {
        private const val TAG = "TabGroupMiddleware"
    }

    private val tabGroupManager: UnifiedTabGroupManager
        get() = lazyTabGroupManager.value

    private val pendingGrouping = mutableMapOf<String, String?>()
    
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction
    ) {
        next(action)
        
        when (action) {
            is TabListAction.AddTabAction -> {
                handleNewTab(store.state, action)
            }
            is ContentAction.UpdateUrlAction -> {
                handleUrlUpdate(store.state, action)
            }
            is TabListAction.SelectTabAction -> {
                handleTabSelection(store.state, action)
            }
            else -> {
            }
        }
    }
    
    private fun handleNewTab(state: BrowserState, action: TabListAction.AddTabAction) {
        val newTab = action.tab
        val newTabUrl = newTab.content.url
        val effectiveUrl = if (newTabUrl.isBlank() || newTabUrl == "about:blank") {
            if (newTab.parentId != null) {
                pendingGrouping[newTab.id] = newTab.parentId
            } else {
                val selectedTab = state.tabs.find { it.id == state.selectedTabId && it.id != newTab.id }
                if (selectedTab != null) {
                    pendingGrouping[newTab.id] = selectedTab.id
                }
            }
            return
        } else {
            newTabUrl
        }

        if ((effectiveUrl.startsWith("about:") && effectiveUrl != "about:homepage") || effectiveUrl.startsWith("chrome:")) {
            return
        }

        val sourceTab = if (newTab.parentId != null) {
            state.tabs.find { it.id == newTab.parentId }
        } else {
            state.selectedTabId?.let { selectedId ->
                if (selectedId != newTab.id) {
                    state.tabs.find { it.id == selectedId }
                } else {
                    state.tabs.filter { it.id != newTab.id }
                        .maxByOrNull { it.lastAccess }
                }
            }
        }
        if (sourceTab != null) {
            val sourceUrl = sourceTab.content.url

            if (sourceUrl.isNotBlank() && sourceUrl != "about:blank") {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        tabGroupManager.handleNewTabFromLink(
                            newTabId = newTab.id,
                            newTabUrl = effectiveUrl,
                            sourceTabId = sourceTab.id,
                            sourceTabUrl = sourceUrl
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to group tabs", e)
                    }
                }
            }
        } else {
        }
    }
    
    private fun handleUrlUpdate(state: BrowserState, action: ContentAction.UpdateUrlAction) {
        val tabId = action.sessionId
        val newUrl = action.url
        
        val parentId = pendingGrouping.remove(tabId) ?: return
        if ((newUrl.startsWith("about:") && newUrl != "about:homepage") || newUrl.startsWith("chrome:")) {
            return
        }
        
        val parentTab = state.tabs.find { it.id == parentId }
        if (parentTab == null) {
            return
        }
        
        val parentUrl = parentTab.content.url
        if (parentUrl.isBlank() || parentUrl == "about:blank") {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tabGroupManager.handleNewTabFromLink(
                    newTabId = tabId,
                    newTabUrl = newUrl,
                    sourceTabId = parentId,
                    sourceTabUrl = parentUrl
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to group tabs after URL update", e)
            }
        }
    }
    
    private fun handleTabSelection(state: BrowserState, action: TabListAction.SelectTabAction) {
    }
}