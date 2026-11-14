package com.prirai.android.lr.middleware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.AppLifecycleAction
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class EnhancedStateCaptureMiddleware(
    private val scope: CoroutineScope,
    private val maxTabsToCapture: Int = 3
) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction
    ) {
        when (action) {
            is AppLifecycleAction.PauseAction -> {
                scope.launch {
                    store.state.selectedTab?.engineState?.engineSession?.checkForFormData(
                        adjustPriority = false
                    )
                }
            }
            else -> {
            }
        }
        
        next(action)
    }
}
