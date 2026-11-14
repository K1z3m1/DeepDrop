package com.prirai.android.lr.components.toolbar

import androidx.navigation.NavController
import com.prirai.android.lr.BrowserActivity
import com.prirai.android.lr.BrowserAnimator.Companion.getToolbarNavOptions
import com.prirai.android.lr.BrowserFragmentDirections
import com.prirai.android.lr.R
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.ext.nav
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineView
import mozilla.components.support.ktx.kotlin.isUrl

interface BrowserToolbarController {
    fun handleScroll(offset: Int)
    fun handleToolbarPaste(text: String)
    fun handleToolbarPasteAndGo(text: String)
    fun handleToolbarClick()
    fun handleTabCounterClick()
}

class DefaultBrowserToolbarController(
    private val store: BrowserStore,
    private val activity: BrowserActivity,
    private val navController: NavController,
    private val engineView: EngineView,
    private val customTabSessionId: String?,
    private val onTabCounterClicked: () -> Unit
) : BrowserToolbarController {
    private val currentSession
        get() = store.state.findCustomTabOrSelectedTab(customTabSessionId)

    override fun handleToolbarPaste(text: String) {
        navController.nav(
            R.id.browserFragment,
            BrowserFragmentDirections.actionGlobalSearchDialog(
                sessionId = currentSession?.id,
                pastedText = text
            ),
            getToolbarNavOptions(activity)
        )
    }

    override fun handleToolbarPasteAndGo(text: String) {
        if (text.isUrl()) {
            store.updateSearchTermsOfSelectedSession("")
            activity.components.sessionUseCases.loadUrl.invoke(text)
            return
        }

        store.updateSearchTermsOfSelectedSession(text)
        activity.components.searchUseCases.defaultSearch.invoke(
            text,
            sessionId = store.state.selectedTabId
        )
    }

    override fun handleToolbarClick() {
        navController.nav(
            R.id.browserFragment,
            BrowserFragmentDirections.actionGlobalSearchDialog(
                currentSession?.id
            ),
            getToolbarNavOptions(activity)
        )
    }

    override fun handleTabCounterClick() {
        onTabCounterClicked.invoke()
    }

    override fun handleScroll(offset: Int) {
    }
}

private fun BrowserStore.updateSearchTermsOfSelectedSession(
    searchTerms: String
) {
    val selectedTabId = state.selectedTabId ?: return

    dispatch(ContentAction.UpdateSearchTermsAction(
        selectedTabId,
        searchTerms
    ))
}