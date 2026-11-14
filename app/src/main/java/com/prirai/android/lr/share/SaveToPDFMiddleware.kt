package com.prirai.android.lr.share

import android.content.Context
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class SaveToPDFMiddleware(
    private val context: Context,
) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        when (action) {
            is EngineAction.SaveToPdfAction -> {
                next(action)
            }

            is EngineAction.SaveToPdfCompleteAction -> { }

            is EngineAction.SaveToPdfExceptionAction -> { }

            is EngineAction.PrintContentAction -> {
                next(action)
            }
            is EngineAction.PrintContentCompletedAction -> { }
            is EngineAction.PrintContentExceptionAction -> { }
            else -> {
                next(action)
            }
        }
    }
}