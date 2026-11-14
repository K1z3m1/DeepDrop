package com.prirai.android.lr

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDirections
import androidx.navigation.fragment.NavHostFragment
import com.prirai.android.lr.addons.WebExtensionPopupFragment
import com.prirai.android.lr.addons.WebExtensionTabletPopupFragment
import com.prirai.android.lr.browser.BrowsingMode
import com.prirai.android.lr.browser.BrowsingModeManager
import com.prirai.android.lr.browser.DefaultBrowsingModeManager
import com.prirai.android.lr.browser.SearchEngineList
import com.prirai.android.lr.browser.SearchEnginePreferences
import com.prirai.android.lr.databinding.ActivityMainBinding
import com.prirai.android.lr.ext.alreadyOnDestination
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.ext.enableEdgeToEdgeMode
import com.prirai.android.lr.ext.nav
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.search.SearchDialogFragmentDirections
import com.prirai.android.lr.utils.Utils
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.AppLifecycleAction
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.contextmenu.ext.DefaultSelectionActionDelegate
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.ktx.kotlin.isUrl
import mozilla.components.support.ktx.kotlin.toNormalizedUrl
import mozilla.components.support.utils.SafeIntent
import mozilla.components.support.webextensions.WebExtensionPopupObserver
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import android.view.ViewGroup
import android.widget.SpinnerAdapter
import androidx.appcompat.app.AlertDialog
import androidx.navigation.NavController
import com.google.android.material.color.DynamicColors
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.browser.tabs.NewTabCreator
import com.prirai.android.lr.components.toolbar.unified.UnifiedToolbar
import com.prirai.android.lr.media.MediaSessionService
import com.prirai.android.lr.theme.ColorConstants
import com.prirai.android.lr.theme.ThemeManager
import java.io.File
import kotlinx.coroutines.Dispatchers

open class BrowserActivity : AppCompatActivity(), ComponentCallbacks2, NavHostActivity {
    lateinit var binding: ActivityMainBinding

    lateinit var browsingModeManager: BrowsingModeManager
    private lateinit var currentTheme: BrowsingMode

    private var isToolbarInflated = false
    private lateinit var navigationToolbar: Toolbar

    private val navHost by lazy {
        supportFragmentManager.findFragmentById(R.id.container) as NavHostFragment
    }

    private val externalSourceIntentProcessors by lazy {
        listOf(
            OpenBrowserIntentProcessor(this, ::getIntentSessionId),
            OpenSpecificTabIntentProcessor(this)
        )
    }

    private val webExtensionPopupFeature by lazy {
        WebExtensionPopupObserver(components.store, ::openPopup)
    }

    private var originalContext: Context? = null

    private var lastToolbarPosition: Int = 0


    protected open fun getIntentSessionId(intent: SafeIntent): String? = null

    @VisibleForTesting
    internal fun isActivityColdStarted(startingIntent: Intent, activityIcicle: Bundle?): Boolean {
        return activityIcicle == null && startingIntent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppThemeNotActionBar)

        ThemeManager.applyTheme(this)

        val prefs = UserPreferences(this)

        if (ThemeManager.shouldUseDynamicColors(this)) {
            DynamicColors.applyToActivityIfAvailable(this)
        }

        super.onCreate(savedInstanceState)

        val profileManager = ProfileManager.getInstance(this)
        val initialProfile = profileManager.getActiveProfile()
        val isPrivate = prefs.lastKnownPrivate

        profileManager.setPrivateMode(isPrivate)

        browsingModeManager = createBrowsingModeManager(
            if (isPrivate) BrowsingMode.Private else BrowsingMode.Normal,
            initialProfile
        )
        currentTheme = browsingModeManager.mode

        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root

        setContentView(view)

        lastToolbarPosition = prefs.toolbarPosition

        view.post {
            lifecycleScope.launch {
                SearchEnginePreferences.apply(this@BrowserActivity, private = false)
                if (UserPreferences(this@BrowserActivity).privateSearchEngineChoice >= 0) {
                    SearchEnginePreferences.apply(this@BrowserActivity, private = true)
                }
            }
        }

        if (isActivityColdStarted(intent, savedInstanceState) &&
            !externalSourceIntentProcessors.any { it.process(intent, navHost.navController, this.intent) }
        ) {
            navigateToBrowserOnColdStart()
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                components.store.dispatch(AppLifecycleAction.PauseAction)
                components.sleepingTabsManager.onAppBackgrounded(components.store)
            }

            override fun onResume(owner: LifecycleOwner) {
                components.store.dispatch(AppLifecycleAction.ResumeAction)
            }
        })

        enableEdgeToEdgeMode()

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets -> insets }

        components.notificationsDelegate.bindToActivity(this)

        view.post {
            components.appRequestInterceptor.setNavController(navHost.navController)

            lifecycleScope.launch(Dispatchers.IO) {
                cleanupOrphanedThumbnails()
            }

            maybeShowDefaultProfileMigrationPopup()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                supportFragmentManager.primaryNavigationFragment?.childFragmentManager?.fragments?.forEach {
                    if (it is UserInteractionHandler && it.onBackPressed()) {
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    override fun onStart() {
        super.onStart()
        lifecycle.addObserver(webExtensionPopupFeature)
    }

    override fun onResume() {
        super.onResume()

        val currentPosition = UserPreferences(this).toolbarPosition
        if (currentPosition != lastToolbarPosition) {
            lastToolbarPosition = currentPosition
            recreate()
        }

        updateToolbarAndStatusBarTheme()
    }

    override fun onDestroy() {
        val taskGone =
            isFinishing && isTaskRoot && !isChangingConfigurations
        super.onDestroy()
        if (!taskGone) return

        try {
            components.mediaSessionFeature.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "mediaSessionFeature.stop() failed", t)
        }
        try {
            stopService(
                Intent(this, MediaSessionService::class.java),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "stopService(MediaSessionService) failed", t)
        }

        Log.i(TAG, "Task removed; killing process to force Gecko child-service teardown")
        Process.killProcess(Process.myPid())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            components.sleepingTabsManager.onTrimMemory(components.store, level)
        }

        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    cleanupOrphanedThumbnails()
                }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                components.icons.onTrimMemory(level)
            }
        }
    }

    final override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openToBrowser(BrowserDirection.FromGlobal)
        intent.let {
            handleNewIntent(it)
        }
    }

    open fun handleNewIntent(intent: Intent) {
        val intentProcessors = externalSourceIntentProcessors
        val intentHandled =
            intentProcessors.any { it.process(intent, navHost.navController, this.intent) }

        if (intentHandled) {
            supportFragmentManager
                .primaryNavigationFragment
                ?.childFragmentManager
                ?.fragments
                ?.lastOrNull()
        }
    }

    open fun navigateToBrowserOnColdStart() {
        if (!browsingModeManager.mode.isPrivate) {
            openToBrowser(BrowserDirection.FromGlobal, null)
        }
    }

    protected open fun createBrowsingModeManager(
        initialMode: BrowsingMode,
        initialProfile: BrowserProfile
    ): BrowsingModeManager {
        return DefaultBrowsingModeManager(
            initialMode,
            initialProfile,
            UserPreferences(this),
            { newMode ->
                currentTheme = newMode
                UserPreferences(this).lastKnownPrivate = newMode.isPrivate
                ProfileManager.getInstance(this)
                    .setPrivateMode(newMode.isPrivate)
            },
            { newProfile ->
                ProfileManager.getInstance(this).setActiveProfile(newProfile)
            }
        )
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
        when (name) {
            EngineView::class.java.name -> components.engine.createView(context, attrs).apply {
                selectionActionDelegate = DefaultSelectionActionDelegate(
                    store = components.store,
                    context = context
                )
            }.asView()

            else -> super.onCreateView(parent, name, context, attrs)
        }

    override fun getSupportActionBarAndInflateIfNecessary(): ActionBar {
        if (UserPreferences(this).shouldUseBottomToolbar) {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            return object : ActionBar() {
                override fun setCustomView(view: View?) {}
                override fun setCustomView(view: View?, layoutParams: LayoutParams?) {}
                override fun setCustomView(resId: Int) {}
                override fun setIcon(resId: Int) {}
                override fun setIcon(icon: Drawable?) {}
                override fun setLogo(resId: Int) {}
                override fun setLogo(logo: Drawable?) {}
                override fun setListNavigationCallbacks(
                    adapter: SpinnerAdapter?,
                    callback: OnNavigationListener?
                ) {
                }

                override fun setSelectedNavigationItem(position: Int) {}
                override fun getSelectedNavigationIndex(): Int = 0
                override fun getNavigationItemCount(): Int = 0
                override fun setTitle(title: CharSequence?) {}
                override fun setTitle(resId: Int) {}
                override fun setSubtitle(subtitle: CharSequence?) {}
                override fun setSubtitle(resId: Int) {}
                override fun setDisplayOptions(options: Int) {}
                override fun setDisplayOptions(options: Int, mask: Int) {}
                override fun setDisplayUseLogoEnabled(useLogo: Boolean) {}
                override fun setDisplayShowHomeEnabled(showHome: Boolean) {}
                override fun setDisplayHomeAsUpEnabled(showHomeAsUp: Boolean) {}
                override fun setDisplayShowTitleEnabled(showTitle: Boolean) {}
                override fun setDisplayShowCustomEnabled(showCustom: Boolean) {}
                override fun setBackgroundDrawable(d: Drawable?) {}
                override fun getThemedContext(): Context = this@BrowserActivity
                override fun getCustomView(): View? = null
                override fun getTitle(): CharSequence? = null
                override fun getSubtitle(): CharSequence? = null
                override fun getNavigationMode(): Int = NAVIGATION_MODE_STANDARD
                override fun setNavigationMode(mode: Int) {}
                override fun getDisplayOptions(): Int = 0
                override fun newTab(): Tab? = null
                override fun addTab(tab: Tab?) {}
                override fun addTab(tab: Tab?, setSelected: Boolean) {}
                override fun addTab(tab: Tab?, position: Int) {}
                override fun addTab(tab: Tab?, position: Int, setSelected: Boolean) {}
                override fun removeTab(tab: Tab?) {}
                override fun removeTabAt(position: Int) {}
                override fun removeAllTabs() {}
                override fun selectTab(tab: Tab?) {}
                override fun getSelectedTab(): Tab? = null
                override fun getTabAt(index: Int): Tab? = null
                override fun getTabCount(): Int = 0
                override fun getHeight(): Int = 0
                override fun show() {}
                override fun hide() {}
                override fun isShowing(): Boolean = false
                override fun addOnMenuVisibilityListener(listener: OnMenuVisibilityListener?) {}
                override fun removeOnMenuVisibilityListener(listener: OnMenuVisibilityListener?) {}
                override fun setHomeButtonEnabled(enabled: Boolean) {}
                override fun setHomeAsUpIndicator(resId: Int) {}
                override fun setHomeAsUpIndicator(indicator: Drawable?) {}
                override fun setHomeActionContentDescription(resId: Int) {}
                override fun setHomeActionContentDescription(description: CharSequence?) {}
                override fun setHideOnContentScrollEnabled(hideOnContentScroll: Boolean) {}
                override fun isHideOnContentScrollEnabled(): Boolean = false
                override fun getHideOffset(): Int = 0
                override fun setHideOffset(offset: Int) {}
                override fun setElevation(elevation: Float) {}
                override fun getElevation(): Float = 0f
            }
        }

        if (!isToolbarInflated) {
            navigationToolbar = binding.navigationToolbarStub.inflate() as Toolbar

            setSupportActionBar(navigationToolbar)
            setupNavigationToolbar()

            isToolbarInflated = true
        }

        navigationToolbar.visibility = View.VISIBLE
        navigationToolbar.layoutParams?.apply {
            height = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            width = ViewGroup.LayoutParams.MATCH_PARENT
        }

        return supportActionBar!!
    }

    final override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        supportFragmentManager.primaryNavigationFragment?.childFragmentManager?.fragments?.forEach {
            if (it is ActivityResultHandler && it.onActivityResult(requestCode, data, resultCode)) {
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private var toolbarDestinationListener: NavController.OnDestinationChangedListener? = null

    fun setupNavigationToolbar(vararg topLevelDestinationIds: Int) {
        val navController = navHost.navController
        toolbarDestinationListener?.let(navController::removeOnDestinationChangedListener)
        val topLevel = topLevelDestinationIds.toSet()
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            navigationToolbar.title = destination.label
            if (destination.id in topLevel) {
                navigationToolbar.navigationIcon = null
            } else {
                navigationToolbar.setNavigationIcon(mozilla.components.ui.icons.R.drawable.mozac_ic_back_24)
            }
        }
        toolbarDestinationListener = listener
        navController.addOnDestinationChangedListener(listener)

        navigationToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun openPopup(webExtensionState: WebExtensionState) {
        val fm: FragmentManager = supportFragmentManager
        val editNameDialogFragment =
            if (Utils.isTablet(this)) WebExtensionTabletPopupFragment()
            else WebExtensionPopupFragment()

        val bundle = Bundle()
        bundle.putString("web_extension_id", webExtensionState.id)
        intent.putExtra("web_extension_name", webExtensionState.name)

        editNameDialogFragment.arguments = bundle

        editNameDialogFragment.show(fm, "fragment_edit_name")
    }

    fun openToBrowserAndLoad(
        searchTermOrURL: String,
        newTab: Boolean,
        from: BrowserDirection,
        customTabSessionId: String? = null,
        engine: SearchEngine? = null,
        forceSearch: Boolean = false,
        flags: EngineSession.LoadUrlFlags = EngineSession.LoadUrlFlags.none()
    ) {
        openToBrowser(from, customTabSessionId)
        load(searchTermOrURL, newTab, engine, forceSearch, flags)
    }

    fun openToBrowser(from: BrowserDirection, customTabSessionId: String? = null) {
        if (navHost.navController.alreadyOnDestination(R.id.browserFragment)) return
        @IdRes val fragmentId = if (from.fragmentId != 0) from.fragmentId else null
        val directions = getNavDirections(from, customTabSessionId)
        if (directions != null) {
            navHost.navController.nav(fragmentId, directions)
        }
    }

    protected open fun getNavDirections(
        from: BrowserDirection,
        customTabSessionId: String?
    ): NavDirections? = when (from) {
        BrowserDirection.FromGlobal ->
            NavGraphDirections.actionGlobalBrowser(customTabSessionId)

        BrowserDirection.FromHome ->
            NavGraphDirections.actionGlobalBrowser(customTabSessionId)

        BrowserDirection.FromSearchDialog ->
            SearchDialogFragmentDirections.actionGlobalBrowser(customTabSessionId)
    }

    private fun load(
        searchTermOrURL: String,
        newTab: Boolean,
        engine: SearchEngine?,
        forceSearch: Boolean,
        flags: EngineSession.LoadUrlFlags = EngineSession.LoadUrlFlags.none()
    ) {
        val selectedTab = components.store.state.selectedTabId?.let { id ->
            components.store.state.tabs.find { it.id == id }
        }
        val isPrivateMode = selectedTab?.content?.private ?: browsingModeManager.mode.isPrivate
        val resolvedEngine = engine ?: resolveSearchEngine(isPrivateMode)

        if (resolvedEngine == null || (!forceSearch && searchTermOrURL.isUrl())) {
            components.speculativeConnect(searchTermOrURL.toNormalizedUrl())
            if (newTab) {
                NewTabCreator.createHomeTabAndLoad(this, isPrivateMode) {
                    components.sessionUseCases.loadUrl.invoke(searchTermOrURL.toNormalizedUrl(), flags)
                }
            } else {
                components.sessionUseCases.loadUrl.invoke(searchTermOrURL.toNormalizedUrl(), flags)
            }
        } else {
            if (newTab) {
                NewTabCreator.createHomeTabAndLoad(this, isPrivateMode) {
                    components.searchUseCases.defaultSearch.invoke(searchTermOrURL, resolvedEngine)
                }
            } else {
                components.searchUseCases.defaultSearch.invoke(searchTermOrURL, resolvedEngine)
            }
        }
    }

    private fun resolveSearchEngine(isPrivateMode: Boolean): SearchEngine? {
        components.store.state.search.selectedOrDefaultSearchEngine(isPrivateMode)?.let { return it }
        return try {
            SearchEngineList(this).getSelectedEngine(UserPreferences(this))
        } catch (_: Exception) {
            null
        }
    }

    override fun attachBaseContext(base: Context) {
        this.originalContext = base
        super.attachBaseContext(base)
    }

    private fun updateToolbarAndStatusBarTheme() {
        val selectedTab = components.store.state.tabs.find { it.id == components.store.state.selectedTabId }
        val isPrivate = selectedTab?.content?.private == true

        ThemeManager.applySystemBarsTheme(this, isPrivate)
    }

    private fun cleanupOrphanedThumbnails() {
        try {
            val currentTabIds = components.store.state.tabs.map { it.id }.toSet()
            val thumbnailDir = File(applicationContext.filesDir, "thumbnails")

            if (thumbnailDir.exists() && thumbnailDir.isDirectory) {
                var deletedCount = 0
                thumbnailDir.listFiles()?.forEach { file ->
                    val tabId = file.nameWithoutExtension
                    if (!currentTabIds.contains(tabId)) {
                        if (file.delete()) {
                            deletedCount++
                        }
                    }
                }
                if (deletedCount > 0) {
                    Log.i("BrowserActivity", "Cleaned up $deletedCount orphaned thumbnail(s)")
                }
            }
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Error cleaning up thumbnails", e)
        }
    }

    private fun maybeShowDefaultProfileMigrationPopup() {
        val prefs = UserPreferences(this)
        if (!prefs.pendingDefaultMigrationPopup && prefs.defaultProfileMigrationDone) return

        lifecycleScope.launch {
            var elapsed = 0
            while (elapsed < 3_000 && !prefs.pendingDefaultMigrationPopup) {
                kotlinx.coroutines.delay(100)
                elapsed += 100
            }
            if (!prefs.pendingDefaultMigrationPopup) return@launch
            if (isFinishing || isDestroyed) return@launch

            val count = prefs.pendingDefaultMigrationTabCount
            val message = if (count > 0) {
                getString(R.string.lr_default_profile_migration_message, count)
            } else {
                getString(R.string.lr_default_profile_migration_message_zero)
            }

            AlertDialog.Builder(this@BrowserActivity)
                .setTitle(R.string.lr_default_profile_migration_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
                .setOnDismissListener {
                    prefs.pendingDefaultMigrationPopup = false
                    prefs.pendingDefaultMigrationTabCount = 0
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun updateToolbarBackground() {
        val toolbar = findViewById<View>(R.id.toolbar)
        val unifiedToolbar = toolbar as? UnifiedToolbar
        val isPrivate = components.store.state.tabs.find { it.id == components.store.state.selectedTabId }?.content?.private == true
        if (isPrivate) {
            val purpleColor = ColorConstants.PrivateMode.PURPLE
            unifiedToolbar?.setBackgroundColor(purpleColor) ?: toolbar?.setBackgroundColor(purpleColor)
        }
    }

    companion object {
        const val OPEN_TO_BROWSER = "open_to_browser"
        private const val TAG = "LRBrowserAct"
    }
}
