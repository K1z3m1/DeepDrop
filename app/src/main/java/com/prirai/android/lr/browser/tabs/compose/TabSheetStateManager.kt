package com.prirai.android.lr.browser.tabs.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TabSheetStateManager {
    private val _dismissedTimestamp = MutableStateFlow(0L)
    val dismissedTimestamp: StateFlow<Long> = _dismissedTimestamp
    
    fun notifyTabSheetDismissed() {
        _dismissedTimestamp.value = System.currentTimeMillis()
    }
}
