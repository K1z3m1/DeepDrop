@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.prirai.android.lr.R
import com.prirai.android.lr.browser.tabs.NewTabCreator
import com.prirai.android.lr.browser.tabs.NewTabCreator.NEW_TAB_INDEX
import com.prirai.android.lr.browser.tabs.compose.FaviconImageFromUrl
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.ui.components.LRCardRow
import com.prirai.android.lr.ui.components.LRSheetHeader
import com.prirai.android.lr.ui.theme.LRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.state.content.HistoryState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.ktx.kotlin.tryGetHostFromUrl
import androidx.fragment.app.FragmentManager

sealed class TabHistoryMode {
    data object Back : TabHistoryMode()
    data object Forward : TabHistoryMode()

    companion object {
        private const val NAME_BACK = "back"
        private const val NAME_FORWARD = "forward"

        fun toName(mode: TabHistoryMode): String = when (mode) {
            Back -> NAME_BACK
            Forward -> NAME_FORWARD
        }
    }
}

data class TabHistoryRow(
    val index: Int,
    val title: String,
    val uri: String,
    val isCurrent: Boolean,
)

class TabHistoryBottomSheet : BottomSheetDialogFragment() {
    private val sessionId get() = arguments?.getString(KEY_SESSION_ID)

    private val rowsState = mutableStateOf<List<TabHistoryRow>>(emptyList())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val context = LocalContext.current
            val prefs = UserPreferences(context)
            LRTheme(
                darkTheme = ThemeManager.isDarkMode(context),
                amoledMode = prefs.amoledMode,
                dynamicColor = prefs.dynamicColors
            ) {
                TabHistorySheetContent(
                    rows = rowsState.value,
                    onRowClick = ::onRowClicked,
                    onDismiss = ::dismiss,
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val components = requireContext().components

        components.store.state.findCustomTabOrSelectedTab(sessionId)?.let { tab ->
            if (tab.content.history.currentIndex != NEW_TAB_INDEX) {
                components.store.dispatch(EngineAction.FlushEngineSessionStateAction(tab.id))
            }
        }

        var hasShownRows = false
        components.store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
            flow.mapNotNull { state -> state.findCustomTabOrSelectedTab(sessionId)?.content?.history }
                .distinctUntilChanged()
                .collect { history ->
                    val rows = buildRows(history)
                    if (rows.isEmpty()) {
                        if (hasShownRows) {
                            dismissAllowingStateLoss()
                        }
                        return@collect
                    }
                    hasShownRows = true
                    rowsState.value = rows
                }
        }
    }

    private fun onRowClicked(row: TabHistoryRow) {
        val components = requireContext().components
        components.store.state.findCustomTabOrSelectedTab(sessionId)?.let { tab ->
            if (row.index >= 0) {
                components.sessionUseCases.goToHistoryIndex(row.index, tab.id)
                components.store.dispatch(ContentAction.UpdateUrlAction(tab.id, row.uri))
                components.store.dispatch(ContentAction.UpdateTitleAction(tab.id, row.title))
            } else {
                NewTabCreator.setTabToNewTabPage(components.store, tab.id)
            }
        }
        dismiss()
    }

    private fun buildRows(history: HistoryState?): List<TabHistoryRow> {
        val items = history?.items ?: return emptyList()
        if (items.isEmpty()) return emptyList()
        val current = history.currentIndex

        val isPrivateTab =
            components.store.state.findCustomTabOrSelectedTab(sessionId)?.content?.private == true

        fun row(index: Int, isCurrent: Boolean) = TabHistoryRow(
            index = index,
            title = if (items[index].uri in NEW_TAB_PAGE_URLS) {
                getString(R.string.tab_history_new_tab)
            } else {
                items[index].title
            },
            uri = items[index].uri,
            isCurrent = isCurrent
        )

        val currentRow = when {
            current == NEW_TAB_INDEX -> TabHistoryRow(
                index = NEW_TAB_INDEX,
                title = getString(R.string.tab_history_new_tab),
                uri = "about:homepage",
                isCurrent = true
            )
            current in items.indices -> row(current, isCurrent = true)
            else -> return emptyList()
        }

        val backRows = if (current > 0) {
            ((current - 1) downTo 0).map { row(it, isCurrent = false) }
        } else {
            emptyList()
        }

        val forwardRows = when {
            current == NEW_TAB_INDEX -> items.indices.map { row(it, isCurrent = false) }
            current in items.indices && current < items.lastIndex ->
                ((current + 1) until items.size).map { row(it, isCurrent = false) }
            else -> emptyList()
        }

        var rows = backRows + currentRow + forwardRows

        if (current >= 0 && !isPrivateTab && items.firstOrNull()?.uri !in NEW_TAB_PAGE_URLS) {
            rows = listOf(
                TabHistoryRow(
                    index = NEW_TAB_INDEX,
                    title = getString(R.string.tab_history_new_tab),
                    uri = "about:homepage",
                    isCurrent = false
                )
            ) + rows
        }

        return rows
    }

    companion object {
        const val TAG = "TabHistoryBottomSheet"
        private const val KEY_MODE = "mode"
        private const val KEY_SESSION_ID = "sessionId"

        private val NEW_TAB_PAGE_URLS = setOf("about:homepage", "about:blank")

        fun newInstance(mode: TabHistoryMode, sessionId: String? = null): TabHistoryBottomSheet {
            return TabHistoryBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(KEY_MODE, TabHistoryMode.toName(mode))
                    putString(KEY_SESSION_ID, sessionId)
                }
            }
        }

        fun showIfAvailable(
            fragmentManager: FragmentManager,
            store: BrowserStore,
            mode: TabHistoryMode,
            sessionId: String?
        ): Boolean {
            val tab = store.state.findCustomTabOrSelectedTab(sessionId) ?: return false
            val hasEntries = when (mode) {
                TabHistoryMode.Back -> tab.content.canGoBack
                TabHistoryMode.Forward -> tab.content.canGoForward
            }
            if (!hasEntries) return false

            newInstance(mode, sessionId).show(fragmentManager, TAG)
            return true
        }
    }
}

@Composable
private fun TabHistorySheetContent(
    rows: List<TabHistoryRow>,
    onRowClick: (TabHistoryRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = rows.indexOfFirst { it.isCurrent }.takeIf { it >= 0 }

    LaunchedEffect(currentIndex, rows.size) {
        if (currentIndex != null && rows.isNotEmpty()) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val itemHeight = info.visibleItemsInfo.firstOrNull { it.index == currentIndex }?.size
                ?: info.visibleItemsInfo.firstOrNull()?.size
                ?: 120
            listState.scrollToItem(
                currentIndex,
                scrollOffset = -(info.viewportSize.height - itemHeight) / 2,
            )
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LRSheetHeader(
            title = stringResource(R.string.action_history),
            onClose = onDismiss,
            showDragHandle = false,
            dividerAlpha = null,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> "${row.index}-${row.uri}" }) { index, row ->
                TabHistoryCardRow(
                    row = row,
                    isTopRow = index == rows.lastIndex,
                    isBottomRow = index == 0,
                    showDivider = index > 0,
                    onClick = onRowClick,
                )
            }
        }
    }
}

@Composable
private fun TabHistoryCardRow(
    row: TabHistoryRow,
    isTopRow: Boolean,
    isBottomRow: Boolean,
    showDivider: Boolean,
    onClick: (TabHistoryRow) -> Unit,
) {
    LRCardRow(
        title = row.title.ifBlank { row.uri.tryGetHostFromUrl() },
        isTopRow = isTopRow,
        isBottomRow = isBottomRow,
        showDivider = showDivider,
        onClick = if (row.isCurrent) {
            null
        } else {
            { onClick(row) }
        },
        titleColor = if (row.isCurrent) {
MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        leading = {
            if (row.isCurrent) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(10.dp),
                ) {}
            } else {
                FaviconImageFromUrl(
                    url = row.uri,
                    title = row.title,
                    size = 24.dp,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        },
    )
}
