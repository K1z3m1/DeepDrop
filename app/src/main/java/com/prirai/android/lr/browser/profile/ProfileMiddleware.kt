package com.prirai.android.lr.browser.profile

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class ProfileMiddleware(
    private val profileManager: ProfileManager
) : Middleware<BrowserState, BrowserAction> {
    private val guestTabIds = mutableSetOf<String>()
    
    fun markAsGuestTab(tabId: String) {
        guestTabIds.add(tabId)
    }
    
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction
    ) {
        when (action) {
            is TabListAction.AddTabAction -> {
                if (guestTabIds.contains(action.tab.id)) {
                    guestTabIds.remove(action.tab.id)
                    next(action)
                    return
                }
                
                if (action.tab.contextId != null) {
                    next(action)
                    return
                }
                
                val isPrivate = action.tab.content.private || profileManager.isPrivateMode()
                val currentProfile = profileManager.getActiveProfile()

                val contextId: String? = when {
                    isPrivate -> "private"
                    currentProfile.isDefault || currentProfile.id == "default" -> null
                    else -> "profile_${currentProfile.id}"
                }

                if (contextId == null && action.tab.contextId == null) {
                    next(action)
                    return
                }

                val updatedTab = action.tab.copy(contextId = contextId)
                next(TabListAction.AddTabAction(updatedTab, action.select))
                return
            }
            else -> next(action)
        }
    }
}
