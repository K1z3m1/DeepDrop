package com.prirai.android.lr

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.annotation.CallSuper
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.prirai.android.lr.addons.WebExtensionPromptFeature
import com.prirai.android.lr.auth.PasskeyAuthFeature
import com.prirai.android.lr.browser.BrowsingMode
import com.prirai.android.lr.browser.SwipeGestureLayout
import com.prirai.android.lr.browser.home.SharedViewModel
import com.prirai.android.lr.components.toolbar.BrowserInteractor
import com.prirai.android.lr.components.toolbar.BrowserToolbarViewInteractor
import com.prirai.android.lr.components.toolbar.DefaultBrowserToolbarController
import com.prirai.android.lr.components.toolbar.DefaultBrowserToolbarMenuController
import com.prirai.android.lr.components.toolbar.ToolbarPosition
import com.prirai.android.lr.databinding.FragmentBrowserBinding
import com.prirai.android.lr.downloads.DownloadService
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.integration.ContextMenuIntegration
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.settings.HomepageChoice
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.CustomTabSessionState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.feature.media.fullscreen.MediaSessionFullscreenFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.search.SearchFeature
import mozilla.components.feature.session.FullScreenFeature
import mozilla.components.feature.session.PictureInPictureFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.tabs.LastTabFeature
import mozilla.components.lib.state.ext.consumeFlow
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.PermissionsFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger.Companion.debug
import mozilla.components.support.ktx.android.view.enterImmersiveMode
import mozilla.components.support.ktx.android.view.exitImmersiveMode
import mozilla.components.support.ktx.android.view.hideKeyboard
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged
import mozilla.components.support.utils.ext.requestInPlacePermissions
import java.lang.ref.WeakReference
import android.os.Environment
import android.view.Gravity
import com.prirai.android.lr.browser.WebContentPositionManager
import com.prirai.android.lr.browser.tabs.TabLRUManager
import com.prirai.android.lr.browser.tabs.TabsBottomSheetFragment
import com.prirai.android.lr.components.toolbar.unified.UnifiedToolbar
import mozilla.components.support.utils.DefaultDownloadFileUtils

@ExperimentalCoroutinesApi
abstract class BaseBrowserFragment : Fragment(), UserInteractionHandler, ActivityResultHandler,
    AccessibilityManager.AccessibilityStateChangeListener {
    private lateinit var browserAnimator: BrowserAnimator

    private var _browserInteractor: BrowserToolbarViewInteractor? = null
    protected val browserInteractor: BrowserToolbarViewInteractor
        get() = _browserInteractor!!

    @VisibleForTesting
    internal var _unifiedToolbar: UnifiedToolbar? = null

    @VisibleForTesting
    internal val unifiedToolbar: UnifiedToolbar?
        get() = _unifiedToolbar

    protected val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val contextMenuIntegration = ViewBoundFeatureWrapper<ContextMenuIntegration>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val sitePermissionsFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    
    private var webContentPositionManager: WebContentPositionManager? = null
    private val webExtensionPromptFeature = ViewBoundFeatureWrapper<WebExtensionPromptFeature>()
    private var fullScreenMediaSessionFeature =
        ViewBoundFeatureWrapper<MediaSessionFullscreenFeature>()
    private val searchFeature = ViewBoundFeatureWrapper<SearchFeature>()
    private val passkeyAuthFeature = ViewBoundFeatureWrapper<PasskeyAuthFeature>()
    private var pipFeature: PictureInPictureFeature? = null

    private val lastTabFeature = ViewBoundFeatureWrapper<LastTabFeature>()

    var customTabSessionId: String? = null

    @VisibleForTesting
    internal var browserInitialized: Boolean = false
    private var initUIJob: Job? = null

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private var _binding: FragmentBrowserBinding? = null
    protected val binding get() = _binding!!
    protected val bindingOrNull: FragmentBrowserBinding? get() = _binding

    @CallSuper
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val argSessionId = requireArguments().getString(EXTRA_SESSION_ID)
        customTabSessionId = argSessionId?.takeIf {
            requireContext().components.store.state.findCustomTab(it) != null
        }

        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        val view = binding.root
        
        val activity = activity as BrowserActivity
        binding.engineView.setActivityContext(activity)

        return view
    }

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initializeUI(view)

        if (customTabSessionId == null) {
            observeRestoreComplete(requireContext().components.store, findNavController())
        }

        observeTabSelection(requireContext().components.store)
    }

    private fun initializeUI(view: View) {
        val tab = getCurrentTab()
        browserInitialized = if (tab != null) {
            initializeUI(view, tab)
            true
        } else {
            false
        }
    }

    @CallSuper
    internal open fun initializeUI(view: View, tab: SessionState) {
        val context = requireContext()
        val store = context.components.store
        val activity = requireActivity() as BrowserActivity

        setupEdgeToEdgeForFragment()

        val toolbarHeight = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)

        browserAnimator = BrowserAnimator(
            fragment = WeakReference(this),
            engineView = WeakReference(binding.engineView),
            swipeRefresh = WeakReference(binding.swipeRefresh),
            viewLifecycleScope = WeakReference(viewLifecycleOwner.lifecycleScope)
        ).apply {
            beginAnimateInIfNecessary()
        }

        val openInFenixIntent = Intent(context, IntentReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(BrowserActivity.OPEN_TO_BROWSER, true)
        }

        val browserToolbarController = DefaultBrowserToolbarController(
            store = store,
            activity = activity,
            navController = findNavController(),
            engineView = binding.engineView,
            customTabSessionId = customTabSessionId,
            onTabCounterClicked = {
                thumbnailsFeature.get()?.requestScreenshot(trigger = "tab_counter_clicked")

                val tabsBottomSheet =
                    TabsBottomSheetFragment.newInstance()
                tabsBottomSheet.show(
                    parentFragmentManager,
                    TabsBottomSheetFragment.TAG
                )
            }
        )
        val browserToolbarMenuController = DefaultBrowserToolbarMenuController(
            activity = activity,
            navController = findNavController(),
            browserAnimator = browserAnimator,
            customTabSessionId = customTabSessionId,
            store = store,
        )

        _browserInteractor = BrowserInteractor(
            browserToolbarController,
            browserToolbarMenuController
        )

        initializeUnifiedToolbar(view, tab)


        contextMenuIntegration.set(
            feature = ContextMenuIntegration(
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                browserStore = components.store,
                tabsUseCases = components.tabsUseCases,
                contextMenuUseCases = components.contextMenuUseCases,
                parentView = view,
                sessionId = customTabSessionId
            ),
            owner = this,
            view = view
        )

        promptsFeature.set(
            PromptFeature(
                fragment = this,
                store = components.store,
                tabsUseCases = components.tabsUseCases,
                fragmentManager = parentFragmentManager,
                fileUploadsDirCleaner = components.fileUploadsDirCleaner,
                onNeedToRequestPermissions = { permissions ->
                    requestInPlacePermissions(REQUEST_KEY_PROMPT_PERMISSIONS, permissions) { result ->
                        promptsFeature.get()?.onPermissionsResult(
                            result.keys.toTypedArray(),
                            result.values.map {
                                when (it) {
                                    true -> PackageManager.PERMISSION_GRANTED
                                    false -> PackageManager.PERMISSION_DENIED
                                }
                            }.toIntArray(),
                        )
                    }
                },
            ),
            this,
            view,
        )

        fullScreenMediaSessionFeature.set(
            feature = MediaSessionFullscreenFeature(
                requireActivity(),
                context.components.store,
                customTabSessionId
            ),
            owner = this,
            view = view
        )

        pipFeature = PictureInPictureFeature(
            store = store,
            activity = requireActivity(),
            tabId = customTabSessionId
        )

        appLinksFeature.set(
            feature = AppLinksFeature(
                context,
                store = store,
                sessionId = customTabSessionId,
                fragmentManager = parentFragmentManager,
                launchInApp = { UserPreferences(context).launchInApp },
                loadUrlUseCase = context.components.sessionUseCases.loadUrl
            ),
            owner = this,
            view = view
        )

        sessionFeature.set(
            feature = SessionFeature(
                requireContext().components.store,
                requireContext().components.sessionUseCases.goBack,
                requireContext().components.sessionUseCases.goForward,
                binding.engineView,
                customTabSessionId
            ),
            owner = this,
            view = view
        )

        lastTabFeature.set(
            feature = LastTabFeature(
                store = requireContext().components.store,
                tabId = customTabSessionId,
                removeTabUseCase = requireContext().components.tabsUseCases.removeTab,
                activity = requireActivity(),
            ),
            owner = this,
            view = view,
        )

        searchFeature.set(
            feature = SearchFeature(store, customTabSessionId) { request, tabId ->
                val parentSession = store.state.findTabOrCustomTab(tabId)
                val useCase = if (request.isPrivate) {
                    requireContext().components.searchUseCases.newPrivateTabSearch
                } else {
                    requireContext().components.searchUseCases.newTabSearch
                }

                if (parentSession is CustomTabSessionState) {
                    useCase.invoke(request.query)
                    requireActivity().startActivity(openInFenixIntent)
                } else {
                    useCase.invoke(request.query, parentSessionId = parentSession?.id)
                }
            },
            owner = this,
            view = view
        )

        passkeyAuthFeature.set(
            feature = PasskeyAuthFeature(
                engine = requireContext().components.engine,
                activity = requireActivity(),
                store = store,
                onGetTabId = { store.state.selectedTabId }
            ),
            owner = this,
            view = view
        )

        val accentHighContrastColor = R.color.secondary_icon

        sitePermissionsFeature.set(
            feature = SitePermissionsFeature(
                context = context,
                storage = context.components.permissionStorage,
                fragmentManager = parentFragmentManager,
                promptsStyling = SitePermissionsFeature.PromptsStyling(
                    gravity = Gravity.CENTER,
                    shouldWidthMatchParent = true,
                    positiveButtonBackgroundColor = accentHighContrastColor,
                    positiveButtonTextColor = R.color.photonWhite
                ),
                dialogConfig = SitePermissionsFeature.DialogConfig(
                    shouldPreselectDoNotAskAgain = true,
                ),
                sessionId = customTabSessionId,
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_APP_PERMISSIONS)
                },
                onShouldShowRequestPermissionRationale = {
                    shouldShowRequestPermissionRationale(
                        it
                    )
                },
                store = store
            ),
            owner = this,
            view = view
        )

        downloadsFeature.set(
            feature = DownloadsFeature(
                requireContext().applicationContext,
                store = components.store,
                useCases = components.downloadsUseCases,
                fragmentManager = childFragmentManager,
                shouldForwardToThirdParties = { UserPreferences(requireContext()).promptExternalDownloader },
                onDownloadStopped = { download, id, status ->
                    debug("Download ID#$id $download with status $status is done.")
                },
                downloadFileUtils = DefaultDownloadFileUtils(
                    context = requireContext().applicationContext,
                    downloadLocation = {
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ).path
                    }
                ),
                downloadManager = FetchDownloadManager(
                    requireContext().applicationContext,
                    components.store,
                    DownloadService::class,
                    notificationsDelegate = components.notificationsDelegate
                ),
                tabId = customTabSessionId,
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_DOWNLOAD_PERMISSIONS)
                }),
            owner = this,
            view = view,
        )

        fullScreenFeature.set(
            feature = FullScreenFeature(
                requireContext().components.store,
                requireContext().components.sessionUseCases,
                customTabSessionId,
                ::viewportFitChange,
                ::fullScreenChanged
            ),
            owner = this,
            view = view
        )

        expandToolbarOnNavigation(store)

        binding.swipeRefresh.isEnabled = shouldPullToRefreshBeEnabled()

        if (binding.swipeRefresh.isEnabled) {
            val primaryTextColor = ContextCompat.getColor(requireContext(), R.color.primary_icon)
            binding.swipeRefresh.setColorSchemeColors(primaryTextColor)
            
            val statusBarHeight = resources.getDimensionPixelSize(
                resources.getIdentifier("status_bar_height", "dimen", "android")
            )
            binding.swipeRefresh.setProgressViewOffset(
                false,
                statusBarHeight,
                statusBarHeight + resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            )
            
            swipeRefreshFeature.set(
                feature = SwipeRefreshFeature(
                    requireContext().components.store,
                    requireContext().components.sessionUseCases.reload,
                    binding.swipeRefresh,
                    ({}),
                    customTabSessionId
                ),
                owner = this,
                view = view
            )
            
            consumeFlow(store) { flow ->
                flow.map { state -> state.selectedTabId }
                    .distinctUntilChanged()
                    .collect {
                        binding.swipeRefresh.isEnabled = shouldPullToRefreshBeEnabled()
                    }
            }
        }

        webExtensionPromptFeature.set(
            feature = WebExtensionPromptFeature(
                store = components.store,
                provideAddons = ::provideAddons,
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                onLinkClicked = { url, _ ->
                    components.tabsUseCases.addTab.invoke(url, selectTab = true)
                },
                view = view,
            ),
            owner = this,
            view = view,
        )

        initializeEngineView(toolbarHeight)
        
        context.components.visualCompletenessQueue.runIfReadyOrQueue {
        }
    }
    
    private suspend fun provideAddons(): List<Addon> {
        return withContext(IO) {
            val addons = requireContext().components.addonManager.getAddons(allowCache = false)
            addons
        }
    }

    @VisibleForTesting
    internal fun expandToolbarOnNavigation(store: BrowserStore) {
        consumeFlow(store) { flow ->
            flow.mapNotNull { state ->
                state.findTabOrCustomTabOrSelectedTab(customTabSessionId)
            }
                .ifAnyChanged { tab ->
                    arrayOf(tab.content.url, tab.content.loadRequest)
                }
                .collect {
                    unifiedToolbar?.expand()
                }
        }
    }

    val tab: SessionState
        get() = customTabSessionId?.let { components.store.state.findTabOrCustomTab(it) }
            ?: createTab("about:blank")

    @VisibleForTesting
    internal fun resumeDownloadDialogState(
        sessionId: String?
    ) {
        val savedDownloadState =
            sharedViewModel.downloadDialogState[sessionId]

        if (savedDownloadState == null || sessionId == null) {
            return
        }

        unifiedToolbar?.expand()
    }

    @VisibleForTesting
    internal fun shouldPullToRefreshBeEnabled(): Boolean {
        return UserPreferences(requireContext()).swipeToRefresh
    }

    @VisibleForTesting
    internal fun initializeEngineView(toolbarHeight: Int) {
        if (context == null) return
        if (_binding == null) return

        val context = requireContext()
        val prefs = UserPreferences(context)

        binding.engineView.setDynamicToolbarMaxHeight(toolbarHeight)
        
        unifiedToolbar?.post {
            if (_binding == null) return@post

            val actualToolbarHeight = unifiedToolbar?.height ?: toolbarHeight

            val swipeRefreshParams = binding.swipeRefresh.layoutParams as CoordinatorLayout.LayoutParams

            when (prefs.toolbarPosition) {
                ToolbarPosition.BOTTOM.ordinal -> {
                    binding.swipeRefresh.setPadding(0, 0, 0, 0)
                    swipeRefreshParams.bottomMargin = actualToolbarHeight
                    swipeRefreshParams.topMargin = 0
                }
                else -> {
                    binding.swipeRefresh.setPadding(0, actualToolbarHeight, 0, 0)
                    swipeRefreshParams.topMargin = 0
                    swipeRefreshParams.bottomMargin = 0
                }
            }

            binding.swipeRefresh.clipToPadding = false // Allow overscroll effects
            binding.swipeRefresh.layoutParams = swipeRefreshParams
            binding.swipeRefresh.requestLayout()
        }
    }

    @VisibleForTesting
    internal fun observeRestoreComplete(store: BrowserStore, navController: NavController) {
        val activity = activity as BrowserActivity
        consumeFlow(store) { flow ->
            flow.map { state -> state.restoreComplete }
                .distinctUntilChanged()
                .collect { restored ->
                    if (restored) {
                        val lruManager = TabLRUManager.getInstance(requireContext())
                        val currentTabIds = store.state.tabs.map { it.id }
                        lruManager.synchronizeWithTabs(currentTabIds)

                        val current = store.state
                        val tabs =
                            current.getNormalOrPrivateTabs(
                                activity.browsingModeManager.mode.isPrivate
                            )
                        if (tabs.isEmpty() || current.selectedTabId == null) {
                            try {
                                if (navController.currentDestination?.id != R.id.homeFragment) {
                                    navController.navigate(R.id.homeFragment)
                                }
                            } catch (e: Exception) {
                            }

                            createHomepagePreferenceTab()
                        }
                    }
                }
        }
    }

    private fun createHomepagePreferenceTab() {
        when (UserPreferences(requireContext()).homepageType) {
            HomepageChoice.VIEW.ordinal -> {
                components.tabsUseCases.addTab.invoke(
                    "about:homepage",
                    selectTab = true
                )
            }

            HomepageChoice.BLANK_PAGE.ordinal -> {
                components.tabsUseCases.addTab.invoke(
                    "about:blank",
                    selectTab = true
                )
            }

            HomepageChoice.ONLY_SEARCH_BAR.ordinal -> {
                components.tabsUseCases.addTab.invoke(
                    "about:homepage",
                    selectTab = true
                )
            }
        }
    }

    @VisibleForTesting
    internal fun observeTabSelection(store: BrowserStore) {
        consumeFlow(store) { flow ->
            flow.distinctUntilChangedBy {
                it.selectedTabId
            }
                .mapNotNull {
                    it.selectedTab
                }
                .collect {
                    handleTabSelected(it)
                }
        }
        
        val activity = activity as? BrowserActivity
        consumeFlow(store) { flow ->
            flow.map { state ->
                state.restoreComplete to state.getNormalOrPrivateTabs(
                    activity?.browsingModeManager?.mode?.isPrivate ?: false
                ).size
            }
                .distinctUntilChanged()
                .collect { (restored, _) ->
                    if (!restored || !isAdded || view == null) return@collect
                    if (store.state.getNormalOrPrivateTabs(
                            activity?.browsingModeManager?.mode?.isPrivate ?: false
                        ).isNotEmpty()
                    ) return@collect

                    try {
                        val navController = findNavController()
                        if (navController.currentDestination?.id != R.id.homeFragment) {
                            navController.navigate(R.id.homeFragment)
                        }

                        createHomepagePreferenceTab()
                    } catch (e: Exception) {
                    }
                }
        }
    }

    private fun handleTabSelected(selectedTab: TabSessionState) {
        if (!this.isRemoving) {
            updateThemeForSession(selectedTab)
        }

        if (browserInitialized) {
            view?.let { view ->
                fullScreenChanged(false)
                unifiedToolbar?.expand()
                resumeDownloadDialogState(selectedTab.id)
            }
        } else {
            view?.let { view -> initializeUI(view) }
        }
    }

    @CallSuper
    override fun onResume() {
        super.onResume()
        val components = requireContext().components

        val preferredColorScheme = components.darkEnabled()
        if (components.engine.settings.preferredColorScheme != preferredColorScheme) {
            components.engine.settings.preferredColorScheme = preferredColorScheme
            components.sessionUseCases.reload()
        }
        (requireActivity() as AppCompatActivity).supportActionBar?.hide()

        components.store.state.findTabOrCustomTabOrSelectedTab(customTabSessionId)?.let {
            updateThemeForSession(it)
        }
    }

    @CallSuper
    override fun onPause() {
        super.onPause()
        if (findNavController().currentDestination?.id != R.id.searchDialogFragment) {
            view?.hideKeyboard()
        }
    }

    @CallSuper
    override fun onStop() {
        super.onStop()
        initUIJob?.cancel()

        requireContext().components.store.state.findTabOrCustomTabOrSelectedTab(customTabSessionId)
            ?.let { session ->
                if (!session.content.pictureInPictureEnabled && fullScreenFeature.onBackPressed()) {
                    fullScreenChanged(false)
                }
            }
    }

    @CallSuper
    override fun onBackPressed(): Boolean {
        if (fullScreenFeature.onBackPressed()) return true
        if (promptsFeature.onBackPressed()) return true
        if (sessionFeature.onBackPressed()) return true

        val currentTab = requireContext().components.store.state.findTabOrCustomTabOrSelectedTab(customTabSessionId)
        if (currentTab?.content?.canGoBack == true) {
            requireContext().components.sessionUseCases.goBack.invoke(currentTab.id)
            return true
        }

        return lastTabFeature.onBackPressed()
    }

    final override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CUSTOM_TAB_SESSION_ID, customTabSessionId)
    }

    final override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.getString(KEY_CUSTOM_TAB_SESSION_ID)?.let {
            if (requireContext().components.store.state.findCustomTab(it) != null) {
                customTabSessionId = it
            }
        }
    }

    final override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        val feature: PermissionsFeature? = when (requestCode) {
            REQUEST_CODE_DOWNLOAD_PERMISSIONS -> downloadsFeature.get()
            REQUEST_CODE_PROMPT_PERMISSIONS -> promptsFeature.get()
            REQUEST_CODE_APP_PERMISSIONS -> sitePermissionsFeature.get()
            else -> null
        }
        feature?.onPermissionsResult(permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, data: Intent?, resultCode: Int): Boolean {
        return listOf(
            promptsFeature
        ).any { it.onActivityResult(requestCode, data, resultCode) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        promptsFeature.withFeature { it.onActivityResult(requestCode, data, resultCode) }
    }

    @VisibleForTesting
    internal fun updateThemeForSession(session: SessionState) {
        val sessionMode = BrowsingMode.fromBoolean(session.content.private)
        (activity as BrowserActivity).browsingModeManager.mode = sessionMode
    }

    @VisibleForTesting
    internal fun getCurrentTab(): SessionState? {
        return requireContext().components.store.state.findTabOrCustomTabOrSelectedTab(customTabSessionId)
    }

    override fun onHomePressed() = pipFeature?.onHomePressed() ?: false

    final override fun onPictureInPictureModeChanged(enabled: Boolean) {
        pipFeature?.onPictureInPictureModeChanged(enabled)
    }

    private fun viewportFitChange(layoutInDisplayCutoutMode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val layoutParams = activity?.window?.attributes
            layoutParams?.layoutInDisplayCutoutMode = layoutInDisplayCutoutMode
            activity?.window?.attributes = layoutParams
        }
    }

    @VisibleForTesting
    internal open fun fullScreenChanged(inFullScreen: Boolean) {
        if (inFullScreen) {
            requireActivity().enterImmersiveMode(
                setOnApplyWindowInsetsListener = { key: String, listener: OnApplyWindowInsetsListener ->
                    binding.engineView.addWindowInsetsListener(key, listener)
                },
            )
            (view as? SwipeGestureLayout)?.isSwipeEnabled = false

            unifiedToolbar?.collapse()
            unifiedToolbar?.visibility = View.GONE
            
            val browserEngine = binding.swipeRefresh.layoutParams as CoordinatorLayout.LayoutParams
            browserEngine.bottomMargin = 0
            browserEngine.topMargin = 0
            browserEngine.behavior = null
            binding.swipeRefresh.translationY = 0f
            binding.swipeRefresh.layoutParams = browserEngine
            
            binding.swipeRefresh.setPadding(0, 0, 0, 0)

            binding.engineView.setDynamicToolbarMaxHeight(0)
            
            onFullScreenModeChanged(true)
        } else {
            requireActivity().exitImmersiveMode(
                unregisterOnApplyWindowInsetsListener = binding.engineView::removeWindowInsetsListener,
            )
            (view as? SwipeGestureLayout)?.isSwipeEnabled = true

            unifiedToolbar?.visibility = View.VISIBLE
            val toolbarHeight = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            initializeEngineView(toolbarHeight)
            unifiedToolbar?.expand()
            
            binding.swipeRefresh.requestApplyInsets()
            
            onFullScreenModeChanged(false)
        }

        binding.swipeRefresh.isEnabled = false
    }
    
    protected open fun onFullScreenModeChanged(inFullScreen: Boolean) {
    }
    
    private fun setupEdgeToEdgeForFragment() {
        if (customTabSessionId == null) {
            webContentPositionManager = WebContentPositionManager(
                engineView = binding.engineView,
                swipeRefreshView = binding.swipeRefresh,
                rootView = binding.root,
                getTopToolbarHeight = { getTopToolbarHeight() },
                getBottomToolbarHeight = { getBottomToolbarHeight() }
            )
            webContentPositionManager?.initialize()
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.browserLayout) { _, insets ->
            insets
        }
    }
    
    private fun getTopToolbarHeight(): Int {
        val prefs = UserPreferences(requireContext())
        return if (prefs.toolbarPosition == ToolbarPosition.TOP.ordinal) {
            val toolbarView = unifiedToolbar
            if (toolbarView != null && toolbarView.height > 0) {
                val visibleHeight = toolbarView.height + toolbarView.translationY.toInt()
                maxOf(0, visibleHeight)
            } else {
                resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            }
        } else {
            0
        }
    }
    
    private fun getBottomToolbarHeight(): Int {
        val prefs = UserPreferences(requireContext())
        val isTopToolbar = prefs.toolbarPosition != ToolbarPosition.BOTTOM.ordinal
        
        return if (isTopToolbar) {
            var height = 0
            unifiedToolbar?.getTabGroupBar()?.takeIf { it.isVisible }?.let {
                height += it.height
            }
            
            if (height == 0) {
                resources.getDimensionPixelSize(R.dimen.contextual_toolbar_height)
            } else {
                height
            }
        } else {
            var height = 0
            unifiedToolbar?.getToolbarView()?.let { height += it.height }
            unifiedToolbar?.getTabGroupBar()?.takeIf { it.isVisible }?.let {
                height += it.height
            }
            
            if (height == 0) {
                resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            } else {
                height
            }
        }
    }
    
    protected fun requestWebContentPositionUpdate() {
        webContentPositionManager?.requestUpdate()
    }

    protected open fun initializeUnifiedToolbar(view: View, tab: SessionState) {
    }

    override fun onDestroyView() {
        super.onDestroyView()

        webContentPositionManager?.destroy()
        webContentPositionManager = null
        _binding?.engineView?.setActivityContext(null)
        _browserInteractor = null
        _unifiedToolbar = null
        _binding = null
    }

    companion object {
        private const val KEY_CUSTOM_TAB_SESSION_ID = "custom_tab_session_id"
        private const val REQUEST_KEY_PROMPT_PERMISSIONS = "promptFeature"
        private const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1
        private const val REQUEST_CODE_PROMPT_PERMISSIONS = 2
        private const val REQUEST_CODE_APP_PERMISSIONS = 3
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) {
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        unifiedToolbar?.getBrowserToolbar()?.dismissMenu()
    }
}
