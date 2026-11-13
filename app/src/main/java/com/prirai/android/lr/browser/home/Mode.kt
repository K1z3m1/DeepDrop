package com.prirai.android.lr.browser.home

import com.prirai.android.lr.browser.BrowsingMode

sealed class Mode {
    data object Normal : Mode()
    data object Private : Mode()

    companion object {
        fun fromBrowsingMode(browsingMode: BrowsingMode) = when (browsingMode) {
            BrowsingMode.Normal -> Normal
            BrowsingMode.Private -> Private
        }
    }
}
