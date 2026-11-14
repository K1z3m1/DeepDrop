package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

fun Modifier.groupHeaderFeedback(
    groupId: String,
    coordinator: DragCoordinator,
    hoverState: TabSheetHoverState,
    draggedScale: Float = 0.95f
): Modifier = composed {
    if (hoverState.shouldShowGroupHeaderHover(groupId)) {
        this.dragVisualFeedback(groupId, coordinator, draggedScale = draggedScale)
    } else {
        this
    }
}

fun Modifier.groupedTabFeedback(
    tabId: String,
    groupId: String,
    coordinator: DragCoordinator,
    hoverState: TabSheetHoverState,
    hoveredGroupId: String?,
    draggedScale: Float = 0.95f
): Modifier = composed {
    val shouldShowHover = hoverState.shouldShowGroupedTabHover(tabId, groupId, hoveredGroupId)
    this.dragVisualFeedback(
        itemId = tabId,
        coordinator = coordinator,
        isDropTarget = shouldShowHover,
        draggedScale = draggedScale
    )
}

fun Modifier.ungroupedTabFeedback(
    tabId: String,
    coordinator: DragCoordinator,
    draggedScale: Float = 0.95f
): Modifier = composed {
    this.dragVisualFeedback(tabId, coordinator, draggedScale = draggedScale)
}

fun Modifier.groupRowTabFeedback(
    tabId: String,
    coordinator: DragCoordinator,
    hoverState: TabSheetHoverState,
    draggedScale: Float = 0.95f
): Modifier = composed {
    if (hoverState.shouldShowGroupRowTabHover(tabId)) {
        this.dragVisualFeedback(tabId, coordinator, draggedScale = draggedScale)
    } else {
        this
    }
}
