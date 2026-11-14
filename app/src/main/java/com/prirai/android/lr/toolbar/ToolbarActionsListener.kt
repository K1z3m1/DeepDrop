package com.prirai.android.lr.toolbar

interface ToolbarActionsListener {
    fun onBackClicked()
    fun onForwardClicked()
    fun onShareClicked()
    fun onSearchClicked()
    fun onNewTabClicked()
    fun onTabCountClicked()
    fun onMenuClicked()
    fun onBookmarksClicked()
    fun onExtensionsClicked()
    fun onBackLongClicked() {}
    fun onForwardLongClicked() {}
}
