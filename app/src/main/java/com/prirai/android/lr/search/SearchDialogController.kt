package com.prirai.android.lr.search

import androidx.navigation.NavController
import com.prirai.android.lr.BrowserActivity
import com.prirai.android.lr.BrowserDirection
import com.prirai.android.lr.browser.SearchEngineList
import com.prirai.android.lr.preferences.UserPreferences
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases

interface SearchController {
    fun handleUrlCommitted(url: String)
    fun handleEditingCancelled()
    fun handleTextChanged(text: String)
    fun handleUrlTapped(url: String)
    fun handleSearchTermsTapped(searchTerms: String)
    fun handleSearchShortcutEngineSelected(searchEngine: SearchEngine)
    fun handleClickSearchEngineSettings()
    fun handleExistingSessionSelected(tabId: String)
    fun handleSearchShortcutsButtonClicked()
}

class SearchDialogController(
    private val activity: BrowserActivity,
    private val store: BrowserStore,
    private val tabsUseCases: TabsUseCases,
    private val fragmentStore: SearchFragmentStore,
    private val navController: NavController,
    private val openedFromHome: Boolean,
    private val dismissDialog: () -> Unit,
    private val clearToolbarFocus: () -> Unit,
    private val focusToolbar: () -> Unit
) : SearchController {
    override fun handleUrlCommitted(url: String) {
        when (url) {
            "moz://a" -> openSearchOrUrl("https://mozilla.org")
            else ->
                if (url.isNotBlank()) {
                    openSearchOrUrl(url)
                }
        }
        dismissDialog()
    }

    private fun openSearchOrUrl(url: String) {
        clearToolbarFocus()

        val searchEngine = resolveSearchEngine()
        val shouldCreateNewTab = shouldCreateNewTabForSearch()

        activity.openToBrowserAndLoad(
            searchTermOrURL = url,
            newTab = shouldCreateNewTab,
            from = BrowserDirection.FromSearchDialog,
            engine = searchEngine
        )
    }

    private fun resolveSearchEngine(): SearchEngine? {
        fragmentStore.state.searchEngineSource.searchEngine?.let { return it }
        fragmentStore.state.defaultEngine?.let { return it }
        store.state.search.selectedOrDefaultSearchEngine(activity.browsingModeManager.mode.isPrivate)?.let { return it }
        return try {
            SearchEngineList(activity).getSelectedEngine(UserPreferences(activity))
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldCreateNewTabForSearch(): Boolean {
        val tabId = fragmentStore.state.tabId ?: return true

        if (!openedFromHome) return false

        val tab = store.state.findTab(tabId) ?: return true
        return tab.content.url !in NEW_TAB_PAGE_URLS
    }

    private companion object {
        val NEW_TAB_PAGE_URLS = setOf("about:homepage", "about:blank")
    }

    override fun handleEditingCancelled() {
        clearToolbarFocus()
    }

    override fun handleTextChanged(text: String) {
        val textMatchesCurrentUrl = fragmentStore.state.url == text
        val textMatchesCurrentSearch = fragmentStore.state.searchTerms == text

        fragmentStore.dispatch(SearchFragmentAction.UpdateQuery(text))

        val showPicker =
            (textMatchesCurrentUrl || textMatchesCurrentSearch || text.isEmpty()) &&
                fragmentStore.state.areShortcutsAvailable
        if (fragmentStore.state.showSearchShortcuts != showPicker) {
            fragmentStore.dispatch(SearchFragmentAction.ShowSearchShortcutEnginePicker(showPicker))
        }

        val allowSuggestionsPrompt =
            text.isNotEmpty() && activity.browsingModeManager.mode.isPrivate
        if (fragmentStore.state.showSearchSuggestionsHint != allowSuggestionsPrompt) {
            fragmentStore.dispatch(
                SearchFragmentAction.AllowSearchSuggestionsInPrivateModePrompt(allowSuggestionsPrompt)
            )
        }
    }

    override fun handleUrlTapped(url: String) {
        clearToolbarFocus()
        
        val shouldCreateNewTab = shouldCreateNewTabForSearch()

        activity.openToBrowserAndLoad(
            searchTermOrURL = url,
            newTab = shouldCreateNewTab,
            from = BrowserDirection.FromSearchDialog
        )
    }

    override fun handleSearchTermsTapped(searchTerms: String) {
        clearToolbarFocus()

        val searchEngine = resolveSearchEngine()
        val shouldCreateNewTab = shouldCreateNewTabForSearch()

        activity.openToBrowserAndLoad(
            searchTermOrURL = searchTerms,
            newTab = shouldCreateNewTab,
            from = BrowserDirection.FromSearchDialog,
            engine = searchEngine,
            forceSearch = true
        )
    }

    override fun handleSearchShortcutEngineSelected(searchEngine: SearchEngine) {
        focusToolbar()
        fragmentStore.dispatch(SearchFragmentAction.SearchShortcutEngineSelected(searchEngine))
    }

    override fun handleSearchShortcutsButtonClicked() {
        val isOpen = fragmentStore.state.showSearchShortcuts
        fragmentStore.dispatch(SearchFragmentAction.ShowSearchShortcutEnginePicker(!isOpen))
    }

    override fun handleClickSearchEngineSettings() {
        clearToolbarFocus()
    }

    override fun handleExistingSessionSelected(tabId: String) {
        clearToolbarFocus()

        tabsUseCases.selectTab(tabId)

        activity.openToBrowser(
            from = BrowserDirection.FromSearchDialog
        )
    }
}
