package com.prirai.android.lr.search

import android.content.Context
import com.prirai.android.lr.browser.SearchEngineList
import com.prirai.android.lr.components.Components
import com.prirai.android.lr.preferences.UserPreferences
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.SearchState
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.lib.state.Action
import mozilla.components.lib.state.State
import mozilla.components.lib.state.Store

class SearchFragmentStore(
    initialState: SearchFragmentState
) : Store<SearchFragmentState, SearchFragmentAction>(
    initialState,
    ::searchStateReducer
)

sealed class SearchEngineSource {
    abstract val searchEngine: SearchEngine?

    data object None : SearchEngineSource() {
        override val searchEngine: SearchEngine? = null
    }

    data class Default(override val searchEngine: SearchEngine) : SearchEngineSource()
    data class Shortcut(override val searchEngine: SearchEngine) : SearchEngineSource()
}

data class SearchFragmentState(
    val query: String,
    val url: String,
    val searchTerms: String,
    val searchEngineSource: SearchEngineSource,
    val defaultEngine: SearchEngine?,
    val showSearchSuggestions: Boolean,
    val showSearchSuggestionsHint: Boolean,
    val showSearchShortcuts: Boolean,
    val areShortcutsAvailable: Boolean,
    val showSearchShortcutsSetting: Boolean,
    val showClipboardSuggestions: Boolean,
    val showHistorySuggestions: Boolean,
    val showBookmarkSuggestions: Boolean,
    val showSyncedTabsSuggestions: Boolean,
    val tabId: String?,
    val pastedText: String? = null,
    val isPrivate: Boolean = false,
) : State

fun createInitialSearchFragmentState(
    components: Components,
    tabId: String?,
    pastedText: String?,
    context: Context? = null
): SearchFragmentState {
    val tab = tabId?.let { components.store.state.findTab(it) }
    val url = tab?.content?.url.orEmpty()
    val isPrivate = tab?.content?.private == true
    val defaultEngine = components.store.state.search.selectedOrDefaultSearchEngine(isPrivate)
        ?: context?.let {
            try {
                SearchEngineList(it).getSelectedEngine(UserPreferences(it))
            } catch (_: Exception) {
                null
            }
        }

    val shouldShowSearchSuggestions = true

    return SearchFragmentState(
        query = url,
        url = url,
        searchTerms = tab?.content?.searchTerms.orEmpty(),
        searchEngineSource = defaultEngine?.let { SearchEngineSource.Default(it) }
            ?: SearchEngineSource.None,
        defaultEngine = defaultEngine,
        showSearchSuggestions = shouldShowSearchSuggestions,
        showSearchSuggestionsHint = false,
        showSearchShortcuts = false,
        areShortcutsAvailable = false,
        showSearchShortcutsSetting = true,
        showClipboardSuggestions = true,
        showHistorySuggestions = true,
        showBookmarkSuggestions = true,
        showSyncedTabsSuggestions = true,
        tabId = tabId,
        pastedText = pastedText,
        isPrivate = isPrivate,
    )
}

sealed class SearchFragmentAction : Action {
    data class SetShowSearchSuggestions(val show: Boolean) : SearchFragmentAction()
    data class SearchShortcutEngineSelected(val engine: SearchEngine) : SearchFragmentAction()
    data class ShowSearchShortcutEnginePicker(val show: Boolean) : SearchFragmentAction()
    data class AllowSearchSuggestionsInPrivateModePrompt(val show: Boolean) : SearchFragmentAction()
    data class UpdateQuery(val query: String) : SearchFragmentAction()

    data class UpdateSearchState(val search: SearchState) : SearchFragmentAction()
}

private fun searchStateReducer(state: SearchFragmentState, action: SearchFragmentAction): SearchFragmentState {
    return when (action) {
        is SearchFragmentAction.SearchShortcutEngineSelected ->
            state.copy(
                searchEngineSource = SearchEngineSource.Shortcut(action.engine),
                showSearchShortcuts = false
            )
        is SearchFragmentAction.ShowSearchShortcutEnginePicker ->
            state.copy(showSearchShortcuts = action.show && state.areShortcutsAvailable)
        is SearchFragmentAction.UpdateQuery ->
            state.copy(query = action.query)
        is SearchFragmentAction.AllowSearchSuggestionsInPrivateModePrompt ->
            state.copy(showSearchSuggestionsHint = action.show)
        is SearchFragmentAction.SetShowSearchSuggestions ->
            state.copy(showSearchSuggestions = action.show)
        is SearchFragmentAction.UpdateSearchState -> {
            val resolvedEngine = action.search.selectedOrDefaultSearchEngine(state.isPrivate) ?: state.defaultEngine
            state.copy(
                defaultEngine = resolvedEngine,
                areShortcutsAvailable = action.search.searchEngines.size > 1,
                showSearchShortcuts = state.url.isEmpty() &&
                    state.showSearchShortcutsSetting &&
                    action.search.searchEngines.size > 1,
                searchEngineSource = (state.searchEngineSource as? SearchEngineSource.Shortcut)
                    ?: (resolvedEngine?.let { SearchEngineSource.Default(it) }
                        ?: SearchEngineSource.None)
            )
        }
    }
}
