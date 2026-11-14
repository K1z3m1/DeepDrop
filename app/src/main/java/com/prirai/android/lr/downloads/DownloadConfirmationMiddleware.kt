package com.prirai.android.lr.downloads

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.feature.downloads.DownloadStorage
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import kotlin.coroutines.CoroutineContext

class DownloadConfirmationMiddleware(
    private val applicationContext: Context,
    coroutineContext: CoroutineContext = Dispatchers.IO,
) : Middleware<BrowserState, BrowserAction> {
    private val scope = CoroutineScope(coroutineContext)
    private val downloadStorage = DownloadStorage(applicationContext)

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is DownloadAction.AddDownloadAction) {
            val download = action.download
            if (!download.private) {
                scope.launch { downloadStorage.add(download) }
            }
            store.dispatch(DownloadAction.UpdateDownloadAction(download))
        } else {
            next(action)
        }
    }
}
