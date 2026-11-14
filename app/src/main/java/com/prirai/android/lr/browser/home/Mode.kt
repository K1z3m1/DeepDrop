package com.prirai.android.lr.browser.home

sealed class Mode {
    data object Normal : Mode()
    data object Private : Mode()
}
