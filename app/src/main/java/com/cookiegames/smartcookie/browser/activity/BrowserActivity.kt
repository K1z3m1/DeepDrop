package com.cookiegames.smartcookie.browser.activity

import android.app.Activity
import android.app.NotificationManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.io.File
import android.os.Handler
import android.os.Message
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.View.*
import android.view.ViewGroup.LayoutParams
import android.view.animation.Animation
import android.view.animation.Transformation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import android.widget.TextView.OnEditorActionListener
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.core.view.marginTop
import androidx.drawerlayout.widget.DrawerLayout
import androidx.palette.graphics.Palette
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import butterknife.ButterKnife
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.anthonycr.grant.PermissionsManager
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.IncognitoActivity
import com.cookiegames.smartcookie.MainActivity
import com.cookiegames.smartcookie.adblock.allowlist.AllowListModel
import com.cookiegames.smartcookie.download.DownloadActivity
import com.cookiegames.smartcookie.history.HistoryActivity
import com.cookiegames.smartcookie.webapp.WebappActivity
import com.cookiegames.smartcookie.settings.activity.SettingsActivity
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.browser.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.cookiegames.smartcookie.browser.bookmarks.BookmarksDrawerView
import com.cookiegames.smartcookie.browser.tabs.TabsDesktopView
import com.cookiegames.smartcookie.browser.tabs.TabsDrawerView
import com.cookiegames.smartcookie.browser.tabs.TabsBottomSheetAdapter
import com.cookiegames.smartcookie.browser.tabs.asTabViewState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cookiegames.smartcookie.controller.UIController
import com.cookiegames.smartcookie.database.Bookmark
import com.cookiegames.smartcookie.database.HistoryEntry
import com.cookiegames.smartcookie.database.SearchSuggestion
import com.cookiegames.smartcookie.database.WebPage
import com.cookiegames.smartcookie.database.bookmark.BookmarkRepository
import com.cookiegames.smartcookie.database.history.HistoryRepository
import com.cookiegames.smartcookie.di.*
import com.cookiegames.smartcookie.dialog.BrowserDialog
import com.cookiegames.smartcookie.dialog.DialogItem
import com.cookiegames.smartcookie.dialog.LightningDialogBuilder
import com.cookiegames.smartcookie.extensions.*
import com.cookiegames.smartcookie.html.bookmark.BookmarkPageFactory
import com.cookiegames.smartcookie.html.history.HistoryPageFactory
import com.cookiegames.smartcookie.html.homepage.HomePageFactory
import com.cookiegames.smartcookie.html.incognito.IncognitoPageFactory
import com.cookiegames.smartcookie.html.onboarding.OnboardingPageFactory
import com.cookiegames.smartcookie.icon.TabCountView
import com.cookiegames.smartcookie.icon.TabCountDrawable
import com.cookiegames.smartcookie.js.DarkReaderHelper
import com.cookiegames.smartcookie.interpolator.BezierDecelerateInterpolator
import com.cookiegames.smartcookie.log.Logger
import com.cookiegames.smartcookie.notifications.IncognitoNotification
import com.cookiegames.smartcookie.popup.PopUpClass
import com.cookiegames.smartcookie.search.SearchEngineProvider
import com.cookiegames.smartcookie.search.SuggestionsAdapter
import com.cookiegames.smartcookie.ssl.SslState
import com.cookiegames.smartcookie.ssl.createSslDrawableForState
import com.cookiegames.smartcookie.ssl.showSslDialog
import com.cookiegames.smartcookie.utils.*
import com.cookiegames.smartcookie.view.*
import com.cookiegames.smartcookie.view.SearchView
import com.cookiegames.smartcookie.view.find.FindResults
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.browser_content.*
import kotlinx.android.synthetic.main.dialog_app_lock.view.*
import kotlinx.android.synthetic.main.search.*
import kotlinx.android.synthetic.main.search_interface.*
import kotlinx.android.synthetic.main.toolbar.*
import java.io.IOException
import java.util.*
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.system.exitProcess


abstract class BrowserActivity : ThemableBrowserActivity(), BrowserView, UIController, OnClickListener, OnKeyboardVisibilityListener {

    // Toolbar ViewscreateSslDrawableForState
    private var searchBackground: View? = null
    private var searchView: SearchView? = null
    private var homeImageView: ImageView? = null
    private var tabCountView: TabCountView? = null

    // Current tab view being displayed
    private var currentTabView: View? = null

    // Full Screen Video Views
    private var fullscreenContainerView: FrameLayout? = null
    private var videoView: VideoView? = null
    private var customView: View? = null

    // Adapter
    private var suggestionsAdapter: SuggestionsAdapter? = null

    // Callback
    private var customViewCallback: CustomViewCallback? = null
    private var uploadMessageCallback: ValueCallback<Uri>? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Primitives
    private var isFullScreen: Boolean = false
    private var hideStatusBar: Boolean = false
    private var isDarkTheme: Boolean = false
    private var isImmersiveMode = false
    private var shouldShowTabsInDrawer: Boolean = false
    private var swapBookmarksAndTabs: Boolean = false

    private var originalOrientation: Int = 0
    private var currentUiColor = Color.BLACK
    private var keyDownStartTime: Long = 0
    private var searchText: String? = null
    private var cameraPhotoPath: String? = null

    private var findResult: FindResults? = null

    private var prefs: SharedPreferences? = null

    private var lastTouchTime = 0
    private var currentTouchTime = 0

    // The singleton BookmarkManager
    @Inject
    lateinit var bookmarkManager: BookmarkRepository

    @Inject
    lateinit var historyModel: HistoryRepository

    @Inject
    lateinit var searchBoxModel: SearchBoxModel

    @Inject
    lateinit var searchEngineProvider: SearchEngineProvider

    @Inject
    lateinit var inputMethodManager: InputMethodManager

    @Inject
    lateinit var clipboardManager: ClipboardManager

    @Inject
    lateinit var notificationManager: NotificationManager

    @Inject
    @field:DiskScheduler
    lateinit var diskScheduler: Scheduler

    @Inject
    @field:DatabaseScheduler
    lateinit var databaseScheduler: Scheduler

    @Inject
    @field:MainScheduler
    lateinit var mainScheduler: Scheduler

    @Inject
    lateinit var tabsManager: TabsManager

    @Inject
    lateinit var homePageFactory: HomePageFactory

    @Inject
    lateinit var incognitoPageFactory: IncognitoPageFactory

    @Inject
    lateinit var onboardingPageFactory: OnboardingPageFactory

    @Inject
    lateinit var bookmarkPageFactory: BookmarkPageFactory

    @Inject
    lateinit var historyPageFactory: HistoryPageFactory

    @Inject
    lateinit var historyPageInitializer: HistoryPageInitializer

    @Inject
    lateinit var downloadPageInitializer: DownloadPageInitializer

    @Inject
    lateinit var homePageInitializer: HomePageInitializer

    @Inject
    lateinit var incognitoPageInitializer: IncognitoPageInitializer

    @Inject
    lateinit var onboardingPageInitializer: OnboardingPageInitializer

    @Inject
    @field:MainHandler
    lateinit var mainHandler: Handler

    @Inject
    lateinit var proxyUtils: ProxyUtils

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var bookmarksDialogBuilder: LightningDialogBuilder

    @Inject
    lateinit var allowListModel: AllowListModel

    // Image
    var webPageBitmap: Bitmap? = null
    private val backgroundDrawable = ColorDrawable()
    private var incognitoNotification: IncognitoNotification? = null

    private var presenter: BrowserPresenter? = null
    private var tabsView: TabsView? = null
    private var bookmarksView: BookmarksView? = null

    // Bottom Sheet Tabs UI
    private var tabsDialog: BottomSheetDialog? = null
    private var tabsBottomSheetAdapter: TabsBottomSheetAdapter? = null

    // Menu
    private var backMenuItem: MenuItem? = null
    private var forwardMenuItem: MenuItem? = null

    private val longPressBackRunnable = Runnable {
        showCloseDialog(tabsManager.positionOf(tabsManager.currentTab))
    }

    /**
     * Determines if the current browser instance is in incognito mode or not.
     */
    abstract fun isIncognito(): Boolean

    /**
     * Choose the behavior when the controller closes the view.
     */
    abstract override fun closeActivity()

    /**
     * Choose what to do when the browser visits a website.
     *
     * @param title the title of the site visited.
     * @param url the url of the site visited.
     */
    abstract override fun updateHistory(title: String?, url: String)

    /**
     * An observable which asynchronously updates the user's cookie preferences.
     */
    protected abstract fun updateCookiePreference(): Completable



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        if (userPreferences.bottomBar) {
            setContentView(R.layout.activity_main_btm)
        } else {
            setContentView(R.layout.activity_main)
        }

        ButterKnife.bind(this)

        if (isIncognito()) {
            incognitoNotification = IncognitoNotification(this, notificationManager)
        }
        tabsManager.addTabNumberChangedListener {
            if (isIncognito()) {
                if (it == 0) {
                    incognitoNotification?.hide()
                } else {
                    incognitoNotification?.show(it)
                }
            }
        }

        presenter = BrowserPresenter(
                this,
                isIncognito(),
                userPreferences,
                tabsManager,
                mainScheduler,
                homePageFactory,
                incognitoPageFactory,
                onboardingPageFactory,
                bookmarkPageFactory,
                RecentTabModel(),
                logger
        )
        prefs = getSharedPreferences("com.cookiegames.smartcookie", MODE_PRIVATE)


        if(userPreferences.navbar) {
            setKeyboardVisibilityListener(this)
        }

        initialize(savedInstanceState)
    }

    private fun setKeyboardVisibilityListener(onKeyboardVisibilityListener: OnKeyboardVisibilityListener) {
        val parentView = (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        parentView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var alreadyOpen = false
            private val defaultKeyboardHeightDP = 100
            private val EstimatedKeyboardDP = defaultKeyboardHeightDP + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) 48 else 0
            private val rect: Rect = Rect()
            override fun onGlobalLayout() {
                val estimatedKeyboardHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, EstimatedKeyboardDP.toFloat(), parentView.resources.displayMetrics).toInt()
                parentView.getWindowVisibleDisplayFrame(rect)
                val heightDiff: Int = parentView.rootView.height - (rect.bottom - rect.top)
                val isShown = heightDiff >= estimatedKeyboardHeight
                if (isShown == alreadyOpen) {
                    return
                }
                alreadyOpen = isShown
                onKeyboardVisibilityListener.onVisibilityChanged(isShown)
            }
        })
    }

    override fun onVisibilityChanged(visible: Boolean) {
            val extraBar = findViewById<BottomNavigationView>(R.id.bottom_navigation)
            if(visible){
                extraBar?.visibility = GONE
            }
            else{
                extraBar?.visibility = View.VISIBLE
            }
    }

    private fun initialize(savedInstanceState: Bundle?) {
        initializeToolbarHeight(resources.configuration)
        setSupportActionBar(toolbar)
        val actionBar = requireNotNull(supportActionBar)

        if(userPreferences.firstLaunch){
            userPreferences.firstLaunch = false
        }

        //TODO make sure dark theme flag gets set correctly
        isDarkTheme = userPreferences.useTheme == AppTheme.DARK || userPreferences.useTheme == AppTheme.BLACK || isIncognito()
        shouldShowTabsInDrawer = userPreferences.showTabsInDrawer
        swapBookmarksAndTabs = userPreferences.bookmarksAndTabsSwapped

        if(!userPreferences.bottomBar){
            isFullScreen = userPreferences.fullScreenEnabled
        }

        // initialize background ColorDrawable
        val primaryColor = ThemeUtils.getPrimaryColor(this)
        backgroundDrawable.color = primaryColor

        currentTabView?.setBackgroundColor(primaryColor)
        currentTabView?.invalidate()

        // Drawer stutters otherwise
        left_drawer.setLayerType(LAYER_TYPE_NONE, null)
        right_drawer.setLayerType(LAYER_TYPE_NONE, null)

        if(userPreferences.bottomBar && userPreferences.navbar){
            val sizeInDP = 56f

            val marginInDp = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, sizeInDP, resources
                    .displayMetrics)

            val param = toolbar.layoutParams as ViewGroup.MarginLayoutParams
            param.setMargins(toolbar.marginLeft, toolbar.marginTop, toolbar.marginRight, marginInDp.roundToInt())
            toolbar.layoutParams = param

        }

        setNavigationDrawerWidth()
        drawer_layout.addDrawerListener(DrawerLocker())

        webPageBitmap = drawable(R.drawable.ic_webpage).toBitmap()

        tabsView = if (shouldShowTabsInDrawer) {
            TabsDrawerView(this, userPreferences = userPreferences).also(findViewById<FrameLayout>(getTabsContainerId())::addView)
        } else {
            TabsDesktopView(this, userPreferences = userPreferences).also(findViewById<FrameLayout>(getTabsContainerId())::addView)
        }
        var mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        var shouldRestoreTabs = mPrefs.getBoolean("shouldRestoreTabs", false)

        if (userPreferences.incognito) {

            WebUtils.clearHistory(this, historyModel, databaseScheduler)
            WebUtils.clearCookies(this)

            if (userPreferences.restoreLostTabsEnabled) {
                var editor = mPrefs.edit()
                editor.putBoolean("shouldRestoreTabs", true)
                editor.commit()
                userPreferences.restoreLostTabsEnabled = false
            }
        } else if (shouldRestoreTabs && userPreferences.restoreLostTabsEnabled == false && !userPreferences.incognito) {
            var editor = mPrefs.edit()
            editor.putBoolean("shouldRestoreTabs", false)
            editor.commit()
            userPreferences.restoreLostTabsEnabled = true
        }

        if(userPreferences.firstLaunch111 && userPreferences.searchSuggestionChoice == 4){
            userPreferences.searchSuggestionChoice = 5
            userPreferences.firstLaunch111 = false
        }

        bookmarksView = BookmarksDrawerView(this, this, userPreferences = userPreferences).also(findViewById<FrameLayout>(getBookmarksContainerId())::addView)

        if (shouldShowTabsInDrawer) {
            tabs_toolbar_container.visibility = GONE
        }

        // set display options of the ActionBar
        actionBar.setDisplayShowTitleEnabled(false)
        actionBar.setDisplayShowHomeEnabled(false)
        actionBar.setDisplayShowCustomEnabled(true)
        actionBar.setCustomView(R.layout.toolbar_content)

        val customView = actionBar.customView
        customView.layoutParams = customView.layoutParams.apply {
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.MATCH_PARENT
        }

        tabCountView = customView.findViewById(R.id.tab_count_view)
        homeImageView = customView.findViewById(R.id.home_image_view)
        if (shouldShowTabsInDrawer && !isIncognito()) {
            tabCountView?.visibility = VISIBLE
            homeImageView?.visibility = GONE
        } else if (shouldShowTabsInDrawer) {
            tabCountView?.visibility = GONE
            homeImageView?.visibility = VISIBLE
            homeImageView?.setImageResource(R.drawable.incognito_mode)
            // Post drawer locking in case the activity is being recreated
            mainHandler.post { drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, getTabDrawer()) }
        } else {
            tabCountView?.visibility = GONE
            homeImageView?.visibility = VISIBLE
            homeImageView?.setImageResource(R.drawable.ic_action_home)
            // Post drawer locking in case the activity is being recreated
            mainHandler.post { drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, getTabDrawer()) }
        }

        // Post drawer locking in case the activity is being recreated
        mainHandler.post { drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, getBookmarkDrawer()) }

        customView.findViewById<FrameLayout>(R.id.home_button).setOnClickListener(this)
        customView.findViewById<FrameLayout>(R.id.home_button).setOnLongClickListener {
            newTabButtonClicked()
            true
        }

        customView.findViewById<FrameLayout>(R.id.more_button).setOnClickListener(this)
        customView.findViewById<FrameLayout>(R.id.download_button).setOnClickListener(this)

        if (userPreferences.navbar) {
            customView.findViewById<FrameLayout>(R.id.home_button)?.visibility = GONE
            customView.findViewById<FrameLayout>(R.id.more_button)?.visibility = GONE
        }

        // create the search EditText in the ToolBar
        searchView = customView.findViewById<SearchView>(R.id.search).apply {
            search_ssl_status.setOnClickListener {
                if (tabsManager.currentTab?.sslCertificate == null) {
                    val builder = MaterialAlertDialogBuilder(context)
                    builder.setTitle(R.string.site_not_secure)
                    builder.setIcon(R.drawable.ic_alert)
                    builder.setPositiveButton(R.string.action_ok) { _, _ ->
                    }
                    builder.show()
                } else {
                    tabsManager.currentTab?.let { tab ->
                        tab.sslCertificate?.let { showSslDialog(it, tab.currentSslState()) }
                    }
                }

            }
            search_ssl_status.updateVisibilityForContent()

            if (isDarkTheme) {
                search_refresh.setImageResource(R.drawable.ic_action_refresh_light)
            } else {
                search_refresh.setImageResource(R.drawable.ic_action_refresh)
            }

            val searchListener = SearchListenerClass()
            setOnKeyListener(searchListener)
            onFocusChangeListener = searchListener
            setOnEditorActionListener(searchListener)
            onPreFocusListener = searchListener
            addTextChangedListener(StyleRemovingTextWatcher())

            initializeSearchSuggestions(this)
        }

        search_refresh.setOnClickListener {
            if (searchView?.hasFocus() == true) {
                searchView?.setText("")
            } else {
                refreshOrStop()
            }
        }


        searchBackground = customView.findViewById<View>(R.id.search_container).apply {
            // initialize search background color
            background?.tint(getSearchBarColor(primaryColor, primaryColor))
        }

        drawer_layout.setDrawerShadow(R.drawable.drawer_right_shadow, GravityCompat.END)
        drawer_layout.setDrawerShadow(R.drawable.drawer_left_shadow, GravityCompat.START)

        var intent: Intent? = if (savedInstanceState == null) {
            intent
        } else {
            null
        }

        val launchedFromHistory = intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

        if (intent?.action == INTENT_PANIC_TRIGGER) {
            setIntent(null)
            panicClean()
        } else {
            if (launchedFromHistory) {
                intent = null
            }
            presenter?.setupTabs(intent)
            setIntent(null)
            proxyUtils.checkForProxy(this)
            updateTopBarVisibilityForUrl(tabsManager.currentTab?.url)
        }

        if(userPreferences.passwordChoiceLock == PasswordChoice.CUSTOM){
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)

            val customLayout: View = layoutInflater.inflate(R.layout.dialog_app_lock, null)
            builder.setView(customLayout)
            builder.setCancelable(false)

            customLayout.forgot_password.setOnClickListener {
                val forgotBuilder: AlertDialog.Builder = AlertDialog.Builder(this)
                forgotBuilder.setTitle(R.string.forgot_password)
                forgotBuilder.setMessage(R.string.recover_app_lock)
                forgotBuilder.setCancelable(false)

                forgotBuilder.setPositiveButton(resources.getString(R.string.action_back)) { dialog, which ->
                    if (customLayout.textFieldText.text.toString() == userPreferences.passwordTextLock) {
                        dialog.cancel()
                    }
                }
                forgotBuilder.show()
            }

            builder.setPositiveButton(resources.getString(R.string.action_ok)) { dialog, which ->
                if (customLayout.textFieldText.text.toString() == userPreferences.passwordTextLock) {
                    dialog.cancel()
                }
                else{
                    this.finishAffinity()
                }
            }
            builder.setNegativeButton(resources.getString(R.string.action_cancel)) { dialog, which ->
                this.finishAffinity()
            }

            val dialog: AlertDialog = builder.create()
            dialog.show()
        }
    }

    private fun getBookmarksContainerId(): Int = if (swapBookmarksAndTabs) {
        R.id.left_drawer
    } else {
        R.id.right_drawer
    }

    private fun getTabsContainerId(): Int = if (shouldShowTabsInDrawer) {
        if (swapBookmarksAndTabs) {
            R.id.right_drawer
        } else {
            R.id.left_drawer
        }
    } else {
        R.id.tabs_toolbar_container
    }

    fun getBookmarkDrawer(): View = if (swapBookmarksAndTabs) {
        left_drawer
    } else {
        right_drawer
    }

    private fun getTabDrawer(): View = if (swapBookmarksAndTabs) {
        right_drawer
    } else {
        left_drawer
    }

    protected fun panicClean() {
        logger.log(TAG, "Closing browser")
        tabsManager.newTab(this, NoOpInitializer(), false)
        tabsManager.switchToTab(0)
        tabsManager.clearSavedState()

        historyPageFactory.deleteHistoryPage().subscribe()
        closeBrowser()
        // System exit needed in the case of receiving
        // the panic intent since finish() isn't completely
        // closing the browser
        exitProcess(1)
    }

    private inner class SearchListenerClass : OnKeyListener,
            OnEditorActionListener,
            OnFocusChangeListener,
            SearchView.PreFocusListener {

        override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> {
                    searchView?.let {
                        inputMethodManager.hideSoftInputFromWindow(it.windowToken, 0)
                        searchTheWeb(it.text.toString())
                    }

                    tabsManager.currentTab?.requestFocus()
                    return true
                }
                else -> {
                }
            }
            return false
        }

        override fun onEditorAction(arg0: TextView, actionId: Int, arg2: KeyEvent?): Boolean {
            // hide the keyboard and search the web when the enter key
            // button is pressed
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || arg2?.action == KeyEvent.KEYCODE_ENTER) {
                searchView?.let {
                    inputMethodManager.hideSoftInputFromWindow(it.windowToken, 0)
                    searchTheWeb(it.text.toString())
                }

                tabsManager.currentTab?.requestFocus()
                return true
            }
            return false
        }

        override fun onFocusChange(v: View, hasFocus: Boolean) {
            val currentView = tabsManager.currentTab
            if (currentView?.url!!.contains("data:text/html;charset=utf-8")) {

            } else if (!hasFocus && currentView != null) {
                setIsLoading(currentView.progress < 100)
                updateUrl(currentView.url, false)
            } else if (hasFocus && currentView != null) {

                // Hack to make sure the text gets selected
                (v as SearchView).selectAll()
                search_ssl_status.visibility = GONE
                search_refresh.setImageResource(R.drawable.ic_action_delete)
            }

            if (!hasFocus) {
                search_ssl_status.updateVisibilityForContent()
                searchView?.let {
                    inputMethodManager.hideSoftInputFromWindow(it.windowToken, 0)
                }
            }
        }

        override fun onPreFocus() {
            val currentView = tabsManager.currentTab ?: return
            val url = currentView.url
            if (!url.isSpecialUrl()) {
                if (searchView?.hasFocus() == false) {
                    if (url.contains("data:text/html") || url.equals("about:blank")) {

                    } else {
                        searchView?.setText(url)
                    }

                }
            }
        }
    }

    private inner class DrawerLocker : DrawerLayout.DrawerListener {

        override fun onDrawerClosed(v: View) {
            val tabsDrawer = getTabDrawer()
            val bookmarksDrawer = getBookmarkDrawer()

            if (v === tabsDrawer) {
                drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, bookmarksDrawer)
            } else if (shouldShowTabsInDrawer) {
                drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, tabsDrawer)
            }
        }

        override fun onDrawerOpened(v: View) {
            val tabsDrawer = getTabDrawer()
            val bookmarksDrawer = getBookmarkDrawer()

            if (v === tabsDrawer) {
                drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, bookmarksDrawer)
            } else {
                drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, tabsDrawer)
            }


        }

        override fun onDrawerSlide(v: View, arg: Float) = Unit

        override fun onDrawerStateChanged(arg: Int) = Unit

    }

    private fun setNavigationDrawerWidth() {
        val width = resources.displayMetrics.widthPixels - dimen(R.dimen.navigation_drawer_minimum_space)
        val maxWidth = resources.getDimensionPixelSize(R.dimen.navigation_drawer_max_width)
        if (width < maxWidth) {
            val params = left_drawer.layoutParams as DrawerLayout.LayoutParams
            params.width = width
            left_drawer.layoutParams = params
            left_drawer.requestLayout()
            val paramsRight = right_drawer.layoutParams as DrawerLayout.LayoutParams
            paramsRight.width = width
            right_drawer.layoutParams = paramsRight
            right_drawer.requestLayout()
        }
    }

    private fun initializePreferences() {
        val currentView = tabsManager.currentTab
        val extraBar = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        if(userPreferences.navbar){
            extraBar.setBackgroundColor(if(isDarkTheme) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }

        webPageBitmap?.let { webBitmap ->
            if (!isIncognito() && !isColorMode() && !isDarkTheme) {
                changeToolbarBackground(webBitmap, null)
            } else if (!isIncognito() && currentView != null && !isDarkTheme) {
                changeToolbarBackground(currentView.favicon ?: webBitmap, null)
            } else if (!isIncognito() && !isDarkTheme) {
                changeToolbarBackground(webBitmap, null)
            } else if (userPreferences.navbarColChoice == ChooseNavbarCol.COLOR && !isIncognito()) {
                changeToolbarColor(null)
            }
        }

        // TODO layout transition causing memory leak
        //        content_frame.setLayoutTransition(new LayoutTransition());

        setFullscreen(userPreferences.hideStatusBarEnabled, false)

        val currentSearchEngine = searchEngineProvider.provideSearchEngine()
        searchText = currentSearchEngine.queryUrl

        updateCookiePreference().subscribeOn(diskScheduler).subscribe()
        proxyUtils.updateProxySettings(this)
        if(!userPreferences.navbar){
            extraBar.visibility = GONE
        }
        else{
            extraBar.visibility = VISIBLE
            try {
                extraBar.menu.setGroupCheckable(0, false, false)
                for (i in 0 until extraBar.menu.size()) {
                    extraBar.menu.getItem(i).isCheckable = false
                }
            } catch (ignored: Exception) {
            }
            extraBar.setOnNavigationItemSelectedListener { item ->
                when(item.itemId) {
                    R.id.back -> {
                        tabsManager.currentTab?.goBack()
                        false
                    }
                    R.id.forward -> {
                        tabsManager.currentTab?.goForward()
                        false
                    }
                    R.id.home -> {
                        tabsManager.currentTab?.loadHomePage()
                        updateTopBarVisibilityForUrl(null)
                        false
                    }
                    R.id.tabs -> {
                        drawer_layout.closeDrawer(getBookmarkDrawer())
                        toggleTabsBottomSheet()
                        false
                    }
                    R.id.menu -> {
                        showViaMenu()
                        false
                    }
                    else -> false
                }
            }
            initTabsBottomSheet()
        }
    }

    private fun toggleDrawer(layout: DrawerLayout, drawer: View) {
        if(layout.isDrawerOpen(drawer)){
            layout.closeDrawer(drawer)
        }
        else{
            layout.openDrawer(drawer)
        }
    }

    public override fun onWindowVisibleToUserAfterResume() {
        super.onWindowVisibleToUserAfterResume()
        val currentUrl = tabsManager.currentTab?.url
        updateTopBarVisibilityForUrl(currentUrl)
        if (currentUrl.isHomeUrl()) {
            findViewById<View>(R.id.toolbar_layout)?.visibility = View.GONE
            findViewById<View>(R.id.toolbar)?.visibility = View.GONE
            searchBackground?.visibility = View.GONE
            toolbar_layout.translationY = 0f
            setWebViewTranslation(0f)
        } else {
            toolbar_layout.translationY = 0f
            setWebViewTranslation(toolbar_layout.height.toFloat())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (searchView?.hasFocus() == true) {
                searchView?.let { searchTheWeb(it.text.toString()) }
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            keyDownStartTime = System.currentTimeMillis()
            mainHandler.postDelayed(longPressBackRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mainHandler.removeCallbacks(longPressBackRunnable)
            if (System.currentTimeMillis() - keyDownStartTime > ViewConfiguration.getLongPressTimeout()) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Keyboard shortcuts
        if (event.action == KeyEvent.ACTION_DOWN) {
            when {
                event.isCtrlPressed -> when (event.keyCode) {
                    KeyEvent.KEYCODE_P -> {
                        // Print
                        tabsManager.currentTab?.webView?.let { tabsManager.currentTab?.createWebPagePrint(it) }
                        return true
                    }
                    KeyEvent.KEYCODE_F -> {
                        // Search in page
                        findInPage()
                        return true
                    }
                    KeyEvent.KEYCODE_T -> {
                        // Open new tab
                        presenter?.newTab(
                                homePageInitializer,
                                true
                        )
                        return true
                    }
                    KeyEvent.KEYCODE_W -> {
                        // Close current tab
                        tabsManager.let { presenter?.deleteTab(it.indexOfCurrentTab()) }
                        return true
                    }
                    KeyEvent.KEYCODE_Q -> {
                        // Close browser
                        closeBrowser()
                        return true
                    }
                    KeyEvent.KEYCODE_H -> {
                        // History
                        tabsManager.currentTab?.loadHistoryPage()
                        return true
                    }
                    KeyEvent.KEYCODE_B -> {
                        // History
                        addBookmark(tabsManager.currentTab!!.title, tabsManager.currentTab!!.url)
                        return true
                    }
                    KeyEvent.KEYCODE_S -> {
                        IntentUtils(this).shareUrl(tabsManager.currentTab?.url, tabsManager.currentTab?.title)
                        return true
                    }
                    KeyEvent.KEYCODE_R -> {
                        // Refresh current tab
                        tabsManager.currentTab?.reload()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (drawer_layout.isDrawerOpen(getBookmarkDrawer())) {
                            drawer_layout.closeDrawers()
                        }
                        drawer_layout.openDrawer(getTabDrawer())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (drawer_layout.isDrawerOpen(getTabDrawer())) {
                            drawer_layout.closeDrawers()
                        }
                        drawer_layout.openDrawer(getBookmarkDrawer())
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        tabsManager.currentTab?.goBack()
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        tabsManager.currentTab?.goForward()
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        tabsManager.let {
                            val nextIndex = if (event.isShiftPressed) {
                                // Go back one tab
                                if (it.indexOfCurrentTab() > 0) {
                                    it.indexOfCurrentTab() - 1
                                } else {
                                    it.last()
                                }
                            } else {
                                // Go forward one tab
                                if (it.indexOfCurrentTab() < it.last()) {
                                    it.indexOfCurrentTab() + 1
                                } else {
                                    0
                                }
                            }

                            presenter?.tabChanged(nextIndex)
                        }

                        return true
                    }
                }
                event.isAltPressed -> // Alt + tab number
                    tabsManager.let {
                        if (KeyEvent.KEYCODE_0 <= event.keyCode && event.keyCode <= KeyEvent.KEYCODE_9) {
                            val nextIndex = if (event.keyCode > it.last() + KeyEvent.KEYCODE_1 || event.keyCode == KeyEvent.KEYCODE_0) {
                                it.last()
                            } else {
                                event.keyCode - KeyEvent.KEYCODE_1
                            }
                            presenter?.tabChanged(nextIndex)
                            return true
                        }
                    }
                else -> when (event.keyCode) {
                    KeyEvent.KEYCODE_SEARCH -> {
                        // Highlight search field
                        searchView?.requestFocus()
                        searchView?.selectAll()
                        return true
                    }
                    KeyEvent.KEYCODE_F5 -> {
                        tabsManager.currentTab?.reload()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // By using a manager, adds a bookmark and notifies third parties about that
    private fun addBookmark(title: String, url: String) {
        val bookmark = Bookmark.Entry(url, title, 0, Bookmark.Folder.Root)
        bookmarksDialogBuilder.showAddBookmarkDialog(this, this, bookmark)
    }

    private fun deleteBookmark(title: String, url: String) {
        bookmarkManager.deleteBookmark(Bookmark.Entry(url, title, 0, Bookmark.Folder.Root))
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe { boolean ->
                    if (boolean) {
                        handleBookmarksChange()
                    }
                }
    }

    private fun putToolbarInRoot() {
        if (toolbar_layout.parent != ui_layout) {
            (toolbar_layout.parent as ViewGroup?)?.removeView(toolbar_layout)

            ui_layout.addView(toolbar_layout, 0)
            ui_layout.requestLayout()
        }

        setWebViewTranslation(0f)
    }

    private fun overlayToolbarOnWebView() {
        if (toolbar_layout.parent != content_frame) {
            (toolbar_layout.parent as ViewGroup?)?.removeView(toolbar_layout)

            content_frame.addView(toolbar_layout)
            content_frame.requestLayout()
        }
        setWebViewTranslation(toolbar_layout.height.toFloat())


    }

    private fun setWebViewTranslation(translation: Float) =
            if (isFullScreen) {
                currentTabView?.translationY = translation
            } else {
                currentTabView?.translationY = 0f
            }


    /**
     * method that shows a dialog asking what string the user wishes to search
     * for. It highlights the text entered.
     */
    public fun findInPage() = BrowserDialog.showEditText(
            this,
            R.string.action_find,
            R.string.action_find,
            R.string.action_find
    ) { text ->
        if (text.isNotEmpty()) {
            findResult = presenter?.findInPage(text)
            showFindInPageControls(text)
        }
    }

    private fun showFindInPageControls(text: String) {
        search_bar.visibility = VISIBLE

        findViewById<TextView>(R.id.search_query).text = text
        findViewById<ImageButton>(R.id.button_next).setOnClickListener(this)
        findViewById<ImageButton>(R.id.button_back).setOnClickListener(this)
        findViewById<ImageButton>(R.id.button_quit).setOnClickListener(this)
        findViewById<ImageButton>(R.id.button_search).setOnClickListener(this)
    }

    override fun isColorMode(): Boolean = userPreferences.colorModeEnabled && !isDarkTheme

    override fun getTabModel(): TabsManager = tabsManager

    override fun showCloseDialog(position: Int) {
        if (position < 0) {
            return
        }
        BrowserDialog.showWithIcons(this, getString(R.string.dialog_title_close_browser),
                DialogItem(title = R.string.close_tab, icon = drawable(R.drawable.ic_delete_this)) {
                    presenter?.deleteTab(position)
                },
                DialogItem(title = R.string.close_other_tabs, icon = drawable(R.drawable.ic_delete_other)) {
                    presenter?.closeAllOtherTabs()
                },
                DialogItem(title = R.string.close_all_tabs, icon = drawable(R.drawable.ic_delete_all), onClick = this::closeBrowser),
                DialogItem(title = R.string.close_app, icon = drawable(R.drawable.ic_action_delete), onClick = this::closeApp))


    }

    override fun notifyTabViewRemoved(position: Int) {
        logger.log(TAG, "Notify Tab Removed: $position")
        tabsView?.tabRemoved(position)
        updateTabsBottomSheetList()
    }

    override fun notifyTabViewAdded() {
        logger.log(TAG, "Notify Tab Added")
        tabsView?.tabAdded()
        updateTabsBottomSheetList()
    }

    override fun notifyTabViewChanged(position: Int) {
        logger.log(TAG, "Notify Tab Changed: $position")
        tabsView?.tabChanged(position)
        updateTabsBottomSheetList()
    }

    override fun notifyTabViewInitialized() {
        logger.log(TAG, "Notify Tabs Initialized")
        tabsView?.tabsInitialized()
        updateTabsBottomSheetList()
    }

    override fun updateSslState(sslState: SslState) {
        val currentTab = tabsManager.currentTab
        val url = currentTab?.url ?: ""
        if (url.startsWith("https://", ignoreCase = true)) {
            if (sslState is SslState.Invalid) {
                search_ssl_status.setImageDrawable(androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.ic_unsecured_severe, theme))
            } else {
                search_ssl_status.setImageDrawable(androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.ic_secured, theme))
            }
        } else if (url.startsWith("http://", ignoreCase = true)) {
            search_ssl_status.setImageDrawable(androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.ic_unsecured, theme))
        } else {
            search_ssl_status.setImageDrawable(null)
        }


        if (searchView?.hasFocus() == false) {
            search_ssl_status.updateVisibilityForContent()
        }
    }

    private fun ImageView.updateVisibilityForContent() {
        drawable?.let { visibility = VISIBLE } ?: run { visibility = GONE }
    }

    override fun tabChanged(tab: SmartCookieView) {
        presenter?.tabChangeOccurred(tab)
    }

    override fun removeTabView() {

        logger.log(TAG, "Remove the tab view")

        currentTabView.removeFromParent()

        currentTabView = null

        drawer_layout.closeDrawers()
    }
    override fun setTabView(view: View) {
        if (currentTabView == view) {
            return
        }

        logger.log(TAG, "Setting the tab view")

        view.removeFromParent()
        currentTabView.removeFromParent()

        content_frame.addView(view, 0, MATCH_PARENT)

         if(isFullScreen) {
            view.translationY = toolbar_layout.height + toolbar_layout.translationY
        }
        else {
         view.translationY = 0f
       }

        val displayMetrics =  DisplayMetrics()
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics)
        val height = displayMetrics.heightPixels

        currentTabView = view

        view.requestFocus()

        updateTopBarVisibilityForUrl(tabsManager.currentTab?.url)

        showActionBar()

        drawer_layout.closeDrawers()
    }

    override fun showBlockedLocalFileDialog(onPositiveClick: Function0<Unit>) {
        MaterialAlertDialogBuilder(this)
                .setCancelable(true)
                .setTitle(R.string.title_warning)
                .setMessage(R.string.message_blocked_local)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_open) { _, _ -> onPositiveClick.invoke() }
                .resizeAndShow()
    }

    override fun showSnackbar(@StringRes resource: Int) = snackbar(resource)

    override fun tabCloseClicked(position: Int) {
        presenter?.deleteTab(position)
    }

    override fun tabClicked(position: Int) {
        presenter?.tabChanged(position)
    }

    override fun newTabButtonClicked() {
        if(userPreferences.tabsToForegroundEnabled){
            if(isIncognito()){
                presenter?.newTab(
                        incognitoPageInitializer,
                        true
                )
            }
            else{
                presenter?.newTab(
                        homePageInitializer,
                        true
                )
            }
        }
        else{
            if(isIncognito()){
                presenter?.newTab(
                        incognitoPageInitializer,
                        false
                )
            }
            else{
                presenter?.newTab(
                        homePageInitializer,
                        false
                )
            }
        }
    }

    override fun newTabButtonLongClicked() {
        presenter?.onNewTabLongClicked()
    }

    override fun bookmarkButtonClicked() {
        val currentTab = tabsManager.currentTab
        val url = currentTab?.url
        val title = currentTab?.title
        if (url == null || title == null) {
            return
        }

        if (!url.isSpecialUrl()) {
            bookmarkManager.isBookmark(url)
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe { boolean ->
                        if (boolean) {
                            MaterialAlertDialogBuilder(this)
                                    .setCancelable(true)
                                    .setTitle(R.string.bookmark_delete)
                                    .setNegativeButton(android.R.string.no, null)
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        deleteBookmark(title, url)
                                    }
                                    .resizeAndShow()
                        } else {
                            addBookmark(title, url)
                        }
                    }
        }
    }

    override fun bookmarkItemClicked(entry: Bookmark.Entry) {
        presenter?.loadUrlInCurrentView(entry.url)
        closeDrawers(null)
    }

    override fun handleHistoryChange() {
        historyPageFactory
                .buildPage()
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribeBy(onSuccess = { tabsManager.currentTab?.reload() })
    }

    protected fun handleNewIntent(intent: Intent) {
        presenter?.onNewIntent(intent)
        if (intent.getBooleanExtra("OPEN_BOOKMARKS", false)) {
            openBookmarks()
        }
        if (intent.getBooleanExtra("OPEN_TABS", false)) {
            toggleTabsBottomSheet()
        }
    }

    protected fun performExitCleanUp() {
        val currentTab = tabsManager.currentTab
        if (userPreferences.clearCacheExit && currentTab != null && !isIncognito()) {
            WebUtils.clearCache(currentTab.webView, applicationContext)
            logger.log(TAG, "Cache Cleared")
        }
        if (userPreferences.clearHistoryExitEnabled && !isIncognito()) {
            WebUtils.clearHistory(this, historyModel, databaseScheduler)
            logger.log(TAG, "History Cleared")
        }
        if (userPreferences.clearCookiesExitEnabled && !isIncognito()) {
            WebUtils.clearCookies(this)
            logger.log(TAG, "Cookies Cleared")
        }
        if (userPreferences.clearWebStorageExitEnabled && !isIncognito()) {
            WebUtils.eraseWebStorage(applicationContext)
            logger.log(TAG, "WebStorage Cleared")
        } else if (isIncognito()) {
            WebUtils.clearWebStorage()     // We want to make sure incognito mode is secure
        }
    }

    fun getScreenSize(): Int{
        var display: Display = getWindowManager().getDefaultDisplay()
        var size: Point = Point()
        var extra = 0
        var height = 0
        var barHeight = 0
        display.getSize(size)
        Log.d("displayMetrics", size.y.toString())
        var resources = getResources()
        var resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            height= resources.getDimensionPixelSize(resourceId)
        }

        if(userPreferences.showTabsInDrawer){
            barHeight = 48
        }
        else{
            barHeight = 72
        }

        return size.y - barHeight - height
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        logger.log(TAG, "onConfigurationChanged")

        if (isFullScreen) {
            showActionBar()

            setWebViewTranslation(toolbar_layout.height.toFloat())

        }

        invalidateOptionsMenu()
        initializeToolbarHeight(newConfig)
    }

    private fun initializeToolbarHeight(configuration: Configuration) =
            ui_layout.doOnLayout {
                // TODO externalize the dimensions
                val toolbarSize = if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    R.dimen.toolbar_height_portrait
                } else {
                    R.dimen.toolbar_height_landscape
                }

                toolbar.layoutParams = (toolbar.layoutParams as LinearLayout.LayoutParams).apply {
                    height = dimen(toolbarSize)
                }
                toolbar.minimumHeight = toolbarSize
                toolbar.doOnLayout {
                    setWebViewTranslation(toolbar_layout.height.toFloat())

                }
                toolbar.requestLayout()
            }

    override fun closeBrowser() {
        currentTabView.removeFromParent()
        performExitCleanUp()
        val size = tabsManager.size()
        tabsManager.shutdown()
        currentTabView = null
        for (n in 0 until size) {
            tabsView?.tabRemoved(0)
        }
        finish()
    }

    fun closeApp(){
        val a = Intent(Intent.ACTION_MAIN)
        a.addCategory(Intent.CATEGORY_HOME)
        a.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(a)
    }
    override fun onBackPressed() {
        if (isTabsBottomSheetShowing()) {
            hideTabsBottomSheet()
            return
        }
        val currentTab = tabsManager.currentTab
        if (drawer_layout.isDrawerOpen(getTabDrawer())) {
            drawer_layout.closeDrawer(getTabDrawer())
        } else if (drawer_layout.isDrawerOpen(getBookmarkDrawer())) {
            bookmarksView?.navigateBack()
        } else {
            if (currentTab != null) {
                logger.log(TAG, "onBackPressed")
                if (searchView?.hasFocus() == true) {
                    currentTab.requestFocus()
                } else if (currentTab.canGoBack()) {
                    if (!currentTab.isShown) {
                        onHideCustomView()
                    } else {
                        currentTab.goBack()
                    }
                } else {
                    if (customView != null || customViewCallback != null) {
                        onHideCustomView()
                    } else {
                        presenter?.deleteTab(tabsManager.positionOf(currentTab), true)
                    }
                }
            } else {
                logger.log(TAG, "This shouldn't happen ever")
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        logger.log(TAG, "onPause")
        tabsManager.pauseAll()

        if (isIncognito() && isFinishing) {
            overridePendingTransition(R.anim.fade_in_scale, R.anim.slide_down_out)
        }
    }

    protected fun saveOpenTabs() {
        if (userPreferences.restoreLostTabsEnabled) {
            tabsManager.saveState()
        }
    }

    override fun onStop() {
        super.onStop()
        proxyUtils.onStop()
    }

    override fun onDestroy() {
        logger.log(TAG, "onDestroy")

        incognitoNotification?.hide()

        mainHandler.removeCallbacksAndMessages(null)

        presenter?.shutdown()

        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        proxyUtils.onStart(this)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        tabsManager.shutdown()
    }

    override fun onResume() {
        super.onResume()
        logger.log(TAG, "onResume")
        if (swapBookmarksAndTabs != userPreferences.bookmarksAndTabsSwapped) {
            restart()
        }

        suggestionsAdapter?.let {
            it.refreshPreferences()
            it.refreshBookmarks()
        }
        tabsManager.resumeAll()
        initializePreferences()


        if(!userPreferences.bottomBar){
            isFullScreen = userPreferences.fullScreenEnabled
        }

        if (isFullScreen) {
            overlayToolbarOnWebView()
        } else {
            putToolbarInRoot()
        }

        if(userPreferences.incognito){
            WebUtils.clearHistory(this, historyModel, databaseScheduler)
            WebUtils.clearCookies(this)
        }

        changeToolbarColor(null)

        val currentUrl = tabsManager.currentTab?.url
        updateTopBarVisibilityForUrl(currentUrl)
        if (currentUrl.isHomeUrl()) {
            findViewById<View>(R.id.toolbar_layout)?.visibility = View.GONE
            findViewById<View>(R.id.toolbar)?.visibility = View.GONE
            searchBackground?.visibility = View.GONE
            toolbar_layout.translationY = 0f
            setWebViewTranslation(0f)
        }

        if(!isIncognito() && !userPreferences.onlyForceClose){
            performExitCleanUp()
        }

        handleBookmarksChange()
    }

    /**
     * searches the web for the query fixing any and all problems with the input
     * checks if it is a search, url, etc.
     */
    fun searchTheWeb(query: String) {
        val currentTab = tabsManager.currentTab
        if (query.isEmpty()) {
            return
        }
        val searchUrl = "$searchText$QUERY_PLACE_HOLDER"
        if (currentTab != null) {
            currentTab.stopLoading()
            val filteredUrl = smartUrlFilter(query.trim(), true, searchUrl)
            updateTopBarVisibilityForUrl(filteredUrl)
            presenter?.loadUrlInCurrentView(filteredUrl)
        }
    }

    private fun loadState(above: Int) = tabsManager.currentTab?.let { it.progress >= above } ?: false

    override fun changeToolbarColor(tabBackground: Drawable?){
        val primaryColor = ThemeUtils.getColorBackground(this)

        if(userPreferences.darkModeExtension) currentTabView?.setBackgroundColor(if (loadState(50)) Color.WHITE else primaryColor); currentTabView?.invalidate()

        if(userPreferences.navbarColChoice == ChooseNavbarCol.COLOR && !isIncognito()){
            if(Utils.isColorTooDark(userPreferences.colorNavbar)){

            }
            tabBackground?.tint(userPreferences.colorNavbar)
            toolbar_layout.setBackgroundColor(userPreferences.colorNavbar)
            currentUiColor = userPreferences.colorNavbar

            searchBackground?.background?.tint(
                    Utils.mixTwoColors(Color.WHITE, userPreferences.colorNavbar, 0.25f)
            )

            backgroundDrawable.color = userPreferences.colorNavbar

            window.setBackgroundDrawable(backgroundDrawable)

        } //Reset theme without needing a restart
        else if(userPreferences.navbarColChoice == ChooseNavbarCol.NONE){
            val isDark = userPreferences.useTheme == AppTheme.DARK || userPreferences.useTheme == AppTheme.BLACK || isIncognito()
            var currentColor = if (isDark) ContextCompat.getColor(this, R.color.black) else ContextCompat.getColor(this, R.color.primary_color)
            if(userPreferences.useTheme == AppTheme.LIGHT && !isIncognito()){
                currentColor = ContextCompat.getColor(this, R.color.primary_color)
            }
            else if(userPreferences.useTheme == AppTheme.DARK){
                currentColor = ContextCompat.getColor(this, R.color.primary_color_dark)
            }
            else if(userPreferences.useTheme == AppTheme.BLACK || isIncognito()){
                currentColor = ContextCompat.getColor(this, R.color.black)
            }
            changeToolbarBackground(null, null)
            tabBackground?.tint(currentColor)
            toolbar_layout.setBackgroundColor(currentColor)
            searchBackground?.background?.tint(
                    Utils.mixTwoColors(Color.WHITE, currentColor, 0.25f)
            )
        }
    }

    /**
     * Animates the color of the toolbar from one color to another. Optionally animates
     * the color of the tab background, for use when the tabs are displayed on the top
     * of the screen.
     *
     * @param favicon the Bitmap to extract the color from
     * @param tabBackground the optional LinearLayout to color
     */
    override fun changeToolbarBackground(favicon: Bitmap?, tabBackground: Drawable?) {
        if (!isColorMode()) {
            return
        }
        val defaultColor = ContextCompat.getColor(this, R.color.primary_color)
        if (currentUiColor == Color.BLACK) {
            currentUiColor = defaultColor
        }
        Palette.from(favicon ?: webPageBitmap!!).generate { palette ->
            // OR with opaque black to remove transparency glitches
            val color = Color.BLACK or (palette?.getVibrantColor(defaultColor) ?: defaultColor)

            // Lighten up the dark color if it is too dark
            val finalColor = if (!shouldShowTabsInDrawer || Utils.isColorTooDark(color)) {
                Utils.mixTwoColors(defaultColor, color, 0.25f)
            } else {
                color
            }

            val window = window
            if (!shouldShowTabsInDrawer) {
                window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }

            val startSearchColor = getSearchBarColor(currentUiColor, defaultColor)
            val finalSearchColor = getSearchBarColor(finalColor, defaultColor)

            val animation = object : Animation() {
                override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
                    val animatedColor = DrawableUtils.mixColor(interpolatedTime, currentUiColor, finalColor)
                    if (shouldShowTabsInDrawer) {
                        backgroundDrawable.color = animatedColor
                        mainHandler.post { window.setBackgroundDrawable(backgroundDrawable) }
                    } else {
                        tabBackground?.tint(animatedColor)
                    }
                    currentUiColor = animatedColor
                    toolbar_layout.setBackgroundColor(animatedColor)
                    searchBackground?.background?.tint(
                            DrawableUtils.mixColor(interpolatedTime, startSearchColor, finalSearchColor)
                    )
                }
            }
            animation.duration = 300
            toolbar_layout.startAnimation(animation)
        }
    }

    private fun getSearchBarColor(requestedColor: Int, defaultColor: Int): Int {
        val targetSurface = if (isDarkTheme) 0xFF22242B.toInt() else 0xFFF1F3F4.toInt()
        return if (requestedColor == defaultColor) {
            targetSurface
        } else {
            DrawableUtils.mixColor(0.20f, requestedColor, targetSurface)
        }
    }

    @ColorInt
    override fun getUiColor(): Int = currentUiColor

    override fun updateUrl(url: String?, isLoading: Boolean) {
        if(!isIncognito()){
            saveOpenTabs()
        }
        updateTopBarVisibilityForUrl(url)
        if (url == null || searchView?.hasFocus() != false) {
            return
        }
        val currentTab = tabsManager.currentTab
        bookmarksView?.handleUpdatedUrl(url)

        val currentTitle = currentTab?.title

        searchView?.setText(searchBoxModel.getDisplayContent(url, currentTitle, isLoading))
    }

    private var bottomNavTabDrawable: TabCountDrawable? = null

    override fun updateTabNumber(number: Int) {
        if (shouldShowTabsInDrawer && !isIncognito()) {
            tabCountView?.setIsIncognito(false)
            tabCountView?.updateCount(number)
        }
        val extraBar = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        extraBar?.let { nav ->
            try {
                nav.removeBadge(R.id.tabs)
                val item = nav.menu.findItem(R.id.tabs)
                if (item != null) {
                    if (bottomNavTabDrawable == null) {
                        bottomNavTabDrawable = TabCountDrawable(this, number, isIncognito())
                    } else {
                        bottomNavTabDrawable?.updateCount(number, isIncognito())
                    }
                    item.icon = bottomNavTabDrawable
                }
            } catch (ignored: Exception) {
            }
        }
    }

    override fun updateProgress(progress: Int) {
        setIsLoading(progress < 100)
        progress_view.progress = progress


    }

    protected fun addItemToHistory(title: String?, url: String) {
        if (url.isBlank()
            || url.isSpecialUrl()
            || url == "about:blank"
            || url.startsWith("data:")
            || url.startsWith("javascript:")
            || url.startsWith("blob:")
            || url.length > 2048) {
            return
        }

        historyModel.visitHistoryEntry(url, title)
                .subscribeOn(databaseScheduler)
                .subscribe({}, { e -> Log.e(TAG, "Error updating history", e) })
    }

    /**
     * method to generate search suggestions for the AutoCompleteTextView from
     * previously searched URLs
     */
    private fun initializeSearchSuggestions(getUrl: AutoCompleteTextView) {
        suggestionsAdapter = SuggestionsAdapter(this, isIncognito())
        suggestionsAdapter?.onInsertClicked = {
            when(it){
                is SearchSuggestion -> {
                    getUrl.setText(it.title)
                    getUrl.setSelection(it.title.length)
                }
                else -> {
                    getUrl.setText(it.url)
                    getUrl.setSelection(it.url.length)
                }
            }

        }
        getUrl.onItemClickListener = OnItemClickListener { _, _, position, _ ->
            val url = when (val selection = suggestionsAdapter?.getItem(position) as WebPage) {
                is HistoryEntry,
                is Bookmark.Entry -> selection.url
                is SearchSuggestion -> selection.title
                else -> null
            } ?: return@OnItemClickListener
            getUrl.setText(url)
            searchTheWeb(url)
            inputMethodManager.hideSoftInputFromWindow(getUrl.windowToken, 0)
            presenter?.onAutoCompleteItemPressed()
        }

        getUrl.setAdapter(suggestionsAdapter)
    }

    /**
     * function that opens the HTML history page in the browser
     */
    fun openHistory() {
        presenter?.newTab(
                historyPageInitializer,
                true
        )
    }

    fun openDownloads() {
        presenter?.newTab(
                downloadPageInitializer,
                true
        )
    }

    /**
     * helper function that opens the bookmark drawer
     */
    private fun openBookmarks() {
        if (drawer_layout.isDrawerOpen(getTabDrawer())) {
            drawer_layout.closeDrawers()
        }
        drawer_layout.openDrawer(getBookmarkDrawer())
    }

    /**
     * This method closes any open drawer and executes the runnable after the drawers are closed.
     *
     * @param runnable an optional runnable to run after the drawers are closed.
     */
    protected fun closeDrawers(runnable: (() -> Unit)?) {
        if (!drawer_layout.isDrawerOpen(left_drawer) && !drawer_layout.isDrawerOpen(right_drawer)) {
            if (runnable != null) {
                runnable()
                return
            }
        }
        drawer_layout.closeDrawers()

        drawer_layout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = Unit

            override fun onDrawerOpened(drawerView: View) = Unit

            override fun onDrawerClosed(drawerView: View) {
                runnable?.invoke()
                drawer_layout.removeDrawerListener(this)
            }

            override fun onDrawerStateChanged(newState: Int) = Unit
        })
    }

    override fun setForwardButtonEnabled(enabled: Boolean) {
        forwardMenuItem?.isEnabled = enabled
        tabsView?.setGoForwardEnabled(enabled)
    }

    override fun setBackButtonEnabled(enabled: Boolean) {
        backMenuItem?.isEnabled = enabled
        tabsView?.setGoBackEnabled(enabled)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        backMenuItem = menu.findItem(R.id.action_back)
        forwardMenuItem = menu.findItem(R.id.action_forward)
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * opens a file chooser
     * param ValueCallback is the message from the WebView indicating a file chooser
     * should be opened
     */
    override fun openFileChooser(uploadMsg: ValueCallback<Uri>) {
        uploadMessageCallback = uploadMsg
        startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, getString(R.string.title_file_chooser)), FILE_CHOOSER_REQUEST_CODE)
    }

    /**
     * used to allow uploading into the browser
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                val result = if (intent == null || resultCode != Activity.RESULT_OK) {
                    null
                } else {
                    intent.data
                }

                uploadMessageCallback?.onReceiveValue(result)
                uploadMessageCallback = null
            } else {
                val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK) {
                    if (intent == null) {
                        // If there is not data, then we may have taken a photo
                        cameraPhotoPath?.let { arrayOf(it.toUri()) }
                    } else {
                        intent.dataString?.let { arrayOf(it.toUri()) }
                    }
                } else {
                    null
                }

                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
            }
        } else {
            super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    override fun showFileChooser(filePathCallback: ValueCallback<Array<Uri>>) {
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = filePathCallback

        // Create the File where the photo should go
        val intentArray: Array<Intent> = try {
            arrayOf(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra("PhotoPath", cameraPhotoPath)
                putExtra(
                        MediaStore.EXTRA_OUTPUT,
                        Uri.fromFile(Utils.createImageFile().also { file ->
                            cameraPhotoPath = "file:${file.absolutePath}"
                        })
                )
            })
        } catch (ex: IOException) {
            // Error occurred while creating the File
            logger.log(TAG, "Unable to create Image File", ex)
            emptyArray()
        }

        startActivityForResult(Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
            putExtra(Intent.EXTRA_TITLE, "Image Chooser")
            putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
        }, FILE_CHOOSER_REQUEST_CODE)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback, requestedOrientation: Int) {
        val currentTab = tabsManager.currentTab
        if (customView != null) {
            try {
                callback.onCustomViewHidden()
            } catch (e: Exception) {
                logger.log(TAG, "Error hiding custom view", e)
            }

            return
        }

        try {
            view.keepScreenOn = true
        } catch (e: SecurityException) {
            logger.log(TAG, "WebView is not allowed to keep the screen on")
        }

        originalOrientation = getRequestedOrientation()
        customViewCallback = callback
        customView = view

        //setRequestedOrientation(requestedOrientation)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR)
        val decorView = window.decorView as FrameLayout

        fullscreenContainerView = FrameLayout(this)
        fullscreenContainerView?.setBackgroundColor(ContextCompat.getColor(this, R.color.black))
        if (view is FrameLayout) {
            val child = view.focusedChild
            if (child is VideoView) {
                videoView = child
                child.setOnErrorListener(VideoCompletionListener())
                child.setOnCompletionListener(VideoCompletionListener())
            }
        } else if (view is VideoView) {
            videoView = view
            view.setOnErrorListener(VideoCompletionListener())
            view.setOnCompletionListener(VideoCompletionListener())
        }
        decorView.addView(fullscreenContainerView, COVER_SCREEN_PARAMS)
        fullscreenContainerView?.addView(customView, COVER_SCREEN_PARAMS)
        decorView.requestLayout()
        setFullscreen(enabled = true, immersive = true)
        currentTab?.setVisibility(INVISIBLE)
    }

    override fun onHideCustomView() {
        val currentTab = tabsManager.currentTab
        if (customView == null || customViewCallback == null || currentTab == null) {
            if (customViewCallback != null) {
                try {
                    customViewCallback?.onCustomViewHidden()
                } catch (e: Exception) {
                    logger.log(TAG, "Error hiding custom view", e)
                }

                customViewCallback = null
            }
            return
        }
        logger.log(TAG, "onHideCustomView")
        currentTab.setVisibility(VISIBLE)
        try {
            customView?.keepScreenOn = false
        } catch (e: SecurityException) {
            logger.log(TAG, "WebView is not allowed to keep the screen on")
        }

        setFullscreen(userPreferences.hideStatusBarEnabled, false)
        if (fullscreenContainerView != null) {
            val parent = fullscreenContainerView?.parent as ViewGroup
            parent.removeView(fullscreenContainerView)
            fullscreenContainerView?.removeAllViews()
        }

        fullscreenContainerView = null
        customView = null

        logger.log(TAG, "VideoView is being stopped")
        videoView?.stopPlayback()
        videoView?.setOnErrorListener(null)
        videoView?.setOnCompletionListener(null)
        videoView = null

        try {
            customViewCallback?.onCustomViewHidden()
        } catch (e: Exception) {
            logger.log(TAG, "Error hiding custom view", e)
        }

        customViewCallback = null
        requestedOrientation = originalOrientation
    }

    private inner class VideoCompletionListener : MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

        override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean = false

        override fun onCompletion(mp: MediaPlayer) = onHideCustomView()

    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        logger.log(TAG, "onWindowFocusChanged")
        if (hasFocus) {
            setFullscreen(hideStatusBar, isImmersiveMode)
        }
    }

    override fun onBackButtonPressed() {
        if (drawer_layout.closeDrawerIfOpen(getTabDrawer())) {
            val currentTab = tabsManager.currentTab
            if (currentTab?.canGoBack() == true) {
                currentTab.goBack()
            } else if (currentTab != null) {
                tabsManager.let { presenter?.deleteTab(it.positionOf(currentTab), true) }
            }
        } else if (drawer_layout.closeDrawerIfOpen(getBookmarkDrawer())) {
            // Don't do anything other than close the bookmarks drawer when the activity is being
            // delegated to.
            return
        }
    }

    override fun onForwardButtonPressed() {
        val currentTab = tabsManager.currentTab
        if (currentTab?.canGoForward() == true) {
            currentTab.goForward()
            closeDrawers(null)
        }
    }

    override fun onHomeButtonPressed() {
        tabsManager.currentTab?.loadHomePage()
        updateTopBarVisibilityForUrl(null)
        closeDrawers(null)
    }

    /**
     * This method sets whether or not the activity will display
     * in full-screen mode (i.e. the ActionBar will be hidden) and
     * whether or not immersive mode should be set. This is used to
     * set both parameters correctly as during a full-screen video,
     * both need to be set, but other-wise we leave it up to user
     * preference.
     *
     * @param enabled   true to enable full-screen, false otherwise
     * @param immersive true to enable immersive mode, false otherwise
     */
    private fun setFullscreen(enabled: Boolean, immersive: Boolean) {
        hideStatusBar = enabled
        isImmersiveMode = immersive
        val window = window
        val decor = window.decorView
        if (enabled) {
            if (immersive) {
                decor.systemUiVisibility = (SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or SYSTEM_UI_FLAG_FULLSCREEN
                        or SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            } else {
                decor.systemUiVisibility = SYSTEM_UI_FLAG_VISIBLE
            }
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decor.systemUiVisibility = SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /**
     * This method handles the JavaScript callback to create a new tab.
     * Basically this handles the event that JavaScript needs to create
     * a popup.
     *
     * @param resultMsg the transport message used to send the URL to
     * the newly created WebView.
     */
    override fun onCreateWindow(resultMsg: Message) {
        presenter?.newTab(ResultMessageInitializer(resultMsg), true)
    }

    /**
     * Closes the specified [SmartCookieView]. This implements
     * the JavaScript callback that asks the tab to close itself and
     * is especially helpful when a page creates a redirect and does
     * not need the tab to stay open any longer.
     *
     * @param tab the LightningView to close, delete it.
     */
    override fun onCloseWindow(tab: SmartCookieView) {
        presenter?.deleteTab(tabsManager.positionOf(tab))
    }

    /**
     * Hide the ActionBar using an animation if we are in full-screen
     * mode. This method also re-parents the ActionBar if its parent is
     * incorrect so that the animation can happen correctly.
     */
    override fun hideActionBar() {
        if (isFullScreen) {
            if (toolbar_layout == null || content_frame == null)
                return

            val height = toolbar_layout.height

            if (toolbar_layout.translationY > -0.01f) {
                val hideAnimation = object : Animation() {
                    override fun applyTransformation(interpolatedTime: Float, t: Transformation) {

                        val trans = interpolatedTime * height
                        toolbar_layout.translationY = -trans
                        setWebViewTranslation(height - trans)

                    }
                }
                hideAnimation.duration = 250
                hideAnimation.interpolator = BezierDecelerateInterpolator()
                content_frame.startAnimation(hideAnimation)
            }
        }
    }

    /**
     * Display the ActionBar using an animation if we are in full-screen
     * mode. This method also re-parents the ActionBar if its parent is
     * incorrect so that the animation can happen correctly.
     */
    override fun showActionBar() {
        if (isFullScreen) {
            logger.log(TAG, "showActionBar")
            if (toolbar_layout == null)
                return

            var height = toolbar_layout.height
            if (height == 0) {
                toolbar_layout.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
                height = toolbar_layout.measuredHeight
            }

            val totalHeight = height
            if (toolbar_layout.translationY < -(height - 0.01f)) {
                val show = object : Animation() {
                    override fun applyTransformation(interpolatedTime: Float, t: Transformation) {

                        val trans = interpolatedTime * totalHeight
                        toolbar_layout.translationY = trans - totalHeight
                        setWebViewTranslation(trans)

                    }
                }
                show.duration = 250
                show.interpolator = BezierDecelerateInterpolator()
                content_frame.startAnimation(show)
            }
        }
    }

    override fun handleBookmarksChange() {
        val currentTab = tabsManager.currentTab
        if (currentTab != null && currentTab.url.isBookmarkUrl()) {
            currentTab.loadBookmarkPage()
        }
        if (currentTab != null) {
            bookmarksView?.handleUpdatedUrl(currentTab.url)
        }
        suggestionsAdapter?.refreshBookmarks()
    }

    override fun handleDownloadDeleted() {
        val currentTab = tabsManager.currentTab
        if (currentTab != null && currentTab.url.isDownloadsUrl()) {
            currentTab.loadDownloadsPage()
        }
        if (currentTab != null) {
            bookmarksView?.handleUpdatedUrl(currentTab.url)
        }
    }

    override fun handleBookmarkDeleted(bookmark: Bookmark) {
        bookmarksView?.handleBookmarkDeleted(bookmark)
        handleBookmarksChange()
    }

    override fun handleNewTab(newTabType: LightningDialogBuilder.NewTab, url: String, addToIndex: Boolean) {
        val urlInitializer = UrlInitializer(url)
        when (newTabType) {
            LightningDialogBuilder.NewTab.FOREGROUND -> if (addToIndex) {
                presenter?.newTabAtPosition(urlInitializer, true, tabsManager.indexOfCurrentTab() + 1)
            } else {
                presenter?.newTab(urlInitializer, true)
            }
            LightningDialogBuilder.NewTab.BACKGROUND -> {
                if (addToIndex) {
                    presenter?.newTabAtPosition(urlInitializer, false, tabsManager.indexOfCurrentTab() + 1)
                } else {
                    presenter?.newTab(urlInitializer, false)
                }
                val snackbar = Snackbar
                        .make(findViewById(android.R.id.content), resources.getString(R.string.new_tab_opened), Snackbar.LENGTH_SHORT)
                        .setAction(resources.getString(R.string.switch_button)) { tabClicked(tabsManager.indexOfCurrentTab() + 1) }
                snackbar.show()
            }
            LightningDialogBuilder.NewTab.INCOGNITO -> {
                drawer_layout.closeDrawers()
                val intent = IncognitoActivity.createIntent(this, url.toUri())
                startActivity(intent)
                overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out_scale)
            }
        }
    }

    /**
     * This method lets the search bar know that the page is currently loading
     * and that it should display the stop icon to indicate to the user that
     * pressing it stops the page from loading
     */
    private fun setIsLoading(isLoading: Boolean) {
        if (searchView?.hasFocus() == false) {
            search_ssl_status.updateVisibilityForContent()
            search_refresh.setImageResource(if (isLoading) R.drawable.ic_action_delete else
                if (isDarkTheme) {
                    R.drawable.ic_action_refresh_light
                } else {
                    R.drawable.ic_action_refresh
                })
        }
    }

    /**
     * handle presses on the refresh icon in the search bar, if the page is
     * loading, stop the page, if it is done loading refresh the page.
     * See setIsFinishedLoading and setIsLoading for displaying the correct icon
     */
    private fun refreshOrStop() {
        val currentTab = tabsManager.currentTab
        if (currentTab != null) {
            if (currentTab.progress < 100) {
                currentTab.stopLoading()
            } else {
                currentTab.reload()
            }
        }
    }

    /**
     * Handle the click event for the views that are using
     * this class as a click listener. This method should
     * distinguish between the various views using their IDs.
     *
     * @param v the view that the user has clickedf
     */
    override fun onClick(v: View) {
        val currentTab = tabsManager.currentTab ?: return
        when (v.id) {
            R.id.home_button -> when {
                searchView?.hasFocus() == true -> currentTab.requestFocus()
                shouldShowTabsInDrawer -> toggleTabsBottomSheet()
                else -> {
                    currentTab.loadHomePage()
                    updateTopBarVisibilityForUrl(null)
                }
            }
            R.id.more_button -> {
                showViaMenu()
            }
            R.id.download_button -> {
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, currentTab.url)
                    type = "text/plain"
                }
                sendIntent.setClassName("com.cookiejarapps.smartcookieweb_ytdl",
                        "com.cookiejarapps.smartcookieweb_ytdl.MainActivity")

                val shareIntent = Intent.createChooser(sendIntent, null)
                startActivity(shareIntent)
            }
            R.id.button_next -> findResult?.nextResult()
            R.id.button_back -> findResult?.previousResult()
            R.id.button_quit -> {
                findResult?.clearResults()
                findResult = null
                search_bar.visibility = GONE
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(currentFocus!!.windowToken, 0)
            }
            R.id.button_search -> {
                showFindInPageControls(findViewById<EditText>(R.id.search_query).text.toString())
                findResult = presenter?.findInPage(findViewById<EditText>(R.id.search_query).text.toString())
            }
        }
    }


    /**
     * Handle the callback that permissions requested have been granted or not.
     * This method should act upon the results of the permissions request.
     *
     * @param requestCode  the request code sent when initially making the request
     * @param permissions  the array of the permissions that was requested
     * @param grantResults the results of the permissions requests that provides
     * information on whether the request was granted or not
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        PermissionsManager.getInstance().notifyPermissionsChange(permissions, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

   override fun getAssets(): AssetManager {
        return resources.assets
    }

    /**
     * If the [drawer] is open, close it and return true. Return false otherwise.
     */
    private fun DrawerLayout.closeDrawerIfOpen(drawer: View): Boolean =
            if (isDrawerOpen(drawer)) {
                closeDrawer(drawer)
                true
            } else {
                false
            }

    // ==========================================
    // Modern Bottom Sheet Tabs Management
    // ==========================================

    private fun initTabsBottomSheet() {
        mainHandler.post {
            try {
                drawer_layout?.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, getTabDrawer())
            } catch (e: Exception) {}
        }
    }

    private fun isTabsBottomSheetShowing(): Boolean {
        return tabsDialog?.isShowing == true
    }

    private fun updateTabsBottomSheetList() {
        if (tabsDialog?.isShowing == true) {
            val tabStates = tabsManager.allTabs.map(SmartCookieView::asTabViewState)
            tabsBottomSheetAdapter?.showTabs(tabStates)
        }
    }

    fun showTabsBottomSheet() {
        if (tabsDialog?.isShowing == true) {
            hideTabsBottomSheet()
            return
        }

        val dialog = BottomSheetDialog(this, R.style.ViaBottomSheetDialogTheme)
        tabsDialog = dialog
        val view = LayoutInflater.from(this).inflate(R.layout.view_tabs_bottom_sheet, null)
        dialog.setContentView(view)

        // Make bottom sheet container completely transparent to eliminate white border leak
        try {
            dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.decorView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = ColorDrawable(android.graphics.Color.TRANSPARENT)
        } catch (e: Exception) {}

        val isDark = isDarkTheme
        val rootBg = if (isDark) R.drawable.dialog_via_menu_bg_dark else R.drawable.dialog_via_menu_bg_light
        view.setBackgroundResource(rootBg)

        // Dynamic theming for elements
        val iconColor = if (isDark) 0xFFA0A4AE.toInt() else 0xFF5F6368.toInt()
        val dividerColor = if (isDark) 0x14FFFFFF else 0x14000000
        val handleColor = if (isDark) 0x40FFFFFF else 0x40000000

        view.findViewById<View>(R.id.tabs_bottom_handle)?.background?.setTint(handleColor)
        view.findViewById<View>(R.id.tabs_bottom_divider)?.setBackgroundColor(dividerColor)
        view.findViewById<ImageView>(R.id.btn_tabs_bottom_new_tab_icon)?.setColorFilter(iconColor)

        val recycler = view.findViewById<RecyclerView>(R.id.tabs_bottom_recycler)
        val newTabBtn = view.findViewById<View>(R.id.btn_tabs_bottom_new_tab)

        val adapter = TabsBottomSheetAdapter(
            isDarkTheme = isDark,
            onTabSelected = { position ->
                dialog.dismiss()
                presenter?.tabChanged(position)
            },
            onTabClosed = { position ->
                presenter?.deleteTab(position)
                val tabStates = tabsManager.allTabs.map(SmartCookieView::asTabViewState)
                if (tabStates.isEmpty()) {
                    dialog.dismiss()
                    newTabButtonClicked()
                } else {
                    tabsBottomSheetAdapter?.showTabs(tabStates)
                }
            }
        )
        tabsBottomSheetAdapter = adapter

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        val allTabStates = tabsManager.allTabs.map(SmartCookieView::asTabViewState)
        adapter.showTabs(allTabStates)

        val currentPos = tabsManager.positionOf(tabsManager.currentTab)
        if (currentPos >= 0) {
            recycler.scrollToPosition(currentPos)
        }

        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        recycler.viewTreeObserver.addOnGlobalLayoutListener {
            if (recycler.height > maxHeight) {
                val params = recycler.layoutParams
                if (params.height != maxHeight) {
                    params.height = maxHeight
                    recycler.layoutParams = params
                }
            }
        }

        newTabBtn?.setOnClickListener {
            dialog.dismiss()
            newTabButtonClicked()
        }

        dialog.setOnDismissListener {
            if (tabsDialog == dialog) {
                tabsDialog = null
                tabsBottomSheetAdapter = null
            }
        }

        dialog.show()
    }

    fun hideTabsBottomSheet() {
        tabsDialog?.dismiss()
        tabsDialog = null
        tabsBottomSheetAdapter = null
    }

    fun toggleTabsBottomSheet() {
        if (isTabsBottomSheetShowing()) {
            hideTabsBottomSheet()
        } else {
            showTabsBottomSheet()
        }
    }

    /**
     * Show the bottom sheet menu modeled directly after Via Browser's swipeable quick action grid.
     */
    fun showViaMenu() {
        val dialog = BottomSheetDialog(this, R.style.ViaBottomSheetDialogTheme)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_via_menu, null)
        dialog.setContentView(view)

        // Make bottom sheet container completely transparent to eliminate white border leak
        try {
            dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.decorView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = ColorDrawable(android.graphics.Color.TRANSPARENT)
        } catch (e: Exception) {}

        val isDark = isDarkTheme
        val rootBg = if (isDark) R.drawable.dialog_via_menu_bg_dark else R.drawable.dialog_via_menu_bg_light
        view.findViewById<View>(R.id.via_menu_root)?.setBackgroundResource(rootBg)

        val textColor = if (isDark) 0xFF8E929C.toInt() else 0xFF3C4043.toInt()
        val iconColor = if (isDark) 0xFFA0A4AE.toInt() else 0xFF5F6368.toInt()
        val activeDotColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF202124.toInt()
        val inactiveDotColor = if (isDark) 0xFF505358.toInt() else 0xFFDADCE0.toInt()
        val activeColor = 0xFF5C87F7.toInt()

        view.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.btn_via_exit)?.setColorFilter(iconColor)
        view.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.btn_via_close)?.setColorFilter(iconColor)

        val page1View = LayoutInflater.from(this).inflate(R.layout.view_via_menu_page1, null)
        val page2View = LayoutInflater.from(this).inflate(R.layout.view_via_menu_page2, null)
        val page3View = LayoutInflater.from(this).inflate(R.layout.view_via_menu_page3, null)

        fun colorize(page: View) {
            if (page !is ViewGroup) return
            for (i in 0 until page.childCount) {
                val row = page.getChildAt(i)
                if (row is ViewGroup) {
                    for (j in 0 until row.childCount) {
                        val item = row.getChildAt(j)
                        if (item is ViewGroup) {
                            for (k in 0 until item.childCount) {
                                val child = item.getChildAt(k)
                                if (child is ImageView) {
                                    child.setColorFilter(iconColor)
                                } else if (child is TextView) {
                                    child.setTextColor(textColor)
                                }
                            }
                        }
                    }
                }
            }
        }
        colorize(page1View)
        colorize(page2View)
        colorize(page3View)

        val viewPager = view.findViewById<ViewPager>(R.id.via_menu_view_pager)
        val pages = listOf(page1View, page2View, page3View)
        viewPager.adapter = object : PagerAdapter() {
            override fun getCount(): Int = pages.size
            override fun isViewFromObject(v: View, obj: Any): Boolean = v === obj
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                container.addView(pages[position])
                return pages[position]
            }
            override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                container.removeView(obj as View)
            }
        }

        val dot1 = view.findViewById<TextView>(R.id.dot_1)
        val dot2 = view.findViewById<TextView>(R.id.dot_2)
        val dot3 = view.findViewById<TextView>(R.id.dot_3)
        fun updateDots(position: Int) {
            dot1?.text = if (position == 0) "●" else "○"
            dot1?.setTextColor(if (position == 0) activeDotColor else inactiveDotColor)
            dot2?.text = if (position == 1) "●" else "○"
            dot2?.setTextColor(if (position == 1) activeDotColor else inactiveDotColor)
            dot3?.text = if (position == 2) "●" else "○"
            dot3?.setTextColor(if (position == 2) activeDotColor else inactiveDotColor)
        }
        updateDots(0)
        dot1?.setOnClickListener { viewPager.currentItem = 0 }
        dot2?.setOnClickListener { viewPager.currentItem = 1 }
        dot3?.setOnClickListener { viewPager.currentItem = 2 }

        viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
            }
        })

        val currentTab = tabsManager.currentTab

        // Active State Indications (Tint active icon & text blue #5C87F7)
        if (userPreferences.invertColors) {
            page1View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_night_mode)?.setColorFilter(activeColor)
            page1View.findViewById<TextView>(R.id.text_via_night_mode)?.setTextColor(activeColor)
        }

        if (currentTab?.toggleDesktop == true) {
            page1View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_desktop)?.setColorFilter(activeColor)
            page1View.findViewById<TextView>(R.id.text_via_desktop)?.setTextColor(activeColor)
        }

        if (isIncognito()) {
            page1View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_incognito)?.setColorFilter(activeColor)
            page1View.findViewById<TextView>(R.id.text_via_incognito)?.apply {
                setTextColor(activeColor)
                text = "Salir de incógnito"
            }
        }

        if (userPreferences.adBlockEnabled) {
            page3View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_adblock)?.setColorFilter(activeColor)
            page3View.findViewById<TextView>(R.id.text_via_adblock)?.setTextColor(activeColor)
        }

        if (userPreferences.blockImagesEnabled) {
            page2View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_images)?.setColorFilter(activeColor)
            page2View.findViewById<TextView>(R.id.text_via_images)?.setTextColor(activeColor)
        }

        if (isFullScreen) {
            page2View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_fullscreen)?.setColorFilter(activeColor)
            page2View.findViewById<TextView>(R.id.text_via_fullscreen)?.setTextColor(activeColor)
        }

        val isTranslated = currentTab?.url?.let {
            it.contains("translate.google.com") || it.contains("translate.googleusercontent.com")
        } == true

        if (isTranslated) {
            page2View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_translate)?.setColorFilter(activeColor)
            page2View.findViewById<TextView>(R.id.text_via_translate)?.setTextColor(activeColor)
        }

        currentTab?.let { tab ->
            if (tab.url.isNotEmpty()) {
                bookmarkManager.isBookmark(tab.url)
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe { isBookmark ->
                        if (isBookmark) {
                            page1View.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.icon_via_add_bookmark)?.setColorFilter(activeColor)
                            page1View.findViewById<TextView>(R.id.text_via_add_bookmark)?.setTextColor(activeColor)
                        }
                    }
            }
        }

        // ================= PAGE 1 ACTIONS =================
        page1View.findViewById<View>(R.id.btn_via_night_mode)?.setOnClickListener {
            dialog.dismiss()
            userPreferences.invertColors = !userPreferences.invertColors
            val enabled = userPreferences.invertColors
            currentTab?.webView?.let { wv ->
                DarkReaderHelper.toggle(wv, enabled)
            }
            Toast.makeText(
                this,
                if (enabled) "Modo nocturno (Dark Reader) activado" else "Modo nocturno desactivado",
                Toast.LENGTH_SHORT
            ).show()
        }

        page1View.findViewById<View>(R.id.btn_via_bookmarks)?.setOnClickListener {
            dialog.dismiss()
            drawer_layout.openDrawer(getBookmarkDrawer())
        }

        page1View.findViewById<View>(R.id.btn_via_history)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        page1View.findViewById<View>(R.id.btn_via_downloads)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, DownloadActivity::class.java))
        }

        page1View.findViewById<View>(R.id.btn_via_incognito)?.setOnClickListener {
            dialog.dismiss()
            if (isIncognito()) {
                Toast.makeText(this, "Modo incógnito desactivado", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            } else {
                startActivity(IncognitoActivity.createIntent(this, null))
            }
        }

        page1View.findViewById<View>(R.id.btn_via_add_bookmark)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val bookmark = Bookmark.Entry(tab.url, tab.title, 0, Bookmark.Folder.Root)
                bookmarksDialogBuilder.showAddBookmarkDialog(this, this, bookmark)
            }
        }

        page1View.findViewById<View>(R.id.btn_via_desktop)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                tab.toggleDesktopUA()
                tab.reload()
                val isDesktop = tab.toggleDesktop
                Toast.makeText(this, if (isDesktop) "Sitio de escritorio activado" else "Sitio móvil activado", Toast.LENGTH_SHORT).show()
            }
        }

        page1View.findViewById<View>(R.id.btn_via_tools)?.setOnClickListener {
            viewPager.currentItem = 1
        }

        page1View.findViewById<View>(R.id.btn_via_settings)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }

        page1View.findViewById<View>(R.id.btn_via_find)?.setOnClickListener {
            dialog.dismiss()
            findInPage()
        }

        // ================= PAGE 2 ACTIONS (HERRAMIENTAS) =================
        page2View.findViewById<View>(R.id.btn_via_clear_data)?.setOnClickListener {
            dialog.dismiss()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_history)
                .setMessage("¿Deseas borrar las cookies, caché y datos de navegación?")
                .setPositiveButton(R.string.action_yes) { _, _ ->
                    currentTab?.webView?.clearHistory()
                    currentTab?.webView?.clearCache(true)
                    CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    Toast.makeText(this, "Datos borrados", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.action_no, null)
                .show()
        }

        page2View.findViewById<View>(R.id.btn_via_translate)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val url = tab.url
                if (url.isBlank() || url.isSpecialUrl()) {
                    return@setOnClickListener
                }
                val isCurrentlyTranslated = url.contains("translate.google.com") || url.contains("translate.googleusercontent.com")
                if (isCurrentlyTranslated) {
                    val originalUrl = try {
                        android.net.Uri.parse(url).getQueryParameter("u")
                    } catch (e: Exception) {
                        null
                    }
                    if (!originalUrl.isNullOrBlank()) {
                        presenter?.loadUrlInCurrentView(originalUrl)
                        Toast.makeText(this, "Traducción desactivada", Toast.LENGTH_SHORT).show()
                    } else if (tab.canGoBack()) {
                        tab.goBack()
                        Toast.makeText(this, "Traducción desactivada", Toast.LENGTH_SHORT).show()
                    } else {
                        tab.reload()
                    }
                } else {
                    val lang = java.util.Locale.getDefault().language.ifBlank { "es" }
                    val trUrl = "https://translate.google.com/translate?sl=auto&tl=$lang&u=" + java.net.URLEncoder.encode(url, "UTF-8")
                    presenter?.loadUrlInCurrentView(trUrl)
                    Toast.makeText(this, "Traduciendo página...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        page2View.findViewById<View>(R.id.btn_via_view_source)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                presenter?.loadUrlInCurrentView("view-source:" + tab.url)
            }
        }

        page2View.findViewById<View>(R.id.btn_via_fullscreen)?.setOnClickListener {
            dialog.dismiss()
            userPreferences.fullScreenEnabled = !userPreferences.fullScreenEnabled
            isFullScreen = userPreferences.fullScreenEnabled
            if (isFullScreen) {
                overlayToolbarOnWebView()
            } else {
                putToolbarInRoot()
            }
            Toast.makeText(this, if (isFullScreen) "Pantalla completa activada" else "Pantalla completa desactivada", Toast.LENGTH_SHORT).show()
        }

        page2View.findViewById<View>(R.id.btn_via_images)?.setOnClickListener {
            dialog.dismiss()
            userPreferences.blockImagesEnabled = !userPreferences.blockImagesEnabled
            tabsManager.allTabs.forEach { tab ->
                tab.initializePreferences()
            }
            if (userPreferences.blockImagesEnabled) {
                currentTab?.webView?.evaluateJavascript("""
                    (function() {
                        var style = document.getElementById('lw_block_images_style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = 'lw_block_images_style';
                            style.textContent = 'img, picture, image, [style*="background-image"] { display: none !important; visibility: hidden !important; }';
                            (document.head || document.documentElement).appendChild(style);
                        }
                    })();
                """.trimIndent(), null)
                Toast.makeText(this, "Modo sin imágenes activado", Toast.LENGTH_SHORT).show()
            } else {
                currentTab?.webView?.evaluateJavascript("""
                    (function() {
                        var style = document.getElementById('lw_block_images_style');
                        if (style) { style.remove(); }
                    })();
                """.trimIndent(), null)
                Toast.makeText(this, "Carga de imágenes activada", Toast.LENGTH_SHORT).show()
                currentTab?.reload()
            }
        }

        page2View.findViewById<View>(R.id.btn_via_inspect)?.setOnClickListener {
            dialog.dismiss()
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Inyectar script")
            val dialogLayout = layoutInflater.inflate(R.layout.dialog_edit_text, null)
            val editText = dialogLayout.findViewById<EditText>(R.id.dialog_edit_text)
            builder.setView(dialogLayout)
            builder.setPositiveButton("OK") { _, _ ->
                currentTab?.loadUrl("javascript:(function() {" + editText.text.toString() + "})()")
            }
            builder.show()
        }

        page2View.findViewById<View>(R.id.btn_via_user_agent)?.setOnClickListener {
            dialog.dismiss()
            val uaOptions = arrayOf(
                "Predeterminado",
                "Android (Phone)",
                "Android (Tablet)",
                "Windows (Chrome)",
                "Windows (IE 11)",
                "macOS",
                "iPhone",
                "iPad",
                "Symbian",
                "Personalizado"
            )
            val checkedItem = (userPreferences.userAgentChoice - 1).coerceIn(0, uaOptions.size - 1)
            MaterialAlertDialogBuilder(this)
                .setTitle("Useragent")
                .setSingleChoiceItems(uaOptions, checkedItem) { d, which ->
                    d.dismiss()
                    if (which == 9) {
                        val dialogLayout = layoutInflater.inflate(R.layout.dialog_edit_text, null)
                        val editText = dialogLayout.findViewById<EditText>(R.id.dialog_edit_text)
                        editText.setText(userPreferences.userAgentString)
                        editText.hint = "Ingresa tu User-Agent personalizado"
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Useragent personalizado")
                            .setView(dialogLayout)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val customUa = editText.text.toString().trim()
                                userPreferences.userAgentString = customUa
                                userPreferences.userAgentChoice = 10
                                currentTab?.reload()
                                Toast.makeText(this, "Useragent: Personalizado", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        userPreferences.userAgentChoice = which + 1
                        currentTab?.reload()
                        Toast.makeText(this, "Useragent: " + uaOptions[which], Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        page2View.findViewById<View>(R.id.btn_via_cookies)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie(tab.url)?.takeIf { it.isNotBlank() } ?: "Sin cookies para este sitio"
                MaterialAlertDialogBuilder(this)
                    .setTitle("Cookies")
                    .setMessage(cookies)
                    .setPositiveButton("Copiar") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Cookies", cookies)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Cookies copiadas al portapapeles", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }

        page2View.findViewById<View>(R.id.btn_via_share)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                IntentUtils(this).shareUrl(tab.url, tab.title)
            }
        }

        // ================= PAGE 3 ACTIONS =================
        page3View.findViewById<View>(R.id.btn_via_add_homescreen)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                if (tab.url.isNotBlank() && !tab.url.isSpecialUrl()) {
                    val iconBmp = tab.favicon ?: webPageBitmap ?: Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                    showInstallAppDialog(tab.url, tab.title, iconBmp)
                } else {
                    Toast.makeText(this, "Esta página no se puede instalar", Toast.LENGTH_SHORT).show()
                }
            }
        }

        page3View.findViewById<View>(R.id.btn_via_orientation)?.setOnClickListener {
            dialog.dismiss()
            val current = requestedOrientation
            requestedOrientation = if (current == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            Toast.makeText(this, "Orientación cambiada", Toast.LENGTH_SHORT).show()
        }

        page3View.findViewById<View>(R.id.btn_via_adblock)?.setOnClickListener {
            dialog.dismiss()
            userPreferences.adBlockEnabled = !userPreferences.adBlockEnabled
            Toast.makeText(this, if (userPreferences.adBlockEnabled) "Bloqueo de anuncios activado" else "Bloqueo de anuncios desactivado", Toast.LENGTH_SHORT).show()
            currentTab?.reload()
        }

        page3View.findViewById<View>(R.id.btn_via_mark_ad)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val isAllowed = allowListModel.isUrlAllowedAds(tab.url)
                if (isAllowed) {
                    allowListModel.removeUrlFromAllowList(tab.url)
                    Toast.makeText(this, "Anuncios bloqueados para este sitio", Toast.LENGTH_SHORT).show()
                } else {
                    allowListModel.addUrlToAllowList(tab.url)
                    Toast.makeText(this, "Sitio añadido a la lista blanca de anuncios", Toast.LENGTH_SHORT).show()
                }
                tab.reload()
            }
        }

        page3View.findViewById<View>(R.id.btn_via_text_size)?.setOnClickListener {
            dialog.dismiss()
            val layout = layoutInflater.inflate(R.layout.dialog_seek_bar, null)
            val textPercent = layout.findViewById<TextView>(R.id.text_size_percent)
            val textSample = layout.findViewById<TextView>(R.id.text_size_sample)
            val seekBar = layout.findViewById<SeekBar>(R.id.text_size_seekbar)

            val originalZoom = userPreferences.getTextZoomPercent()
            seekBar?.max = 150
            seekBar?.progress = (originalZoom - 50).coerceIn(0, 150)

            val updateViews = { zoom: Int ->
                textPercent?.text = if (zoom == 100) "100% (Predeterminado)" else "$zoom%"
                textSample?.textSize = 15f * (zoom / 100f)
                currentTab?.webView?.settings?.textZoom = zoom
            }

            updateViews(originalZoom)

            seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateViews(progress + 50)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            var applied = false
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_text_size)
                .setView(layout)
                .setPositiveButton(R.string.action_ok) { _, _ ->
                    applied = true
                    val finalZoom = (seekBar?.progress ?: 50) + 50
                    userPreferences.textSize = finalZoom
                    tabsManager.allTabs.forEach { tab ->
                        tab.webView?.settings?.textZoom = finalZoom
                    }
                    Toast.makeText(this, "Tamaño del texto: $finalZoom%", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Restablecer") { _, _ ->
                    applied = true
                    userPreferences.textSize = 100
                    tabsManager.allTabs.forEach { tab ->
                        tab.webView?.settings?.textZoom = 100
                    }
                    Toast.makeText(this, "Tamaño del texto: 100%", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> }
                .setOnDismissListener {
                    if (!applied) {
                        currentTab?.webView?.settings?.textZoom = originalZoom
                    }
                }
                .show()
        }

        page3View.findViewById<View>(R.id.btn_via_copy_link)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val url = tab.url
                if (url.isNotBlank() && !url.isSpecialUrl()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("URL", url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Enlace copiado al portapapeles", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No hay enlace para copiar", Toast.LENGTH_SHORT).show()
                }
            }
        }

        page3View.findViewById<View>(R.id.btn_via_open_in_app)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val url = tab.url
                if (url.isNotBlank() && !url.isSpecialUrl()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        val chooser = Intent.createChooser(intent, "Abrir con...")
                        startActivity(chooser)
                    } catch (e: Exception) {
                        Toast.makeText(this, "No se puede abrir en otra aplicación", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Página no compatible para abrir externamente", Toast.LENGTH_SHORT).show()
                }
            }
        }

        page3View.findViewById<View>(R.id.btn_via_print)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                tab.webView?.let { wv ->
                    tab.createWebPagePrint(wv)
                } ?: Toast.makeText(this, "No se puede imprimir esta página", Toast.LENGTH_SHORT).show()
            }
        }

        page3View.findViewById<View>(R.id.btn_via_save_page)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val url = tab.url
                if (url.isBlank() || url.isSpecialUrl()) {
                    Toast.makeText(this, "No se puede guardar esta página", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                tab.webView?.evaluateJavascript(
                    """(function() {
                        var baseTag = document.querySelector('base');
                        var baseHref = baseTag ? baseTag.href : window.location.href;
                        var head = document.head ? document.head.innerHTML : '';
                        if (!baseTag) {
                            head = '<base href="' + baseHref + '">' + head;
                        }
                        var body = document.body ? document.body.innerHTML : '';
                        return '<!DOCTYPE html><html><head>' + head + '</head><body>' + body + '</body></html>';
                    })()"""
                ) { rawJson ->
                    if (rawJson != null && rawJson != "null") {
                        try {
                            val html = org.json.JSONObject("{\"html\": $rawJson}").getString("html")
                            val cleanTitle = (tab.title?.takeIf { it.isNotBlank() } ?: "pagina")
                                .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
                                .take(40)
                            val filename = "${cleanTitle}_${System.currentTimeMillis()}.html"
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadDir.exists()) downloadDir.mkdirs()
                            val destFile = File(downloadDir, filename)
                            destFile.writeText(html, Charsets.UTF_8)
                            Toast.makeText(this, "Guardado en Descargas: $filename", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error al guardar página: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "No se pudo obtener el contenido", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        page3View.findViewById<View>(R.id.btn_via_reading_mode)?.setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val url = tab.url
                if (url.isBlank() || url.isSpecialUrl()) {
                    Toast.makeText(this, "Modo lectura no disponible para esta página", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val readerJs = """
                    (function() {
                        if (window.__lw_reader_active) {
                            if (window.__lw_orig_html) {
                                document.documentElement.innerHTML = window.__lw_orig_html;
                                window.__lw_reader_active = false;
                            } else {
                                location.reload();
                            }
                            return;
                        }
                        window.__lw_orig_html = document.documentElement.innerHTML;
                        window.__lw_reader_active = true;
                        var title = document.title || '';
                        var article = document.querySelector('article') || document.querySelector('main') || document.querySelector('.post-content') || document.querySelector('.article-content') || document.querySelector('#content');
                        var content = article ? article.innerHTML : document.body.innerHTML;
                        var bg = '#121214';
                        var textCol = '#E2E8F0';
                        document.head.innerHTML = '<meta name="viewport" content="width=device-width, initial-scale=1.0"><title>' + title + '</title><style>body { background-color: ' + bg + ' !important; color: ' + textCol + ' !important; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif !important; font-size: 19px !important; line-height: 1.75 !important; padding: 24px 20px 80px 20px !important; max-width: 720px !important; margin: 0 auto !important; } h1, h2, h3 { color: #FFFFFF !important; line-height: 1.3 !important; } a { color: #5C87F7 !important; } img { max-width: 100% !important; height: auto !important; border-radius: 8px !important; margin: 16px 0 !important; } pre, code { background: #1E1F24 !important; color: #79C0FF !important; padding: 8px 12px !important; border-radius: 6px !important; overflow-x: auto !important; } nav, footer, header, aside, .sidebar, .ads, .ad, .social-share, .comments { display: none !important; }</style>';
                        document.body.innerHTML = '<h1>' + title + '</h1><hr style="border: none; border-top: 1px solid #2B2E38; margin: 20px 0 24px 0;">' + content;
                    })();
                """.trimIndent()
                tab.webView?.evaluateJavascript(readerJs, null)
                Toast.makeText(this, "Modo lectura activado", Toast.LENGTH_SHORT).show()
            }
        }

        // ================= BOTTOM BAR ACTIONS =================
        view.findViewById<View>(R.id.btn_via_exit)?.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        view.findViewById<View>(R.id.btn_via_close)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showInstallAppDialog(url: String, pageTitle: String, iconBmp: Bitmap) {
        val defaultTitle = if (pageTitle.isNotBlank()) pageTitle else (Uri.parse(url).host ?: "App")
        val input = EditText(this).apply {
            setText(defaultTitle)
            setSelection(text.length)
            isSingleLine = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Instalar app")
            .setMessage("¿Deseas instalar esta aplicación en tu pantalla de inicio?")
            .setView(container)
            .setPositiveButton("Instalar") { _, _ ->
                val chosenTitle = input.text.toString().trim().ifBlank { defaultTitle }
                installWebAppShortcut(url, chosenTitle, iconBmp)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installWebAppShortcut(url: String, title: String, icon: Bitmap) {
        val webappIntent = Intent(this, WebappActivity::class.java).apply {
            action = "com.lw.browser.webapp.ACTION_OPEN_WEBAPP"
            data = Uri.parse(url)
            putExtra(WebappActivity.EXTRA_URL, url)
            putExtra(WebappActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = getSystemService(ShortcutManager::class.java)
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val pinShortcutInfo = ShortcutInfo.Builder(this, "webapp-" + url.hashCode())
                    .setIntent(webappIntent)
                    .setIcon(Icon.createWithBitmap(icon))
                    .setShortLabel(title)
                    .build()
                shortcutManager.requestPinShortcut(pinShortcutInfo, null)
                Toast.makeText(this, "Aplicación instalada en la pantalla de inicio", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Fallback for Android < O
        val addIntent = Intent().apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, webappIntent)
            putExtra(Intent.EXTRA_SHORTCUT_NAME, title)
            putExtra(Intent.EXTRA_SHORTCUT_ICON, icon)
            action = "com.android.launcher.action.INSTALL_SHORTCUT"
        }
        sendBroadcast(addIntent)
        Toast.makeText(this, "Aplicación instalada en la pantalla de inicio", Toast.LENGTH_SHORT).show()
    }

    fun updateTopBarVisibilityForUrl(url: String?) {
        val isHome = url.isHomeUrl()
        val targetVis = if (isHome) View.GONE else View.VISIBLE
        findViewById<View>(R.id.toolbar_layout)?.let { tbl ->
            if (tbl.visibility != targetVis) {
                tbl.visibility = targetVis
            }
        }
        findViewById<View>(R.id.toolbar)?.let { tb ->
            if (tb.visibility != targetVis) {
                tb.visibility = targetVis
            }
        }
        searchBackground?.visibility = targetVis
        if (isHome) {
            toolbar_layout.translationY = 0f
            setWebViewTranslation(0f)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val decorView = window.decorView
            if (isHome) {
                decorView.systemUiVisibility = decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else if (!isDarkTheme) {
                decorView.systemUiVisibility = decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    companion object {

        private const val TAG = "BrowserActivity"

        const val INTENT_PANIC_TRIGGER = "info.guardianproject.panic.action.TRIGGER"

        private const val FILE_CHOOSER_REQUEST_CODE = 1111

        // Constant
        private val MATCH_PARENT = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        private val COVER_SCREEN_PARAMS = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    }

}