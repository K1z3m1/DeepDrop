package com.prirai.android.lr

import android.os.Bundle
import androidx.navigation.NavDirections

/** Hand-written replacement for the Safe Args generated class (global actions). */
object NavGraphDirections {
    fun actionGlobalBrowser(activeSessionId: String?): NavDirections = object : NavDirections {
        override val actionId: Int = R.id.action_global_browser
        override val arguments: Bundle = Bundle().apply { putString("activeSessionId", activeSessionId) }
    }

    fun actionGlobalSearchDialog(sessionId: String?, pastedText: String? = null): NavDirections =
        object : NavDirections {
            override val actionId: Int = R.id.action_global_search_dialog
            override val arguments: Bundle = Bundle().apply {
                putString("session_id", sessionId)
                putString("pastedText", pastedText)
            }
        }
}
