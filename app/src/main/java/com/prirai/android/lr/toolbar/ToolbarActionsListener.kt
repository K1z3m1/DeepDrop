package com.prirai.android.lr.toolbar

/**
 * Callbacks for the browser chrome actions (address-bar tab counter, menu button
 * and the actions reachable from the ••• menu). Replaces the old
 * ContextualBottomToolbar listener now that the contextual toolbar is gone.
 */
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
