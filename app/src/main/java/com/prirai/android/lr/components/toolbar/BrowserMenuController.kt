package com.prirai.android.lr.components.toolbar

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.prirai.android.lr.BrowserActivity
import com.prirai.android.lr.BrowserAnimator
import com.prirai.android.lr.R
import com.prirai.android.lr.browser.history.TabHistoryBottomSheet
import com.prirai.android.lr.browser.history.TabHistoryMode
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.history.HistoryActivity
import com.prirai.android.lr.settings.activity.SettingsActivity
import com.prirai.android.lr.ssl.showSslDialog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TranslationsAction
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import android.widget.Toast
import com.prirai.android.lr.browser.BrowsingMode
import com.prirai.android.lr.browser.bookmark.ui.BookmarksBottomSheetFragment
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.utils.HomescreenShortcut
import mozilla.components.browser.icons.IconRequest

interface BrowserToolbarMenuController {
    fun handleToolbarItemInteraction(item: ToolbarMenu.Item)
}

class DefaultBrowserToolbarMenuController(
    private val store: BrowserStore,
    private val activity: BrowserActivity,
    private val navController: NavController,
    private val browserAnimator: BrowserAnimator,
    private val customTabSessionId: String?
) : BrowserToolbarMenuController {
    private val currentSession
        get() = store.state.findCustomTabOrSelectedTab(customTabSessionId)

    override fun handleToolbarItemInteraction(item: ToolbarMenu.Item) {
        val sessionUseCases = activity.components.sessionUseCases

        when (item) {
            is ToolbarMenu.Item.Back -> {
                if (item.viewHistory) {
                    TabHistoryBottomSheet.showIfAvailable(
                        activity.supportFragmentManager,
                        store,
                        TabHistoryMode.Back,
                        customTabSessionId
                    )
                } else {
                    currentSession?.let {
                        sessionUseCases.goBack.invoke(it.id)
                    }
                }
            }

            is ToolbarMenu.Item.Forward -> {
                if (item.viewHistory) {
                    TabHistoryBottomSheet.showIfAvailable(
                        activity.supportFragmentManager,
                        store,
                        TabHistoryMode.Forward,
                        customTabSessionId
                    )
                } else {
                    currentSession?.let {
                        sessionUseCases.goForward.invoke(it.id)
                    }
                }
            }

            is ToolbarMenu.Item.Reload -> {
                val flags = if (item.bypassCache) {
                    LoadUrlFlags.select(LoadUrlFlags.BYPASS_CACHE)
                } else {
                    LoadUrlFlags.none()
                }

                currentSession?.let {
                    sessionUseCases.reload.invoke(it.id, flags = flags)
                }
            }

            is ToolbarMenu.Item.Stop -> currentSession?.let {
                sessionUseCases.stopLoading.invoke(
                    it.id
                )
            }

            is ToolbarMenu.Item.Settings -> {
                val settings = Intent(activity, SettingsActivity::class.java)
                settings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                activity.startActivity(settings)
            }

            is ToolbarMenu.Item.RequestDesktop -> {
                currentSession?.let {
                    sessionUseCases.requestDesktopSite.invoke(
                        item.isChecked,
                        it.id
                    )
                }
            }

            is ToolbarMenu.Item.Translate -> {
                val tab = currentSession ?: return
                val translations = tab.translationsState
                if (translations.isTranslated) {
                    store.dispatch(TranslationsAction.TranslateRestoreAction(tab.id))
                } else {
                    val detected = translations.translationEngineState?.detectedLanguages
                    val from = detected?.documentLangTag
                    val to = detected?.userPreferredLangTag
                    if (from.isNullOrBlank() || to.isNullOrBlank()) return
                    store.dispatch(
                        TranslationsAction.TranslateAction(
                            tabId = tab.id,
                            fromLanguage = from,
                            toLanguage = to,
                            options = null,
                        )
                    )
                }
            }

            is ToolbarMenu.Item.Print -> {
                store.state.selectedTab?.let {
                    store.dispatch(EngineAction.PrintContentAction(it.id))
                }
            }

            is ToolbarMenu.Item.PDF -> {
                activity.components.sessionUseCases.saveToPdf.invoke()
            }

            is ToolbarMenu.Item.AddToHomeScreen -> {
                currentSession?.let { session ->
                    MainScope().launch {
                        val icon = activity.components.icons.loadIcon(
                            IconRequest(
                                url = session.content.url
                            )
                        ).await()?.bitmap
                        
                        HomescreenShortcut.add(
                            activity,
                            session,
                            icon
                        )
                        
                        Toast.makeText(
                            activity,
                            "Shortcut added",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            is ToolbarMenu.Item.Share -> {
                MainScope().launch {
                    activity.components.store.state.selectedTab?.content?.let {
                        val shareIntent = Intent(Intent.ACTION_SEND)
                        shareIntent.type = "text/plain"
                        if (it.title != "") {
                            shareIntent.putExtra(Intent.EXTRA_SUBJECT, it.title)
                        }
                        shareIntent.putExtra(Intent.EXTRA_TEXT, it.url)
                        ContextCompat.startActivity(
                            activity,
                            Intent.createChooser(
                                shareIntent,
                                activity.resources.getString(R.string.mozac_selection_context_menu_share)
                            ).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            null
                        )
                    }
                }
            }

            is ToolbarMenu.Item.OpenInApp -> {
                val appLinksUseCases = activity.components.appLinksUseCases
                val getRedirect = appLinksUseCases.appLinkRedirect

                currentSession?.let {
                    val redirect = getRedirect.invoke(it.content.url)
                    redirect.appIntent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    appLinksUseCases.openAppLink.invoke(redirect.appIntent)
                }
            }

            is ToolbarMenu.Item.Bookmarks -> {
                val bookmarksBottomSheet =
                    BookmarksBottomSheetFragment.newInstance()
                bookmarksBottomSheet.show(activity.supportFragmentManager, "BookmarksBottomSheet")
            }

            is ToolbarMenu.Item.History -> browserAnimator.captureEngineViewAndDrawStatically {
                val settings = Intent(activity, HistoryActivity::class.java)
                settings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                activity.startActivity(settings)
            }

            is ToolbarMenu.Item.NewTab -> {
                val profileManager = ProfileManager.getInstance(activity)
                val currentProfile = profileManager.getActiveProfile()
                val contextId = BrowserProfile
                    .contextIdFor(currentProfile, isPrivate = false)

                activity.components.tabsUseCases.addTab.invoke(
                    "about:homepage",
                    selectTab = true,
                    contextId = contextId
                )
            }

            is ToolbarMenu.Item.NewPrivateTab -> {
                activity.browsingModeManager.mode = BrowsingMode.Private
                
                activity.components.tabsUseCases.addTab.invoke(
                    "about:homepage",
                    selectTab = true,
                    private = true,
                    contextId = "private"
                )
            }

            is ToolbarMenu.Item.Security -> {
                activity.showSslDialog()
            }
        }
    }
}
