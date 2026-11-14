package com.prirai.android.lr.components.toolbar.modern

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.browser.tabs.compose.TabBarCompose
import com.prirai.android.lr.browser.tabs.compose.TabViewModel
import com.prirai.android.lr.browser.tabs.compose.TabOrderManager
import com.prirai.android.lr.browser.tabgroups.UnifiedTabGroupManager
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.ui.theme.LRTheme
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.flowScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.prirai.android.lr.browser.tabs.compose.GlobalSnackbarManager
import com.prirai.android.lr.browser.tabs.compose.TabSheetStateManager
import com.prirai.android.lr.preferences.UserPreferences

class ComposeTabBarWithProfileSwitcher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
    }

    private var onTabSelected: ((String) -> Unit)? = null
    private var onTabClosed: ((String) -> Unit)? = null
    private var onProfileSelected: ((BrowserProfile) -> Unit)? = null
    private var onPrivateModeSelected: (() -> Unit)? = null
    private var lastSelectedTabId: String? = null

    private var tabOrderManager: TabOrderManager? = null
    private var groupManager: UnifiedTabGroupManager? = null
    private var tabViewModel: TabViewModel? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        clipToPadding = false
        clipChildren = false
        val elevationDp = 12f * resources.displayMetrics.density
        elevation = elevationDp
        translationZ = elevationDp

        addView(
            composeView, LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )

        groupManager = UnifiedTabGroupManager.getInstance(context)
        tabOrderManager = TabOrderManager.getInstance(context, groupManager!!)
        tabViewModel = TabViewModel(context, groupManager!!)
    }

    fun setup(
        onTabSelected: (String) -> Unit,
        onTabClosed: (String) -> Unit,
        onProfileSelected: (BrowserProfile) -> Unit,
        onPrivateModeSelected: (() -> Unit)? = null
    ) {
        this.onTabSelected = onTabSelected
        this.onTabClosed = onTabClosed
        this.onProfileSelected = onProfileSelected
        this.onPrivateModeSelected = onPrivateModeSelected

        setupComposeContent()
    }

    private fun setupComposeContent() {
        composeView.setContent {
            val prefs = UserPreferences(context)
            val isAmoledActive = prefs.amoledMode
            
            LRTheme(amoledMode = isAmoledActive) {
                TabBarWithProfileSwitcherContent()
            }
        }
    }

    @Composable
    private fun TabBarWithProfileSwitcherContent() {
        val profileManager = remember { ProfileManager.getInstance(context) }

        val store = context.components.store
        val lifecycleOwner = context as LifecycleOwner

        val browserState = produceState(initialValue = store.state, store) {
            store.flowScoped(lifecycleOwner, Dispatchers.Main) { flow ->
                flow.collect { state ->
                    value = state
                }
            }
        }

        val currentProfile = remember(browserState.value) { profileManager.getActiveProfile() }
        val isPrivateMode = remember(browserState.value) { profileManager.isPrivateMode() }

        val tabs = remember(browserState.value.tabs, currentProfile, isPrivateMode) {
            browserState.value.tabs.filter { tab ->
                val tabIsPrivate = tab.content.private
                if (tabIsPrivate != isPrivateMode) {
                    false
                } else if (isPrivateMode) {
                    tab.contextId == "private"
                } else {
                    val expectedContextId = "profile_${currentProfile.id}"
                    if (currentProfile.id == "default") {
                        (tab.contextId == expectedContextId) || (tab.contextId == null)
                    } else {
                        tab.contextId == expectedContextId
                    }
                }
            }
        }

        val selectedTabId = browserState.value.selectedTabId

        val orderManager = tabOrderManager ?: return
        val viewModel = tabViewModel ?: return

        LaunchedEffect(tabs, selectedTabId, currentProfile, isPrivateMode) {
            val profileId = if (isPrivateMode) "private" else currentProfile.id

            orderManager.rebuildOrderForProfile(profileId, tabs)

            viewModel.loadTabsForProfile(profileId, tabs, selectedTabId)
        }

        LaunchedEffect(selectedTabId) {
            val currentTabId = selectedTabId
            if (currentTabId != null && currentTabId != lastSelectedTabId) {
                TabSheetStateManager.notifyTabSheetDismissed()
                lastSelectedTabId = currentTabId
            }
        }

        val scope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            GlobalSnackbarManager.getInstance().coroutineScope = scope
        }

        val profileKey = remember(currentProfile, isPrivateMode) {
            if (isPrivateMode) "private" else "profile_${currentProfile.id}"
        }

        val autoScrollTrigger by TabSheetStateManager.dismissedTimestamp.collectAsState()

        key(profileKey) {
            TabBarCompose(
                tabs = tabs,
                viewModel = viewModel,
                orderManager = orderManager,
                selectedTabId = selectedTabId,
                onTabClick = { tabId: String ->
                    onTabSelected?.invoke(tabId)
                },
                onTabClose = { tabId: String ->
                    viewModel.closeTab(tabId, showUndo = true)
                    onTabClosed?.invoke(tabId)
                },
                modifier = Modifier.fillMaxWidth(),
                autoScrollTrigger = autoScrollTrigger
            )
        }
    }

    fun updateTabs(tabs: List<TabSessionState>, selectedId: String?) {
        setupComposeContent()
    }

    fun updateProfileIcon(profile: BrowserProfile) {
        setupComposeContent()
    }
}
