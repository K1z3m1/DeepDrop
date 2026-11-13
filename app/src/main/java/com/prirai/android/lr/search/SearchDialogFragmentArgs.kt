package com.prirai.android.lr.search

import android.os.Bundle

data class SearchDialogFragmentArgs(
    val sessionId: String?,
    val pastedText: String? = null
) {
    companion object {
        @JvmStatic
        fun fromBundle(bundle: Bundle?): SearchDialogFragmentArgs = SearchDialogFragmentArgs(
            sessionId = bundle?.getString("session_id"),
            pastedText = bundle?.getString("pastedText")
        )
    }
}
