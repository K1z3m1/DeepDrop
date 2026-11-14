package com.prirai.android.lr.components.toolbar

import android.content.Context
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.prirai.android.lr.R
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.ssl.showSslDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.mapNotNull
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.browser.toolbar.display.DisplayToolbar
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.feature.toolbar.ToolbarBehaviorController
import mozilla.components.feature.toolbar.ToolbarFeature
import mozilla.components.feature.toolbar.ToolbarPresenter
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.ktx.android.content.getColorFromAttr
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged
import com.prirai.android.lr.theme.ColorConstants
import kotlinx.coroutines.Dispatchers

@ExperimentalCoroutinesApi
abstract class ToolbarIntegration(
    context: Context,
    toolbar: BrowserToolbar,
    toolbarMenu: ToolbarMenu,
    sessionId: String?,
    isPrivate: Boolean,
    renderStyle: ToolbarFeature.RenderStyle
) : LifecycleAwareFeature {
    val store = context.components.store
    private val toolbarPresenter: ToolbarPresenter = ToolbarPresenter(
        toolbar,
        store,
        sessionId,
        false,
        ToolbarFeature.UrlRenderConfiguration(
            PublicSuffixList(context),
            ContextCompat.getColor(context, R.color.primary_icon),
            renderStyle = renderStyle
        )
    )

    private val toolbarController = ToolbarBehaviorController(toolbar, store, sessionId)

    private val menuPresenter =
        MenuPresenter(toolbar, context.components.store, sessionId)

    private var securityBackgroundScope: CoroutineScope? = null
    protected val toolbar: BrowserToolbar = toolbar
    protected val context: Context = context

    init {
        toolbar.display.menuBuilder = null
        toolbar.private = isPrivate
        
        toolbar.display.setOnSiteInfoClickedListener {
            context.showSslDialog()
        }
    }

    override fun start() {
        menuPresenter.start()
        toolbarPresenter.start()
        toolbarController.start()
        startSecurityBackgroundObserver()
    }

    override fun stop() {
        menuPresenter.stop()
        toolbarPresenter.stop()
        toolbarController.stop()
        securityBackgroundScope?.cancel()
    }

    private fun startSecurityBackgroundObserver() {
        securityBackgroundScope = store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow.mapNotNull { state -> state.selectedTab }
                .ifAnyChanged { tab ->
                    arrayOf(tab.content.securityInfo, tab.content.url)
                }
                .collect { tab ->
                    updateSecurityBackground(tab.content.securityInfo?.isSecure ?: false)
                    updateSecurityIconColor(tab.content.url, tab.content.securityInfo?.isSecure ?: false)
                }
        }
    }

    private fun updateSecurityBackground(isSecure: Boolean) {
        val background = AppCompatResources.getDrawable(context, R.drawable.toolbar_background)
        toolbar.display.setUrlBackground(background)

        val textColor = context.getColorFromAttr(android.R.attr.textColorPrimary)

        toolbar.display.colors = toolbar.display.colors.copy(
            text = textColor,
            hint = 0x1E15141a
        )
    }

    private fun updateSecurityIconColor(url: String, isSecure: Boolean) {
        val isInternalPage = isInternalUrl(url)
        
        val (secureColor, insecureColor) = when {
            isInternalPage -> {
                val blueColor = context.getColor(R.color.security_indicator_secure)
                Pair(blueColor, blueColor)
            }
            else -> {
                Pair(
                    ColorConstants.getSecureColor(toolbar.context),
                    ColorConstants.getInsecureColor(toolbar.context)
                )
            }
        }
        
        toolbar.display.colors = toolbar.display.colors.copy(
            siteInfoIconSecure = secureColor,
            siteInfoIconInsecure = insecureColor
        )
    }

    private fun isInternalUrl(url: String): Boolean {
        return url.startsWith("about:") || 
               url.startsWith("moz-extension://") ||
               url.startsWith("resource://") ||
               url.startsWith("chrome://")
    }
}

@ExperimentalCoroutinesApi
class DefaultToolbarIntegration(
    context: Context,
    toolbar: BrowserToolbar,
    toolbarMenu: ToolbarMenu,
    historyStorage: HistoryStorage,
    lifecycleOwner: LifecycleOwner,
    sessionId: String? = null,
    isPrivate: Boolean,
    interactor: BrowserToolbarViewInteractor,
    engine: Engine
) : ToolbarIntegration(
    context = context,
    toolbar = toolbar,
    toolbarMenu = toolbarMenu,
    sessionId = sessionId,
    isPrivate = isPrivate,
    renderStyle = ToolbarFeature.RenderStyle.UncoloredUrl
) {
    init {
        toolbar.display.menuBuilder = null
        toolbar.private = isPrivate

        toolbar.display.indicators =
            listOf(
                DisplayToolbar.Indicators.SECURITY,
                DisplayToolbar.Indicators.HIGHLIGHT
            )

        toolbar.display.colors = toolbar.display.colors.copy(
            siteInfoIconInsecure = ColorConstants.getInsecureColor(context),
            siteInfoIconSecure = ColorConstants.getSecureColor(context),
            text = context.getColorFromAttr(android.R.attr.textColorPrimary),
            menu = context.getColorFromAttr(android.R.attr.textColorPrimary),
            separator = ColorConstants.getColorFromAttr(
                context,
                com.google.android.material.R.attr.colorOutlineVariant,
                0x1E15141a
            ),
            trackingProtection = if (isPrivate) {
                ColorConstants.PrivateMode.PURPLE
            } else {
                ColorConstants.getColorFromAttr(
                    context,
                    android.R.attr.colorPrimary,
                    0xFF20123a.toInt()
                )
            },
            emptyIcon = if (isPrivate) {
                ColorConstants.PrivateMode.PURPLE
            } else {
                ColorConstants.getColorFromAttr(
                    context,
                    android.R.attr.colorPrimary,
                    0xFF20123a.toInt()
                )
            },
            hint = ColorConstants.getColorFromAttr(
                context,
                com.google.android.material.R.attr.colorOutlineVariant,
                0x1E15141a
            )
        )

        toolbar.edit.colors = toolbar.edit.colors.copy(
            text = context.getColorFromAttr(android.R.attr.textColorPrimary),
            clear = context.getColorFromAttr(android.R.attr.textColorPrimary),
            icon = context.getColorFromAttr(android.R.attr.textColorPrimary)
        )

        if (isPrivate) {
            toolbar.display.setUrlBackground(
                AppCompatResources.getDrawable(
                    context,
                    R.drawable.toolbar_background_private
                )
            )
        } else {
            toolbar.display.setUrlBackground(
                AppCompatResources.getDrawable(
                    context,
                    R.drawable.toolbar_background
                )
            )
        }
    }
}
