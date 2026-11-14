package com.prirai.android.lr.browser.tabs

import android.content.Context
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.ext.components
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.state.selector.selectedTab

object NewTabCreator {
    const val NEW_TAB_INDEX = -1

    private const val WAIT_STEP_MS = 50L
    private const val WAIT_STEPS = 20 // 1s total

    fun createHomeTabAndLoad(context: Context, isPrivate: Boolean, load: () -> Unit) {
        val components = context.components

        val previousSelectedTabId = components.store.state.selectedTabId
        components.tabsUseCases.addTab.invoke(
            url = "about:homepage",
            private = isPrivate,
            contextId = contextIdForNewTab(context, isPrivate),
            selectTab = true,
        )

        val selectedNow = components.store.state.selectedTabId
        if (selectedNow != null && selectedNow != previousSelectedTabId) {
            load()
            return
        }

        MainScope().launch {
            var steps = 0
            while (components.store.state.selectedTabId == previousSelectedTabId && steps < WAIT_STEPS) {
                delay(WAIT_STEP_MS)
                steps++
            }
            load()
        }
    }

    private fun contextIdForNewTab(context: Context, isPrivate: Boolean): String? {
        if (isPrivate) return "private"

        val tabContextId = context.components.store.state.selectedTab?.contextId
        if (tabContextId != null && tabContextId.startsWith("profile_") &&
            tabContextId != "profile_default"
        ) {
            return tabContextId
        }

        val profileManager = ProfileManager.getInstance(context)
        return BrowserProfile.contextIdFor(
            profile = profileManager.getActiveProfile(),
            isPrivate = false,
        )
    }

    fun setTabToNewTabPage(store: BrowserStore, tabId: String) {
        val items = store.state.findTab(tabId)?.content?.history?.items ?: emptyList()
        store.dispatch(
            ContentAction.UpdateHistoryStateAction(
                sessionId = tabId,
                historyList = items,
                currentIndex = NEW_TAB_INDEX,
            )
        )
        store.dispatch(ContentAction.UpdateUrlAction(tabId, "about:homepage"))
        store.dispatch(ContentAction.UpdateTitleAction(tabId, ""))
        store.dispatch(ContentAction.UpdateBackNavigationStateAction(tabId, canGoBack = false))
        store.dispatch(ContentAction.UpdateForwardNavigationStateAction(tabId, canGoForward = true))
    }
}
