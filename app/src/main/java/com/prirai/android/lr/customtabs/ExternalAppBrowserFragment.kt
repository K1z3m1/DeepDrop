@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.customtabs

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.prirai.android.lr.BrowserActivity
import com.prirai.android.lr.R
import com.prirai.android.lr.databinding.FragmentBrowserBinding
import com.prirai.android.lr.downloads.DownloadService
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.integration.ContextMenuIntegration
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.ui.theme.LRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapNotNull
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.customtabs.CustomTabWindowFeature
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.media.fullscreen.MediaSessionFullscreenFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.session.PictureInPictureFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import kotlin.math.pow
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.prirai.android.lr.components.menu.Material3BrowserMenu
import java.util.UUID
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.support.ktx.util.URLStringUtils
import mozilla.components.support.utils.DefaultDownloadFileUtils

class ExternalAppBrowserFragment : Fragment(), UserInteractionHandler, ActivityResultHandler {
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    
    var customTabSessionId: String? = null
    
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val customTabWindowFeature = ViewBoundFeatureWrapper<CustomTabWindowFeature>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val contextMenuIntegration = ViewBoundFeatureWrapper<ContextMenuIntegration>()
    private val sitePermissionsFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val fullScreenMediaSessionFeature = ViewBoundFeatureWrapper<MediaSessionFullscreenFeature>()

    private var pipFeature: PictureInPictureFeature? = null

    private var headerRow: ComposeView? = null

    private val headerTitle = mutableStateOf("")
    private val headerUrl = mutableStateOf("")

    private var observedTitle: String? = null
    private var observedUrl: String? = null
    private var showedTitleOnce: Boolean = false
    
    private var engineView: EngineView? = null
    
    private var toolbarColor: Int = -1
    private var textColor: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        customTabSessionId = arguments?.getString("activeSessionId")
        
        toolbarColor = arguments?.getInt(CustomTabsIntent.EXTRA_TOOLBAR_COLOR, -1) ?: -1
        
        if (toolbarColor != -1) {
            textColor = if (isColorLight(toolbarColor)) {
                "#000000".toColorInt()
            } else {
                "#FFFFFF".toColorInt()
            }
        }
        
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        customTabSessionId = customTabSessionId ?: arguments?.getString("activeSessionId")
        val sessionId = customTabSessionId ?: return
        val tab = requireContext().components.store.state.findCustomTab(sessionId) ?: return
        
        setupEdgeToEdge()
        
        engineView = binding.engineView
        
        addCustomHeader(view, tab)
        
        setupCustomTabContentPadding(view)
        
        initializeFeatures(sessionId)
        
        observeTabChanges(sessionId)
    }
    
    private fun setupEdgeToEdge() {
    }
    
    private fun initializeFeatures(sessionId: String) {
        val components = requireContext().components
        
        sessionFeature.set(
            feature = SessionFeature(
                components.store,
                components.sessionUseCases.goBack,
                components.sessionUseCases.goForward,
                binding.engineView,
                sessionId
            ),
            owner = this,
            view = binding.root
        )

        promptsFeature.set(
            feature = PromptFeature(
                fragment = this,
                store = components.store,
                customTabId = sessionId,
                tabsUseCases = components.tabsUseCases,
                fragmentManager = childFragmentManager,
                fileUploadsDirCleaner = components.fileUploadsDirCleaner,
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_PROMPT_PERMISSIONS)
                }
            ),
            owner = this,
            view = binding.root
        )

        sitePermissionsFeature.set(
            feature = SitePermissionsFeature(
                context = requireContext(),
                storage = components.permissionStorage,
                fragmentManager = childFragmentManager,
                promptsStyling = SitePermissionsFeature.PromptsStyling(
                    gravity = Gravity.CENTER,
                    shouldWidthMatchParent = true,
                    positiveButtonBackgroundColor = com.prirai.android.lr.R.color.secondary_icon,
                    positiveButtonTextColor = com.prirai.android.lr.R.color.photonWhite
                ),
                dialogConfig = SitePermissionsFeature.DialogConfig(
                    shouldPreselectDoNotAskAgain = true,
                ),
                sessionId = sessionId,
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_APP_PERMISSIONS)
                },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = components.store
            ),
            owner = this,
            view = binding.root
        )

        fullScreenMediaSessionFeature.set(
            feature = MediaSessionFullscreenFeature(
                requireActivity(),
                components.store,
                sessionId
            ),
            owner = this,
            view = binding.root
        )

        pipFeature = PictureInPictureFeature(
            store = components.store,
            activity = requireActivity(),
            tabId = sessionId,
        )
        
        customTabWindowFeature.set(
            feature = CustomTabWindowFeature(
                activity = requireActivity(),
                store = components.store,
                sessionId = sessionId
            ),
            owner = this,
            view = binding.root
        )
        
        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                components.store,
                components.sessionUseCases.reload,
                binding.swipeRefresh,
                { },
                sessionId
            ),
            owner = this,
            view = binding.root
        )
        
        contextMenuIntegration.set(
            feature = ContextMenuIntegration(
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                browserStore = components.store,
                tabsUseCases = components.tabsUseCases,
                contextMenuUseCases = components.contextMenuUseCases,
                parentView = binding.root,
                sessionId = sessionId,
                engineView = binding.engineView
            ),
            owner = this,
            view = binding.root
        )
        
        downloadsFeature.set(
            feature = DownloadsFeature(
                requireContext().applicationContext,
                store = components.store,
                useCases = components.downloadsUseCases,
                fragmentManager = childFragmentManager,
                shouldForwardToThirdParties = {
                    UserPreferences(requireContext()).promptExternalDownloader
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
                tabId = sessionId,
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_DOWNLOAD_PERMISSIONS)
                }
            ),
            owner = this,
            view = binding.root
        )
        
        setupKeyboardAdjustment()
    }
    
    private fun setupKeyboardAdjustment() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.browserLayout) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val keyboardHeight = (imeInsets.bottom - systemBarsInsets.bottom).coerceAtLeast(0)
            val layoutParams = binding.swipeRefresh.layoutParams as? CoordinatorLayout.LayoutParams
                ?: return@setOnApplyWindowInsetsListener insets

            if (layoutParams.bottomMargin != keyboardHeight) {
                layoutParams.bottomMargin = keyboardHeight
                binding.swipeRefresh.layoutParams = layoutParams
                binding.swipeRefresh.requestLayout()
            }

            insets
        }

        ViewCompat.requestApplyInsets(binding.browserLayout)
    }
    
    private var headerSidePaddingPx = 0
    private var oppositeSystemBarPaddingPx = 0
    private var headerAtBottom = false

    private fun applyBrowserLayoutPadding() {
        if (headerAtBottom) {
            binding.browserLayout.setPadding(0, oppositeSystemBarPaddingPx, 0, headerSidePaddingPx)
        } else {
            binding.browserLayout.setPadding(0, headerSidePaddingPx, 0, oppositeSystemBarPaddingPx)
        }
    }

    private fun setupCustomTabContentPadding(view: View) {
        val browserWindow = view.findViewById<ViewGroup>(R.id.browserWindow) ?: return
        headerAtBottom = UserPreferences(requireContext()).shouldUseBottomToolbar

        val density = resources.displayMetrics.density
        headerSidePaddingPx = ((56f + 24f) * density).toInt()
        applyBrowserLayoutPadding()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            oppositeSystemBarPaddingPx = if (headerAtBottom) systemBars.top else systemBars.bottom
            applyBrowserLayoutPadding()
            insets
        }

        val headerWrapper = browserWindow.findViewWithTag<View>("customTabHeaderWrapper")
            ?: return
        headerWrapper.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (headerSidePaddingPx != v.height) {
                headerSidePaddingPx = v.height
                applyBrowserLayoutPadding()
            }
        }
    }

    private fun addCustomHeader(view: View, tab: SessionState) {
        val browserWindow = view.findViewById<ViewGroup>(R.id.browserWindow) ?: return

        val bottom = UserPreferences(requireContext()).shouldUseBottomToolbar

        val headerView = FrameLayout(requireContext()).apply {
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (bottom) {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                } else {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                }
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            setBackgroundColor(typedValue.data)
            elevation = 8f
            tag = "customTabHeaderWrapper"
            id = View.generateViewId()
        }

        val composeRow = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        composeRow.setContent {
            val context = LocalContext.current
            val prefs = UserPreferences(context)
            LRTheme(
                darkTheme = ThemeManager.isDarkMode(context),
                amoledMode = prefs.amoledMode,
                dynamicColor = prefs.dynamicColors
            ) {
                CustomTabHeaderRow(
                    title = headerTitle.value,
                    url = headerUrl.value,
                    headerColor = toolbarColor.takeIf { it != -1 }?.let { Color(it) },
                    contentColor = textColor.takeIf { toolbarColor != -1 }?.let { Color(it) },
                    onClose = ::closeCustomTab,
                    onMinimize = { pipFeature?.enterPipModeCompat() },
                    onMenu = { showCustomTabMenu(composeRow) },
                )
            }
        }

        headerView.addView(composeRow)
        browserWindow.addView(headerView)

        ViewCompat.setOnApplyWindowInsetsListener(headerView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (bottom) {
                v.setPadding(0, 0, 0, systemBars.bottom)
            } else {
                v.setPadding(0, systemBars.top, 0, 0)
            }
            insets
        }

        headerView.post {
            ViewCompat.requestApplyInsets(headerView)
        }

        headerRow = composeRow

        updateHeaderDisplay(tab)
    }
    
    private fun observeTabChanges(sessionId: String) {
        requireContext().components.store.flowScoped(viewLifecycleOwner, Dispatchers.Main) { flow ->
            flow.mapNotNull { state -> state.findCustomTab(sessionId) }
                .collect { tab ->
                    updateHeaderDisplay(tab)
                }
        }
    }
    
    private fun updateHeaderDisplay(tab: SessionState) {
        val newTitle = tab.content.title
        val newUrl = tab.content.url
        val displayUrl = if (newUrl.isNotEmpty()) {
            URLStringUtils
                .toDisplayUrl(newUrl).toString()
        } else {
            ""
        }

        if (newTitle != observedTitle) {
            if (newTitle.isNotEmpty()) {
                headerTitle.value = newTitle
                showedTitleOnce = true
            } else {
                headerTitle.value = displayUrl
            }
            observedTitle = newTitle
        }

        if (newUrl != observedUrl) {
            if (!showedTitleOnce || newTitle.isEmpty()) {
                headerTitle.value = displayUrl
            }
            headerUrl.value = displayUrl
            observedUrl = newUrl
        }
    }

    private fun showCustomTabMenu(anchor: View) {
        val menuItems = buildList {
            add(
                Material3BrowserMenu.MenuItem.Action(
                    id = "share",
                    title = getString(R.string.share),
                    iconRes = R.drawable.ios_share_24,
                    onClick = { shareCurrentUrl() },
                ),
            )
            add(Material3BrowserMenu.MenuItem.Divider)
            add(
                Material3BrowserMenu.MenuItem.Action(
                    id = "open_in_browser",
                    title = getString(R.string.browser_menu_open_in_lr),
                    iconRes = mozilla.components.ui.icons.R.drawable.mozac_ic_external_link_24,
                    onClick = { openInBrowser() },
                ),
            )
        }

        val headerAtBottom = UserPreferences(requireContext()).shouldUseBottomToolbar
        Material3BrowserMenu(
            requireContext(),
            menuItems,
        ).show(anchor, preferBottom = !headerAtBottom)
    }
    
    private fun isColorLight(color: Int): Boolean {
        val red = android.graphics.Color.red(color) / 255.0
        val green = android.graphics.Color.green(color) / 255.0
        val blue = android.graphics.Color.blue(color) / 255.0
        
        val r = if (red <= 0.03928) red / 12.92 else ((red + 0.055) / 1.055).pow(2.4)
        val g = if (green <= 0.03928) green / 12.92 else ((green + 0.055) / 1.055).pow(2.4)
        val b = if (blue <= 0.03928) blue / 12.92 else ((blue + 0.055) / 1.055).pow(2.4)
        
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance > 0.5
    }
    
    private fun shareCurrentUrl() {
        customTabSessionId?.let { sessionId ->
            val customTab = requireContext().components.store.state.findCustomTab(sessionId)
            customTab?.let { tab ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, tab.content.url)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
            }
        }
    }

    private fun openInBrowser() {
        customTabSessionId?.let { sessionId ->
            val customTab = requireContext().components.store.state.findCustomTab(sessionId)
            customTab?.let { tab ->
                val newTab = TabSessionState(
                    id = UUID.randomUUID().toString(),
                    content = ContentState(
                        url = tab.content.url,
                        private = false
                    ),
                    contextId = null,
                    source = SessionState.Source.Internal.NewTab
                )
                
                requireContext().components.profileMiddleware.markAsGuestTab(newTab.id)
                
                requireContext().components.store.dispatch(
                    TabListAction.AddTabAction(newTab, select = true)
                )
                
                pauseCustomTabMedia(sessionId)
                requireContext().components.customTabsUseCases.remove(sessionId)
                
                val intent = Intent(requireContext(), BrowserActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("activeSessionId", newTab.id)
                }
                startActivity(intent)
                requireActivity().finish()
            }
        }
    }

    private fun pauseCustomTabMedia(sessionId: String) {
        val tab = requireContext().components.store.state.findCustomTab(sessionId) ?: return
        tab.mediaSessionState?.controller?.pause()
    }

    private fun closeCustomTab() {
        val sessionId = customTabSessionId
        if (sessionId != null) {
            pauseCustomTabMedia(sessionId)
            requireContext().components.customTabsUseCases.remove(sessionId)
        }
        requireActivity().finish()
    }

    override fun onBackPressed(): Boolean {
        if (promptsFeature.onBackPressed()) {
            return true
        }
        if (sessionFeature.onBackPressed()) {
            return true
        }
        closeCustomTab()
        return true
    }

    override fun onActivityResult(requestCode: Int, data: Intent?, resultCode: Int): Boolean {
        return promptsFeature.onActivityResult(requestCode, data, resultCode)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        pipFeature?.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_CODE_DOWNLOAD_PERMISSIONS -> {
                downloadsFeature.get()?.onPermissionsResult(permissions, grantResults)
            }
            REQUEST_CODE_PROMPT_PERMISSIONS -> {
                promptsFeature.get()?.onPermissionsResult(permissions, grantResults)
            }
            REQUEST_CODE_APP_PERMISSIONS -> {
                sitePermissionsFeature.get()?.onPermissionsResult(permissions, grantResults)
            }
        }
    }
    
    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
    
    companion object {
        private const val REQUEST_CODE_DOWNLOAD_PERMISSIONS = 1
        private const val REQUEST_CODE_PROMPT_PERMISSIONS = 2
        private const val REQUEST_CODE_APP_PERMISSIONS = 3
    }
}

@Composable
private fun CustomTabHeaderRow(
    title: String,
    url: String,
    headerColor: Color?,
    contentColor: Color?,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMenu: () -> Unit,
) {
    val backgroundColor = headerColor ?: MaterialTheme.colorScheme.surface
    val titleColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    val urlColor = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = contentColor ?: MaterialTheme.colorScheme.onSurface

    Surface(
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_round_close),
                    contentDescription = stringResource(R.string.close),
                    tint = iconTint,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmallEmphasized,
                    color = urlColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onMinimize) {
                Icon(
                    painter = painterResource(R.drawable.mozac_ic_chevron_down_24),
                    contentDescription = stringResource(R.string.custom_tab_menu_minimize),
                    tint = iconTint,
                )
            }

            IconButton(onClick = onMenu) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.menu),
                    tint = iconTint,
                )
            }
        }
    }
}
