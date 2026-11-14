package com.prirai.android.lr.components.toolbar.unified

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.prirai.android.lr.components.toolbar.BrowserToolbarView
import com.prirai.android.lr.components.toolbar.BrowserToolbarViewInteractor
import com.prirai.android.lr.components.toolbar.ToolbarPosition
import com.prirai.android.lr.components.toolbar.modern.ModernToolbarSystem
import com.prirai.android.lr.components.toolbar.modern.ComposeTabBarWithProfileSwitcher
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.toolbar.ToolbarActionsListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.CustomTabSessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.ktx.android.content.res.resolveAttribute
import com.prirai.android.lr.components.toolbar.modern.ModernScrollBehavior
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.ElevationOverlayProvider
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.components.menu.Material3BrowserMenu
import com.prirai.android.lr.integration.ReloadStopButtonIntegration
import com.prirai.android.lr.theme.ColorConstants
import com.prirai.android.lr.theme.ThemeManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mozilla.components.concept.toolbar.Toolbar

class UnifiedToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), ScrollableToolbar {
    private val prefs = UserPreferences(context)

    private val toolbarSystem: ModernToolbarSystem = ModernToolbarSystem(context, attrs, defStyleAttr)

    private var tabGroupBar: ComposeTabBarWithProfileSwitcher? = null
    private var browserToolbar: BrowserToolbar? = null
    private var browserToolbarView: BrowserToolbarView? = null

    private var tabCountBadgeView: TextView? = null
    private var tabCountBadgeContainer: View? = null
    
    private var engineView: EngineView? = null
    
    private var browserStore: BrowserStore? = null
    
    private var onTabSelectedCallback: ((String) -> Unit)? = null
    
    private var onExpansionStateChanged: ((Boolean) -> Unit)? = null
    
    private var onToolbarOffsetChanged: ((Int, Int) -> Unit)? = null

    private var minimalState: Boolean = false
    private var onMinimalStateChanged: ((Boolean) -> Unit)? = null

    private var minimalAnimator: ValueAnimator? = null

    private var suppressReconcileDuringAnimation = false

    private var reloadStopIntegration: ReloadStopButtonIntegration? = null

    private var contextualToolbarListener: ToolbarActionsListener? = null

    private var showTabGroupBar: Boolean = prefs.showTabGroupBar
    
    private var bottomComponentsContainer: ViewGroup? = null
    private var shouldHideBottomComponents = false

    init {
        clipChildren = false
        clipToPadding = false

        addView(toolbarSystem, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ))

        val toolbarPos = if (prefs.toolbarPosition == ToolbarPosition.BOTTOM.ordinal) {
            ModernToolbarSystem.ToolbarPosition.BOTTOM
        } else {
            ModernToolbarSystem.ToolbarPosition.TOP
        }
        toolbarSystem.setToolbarPosition(toolbarPos)
        shouldHideBottomComponents = (toolbarPos == ModernToolbarSystem.ToolbarPosition.TOP)

        toolbarSystem.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && !suppressReconcileDuringAnimation) {
                reconcileEngineViewHeight()
            }
        }
    }
    
    override fun enableScrolling() {
        toolbarSystem.enableScrolling()
        (layoutParams as? CoordinatorLayout.LayoutParams)?.let {
            (it.behavior as? ModernScrollBehavior)?.enableScrolling()
        }
    }
    
    override fun disableScrolling() {
        toolbarSystem.disableScrolling()
        (layoutParams as? CoordinatorLayout.LayoutParams)?.let {
            (it.behavior as? ModernScrollBehavior)?.disableScrolling()
        }
    }
    
    override fun expand() {
        toolbarSystem.expand()
        if (shouldHideBottomComponents) {
            bottomComponentsContainer?.let { container ->
                container.visibility = VISIBLE
                container.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            }
        }
        updateStatusBarForToolbarState(expanded = true)
        onExpansionStateChanged?.invoke(true)
    }
    
    override fun collapse() {
        toolbarSystem.collapse()
        if (shouldHideBottomComponents) {
            bottomComponentsContainer?.let { container ->
                container.visibility = GONE
                val height = container.height.toFloat()
                container.animate()
                    .translationY(height)
                    .alpha(0f)
                    .setDuration(200)
                    .start()
            }
        }
        updateStatusBarForToolbarState(expanded = false)
        onExpansionStateChanged?.invoke(false)
    }
    
    fun showMenu(menuItems: List<Material3BrowserMenu.MenuItem>) {
        val toolbarView = browserToolbarView?.view
        if (toolbarView != null) {
            Material3BrowserMenu(
                context,
                menuItems
            ).show(toolbarView)
        }
    }

    fun setup(
        parent: ViewGroup,
        lifecycleOwner: LifecycleOwner,
        interactor: BrowserToolbarViewInteractor,
        customTabSession: CustomTabSessionState? = null,
        store: BrowserStore
    ): UnifiedToolbar {
        this.browserStore = store

        val toolbarPos = if (prefs.toolbarPosition == ToolbarPosition.BOTTOM.ordinal) {
            ModernToolbarSystem.ToolbarPosition.BOTTOM
        } else {
            ModernToolbarSystem.ToolbarPosition.TOP
        }

        when (toolbarPos) {
            ModernToolbarSystem.ToolbarPosition.TOP -> {
                addAddressBar(parent, lifecycleOwner, interactor, customTabSession, store)
                addBottomComponentsForTopToolbar(store, lifecycleOwner)
            }
            ModernToolbarSystem.ToolbarPosition.BOTTOM -> {
                addAddressBar(parent, lifecycleOwner, interactor, customTabSession, store)
                addTabGroupBar(store, lifecycleOwner)
            }
        }

        addTabCountBadgeToAddressBar()

        addBrowserActionsToAddressBar()

        setupScrollBehavior(parent)

        return this
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun createTabGroupBar(
        store: BrowserStore,
        lifecycleOwner: LifecycleOwner
    ) {
        if (!showTabGroupBar) {
            return
        }

        tabGroupBar = ComposeTabBarWithProfileSwitcher(context).apply {
            setup(
                onTabSelected = { tabId ->
                    onTabSelectedCallback?.invoke(tabId)
                },
                onTabClosed = { tabId ->
                    context.components.tabsUseCases.removeTab(tabId)
                },
                onProfileSelected = { profile ->
                    val profileManager = ProfileManager.getInstance(context)
                    profileManager.setActiveProfile(profile)
                    profileManager.setPrivateMode(false) // Switching to a profile exits private mode

                    val expectedContextId = "profile_${profile.id}"
                    val state = store.state
                    val filteredTabs = state.tabs.filter { tab ->
                        if (tab.contextId == null) {
                            tab.content.private == false
                        } else {
                            tab.content.private == false && tab.contextId == expectedContextId
                        }
                    }

                    tabGroupBar?.updateTabs(filteredTabs, state.selectedTabId)
                    tabGroupBar?.updateProfileIcon(profile)

                    context.components.sessionUseCases.reload()
                },
                onPrivateModeSelected = {
                    val profileManager = ProfileManager.getInstance(context)
                    val isPrivateMode = profileManager.isPrivateMode()
                    val currentProfile = profileManager.getActiveProfile()

                    val expectedContextId = if (isPrivateMode) {
                        "private"
                    } else {
                        "profile_${currentProfile.id}"
                    }
                    val state = store.state
                    val filteredTabs = state.tabs.filter { tab ->
                        if (tab.contextId == null && !isPrivateMode) {
                            tab.content.private == false
                        } else {
                            tab.content.private == isPrivateMode && tab.contextId == expectedContextId
                        }
                    }

                    tabGroupBar?.updateTabs(filteredTabs, state.selectedTabId)

                    context.components.sessionUseCases.reload()
                }
            )
        }

        lifecycleOwner.lifecycleScope.launch {
            store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                flow.collect { state ->
                    val profileManager = ProfileManager.getInstance(context)
                    val isPrivateMode = profileManager.isPrivateMode()
                    val currentProfile = profileManager.getActiveProfile()
                    
                    val expectedContextId = if (isPrivateMode) {
                        "private"
                    } else {
                        "profile_${currentProfile.id}"
                    }
                    
                    val filteredTabs = state.tabs.filter { tab ->
                        if (tab.contextId == null) {
                            !isPrivateMode && !tab.content.private
                        } else {
                            tab.content.private == isPrivateMode && tab.contextId == expectedContextId
                        }
                    }
                    
                    tabGroupBar?.updateTabs(filteredTabs, state.selectedTabId)
                }
            }
        }
    }
    
    fun forceUpdateTabBar(filteredTabs: List<TabSessionState>, selectedTabId: String?) {
        tabGroupBar?.updateTabs(filteredTabs, selectedTabId)
    }
    
    private fun addTabGroupBar(
        store: BrowserStore,
        lifecycleOwner: LifecycleOwner
    ) {
        createTabGroupBar(store, lifecycleOwner)
        if (tabGroupBar != null) {
            toolbarSystem.addComponent(tabGroupBar!!, ModernToolbarSystem.ComponentType.TAB_GROUP)
        }
    }

    private fun addAddressBar(
        parent: ViewGroup,
        lifecycleOwner: LifecycleOwner,
        interactor: BrowserToolbarViewInteractor,
        customTabSession: CustomTabSessionState?,
        store: BrowserStore
    ) {
        val tempContainer = FrameLayout(context)
        
        val toolbarPos = if (prefs.toolbarPosition == ToolbarPosition.BOTTOM.ordinal) {
            ToolbarPosition.BOTTOM
        } else {
            ToolbarPosition.TOP
        }
        
        browserToolbarView = BrowserToolbarView(
            container = tempContainer,
            toolbarPosition = toolbarPos,
            interactor = interactor,
            customTabSession = customTabSession,
            lifecycleOwner = lifecycleOwner,
            engineView = engineView
        )
        
        browserToolbar = browserToolbarView!!.view
        
        (browserToolbar?.parent as? ViewGroup)?.removeView(browserToolbar)
        
        toolbarSystem.addComponent(browserToolbar!!, ModernToolbarSystem.ComponentType.ADDRESS_BAR)
        
        browserToolbarView!!.toolbarIntegration.start()
        
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                browserToolbarView?.toolbarIntegration?.stop()
            }
        })
        
        setupReloadStopButton(store, lifecycleOwner)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setupReloadStopButton(store: BrowserStore, lifecycleOwner: LifecycleOwner) {
        val toolbar = browserToolbar ?: return
        
        reloadStopIntegration = ReloadStopButtonIntegration(
            context = context,
            store = store,
            toolbar = toolbar,
            onReload = {
                context.components.sessionUseCases.reload()
            },
            onStop = {
                context.components.sessionUseCases.stopLoading()
            }
        )
        
        reloadStopIntegration?.start()
        
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                reloadStopIntegration?.stop()
                reloadStopIntegration = null
            }
        })
    }

    private fun addBottomComponentsForTopToolbar(store: BrowserStore, lifecycleOwner: LifecycleOwner) {
        if (showTabGroupBar) {
            createTabGroupBar(store, lifecycleOwner)
        }
    }

    private fun addBrowserActionsToAddressBar() {
        val toolbar = browserToolbar ?: return
        val density = context.resources.displayMetrics.density

        toolbar.addBrowserAction(object : Toolbar.Action {
            override fun createView(parent: ViewGroup): View {
                return ImageButton(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        (48 * density).toInt(),
                        (48 * density).toInt()
                    )
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, outValue, true
                    )
                    setBackgroundResource(outValue.resourceId)
                    setImageDrawable(
                        ContextCompat.getDrawable(context, com.prirai.android.lr.R.drawable.ic_more_vert)
                    )
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = context.getString(com.prirai.android.lr.R.string.menu)
                    setOnClickListener { contextualToolbarListener?.onMenuClicked() }
                }
            }

            override fun bind(view: View) = Unit
        })
    }

    private fun addTabCountBadgeToAddressBar() {
        val toolbar = browserToolbar ?: return

        val tabCountContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                (48 * context.resources.displayMetrics.density).toInt(),
                (48 * context.resources.displayMetrics.density).toInt()
            )
            val padding = (4 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)

            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true

            val backgroundView = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, com.prirai.android.lr.R.drawable.tab_number_background))
                val size = (28 * context.resources.displayMetrics.density).toInt()
                layoutParams = LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.FIT_XY
                imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.BLACK
                    )
                )
            }
            addView(backgroundView)

            val countText = TextView(context).apply {
                val size = (28 * context.resources.displayMetrics.density).toInt()
                layoutParams = LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER
                }
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.BLACK
                    )
                )
                maxLines = 1
            }
            addView(countText)
            tabCountBadgeView = countText

            setOnClickListener {
                contextualToolbarListener?.onTabCountClicked()
            }
        }
        tabCountBadgeContainer = tabCountContainer

        toolbar.addBrowserAction(object : Toolbar.Action {
            override fun createView(parent: ViewGroup): View {
                (tabCountContainer.parent as? ViewGroup)?.removeView(tabCountContainer)
                return tabCountContainer
            }

            override fun bind(view: View) = Unit
        })

        observeTabCountForBadge()
    }
    
    private fun observeTabCountForBadge() {
        val store = context.components.store
        val lifecycleOwner = context as? LifecycleOwner ?: return
        
        lifecycleOwner.lifecycleScope.launch {
            store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                flow.collect { state ->
                    val profileManager = ProfileManager.getInstance(context)
                    val isPrivateMode = profileManager.isPrivateMode()
                    val currentProfile = profileManager.getActiveProfile()
                    val expectedContextId = if (isPrivateMode) {
                        "private"
                    } else {
                        "profile_${currentProfile.id}"
                    }
                    
                    val filteredTabCount = state.tabs.count { tab ->
                        tab.content.private == isPrivateMode &&
                            ((tab.contextId == expectedContextId) || (tab.contextId == null))
                    }

                    tabCountBadgeView?.text = if (filteredTabCount > 99) "99+" else filteredTabCount.toString()
                    tabCountBadgeContainer?.contentDescription =
                        context.getString(
                            com.prirai.android.lr.R.string.tabs_count_description,
                            filteredTabCount
                        )
                }
            }
        }
    }

    private fun setupScrollBehavior(parent: ViewGroup) {
        if (parent is CoordinatorLayout) {
            val toolbarPos = if (prefs.toolbarPosition == ToolbarPosition.BOTTOM.ordinal) {
                ToolbarPosition.BOTTOM
            } else {
                ToolbarPosition.TOP
            }
            
            val layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = when (toolbarPos) {
                    ToolbarPosition.BOTTOM -> Gravity.BOTTOM
                    ToolbarPosition.TOP -> Gravity.TOP
                }

                behavior = ModernScrollBehavior(context, null)
            }

            this.layoutParams = layoutParams
        }
    }

    fun setContextualToolbarListener(listener: ToolbarActionsListener) {
        contextualToolbarListener = listener
    }
    
    fun setOnTabSelectedListener(listener: (String) -> Unit) {
        onTabSelectedCallback = listener
    }

    fun setOnExpansionStateChangedListener(listener: (Boolean) -> Unit) {
        onExpansionStateChanged = listener
    }
    
    fun setOnToolbarOffsetChangedListener(listener: (Int, Int) -> Unit) {
        onToolbarOffsetChanged = listener
        toolbarSystem.setOnOffsetChangedListener(listener)
    }

    fun getBrowserToolbar(): BrowserToolbar? = browserToolbar

    fun getToolbarView(): View? = browserToolbar

    fun getTabGroupBar(): ComposeTabBarWithProfileSwitcher? = tabGroupBar
    
    fun getBottomComponentsContainer(): ViewGroup? {
        val toolbarPos = if (prefs.toolbarPosition == ToolbarPosition.BOTTOM.ordinal) {
            ToolbarPosition.BOTTOM
        } else {
            ToolbarPosition.TOP
        }
        
        if (toolbarPos != ToolbarPosition.TOP) {
            return null
        }
        
        if (bottomComponentsContainer == null) {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                }
            }
            
            tabGroupBar?.let { container.addView(it) }
            
            bottomComponentsContainer = container
        }
        
        return bottomComponentsContainer
    }

    fun isMinimal(): Boolean = minimalState

    fun enterMinimalState() {
        if (minimalState) return
        minimalState = true
        animateAuxiliaryComponents(show = false)
        onMinimalStateChanged?.invoke(true)
    }

    fun exitMinimalState() {
        if (!minimalState) return
        minimalState = false
        animateAuxiliaryComponents(show = true)
        onMinimalStateChanged?.invoke(false)
    }

    private fun animateAuxiliaryComponents(show: Boolean) {
        val auxiliaries = listOfNotNull(
            tabGroupBar?.takeIf { showTabGroupBar },
        )
        if (auxiliaries.isEmpty()) return

        minimalAnimator?.cancel()

        auxiliaries.forEach { it.visibility = VISIBLE }
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        val auxTotalHeight = auxiliaries.sumOf { it.measuredHeight }
        if (auxTotalHeight <= 0) return

        val addressBarHeight = (toolbarSystem.measuredHeight - auxTotalHeight)
            .coerceAtLeast(0)
        val fullHeight = addressBarHeight + auxTotalHeight

        val startTranslation = auxiliaries.first().translationY
        val targetTranslation = if (show) 0f else auxTotalHeight.toFloat()

        suppressReconcileDuringAnimation = true

        minimalAnimator = ValueAnimator.ofFloat(startTranslation, targetTranslation).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animator ->
                val currentTranslation = animator.animatedValue as Float
                val hiddenPx = currentTranslation.coerceIn(0f, auxTotalHeight.toFloat())
                auxiliaries.forEach { view ->
                    view.translationY = hiddenPx
                    view.alpha = 1f - (hiddenPx / auxTotalHeight.toFloat()).coerceIn(0f, 1f)
                }
                onToolbarOffsetChanged?.invoke(hiddenPx.toInt(), fullHeight)
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    auxiliaries.forEach { view ->
                        view.translationY = 0f
                        if (show) {
                            view.alpha = 1f
                            view.visibility = VISIBLE
                        } else {
                            view.alpha = 1f // reset for next show
                            view.visibility = GONE
                        }
                    }
                    suppressReconcileDuringAnimation = false
                    reconcileEngineViewHeight()
                    minimalAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                }
            })

            start()
        }
    }

    fun setOnMinimalStateChangedListener(listener: (Boolean) -> Unit) {
        onMinimalStateChanged = listener
    }

    fun setEngineView(engine: EngineView) {
        this.engineView = engine
        toolbarSystem.setEngineView(engine)
        post { reconcileEngineViewHeight() }
    }

    fun reconcileEngineViewHeight() {
        val engine = engineView ?: return
        val visible = toolbarSystem.getTotalHeight()
        engine.setDynamicToolbarMaxHeight(0)
        onToolbarOffsetChanged?.invoke(0, visible)
    }
    
    fun applyPrivateMode(isPrivate: Boolean) {
        val bgColor = if (isPrivate) {
            ColorConstants.PrivateMode.PURPLE
        } else {
            if (ThemeManager.isAmoledActive(context)) {
                Color.BLACK
            } else {
                val elevationDp = 3f * resources.displayMetrics.density
                ElevationOverlayProvider(context)
                    .compositeOverlayWithThemeSurfaceColorIfNeeded(elevationDp)
            }
        }

        toolbarSystem.setBackgroundColor(bgColor)
        
        val activity = context as? Activity
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ThemeManager.applySystemBarsTheme(activity, isPrivate)
        }
    }
    
    private fun updateStatusBarForToolbarState(expanded: Boolean) {
        val toolbarPos = if (prefs.toolbarPosition == ToolbarPosition.BOTTOM.ordinal) {
            ToolbarPosition.BOTTOM
        } else {
            ToolbarPosition.TOP
        }
        
        if (toolbarPos != ToolbarPosition.TOP) {
            return
        }
        
        val activity = context as? Activity ?: return
        val window = activity.window
        
        val isDark = ThemeManager.isDarkMode(context)
        
        if (expanded) {
            window.statusBarColor = if (isDark) {
                Color.argb(230, 28, 27, 31) // 90% opaque Material 3 dark surface
            } else {
                Color.argb(230, 255, 251, 254) // 90% opaque Material 3 light surface
            }
        } else {
            window.statusBarColor = if (isDark) {
                Color.argb(255, 28, 27, 31) // 100% opaque Material 3 dark surface
            } else {
                Color.argb(255, 255, 251, 254) // 100% opaque Material 3 light surface
            }
        }
    }

    companion object {
        private const val ANIMATION_DURATION_MS: Long = 200L

        fun create(
            context: Context,
            parent: ViewGroup,
            lifecycleOwner: LifecycleOwner,
            interactor: BrowserToolbarViewInteractor,
            customTabSession: CustomTabSessionState? = null,
            store: BrowserStore
        ): UnifiedToolbar {
            val toolbar = UnifiedToolbar(context)

            if (parent is CoordinatorLayout) {
                val prefs = UserPreferences(context)
                val toolbarPos = if (prefs.toolbarPosition == ToolbarPosition.BOTTOM.ordinal) {
                    ToolbarPosition.BOTTOM
                } else {
                    ToolbarPosition.TOP
                }
                
                val layoutParams = CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.MATCH_PARENT,
                    CoordinatorLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = when (toolbarPos) {
                        ToolbarPosition.BOTTOM -> Gravity.BOTTOM
                        ToolbarPosition.TOP -> Gravity.TOP
                    }
                }
                parent.addView(toolbar, layoutParams)
            } else {
                parent.addView(toolbar, ViewGroup.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                ))
            }

            return toolbar.setup(
                parent,
                lifecycleOwner,
                interactor,
                customTabSession,
                store
            )
        }
    }
}
