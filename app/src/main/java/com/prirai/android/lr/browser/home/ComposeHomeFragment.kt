package com.prirai.android.lr.browser.home

import androidx.compose.runtime.getValue
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prirai.android.lr.BrowserActivity
import com.prirai.android.lr.NavGraphDirections
import com.prirai.android.lr.R
import com.prirai.android.lr.browser.BrowsingMode
import com.prirai.android.lr.browser.bookmark.repository.BookmarkManager
import com.prirai.android.lr.browser.bookmark.ui.BookmarksBottomSheetFragment
import com.prirai.android.lr.browser.home.compose.AddShortcutDialog
import com.prirai.android.lr.browser.home.compose.HomeScreen
import com.prirai.android.lr.browser.home.compose.HomeViewModel
import com.prirai.android.lr.browser.home.compose.HomeViewModelFactory
import com.prirai.android.lr.browser.home.compose.ProfileInfo
import com.prirai.android.lr.browser.home.compose.jumpBackInItems
import com.prirai.android.lr.browser.home.compose.recentlyClosedItems
import com.prirai.android.lr.browser.shortcuts.ShortcutDatabase
import com.prirai.android.lr.components.toolbar.BrowserToolbarViewInteractor
import com.prirai.android.lr.components.toolbar.ToolbarMenu
import com.prirai.android.lr.components.toolbar.ToolbarPosition
import com.prirai.android.lr.components.toolbar.unified.UnifiedToolbar
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.settings.HomepageBackgroundChoice
import com.prirai.android.lr.ui.theme.LRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.lib.state.ext.observeAsComposableState
import android.content.Intent
import android.view.Gravity
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.prirai.android.lr.addons.ExtensionsBottomSheetFragment
import com.prirai.android.lr.browser.FakeTab
import com.prirai.android.lr.browser.SwipeGestureCoordinatorLayout
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.browser.tabs.TabsBottomSheetFragment
import com.prirai.android.lr.browser.tabs.compose.TabSheetStateManager
import com.prirai.android.lr.browser.toolbar.HomeToolbarGestureHandler
import com.prirai.android.lr.components.menu.Material3BrowserMenu
import com.prirai.android.lr.history.HistoryActivity
import com.prirai.android.lr.settings.activity.SettingsActivity
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.toolbar.ToolbarActionsListener

class ComposeHomeFragment : Fragment() {
    private val browsingModeManager get() = (activity as BrowserActivity).browsingModeManager
    private val components get() = requireContext().components

    private lateinit var viewModel: HomeViewModel
    private var unifiedToolbar: UnifiedToolbar? = null
    
    companion object {
        private var shouldPreventAutoNavigation = false
        
        fun navigateToHome() {
            shouldPreventAutoNavigation = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = ShortcutDatabase.getInstance(requireContext())

        val factory = HomeViewModelFactory(
            bookmarkManager = BookmarkManager.getInstance(requireContext()),
            shortcutDao = database.shortcutDao(),
            historyStorage = requireContext().components.historyStorage,
        )

        viewModel = ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val coordinatorLayout = SwipeGestureCoordinatorLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            fitsSystemWindows = false
        }

        val composeView = ComposeView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val store = requireContext().components.store
                
                val selectedTabId by store.observeAsComposableState { state -> state.selectedTabId }
                
                val selectedTab = selectedTabId?.let { id ->
                    store.state.tabs.find { it.id == id }
                }

                val isPrivateMode = selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate

                val shortcuts by viewModel.shortcuts.collectAsState()
                val showAddDialog by viewModel.showAddShortcutDialog.collectAsState()
                val jumpBackInHistory by viewModel.jumpBackInHistory.collectAsState()
                val recentsKey by store.observeAsComposableState { state ->
                    Triple(
                        state.selectedTabId,
                        state.tabs.map { it.id to it.lastAccess },
                        state.closedTabs.map { it.id },
                    )
                }
                val jumpBackInItems = androidx.compose.runtime.remember(recentsKey, jumpBackInHistory) {
                    store.state.jumpBackInItems(jumpBackInHistory)
                }
                val recentlyClosedItems by store.observeAsComposableState { state ->
                    state.recentlyClosedItems()
                }

                if (isPrivateMode) {
                    store.state.privateTabs.size
                } else {
                    store.state.normalTabs.size
                }

                val profileManager =
                    ProfileManager.getInstance(requireContext())
                val currentProfile = run {
                    val tabContextId = selectedTab?.contextId
                    when {
                        tabContextId == "private" || isPrivateMode -> {
                            ProfileInfo("private", "Private", "🕵️", true, 0)
                        }

                        tabContextId != null && tabContextId.startsWith("profile_") -> {
                            val profileId = tabContextId.removePrefix("profile_")
                            val profile = profileManager.getAllProfiles().find { it.id == profileId }
                                ?: profileManager.getActiveProfile()
                            ProfileInfo(profile.id, profile.name, profile.emoji, false, profile.color)
                        }

                        else -> {
                            val profile = profileManager.getActiveProfile()
                            ProfileInfo(profile.id, profile.name, profile.emoji, false, profile.color)
                        }
                    }
                }

                val prefs = UserPreferences(requireContext())
                val backgroundImageUrl = when (prefs.homepageBackgroundChoice) {
                    HomepageBackgroundChoice.NONE.ordinal -> null
                    HomepageBackgroundChoice.URL.ordinal,
                    HomepageBackgroundChoice.GALLERY.ordinal -> prefs.homepageBackgroundUrl
                    else -> null
                }
                
                val isToolbarAtTop = prefs.toolbarPosition == ToolbarPosition.TOP.ordinal
                
                val homepageType = prefs.homepageType
                
                val wallpaperDimmed = prefs.keepWallpaperDimmed

                LRTheme(
                    isPrivateMode = isPrivateMode,
                    amoledMode = prefs.amoledMode,
                    dynamicColor = prefs.dynamicColors
                ) {
                    HomeScreen(
                        isPrivateMode = isPrivateMode,
                        shortcuts = shortcuts,
                        currentProfile = currentProfile,
                        onProfileClick = {}, // Profile icon is now display-only
                        backgroundImageUrl = backgroundImageUrl,
                        isToolbarAtTop = isToolbarAtTop,
                        homepageType = homepageType,
                        wallpaperDimmed = wallpaperDimmed,
                        onShortcutClick = { shortcut ->
                            components.sessionUseCases.loadUrl(shortcut.url)
                            try {
                                findNavController().navigate(R.id.browserFragment)
                            } catch (e: Exception) {
                            }
                        },
                        onShortcutDelete = { shortcut ->
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Delete Shortcut")
                                .setMessage("Are you sure you want to delete '${shortcut.title}'?")
                                .setPositiveButton("Delete") { _, _ ->
                                    viewModel.deleteShortcut(shortcut)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        },
                        onShortcutAdd = { viewModel.showAddShortcutDialog() },
                        onHistoryClick = {
                            startActivity(
                                Intent(
                                    requireContext(),
                                    HistoryActivity::class.java
                                )
                            )
                        },
                        onBookmarksClick = {
                            BookmarksBottomSheetFragment.newInstance()
                                .show(parentFragmentManager, "BookmarksBottomSheet")
                        },
                        jumpBackInItems = jumpBackInItems,
                        recentlyClosedItems = recentlyClosedItems.orEmpty(),
                        onJumpBackInClick = { item ->
                            val tabId = item.tabId
                            if (tabId != null) {
                                components.tabsUseCases.selectTab(tabId)
                            } else {
                                components.sessionUseCases.loadUrl(item.url)
                            }
                        },
                        onRecentlyClosedClick = { item ->
                            components.sessionUseCases.loadUrl(item.url)
                        },
                        onSearchClick = {
                            val sessionId = components.store.state.selectedTabId
                            val directions = NavGraphDirections.actionGlobalSearchDialog(sessionId = sessionId)
                            findNavController().navigate(directions)
                        }
                    )

                    if (showAddDialog) {
                        AddShortcutDialog(
                            onDismiss = { viewModel.hideAddShortcutDialog() },
                            onSave = { url, title ->
                                viewModel.addShortcut(url, title)
                            }
                        )
                    }
                }
            }
        }

        coordinatorLayout.addView(composeView)

        setupUnifiedToolbar(coordinatorLayout)

        return coordinatorLayout
    }

    private fun setupUnifiedToolbar(coordinatorLayout: CoordinatorLayout) {
        val prefs = UserPreferences(requireContext())
        requireContext().components.tabGroupManager

        val toolbarInteractor = object : BrowserToolbarViewInteractor {
            override fun onBrowserToolbarPaste(text: String) {
                val sessionId = components.store.state.selectedTabId
                val directions = NavGraphDirections.actionGlobalSearchDialog(sessionId = sessionId)
                findNavController().navigate(directions)
            }

            override fun onBrowserToolbarPasteAndGo(text: String) {
                components.sessionUseCases.loadUrl(text)
            }

            override fun onBrowserToolbarClicked() {
                val sessionId = components.store.state.selectedTabId
                val directions = NavGraphDirections.actionGlobalSearchDialog(sessionId = sessionId)
                findNavController().navigate(directions)
            }

            override fun onBrowserToolbarMenuItemTapped(item: ToolbarMenu.Item) {
            }

            override fun onTabCounterClicked() {
                val tabsBottomSheet =
                    TabsBottomSheetFragment.newInstance()
                tabsBottomSheet.show(parentFragmentManager, TabsBottomSheetFragment.TAG)
            }

            override fun onScrolled(offset: Int) {
            }
        }

        unifiedToolbar = UnifiedToolbar.create(
            context = requireContext(),
            parent = coordinatorLayout,
            lifecycleOwner = viewLifecycleOwner,
            interactor = toolbarInteractor,
            customTabSession = null,
            store = components.store
        )
        
        setupToolbarGestureHandler(coordinatorLayout)
        
        if (prefs.toolbarPosition == ToolbarPosition.TOP.ordinal) {
            val bottomContainer = unifiedToolbar?.getBottomComponentsContainer()
            
            bottomContainer?.let { container ->
                val layoutParams = CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.MATCH_PARENT,
                    CoordinatorLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                }
                
                ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(0, 0, 0, systemBars.bottom)
                    insets
                }
                
                coordinatorLayout.addView(container, layoutParams)
                container.visibility = View.VISIBLE
                
                ViewCompat.requestApplyInsets(container)
            }
        }

        unifiedToolbar?.setOnTabSelectedListener { tabId ->
            components.tabsUseCases.selectTab(tabId)
            
            val selectedTab = components.store.state.tabs.find { it.id == tabId }
            if (selectedTab?.content?.url != "about:homepage" && selectedTab?.content?.url?.isNotBlank() == true) {
                (requireActivity() as BrowserActivity).openToBrowser(
                    from = com.prirai.android.lr.BrowserDirection.FromHome,
                    customTabSessionId = tabId
                )
            }
        }

        unifiedToolbar?.setContextualToolbarListener(object :
            ToolbarActionsListener {
            override fun onBackClicked() {
                handleTabSwipe(false, components.store.state.selectedTab?.content?.private ?: false)
            }

            override fun onForwardClicked() {
                handleTabSwipe(true, components.store.state.selectedTab?.content?.private ?: false)
            }

            override fun onShareClicked() {
            }

            override fun onSearchClicked() {
                val sessionId = components.store.state.selectedTabId
                val directions = NavGraphDirections.actionGlobalSearchDialog(sessionId = sessionId)
                findNavController().navigate(directions)
            }

            override fun onNewTabClicked() {
                val profileManager = ProfileManager.getInstance(requireContext())
                val isPrivateMode = components.store.state.selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate
                val currentProfile = profileManager.getActiveProfile()
                val contextId = BrowserProfile
                    .contextIdFor(currentProfile, isPrivateMode)

                components.tabsUseCases.addTab(
                    url = "about:homepage",
                    selectTab = true,
                    private = isPrivateMode,
                    contextId = contextId
                )
                
                TabSheetStateManager.notifyTabSheetDismissed()
                
                findNavController().navigate(R.id.browserFragment)
            }

            override fun onTabCountClicked() {
                val tabsBottomSheet =
                    TabsBottomSheetFragment.newInstance()
                tabsBottomSheet.show(
                    parentFragmentManager,
                    TabsBottomSheetFragment.TAG
                )
            }

            override fun onExtensionsClicked() {
                val extensionsBottomSheet =
                    ExtensionsBottomSheetFragment.newInstance()
                extensionsBottomSheet.show(
                    parentFragmentManager,
                    ExtensionsBottomSheetFragment.TAG
                )
            }

            override fun onMenuClicked() {
                showNativeMenu()
            }

            override fun onBookmarksClicked() {
                val bookmarksBottomSheet = BookmarksBottomSheetFragment.newInstance()
                bookmarksBottomSheet.show(parentFragmentManager, "BookmarksBottomSheet")
            }
        })

        val isPrivate = components.store.state.selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate
        unifiedToolbar?.applyPrivateMode(isPrivate)
        
        forceTabBarUpdate()
    }

    private var lastBackPressTime = 0L
    private val BACK_PRESS_TIMEOUT = 4000L // 4 seconds

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateToolbarStyling()
        restoreLastMode()

        val store = components.store
        store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
            flow.map { state -> 
                state.selectedTabId?.let { tabId ->
                    val tab = state.tabs.find { it.id == tabId }
                    tab?.content?.url
                }
            }.distinctUntilChanged()
            .collect { url ->
                if (shouldPreventAutoNavigation) {
                    shouldPreventAutoNavigation = false
                    return@collect
                }
                
                if (url != null && url != "about:homepage" && url != "about:privatebrowsing") {
                    if (isAdded && view != null) {
                        try {
                            findNavController().navigate(R.id.browserFragment)
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        }
        
        store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
            flow.map { state -> state.selectedTabId }
                .distinctUntilChanged()
                .collect { _ ->
                    if (!isAdded || context == null) return@collect
                    
                    val isPrivate = store.state.selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate
                    unifiedToolbar?.applyPrivateMode(isPrivate)
                }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < BACK_PRESS_TIMEOUT) {
                    requireActivity().finish()
                } else {
                    lastBackPressTime = currentTime
                    Toast.makeText(
                        requireContext(),
                        "Perform back gesture again to close app",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadShortcuts()
        viewModel.loadBookmarks()
        viewModel.loadJumpBackInHistory()
        updateToolbarStyling()
    }

    private fun updateToolbarStyling() {
        if (!isResumed || !isVisible) {
            return
        }

        val isPrivate = components.store.state.selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate
        
        ThemeManager.applySystemBarsTheme(
            requireActivity(),
            isPrivate
        )

        unifiedToolbar?.applyPrivateMode(isPrivate)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        restoreDefaultStyling()
    }

    private fun restoreDefaultStyling() {
        val isPrivate = components.store.state.selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate
        ThemeManager.applySystemBarsTheme(
            requireActivity(),
            isPrivate
        )
    }

    private fun handleTabSwipe(isNext: Boolean, isPrivateMode: Boolean) {
        val currentTab = components.store.state.selectedTab ?: return
        val tabs = components.store.state.getNormalOrPrivateTabs(isPrivateMode)
        val currentIndex = tabs.indexOfFirst { it.id == currentTab.id }

        if (currentIndex == -1) return

        val newIndex = if (isNext) currentIndex + 1 else currentIndex - 1

        if (newIndex in tabs.indices) {
            val newTab = tabs[newIndex]
            components.tabsUseCases.selectTab(newTab.id)

            if (newTab.content.url != "about:homepage") {
                findNavController().navigate(R.id.browserFragment)
            }
        }
    }

    private fun restoreLastMode() {
        val profileManager = ProfileManager.getInstance(requireContext())
        val isPrivate = profileManager.isPrivateMode()
        
        if (isPrivate) {
            browsingModeManager.mode = BrowsingMode.Private
        } else {
            browsingModeManager.mode = BrowsingMode.Normal
        }
    }

    private fun setupToolbarGestureHandler(coordinatorLayout: CoordinatorLayout) {
        val toolbar = unifiedToolbar?.getBrowserToolbar() ?: return

        val fakeTab = FakeTab(requireContext())
        fakeTab.visibility = View.GONE
        coordinatorLayout.addView(fakeTab)
        
        val gestureHandler = HomeToolbarGestureHandler(
            activity = requireActivity(),
            contentLayout = coordinatorLayout,
            tabPreview = fakeTab,
            toolbarLayout = toolbar,
            store = components.store,
            selectTabUseCase = components.tabsUseCases.selectTab
        )
        
        (coordinatorLayout as? SwipeGestureCoordinatorLayout)
            ?.addGestureListener(gestureHandler)
    }

    private fun showNativeMenu() {
        val profileManager = ProfileManager.getInstance(requireContext())
        val currentProfile = profileManager.getActiveProfile()
        
        val menuItems = listOf(
            Material3BrowserMenu.MenuItem.Action(
                id = "new_tab",
                title = getString(R.string.mozac_browser_menu_new_tab),
                iconRes = R.drawable.mozac_ic_tab_new_24,
                onClick = {
                    val activeProfile = profileManager.getActiveProfile()
                    val contextId = BrowserProfile
                        .contextIdFor(activeProfile, isPrivate = false)
                    components.tabsUseCases.addTab(
                        url = "about:homepage",
                        selectTab = true,
                        private = false,
                        contextId = contextId
                    )
                }
            ),
            Material3BrowserMenu.MenuItem.Action(
                id = "new_private_tab",
                title = getString(R.string.mozac_browser_menu_new_private_tab),
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_private_mode_24,
                onClick = {
                    browsingModeManager.mode = BrowsingMode.Private
                    components.tabsUseCases.addTab(
                        url = "about:homepage",
                        selectTab = true,
                        private = true,
                        contextId = "private"
                    )
                }
            ),
            Material3BrowserMenu.MenuItem.Divider,
            Material3BrowserMenu.MenuItem.Action(
                id = "history",
                title = getString(R.string.action_history),
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_history_24,
                onClick = {
                    startActivity(Intent(
                        requireContext(),
                        HistoryActivity::class.java
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            ),
            Material3BrowserMenu.MenuItem.Action(
                id = "bookmarks",
                title = getString(R.string.action_bookmarks),
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_bookmark_24,
                onClick = {
                    val bookmarksBottomSheet = BookmarksBottomSheetFragment.newInstance()
                    bookmarksBottomSheet.show(parentFragmentManager, "BookmarksBottomSheet")
                }
            ),
            Material3BrowserMenu.MenuItem.Divider,
            Material3BrowserMenu.MenuItem.Action(
                id = "settings",
                title = getString(R.string.settings),
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_settings_24,
                onClick = {
                    startActivity(Intent(
                        requireContext(),
                        SettingsActivity::class.java
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            ),
            Material3BrowserMenu.MenuItem.Action(
                id = "addons",
                title = getString(R.string.extensions),
                iconRes = R.drawable.mozac_ic_extension_24,
                onClick = {
                    val extensionsBottomSheet = ExtensionsBottomSheetFragment.newInstance()
                    extensionsBottomSheet.show(parentFragmentManager, ExtensionsBottomSheetFragment.TAG)
                }
            )
        )
        
        unifiedToolbar?.showMenu(menuItems)
    }
    
    private fun forceTabBarUpdate() {
        if (!isAdded || context == null) return
        
        val profileManager = ProfileManager.getInstance(requireContext())
        val store = components.store.state
        val isPrivateMode = profileManager.isPrivateMode()
        val currentProfile = profileManager.getActiveProfile()
        
        val expectedContextId = if (isPrivateMode) {
            "private"
        } else {
            "profile_${currentProfile.id}"
        }
        
        val filteredTabs = store.tabs.filter { tab ->
            if (tab.contextId == null) {
                !isPrivateMode && !tab.content.private
            } else {
                tab.content.private == isPrivateMode && tab.contextId == expectedContextId
            }
        }
        
        unifiedToolbar?.forceUpdateTabBar(filteredTabs, store.selectedTabId)
    }
}
