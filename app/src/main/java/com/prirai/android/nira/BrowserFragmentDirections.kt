package com.prirai.android.nira

import androidx.navigation.NavDirections

object BrowserFragmentDirections {
    fun actionGlobalBrowser(activeSessionId: String?): NavDirections =
        NavGraphDirections.actionGlobalBrowser(activeSessionId)

    fun actionGlobalSearchDialog(sessionId: String?, pastedText: String? = null): NavDirections =
        NavGraphDirections.actionGlobalSearchDialog(sessionId, pastedText)
}
