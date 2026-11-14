package com.prirai.android.lr

import androidx.compose.runtime.getValue
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.prirai.android.lr.browser.history.TabHistoryBottomSheet
import com.prirai.android.lr.browser.history.TabHistoryMode
import com.prirai.android.lr.browser.home.compose.jumpBackInItems
import com.prirai.android.lr.browser.tabs.NewTabCreator
import com.prirai.android.lr.browser.home.compose.recentlyClosedItems
import com.prirai.android.lr.browser.toolbar.ToolbarGestureHandler
import com.prirai.android.lr.components.toolbar.ToolbarMenu
import com.prirai.android.lr.downloads.DownloadsBottomSheetFragment
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.ext.nav
import com.prirai.android.lr.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import com.prirai.android.lr.addons.ExtensionsBottomSheetFragment
import com.prirai.android.lr.browser.bookmark.repository.BookmarkManager
import com.prirai.android.lr.browser.bookmark.ui.AddBookmarkSiteDialog
import com.prirai.android.lr.browser.bookmark.ui.BookmarksBottomSheetFragment
import com.prirai.android.lr.browser.home.ComposeHomeFragment
import com.prirai.android.lr.browser.home.compose.AddShortcutDialog
import com.prirai.android.lr.browser.home.compose.HomeScreen
import com.prirai.android.lr.browser.home.compose.HomeViewModel
import com.prirai.android.lr.browser.home.compose.HomeViewModelFactory
import com.prirai.android.lr.browser.home.compose.ProfileInfo
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.browser.shortcuts.AddShortcutDialogFragment
import com.prirai.android.lr.browser.shortcuts.ShortcutDatabase
import com.prirai.android.lr.browser.tabs.TabLRUManager
import com.prirai.android.lr.browser.tabs.TabsBottomSheetFragment
import com.prirai.android.lr.browser.tabs.compose.TabSheetStateManager
import com.prirai.android.lr.components.menu.Material3BrowserMenu
import com.prirai.android.lr.components.toolbar.ToolbarPosition
import com.prirai.android.lr.components.toolbar.unified.UnifiedToolbar
import com.prirai.android.lr.history.HistoryActivity
import com.prirai.android.lr.settings.HomepageBackgroundChoice
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.toolbar.ToolbarActionsListener
import com.prirai.android.lr.ui.theme.LRTheme

@ExperimentalCoroutinesApi
class BrowserFragment : BaseBrowserFragment(), UserInteractionHandler {
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()

    private var lastTabIds = setOf<String>()
    
    private lateinit var homeViewModel: HomeViewModel

    private val cachedToolbarPosition: Int by lazy {
        UserPreferences(requireContext()).toolbarPosition
    }

    override fun initializeUI(view: View, tab: SessionState) {
        super.initializeUI(view, tab)

        val context = requireContext()
        val components = context.components
        
        initializeHomeViewModel()
        
        setupHomePageComposeView()
        
        updateContentVisibility(tab.content.url)

        unifiedToolbar?.getBrowserToolbar()?.let { toolbar ->
            binding.gestureLayout.addGestureListener(
                ToolbarGestureHandler(
                    activity = requireActivity(),
                    contentLayout = binding.browserLayout,
                    tabPreview = binding.tabPreview,
                    toolbarLayout = toolbar,
                    store = components.store,
                    selectTabUseCase = components.tabsUseCases.selectTab
                )
            )
        }

        thumbnailsFeature.set(
            feature = BrowserThumbnails(context, binding.engineView, components.store),
            owner = this,
            view = view
        )

        windowFeature.set(
            feature = WindowFeature(
                store = components.store, tabsUseCases = components.tabsUseCases
            ), owner = this, view = view
        )

        observeTabChangesForToolbar()
        observeDownloadsForSheet()
        observeNewTabSelection()
    }

    override fun onResume() {
        super.onResume()

        binding.tabPreview.visibility = View.GONE

        applyThemeColors()
        
        updateToolbarForPrivateMode()
    }
    
    private fun updateToolbarForPrivateMode() {
        val selectedTab = requireContext().components.store.state.tabs.find { 
            it.id == requireContext().components.store.state.selectedTabId 
        }
        val isPrivate = selectedTab?.content?.private == true
        
        _unifiedToolbar?.applyPrivateMode(isPrivate)
        
        updateStatusBarForPrivateMode(isPrivate)
    }
    
    private fun updateStatusBarForPrivateMode(isPrivate: Boolean) {
        val window = requireActivity().window
        if (isPrivate) {
            val purpleColor = Color.parseColor("#6200EE")
            window.statusBarColor = purpleColor
        } else {
            val typedValue = TypedValue()
            requireContext().theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, 
                typedValue, 
                true
            )
            window.statusBarColor = typedValue.data
        }
    }
    
    private fun applyThemeColors() {
        val bgColor = ThemeManager.getBackgroundColor(requireContext())
        
        binding.gestureLayout.setBackgroundColor(bgColor)
        binding.browserWindow.setBackgroundColor(bgColor)
        binding.browserLayout.setBackgroundColor(bgColor)
        binding.swipeRefresh.setBackgroundColor(bgColor)
    }

    private fun observeDownloadsForSheet() {
        val shownDownloadIds = mutableSetOf<String>()
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().components.store.flow()
                .map { state ->
                    state.downloads.values.filter {
                        it.status == DownloadState.Status.INITIATED ||
                        it.status == DownloadState.Status.DOWNLOADING
                    }
                }
                .distinctUntilChanged()
                .collect { activeDownloads ->
                    val newDownloads = activeDownloads.filter { it.id !in shownDownloadIds }
                    if (newDownloads.isNotEmpty()) {
                        newDownloads.forEach { shownDownloadIds.add(it.id) }
                        if (!isDetached && isAdded && activity != null) {
                            val existing = parentFragmentManager.findFragmentByTag(DownloadsBottomSheetFragment.TAG)
                            if (existing == null) {
                                DownloadsBottomSheetFragment.newInstance()
                                    .show(parentFragmentManager, DownloadsBottomSheetFragment.TAG)
                            }
                        }
                    }
                }
        }
    }

    private fun observeNewTabSelection() {
        if (!isAdded) return
        var knownTabIds = requireContext().components.store.state.tabs.map { it.id }.toSet()
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().components.store.flow()
                .map { state -> state.selectedTabId to state.tabs.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { (selectedId, currentIds) ->
                    val newIds = currentIds - knownTabIds
                    knownTabIds = currentIds
                    if (selectedId != null && selectedId in newIds) {
                        TabSheetStateManager.notifyTabSheetDismissed()
                    }
                }
        }
    }

    private fun observeTabChangesForToolbar() {
        if (!isAdded) return
        
        val lruManager = TabLRUManager.getInstance(requireContext())
        
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().components.store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
                flow.mapNotNull { state ->
                    if (!isAdded) return@mapNotNull null
                    val currentTab = state.tabs.find { it.id == state.selectedTabId }
                    currentTab
                }.ifAnyChanged { tab ->
                    arrayOf(
                        tab.content.loading,
                        tab.content.canGoBack,
                        tab.content.canGoForward,
                        tab.content.url,
                        tab.id
                    )
                }.collect { tab ->
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded) return@launch
            
            var lastTabIds = requireContext().components.store.state.tabs.map { it.id }.toSet()
            requireContext().components.store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
                flow.mapNotNull { state -> 
                    if (!isAdded) return@mapNotNull null
                    state.tabs.map { it.id }.toSet() 
                }.distinctUntilChangedBy { it.hashCode() }
                .collect { currentTabIds: Set<String> ->
                    val removedTabIds = lastTabIds - currentTabIds
                    removedTabIds.forEach { tabId: String ->
                        lruManager.onTabClosed(tabId)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().components.store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
                flow.mapNotNull { state ->
                    if (!isAdded) return@mapNotNull null
                    state
                }.distinctUntilChangedBy { it.selectedTabId }.collect { state ->
                    if (!isAdded || context == null) return@collect
                    
                    val fragmentContext = requireContext()
                    
                    state.selectedTabId?.let { tabId ->
                        lruManager.onTabSelected(tabId)
                    }
                    
                    val selectedTab = state.tabs.find { it.id == state.selectedTabId }
                    selectedTab?.let { tab ->
                        updateToolbarForPrivateMode()
                        
                        val tabContextId = tab.contextId
                        val profileManager = ProfileManager.getInstance(fragmentContext)
                        val currentProfile = profileManager.getActiveProfile()

                        if (tab.content.private || tabContextId == "private") {
                        } else if (tabContextId == null || tabContextId == "profile_default") {
                            if (currentProfile.id != "default") {
                                val defaultProfile = BrowserProfile.getDefaultProfile()
                                profileManager.setActiveProfile(defaultProfile)
                                unifiedToolbar?.getTabGroupBar()?.updateProfileIcon(defaultProfile)
                            }
                        } else {
                            val profileId = tabContextId!!.removePrefix("profile_")
                            val allProfiles = profileManager.getAllProfiles()
                            val profile = allProfiles.find { it.id == profileId }

                            if (profile != null && currentProfile.id != profileId) {
                                profileManager.setActiveProfile(profile)
                                unifiedToolbar?.getTabGroupBar()?.updateProfileIcon(profile)
                            }
                        }
                    }
                }
            }
        }

        components.store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
            flow.mapNotNull { state -> state.selectedTabId }
                .distinctUntilChanged()
                .collect { tabId: String ->
                    if (!isAdded) return@collect
                    val tab = components.store.state.tabs.find { it.id == tabId }
                    tab?.let {
                        updateContentVisibility(it.content.url)
                    }
                }
        }

        components.store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
            flow.mapNotNull { state ->
                state.tabs.find { it.id == state.selectedTabId }
            }
            .distinctUntilChangedBy { it.content.url }
            .collect { tab ->
                if (!isAdded) return@collect
                updateContentVisibility(tab.content.url)
            }
        }
    }

    override fun initializeUnifiedToolbar(view: View, tab: SessionState) {
        val prefs = UserPreferences(requireContext())

        try {
            _unifiedToolbar = UnifiedToolbar.create(
                context = requireContext(),
                parent = binding.browserLayout,
                lifecycleOwner = viewLifecycleOwner,
                interactor = browserInteractor,
                customTabSession = null,
                store = requireContext().components.store
            )

            unifiedToolbar?.setEngineView(binding.engineView)

            unifiedToolbar?.getToolbarView()?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                requestWebContentPositionUpdate()
            }

            if (prefs.toolbarPosition == ToolbarPosition.TOP.ordinal) {
                val bottomContainer = unifiedToolbar?.getBottomComponentsContainer()
                
                bottomContainer?.let { container ->
                    val coordinatorLayout = binding.browserLayout as? CoordinatorLayout
                    
                    coordinatorLayout?.let {
                        val layoutParams = CoordinatorLayout.LayoutParams(
                            CoordinatorLayout.LayoutParams.MATCH_PARENT,
                            CoordinatorLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.BOTTOM
                        }
                        
                        val elevationDp = 3f * resources.displayMetrics.density
                        val elevatedColor = ElevationOverlayProvider(requireContext())
                            .compositeOverlayWithThemeSurfaceColorIfNeeded(elevationDp)
                        container.setBackgroundColor(elevatedColor)
                        
                        container.elevation = 16f * resources.displayMetrics.density
                        
                        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
                            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                            view.setPadding(0, 0, 0, navigationBars.bottom)
                            insets
                        }
                        
                        it.addView(container, layoutParams)
                        container.visibility = View.VISIBLE
                        
                        container.layoutTransition = null
                        
                        container.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        
                        ViewCompat.requestApplyInsets(container)
                    }
                }
            }
            
            unifiedToolbar?.setOnTabSelectedListener { tabId ->
                requireContext().components.tabsUseCases.selectTab(tabId)
            }

            unifiedToolbar?.setOnExpansionStateChangedListener { expanded ->
                requestWebContentPositionUpdate()
            }

            unifiedToolbar?.setOnMinimalStateChangedListener {
                requestWebContentPositionUpdate()
            }
            
            unifiedToolbar?.setOnToolbarOffsetChangedListener { currentOffset, totalHeight ->
                adjustWebContentMarginsForToolbarOffset(currentOffset, totalHeight)
            }

            val contextualListener = object : ToolbarActionsListener {
                override fun onBackClicked() {
                    requireContext().components.sessionUseCases.goBack()
                }

                override fun onForwardClicked() {
                    goForwardOrFromNewTab()
                }

                override fun onBackLongClicked() {
                    TabHistoryBottomSheet.showIfAvailable(
                        parentFragmentManager,
                        requireContext().components.store,
                        TabHistoryMode.Back,
                        null
                    )
                }

                override fun onForwardLongClicked() {
                    TabHistoryBottomSheet.showIfAvailable(
                        parentFragmentManager,
                        requireContext().components.store,
                        TabHistoryMode.Forward,
                        null
                    )
                }

                override fun onBookmarksClicked() {
                    val bookmarksBottomSheet = BookmarksBottomSheetFragment.newInstance()
                    bookmarksBottomSheet.show(parentFragmentManager, "BookmarksBottomSheet")
                }

                override fun onShareClicked() {
                    val state = requireContext().components.store.state
                    val selectedTab = state.tabs.find { it.id == state.selectedTabId }
                    val currentUrl = selectedTab?.content?.url
                    if (!currentUrl.isNullOrBlank()) {
                        val shareIntent = Intent()
                        shareIntent.action = Intent.ACTION_SEND
                        shareIntent.putExtra(Intent.EXTRA_TEXT, currentUrl)
                        shareIntent.type = "text/plain"
                        startActivity(Intent.createChooser(shareIntent, "Share"))
                    }
                }

                override fun onTabCountClicked() {
                    try {
                        val tabsBottomSheet =
                            TabsBottomSheetFragment.newInstance()
                        tabsBottomSheet.show(
                            parentFragmentManager,
                            TabsBottomSheetFragment.TAG
                        )
                    } catch (e: Exception) {
                    }
                }

                override fun onExtensionsClicked() {
                    try {
                        val extensionsBottomSheet =
                            ExtensionsBottomSheetFragment.newInstance()
                        extensionsBottomSheet.show(
                            parentFragmentManager,
                            ExtensionsBottomSheetFragment.TAG
                        )
                    } catch (e: Exception) {
                    }
                }

                override fun onMenuClicked() {
                    try {
                        if (view != null && isAdded) {
                            showBrowserMenu()
                        }
                    } catch (e: Exception) {
                        Log.e("BrowserFragment", "Error showing menu", e)
                    }
                }

                override fun onSearchClicked() {
                    val directions = NavGraphDirections.actionGlobalSearchDialog(sessionId = null)
                    nav(R.id.browserFragment, directions)
                }

                override fun onNewTabClicked() {
                    requireContext().components.tabsUseCases.addTab.invoke("about:homepage")
                    TabSheetStateManager.notifyTabSheetDismissed()
                }
            }

            unifiedToolbar?.setContextualToolbarListener(contextualListener)

            observeTabChangesForModernToolbar()

            initializeModernToolbarWithCurrentState()

        } catch (e: Exception) {
            Log.e("BrowserFragment", "UnifiedToolbar init failed", e)
        }
    }

    private fun observeTabChangesForModernToolbar() {
        var lastTabIds = emptySet<String>()

        viewLifecycleOwner.lifecycleScope.launch {
            val store = requireContext().components.store

            store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
                flow.mapNotNull { state ->
                    if (!isAdded) return@mapNotNull null

                    val activity = activity as? BrowserActivity ?: return@mapNotNull null
                    val isPrivateMode = activity.browsingModeManager.mode.isPrivate
                    val profileManager =
                        ProfileManager.getInstance(requireContext())
                    val currentProfile = profileManager.getActiveProfile()
                    val currentProfileContextId = if (isPrivateMode) {
                        "private"
                    } else {
                        "profile_${currentProfile.id}"
                    }

                    val filteredTabs = state.tabs.filter { tab ->
                        if (tab.content.private != isPrivateMode) {
                            false
                        } else {
                            (tab.contextId == currentProfileContextId) || (tab.contextId == null)
                        }
                    }

                    state to filteredTabs
                }.distinctUntilChangedBy { (state, filteredTabs) ->
                    Triple(
                        filteredTabs.map { it.id },
                        state.selectedTabId,
                        filteredTabs.joinToString("|") { tab ->
                            "${tab.id}:${tab.content.title}:${tab.content.url}:${tab.content.icon?.hashCode()}:${tab.content.loading}"
                        }
                    )
                }.collect { (state, filteredTabs) ->
                    if (!isAdded) return@collect

                    val currentTabIds = state.tabs.map { it.id }.toSet()
                    currentTabIds - lastTabIds
                }
            }
        }
    }

    private fun initializeModernToolbarWithCurrentState() {
    }

    private fun adjustWebContentMarginsForToolbarOffset(currentOffset: Int, totalHeight: Int) {
        if (!isAdded || view == null) {
            return
        }

        val visibleHeight = (totalHeight - currentOffset).coerceAtLeast(0)

        val lp = binding.swipeRefresh.layoutParams
            as? CoordinatorLayout.LayoutParams
            ?: return

        when (cachedToolbarPosition) {
            ToolbarPosition.BOTTOM.ordinal -> {
                if (lp.bottomMargin != visibleHeight) {
                    lp.bottomMargin = visibleHeight
                    binding.swipeRefresh.layoutParams = lp
                }
                if (binding.swipeRefresh.paddingTop != 0 ||
                    binding.swipeRefresh.paddingBottom != 0) {
                    binding.swipeRefresh.setPadding(0, 0, 0, 0)
                }
            }
            else -> {
                if (binding.swipeRefresh.paddingTop != visibleHeight) {
                    binding.swipeRefresh.setPadding(0, visibleHeight, 0, 0)
                }
                if (lp.bottomMargin != 0) {
                    lp.bottomMargin = 0
                    binding.swipeRefresh.layoutParams = lp
                }
            }
        }
    }

    override fun onFullScreenModeChanged(inFullScreen: Boolean) {
        if (inFullScreen) {
            expandBrowserViewForFullscreen()
        } else {
            collapseBrowserViewFromFullscreen()
        }
    }

    private fun expandBrowserViewForFullscreen() {
        unifiedToolbar?.apply {
            collapse()
            isVisible = false
        }
    }

    private fun collapseBrowserViewFromFullscreen() {
        unifiedToolbar?.apply {
            isVisible = true
            post {
                requestApplyInsets()
                expand()
                requestLayout()
        }
    }
}
    private fun showBrowserMenu() {
        val store = requireContext().components.store
        val selectedTab = store.state.tabs.find { it.id == store.state.selectedTabId }
        
        val menuItems = mutableListOf<Material3BrowserMenu.MenuItem>()
        
        menuItems.add(
            Material3BrowserMenu.MenuItem.QuadRow(
                title1 = getString(R.string.action_history),
                icon1 = mozilla.components.ui.icons.R.drawable.mozac_ic_history_24,
                onClick1 = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.History)
                },
                title2 = getString(R.string.action_bookmarks),
                icon2 = mozilla.components.ui.icons.R.drawable.mozac_ic_bookmark_24,
                onClick2 = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.Bookmarks)
                },
                title3 = getString(R.string.action_print),
                icon3 = mozilla.components.ui.icons.R.drawable.mozac_ic_print_24,
                onClick3 = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.Print)
                },
                title4 = getString(R.string.save_as_pdf),
                icon4 = mozilla.components.ui.icons.R.drawable.mozac_ic_save_file_24,
                onClick4 = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.PDF)
                }
            )
        )

        menuItems.add(Material3BrowserMenu.MenuItem.Divider)

        menuItems.add(
            Material3BrowserMenu.MenuItem.IconRow(
                items = listOf(
                    Material3BrowserMenu.IconRowItem(
                        title = "Add Bookmark",
                        iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_bookmark_fill_24,
                        onClick = {
                            selectedTab?.let { tab ->
                                val title = tab.content.title.ifEmpty { tab.content.url }
                                val url = tab.content.url
                                val dialog = AddBookmarkSiteDialog(
                                    requireActivity(),
                                    title,
                                    url
                                )
                                dialog.setOnClickListener { _, _ ->
                                    val saved = BookmarkManager
                                        .getInstance(requireContext())
                                        .save()
                                    Toast.makeText(
                                        requireContext(),
                                        if (saved) "Bookmark added" else "Failed to save bookmark",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                dialog.show()
                            }
                        }
                    ),
                    Material3BrowserMenu.IconRowItem(
                        title = getString(R.string.menu_add_to_favorite),
                        iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_pin_fill_24,
                        onClick = {
                            selectedTab?.let { tab ->
                                val title = tab.content.title.ifEmpty { tab.content.url }
                                val url = tab.content.url
                                val dialog = AddShortcutDialogFragment.newInstance(url, title)
                                dialog.show(parentFragmentManager, AddShortcutDialogFragment.TAG)
                            }
                        }
                    )
                )
            )
        )

        menuItems.add(Material3BrowserMenu.MenuItem.Divider)

        menuItems.add(
            Material3BrowserMenu.MenuItem.Toggle(
                id = "desktop_mode",
                title = getString(R.string.desktop_mode),
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_device_desktop_24,
                isChecked = selectedTab?.content?.desktopMode ?: false,
                onToggle = { checked ->
                    browserInteractor.onBrowserToolbarMenuItemTapped(
                        ToolbarMenu.Item.RequestDesktop(checked)
                    )
                }
            )
        )

        val translateItem = if (UserPreferences(requireContext()).translationsEnabled &&
            selectedTab?.content?.url?.startsWith("http") == true
        ) {
            val translated = selectedTab.translationsState.isTranslated
            Material3BrowserMenu.IconRowItem(
                title = getString(
                    if (translated) R.string.show_original_page else R.string.translate_page
                ),
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_translate_24,
                onClick = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.Translate)
                }
            )
        } else {
            null
        }

        menuItems.add(
            Material3BrowserMenu.MenuItem.IconRow(
                items = listOfNotNull(
                    translateItem,
                    Material3BrowserMenu.IconRowItem(
                        title = getString(R.string.extensions),
                        iconRes = R.drawable.mozac_ic_extension_24,
                        onClick = {
                            val extensionsBottomSheet = ExtensionsBottomSheetFragment.newInstance()
                            extensionsBottomSheet.show(parentFragmentManager, ExtensionsBottomSheetFragment.TAG)
                        }
                    ),
                    Material3BrowserMenu.IconRowItem(
                        title = getString(R.string.mozac_browser_menu_new_private_tab),
                        iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_private_mode_24,
                        onClick = {
                            browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.NewPrivateTab)
                        }
                    ),
                )
            )
        )

        menuItems.add(Material3BrowserMenu.MenuItem.Divider)

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
            menuItems.add(
                Material3BrowserMenu.MenuItem.Action(
                    id = "add_to_homescreen",
                    title = getString(R.string.action_add_to_homescreen),
                    iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_device_mobile_24,
                    onClick = {
                        browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.AddToHomeScreen)
                    }
                )
            )
        }

        selectedTab?.let { tab ->
            if (requireContext().components.appLinksUseCases.appLinkRedirect(tab.content.url).hasExternalApp()) {
                menuItems.add(
                    Material3BrowserMenu.MenuItem.Action(
                        id = "open_in_app",
                        title = getString(R.string.mozac_feature_contextmenu_open_link_in_external_app),
                        iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_external_link_24,
                        onClick = {
                            browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.OpenInApp)
                        }
                    )
                )
            }
        }

        menuItems.add(
            Material3BrowserMenu.MenuItem.Action(
                id = "downloads",
                title = "Downloads",
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_download_24,
                onClick = {
                    val downloadsBottomSheet = DownloadsBottomSheetFragment.newInstance()
                    downloadsBottomSheet.show(parentFragmentManager, DownloadsBottomSheetFragment.TAG)
                }
            )
        )

        menuItems.add(
            Material3BrowserMenu.MenuItem.Action(
                id = "settings",
                title = getString(R.string.settings),
                iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_settings_24,
                onClick = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.Settings)
                }
            )
        )

        menuItems.add(Material3BrowserMenu.MenuItem.Divider)

        menuItems.add(
            Material3BrowserMenu.MenuItem.Action(
                id = "new_tab",
                title = getString(R.string.mozac_browser_menu_new_tab),
                iconRes = R.drawable.mozac_ic_tab_new_24,
                onClick = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(ToolbarMenu.Item.NewTab)
                }
            )
        )

        selectedTab?.let { tab ->
            menuItems.add(
                Material3BrowserMenu.MenuItem.Action(
                    id = "close_tab",
                    title = getString(R.string.menu_close_tab),
                    iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_cross_24,
                    onClick = {
                        requireContext().components.tabsUseCases.removeTab(tab.id)
                    }
                )
            )
        }

        menuItems.add(Material3BrowserMenu.MenuItem.Divider)
        
        menuItems.add(
            Material3BrowserMenu.MenuItem.ToolbarRow(
                onBackClick = {
                    requireContext().components.sessionUseCases.goBack()
                },
                onForwardClick = {
                    goForwardOrFromNewTab()
                },
                onReloadClick = {
                    requireContext().components.sessionUseCases.reload()
                },
                onShareClick = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(
                        ToolbarMenu.Item.Share
                    )
                },
                onCollapseClick = unifiedToolbar
                    ?.takeIf { it.getTabGroupBar() != null }
                    ?.let { tb ->
                        {
                            if (tb.isMinimal()) tb.exitMinimalState() else tb.enterMinimalState()
                        }
                    },
                tabBarHidden = unifiedToolbar?.isMinimal() == true,
                backEnabled = selectedTab?.content?.canGoBack ?: false,
                forwardEnabled = selectedTab?.content?.canGoForward ?: false,
                onBackLongClick = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(
                        ToolbarMenu.Item.Back(viewHistory = true)
                    )
                },
                onForwardLongClick = {
                    browserInteractor.onBrowserToolbarMenuItemTapped(
                        ToolbarMenu.Item.Forward(viewHistory = true)
                    )
                }
            )
        )
        
        val anchor = unifiedToolbar?.getToolbarView() ?: view
        val preferBottom = cachedToolbarPosition ==
            ToolbarPosition.BOTTOM.ordinal

        anchor?.let {
            Material3BrowserMenu(
                requireContext(),
                menuItems
            ).show(it, preferBottom)
        }
    }

    private fun goForwardOrFromNewTab() {
        val store = requireContext().components.store
        val selectedTab = store.state.tabs.find { it.id == store.state.selectedTabId }
        if (selectedTab != null &&
            selectedTab.content.url == "about:homepage" &&
            selectedTab.content.history.currentIndex == NewTabCreator.NEW_TAB_INDEX
        ) {
            store.let {
                requireContext().components.sessionUseCases.goToHistoryIndex(0)
            }
        } else {
            requireContext().components.sessionUseCases.goForward()
        }
    }

    override fun onBackPressed(): Boolean {
        val handled = super.onBackPressed()
        
        if (!handled) {
            val store = requireContext().components.store
            val selectedTab = store.state.tabs.find { it.id == store.state.selectedTabId }
            
            if (selectedTab != null && !selectedTab.content.canGoBack) {
                if (selectedTab.content.url == "about:homepage" ||
                    selectedTab.content.url == "about:blank"
                ) {
                    val lastUsedTab = store.state.tabs
                        .filter {
                            it.id != selectedTab.id &&
                                it.content.private == selectedTab.content.private &&
                                it.contextId == selectedTab.contextId
                        }
                        .maxByOrNull { it.lastAccess }
                    if (lastUsedTab != null) {
                        requireContext().components.tabsUseCases.selectTab(lastUsedTab.id)
                        requireContext().components.tabsUseCases.removeTab(selectedTab.id)
                    }
                    return true
                }

                val url = selectedTab.content.url
                val isInternalPage = url.startsWith("about:") ||
                    url.startsWith("chrome:") ||
                    url.startsWith("moz-extension:")
                if (!selectedTab.content.private && !isInternalPage) {
                    NewTabCreator.setTabToNewTabPage(
                        store, selectedTab.id
                    )
                    return true
                }

                try {
                    val navController = NavHostFragment.findNavController(this)
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        ComposeHomeFragment.navigateToHome()
                        navController.navigate(R.id.homeFragment)
                        return true
                    }
                } catch (e: Exception) {
                }
            }
        }
        
        return handled
    }
    
    private fun initializeHomeViewModel() {
        val database = ShortcutDatabase
            .getInstance(requireContext())

        val factory = HomeViewModelFactory(
            bookmarkManager = BookmarkManager.getInstance(requireContext()),
            shortcutDao = database.shortcutDao(),
            historyStorage = requireContext().components.historyStorage,
        )

        homeViewModel = ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }
    
    private fun setupHomePageComposeView() {
        val homePageView = binding.root.findViewById<ComposeView>(
            R.id.homePageComposeView
        ) ?: return
        
        homePageView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        
        homePageView.setContent {
            val store = requireContext().components.store
            val browsingModeManager = (requireActivity() as BrowserActivity).browsingModeManager
            
            val selectedTabId by store.observeAsComposableState { state -> state.selectedTabId }
            
            val selectedTab = selectedTabId?.let { id ->
                store.state.tabs.find { it.id == id }
            }

            val isPrivateMode = selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate

            val shortcuts by homeViewModel.shortcuts.collectAsState()
            val showAddDialog by homeViewModel.showAddShortcutDialog.collectAsState()
            val jumpBackInHistory by homeViewModel.jumpBackInHistory.collectAsState()
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
                    onProfileClick = {}, // Profile icon is display-only
                    backgroundImageUrl = backgroundImageUrl,
                    isToolbarAtTop = isToolbarAtTop,
                    homepageType = homepageType,
                    wallpaperDimmed = wallpaperDimmed,
                    onShortcutClick = { shortcut ->
                        components.sessionUseCases.loadUrl(shortcut.url)
                    },
                    onShortcutDelete = { shortcut ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Delete Shortcut")
                            .setMessage("Are you sure you want to delete '${shortcut.title}'?")
                            .setPositiveButton("Delete") { _, _ ->
                                homeViewModel.deleteShortcut(shortcut)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    },
                    onShortcutAdd = { homeViewModel.showAddShortcutDialog() },
                    onHistoryClick = {
                        startActivity(
                            Intent(
                                requireContext(),
                                HistoryActivity::class.java
                            )
                        )
                    },
                    onBookmarksClick = {
                        BookmarksBottomSheetFragment
                            .newInstance()
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
                        try {
                            NavHostFragment.findNavController(this@BrowserFragment).navigate(directions)
                        } catch (e: Exception) {
                        }
                    }
                )

                if (showAddDialog) {
                    AddShortcutDialog(
                        onDismiss = { homeViewModel.hideAddShortcutDialog() },
                        onSave = { url, title ->
                            homeViewModel.addShortcut(url, title)
                        }
                    )
                }
            }
        }
    }
    
    private fun updateContentVisibility(url: String) {
        val binding = bindingOrNull ?: return
        val isHomepage = url == "about:homepage"

        val homePageView = binding.root.findViewById<ComposeView>(
            R.id.homePageComposeView
        )

        binding.swipeRefresh.visibility = View.VISIBLE
        binding.engineView.asView().visibility = View.VISIBLE
        homePageView?.apply {
            visibility = if (isHomepage) View.VISIBLE else View.GONE
            isClickable = isHomepage
            isFocusable = isHomepage
        }
    }
}
