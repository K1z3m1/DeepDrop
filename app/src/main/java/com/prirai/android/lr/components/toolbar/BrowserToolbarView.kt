package com.prirai.android.lr.components.toolbar

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.annotation.VisibleForTesting
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.prirai.android.lr.R
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.utils.ToolbarPopupWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.CustomTabSessionState
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.browser.toolbar.display.DisplayToolbar
import mozilla.components.support.ktx.util.URLStringUtils.toDisplayUrl
import mozilla.components.ui.widgets.behavior.EngineViewScrollingGesturesBehavior
import java.lang.ref.WeakReference
import androidx.core.net.toUri
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.pm.ShortcutManagerCompat
import mozilla.components.concept.engine.EngineView

interface BrowserToolbarViewInteractor {
    fun onBrowserToolbarPaste(text: String)
    fun onBrowserToolbarPasteAndGo(text: String)
    fun onBrowserToolbarClicked()
    fun onBrowserToolbarMenuItemTapped(item: ToolbarMenu.Item)
    fun onTabCounterClicked()
    fun onScrolled(offset: Int)
}

@ExperimentalCoroutinesApi
class BrowserToolbarView(
    private val container: ViewGroup,
    private val toolbarPosition: ToolbarPosition,
    private val interactor: BrowserToolbarViewInteractor,
    private val customTabSession: CustomTabSessionState?,
    private val lifecycleOwner: LifecycleOwner,
    private val engineView: EngineView? = null
) {
    private val settings = UserPreferences(container.context)

    @LayoutRes
    private val toolbarLayout = when (settings.toolbarPosition) {
        ToolbarPosition.BOTTOM.ordinal -> R.layout.component_bottom_browser_toolbar
        else -> R.layout.component_browser_top_toolbar
    }

    private val layout = if (toolbarLayout == R.layout.component_bottom_browser_toolbar && container is CoordinatorLayout) {
        val inflatedView = LayoutInflater.from(container.context).inflate(toolbarLayout, null, false)
        val toolbarContainer = inflatedView.findViewById<View>(R.id.toolbarContainer) ?: inflatedView
        
        val layoutParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        
        container.addView(toolbarContainer, layoutParams)
        
        inflatedView
    } else {
        LayoutInflater.from(container.context).inflate(toolbarLayout, container, true)
    }

    @VisibleForTesting
    internal var view: BrowserToolbar = layout
        .findViewById(R.id.toolbar)

    private val toolbarContainer: View? = if (toolbarLayout == R.layout.component_bottom_browser_toolbar) {
        val container = layout.findViewById<View>(R.id.toolbarContainer)
        container ?: layout
    } else null

    val toolbarIntegration: ToolbarIntegration

    init {
        view.display.setOnUrlLongClickListener {
            ToolbarPopupWindow.show(
                WeakReference(view),
                customTabSession?.id,
                interactor::onBrowserToolbarPasteAndGo,
                interactor::onBrowserToolbarPaste
            )
            true
        }

        with(container.context) {
            val isPinningSupported = ShortcutManagerCompat.isRequestPinShortcutSupported(this)

            view.apply {
                setToolbarBehavior()

                elevation = resources.getDimension(R.dimen.browser_fragment_toolbar_elevation)

                display.setUrlBackground(
                    AppCompatResources.getDrawable(
                        container.context,
                        R.drawable.toolbar_background
                    )
                )

                val pillInsetPx = (12f * resources.displayMetrics.density).toInt()
                display.setUrlBackgroundMargins(
                    DisplayToolbar.DisplayMargins(
                        goneStartMargin = pillInsetPx,
                        goneEndMargin = pillInsetPx,
                    )
                )

                display.onUrlClicked = {
                    interactor.onBrowserToolbarClicked()
                    false
                }

                display.progressGravity = when (toolbarPosition) {
                    ToolbarPosition.BOTTOM -> DisplayToolbar.Gravity.TOP
                    ToolbarPosition.TOP -> DisplayToolbar.Gravity.BOTTOM
                }

                val primaryTextColor = ContextCompat.getColor(
                    container.context,
                    R.color.primary_icon
                )
                val secondaryTextColor = ContextCompat.getColor(
                    container.context,
                    R.color.secondary_icon
                )
                val separatorColor = ContextCompat.getColor(
                    container.context,
                    R.color.primary_icon
                )

                display.urlFormatter =
                    if (UserPreferences(context).showUrlProtocol) {
                            url -> url
                    } else {
                            url -> smartUrlDisplay(url)
                    }

                display.colors = display.colors.copy(
                    text = primaryTextColor,
                    siteInfoIconSecure = primaryTextColor,
                    siteInfoIconInsecure = primaryTextColor,
                    menu = primaryTextColor,
                    hint = secondaryTextColor,
                    separator = separatorColor,
                    trackingProtection = primaryTextColor
                )

                display.hint = context.getString(R.string.search)
            }

            val menuToolbar: ToolbarMenu
            BrowserMenu(
                context = this,
                store = components.store,
                onItemTapped = {
                    it.performHapticIfNeeded(view)
                    interactor.onBrowserToolbarMenuItemTapped(it)
                },
                lifecycleOwner = lifecycleOwner,
                isPinningSupported = isPinningSupported,
                shouldReverseItems = settings.toolbarPosition == ToolbarPosition.TOP.ordinal
            ).also { menuToolbar = it }

            view.display.setMenuDismissAction {
                view.invalidateActions()
            }

            toolbarIntegration = DefaultToolbarIntegration(
                    this,
                    view,
                    menuToolbar,
                    components.historyStorage,
                    lifecycleOwner,
                    sessionId = null,
                    isPrivate = components.store.state.selectedTab?.content?.private ?: false,
                    interactor = interactor,
                    engine = components.engine
                )
        }
    }

    fun expand() {
        val targetView = if (toolbarLayout == R.layout.component_bottom_browser_toolbar) {
            toolbarContainer ?: layout
        } else {
            view
        }
        
        (targetView.layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            (behavior as? EngineViewScrollingGesturesBehavior)?.forceExpand()
        }
    }

    fun collapse() {
        val targetView = if (toolbarLayout == R.layout.component_bottom_browser_toolbar) {
            toolbarContainer ?: layout
        } else {
            view
        }
        
        (targetView.layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            (behavior as? EngineViewScrollingGesturesBehavior)?.forceCollapse()
        }
    }

    fun setToolbarBehavior(shouldDisableScroll: Boolean = false) {
        expandToolbarAndMakeItFixed()
    }

    @VisibleForTesting
    internal fun expandToolbarAndMakeItFixed() {
        expand()
        val targetView = if (toolbarLayout == R.layout.component_bottom_browser_toolbar) {
            toolbarContainer ?: layout
        } else {
            view
        }

        (targetView.layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            behavior = null
        }
    }

    private fun ToolbarMenu.Item.performHapticIfNeeded(view: View) {
        if (this is ToolbarMenu.Item.Reload && this.bypassCache ||
            this is ToolbarMenu.Item.Back && this.viewHistory ||
            this is ToolbarMenu.Item.Forward && this.viewHistory
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun smartUrlDisplay(url: CharSequence): CharSequence {
        return try {
            val uri = url.toString().toUri()
            val host = uri.host ?: return toDisplayUrl(url)
            val path = uri.path ?: return host
            
            val cleanHost = host.removePrefix("www.")
            
            if (path == "/" || path.isEmpty()) {
                return cleanHost
            }
            
            val pathSegments = path.split("/").filter { it.isNotEmpty() }
            
            when {
                pathSegments.size >= 2 && pathSegments[0] == "wiki" -> {
                    "$cleanHost/w/${pathSegments[1]}"
                }
                pathSegments.size >= 2 -> {
                    val abbreviatedSegments = mutableListOf<String>()
                    
                    abbreviatedSegments.add(pathSegments[0].take(1))
                    
                    for (i in 1 until pathSegments.size - 1) {
                        abbreviatedSegments.add(pathSegments[i].take(1))
                    }
                    
                    abbreviatedSegments.add(pathSegments.last())
                    
                    "$cleanHost/${abbreviatedSegments.joinToString("/")}"
                }
                else -> {
                    "$cleanHost$path"
                }
            }
        } catch (_: Exception) {
            toDisplayUrl(url)
        }
    }
}
