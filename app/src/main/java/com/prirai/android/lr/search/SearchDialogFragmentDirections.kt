package com.prirai.android.lr.search

import androidx.navigation.NavDirections
import com.prirai.android.lr.NavGraphDirections

object SearchDialogFragmentDirections {
    fun actionGlobalBrowser(activeSessionId: String?): NavDirections =
        NavGraphDirections.actionGlobalBrowser(activeSessionId)

    fun actionGlobalSearchDialog(sessionId: String?, pastedText: String? = null): NavDirections =
        NavGraphDirections.actionGlobalSearchDialog(sessionId, pastedText)
}
