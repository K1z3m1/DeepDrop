package com.prirai.android.lr.components

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.InitAction
import mozilla.components.browser.state.action.SearchAction
import mozilla.components.browser.state.search.RegionState
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import java.util.Locale

class LocaleRegionMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        next(action)
        if (action is InitAction) {
            val region = Locale.getDefault().country
            if (region.isNotEmpty()) {
                store.dispatch(SearchAction.SetRegionAction(RegionState(region, region)))
            }
        }
    }
}
