package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.runtime.*
import com.prirai.android.lr.browser.tabs.compose.DragCoordinator
import com.prirai.android.lr.browser.tabs.compose.DraggableItemType
import com.prirai.android.lr.browser.tabs.compose.DropTargetType
import com.prirai.android.lr.browser.tabs.compose.UnifiedItem

class TabSheetHoverState(
    private val coordinator: DragCoordinator,
    private val uniqueItems: List<UnifiedItem>
) {
    @Composable
    fun getHoveredGroupIdForUngroupedDrag(): String? {
        return remember {
            derivedStateOf {
                val draggedItem = coordinator.dragState.value.draggedItem
                val isDraggingUngroupedTab = draggedItem is DraggableItemType.Tab && draggedItem.groupId == null

                if (isDraggingUngroupedTab) {
                    val dropTarget = coordinator.dragState.value.currentDropTarget
                    when {
                        dropTarget != null && dropTarget.type == DropTargetType.GROUP_HEADER -> {
                            dropTarget.metadata["groupId"] as? String
                        }

                        dropTarget != null && dropTarget.type == DropTargetType.TAB -> {
                            val groupContainer = uniqueItems.filterIsInstance<UnifiedItem.GroupContainer>()
                                .find { container -> container.children.any { it.id == dropTarget.id } }
                            groupContainer?.groupId
                        }

                        else -> null
                    }
                } else {
                    null
                }
            }
        }.value
    }

    @Composable
    fun getHoveredGroupIdForUngroupedDragGrid(): String? {
        return remember {
            derivedStateOf {
                val draggedItem = coordinator.dragState.value.draggedItem
                val isDraggingUngroupedTab = draggedItem is DraggableItemType.Tab && draggedItem.groupId == null

                if (isDraggingUngroupedTab) {
                    val dropTarget = coordinator.dragState.value.currentDropTarget
                    when {
                        dropTarget != null && dropTarget.type == DropTargetType.GROUP_HEADER -> {
                            dropTarget.metadata["groupId"] as? String
                        }

                        dropTarget != null && dropTarget.type == DropTargetType.TAB -> {
                            val groupContainer = uniqueItems.filterIsInstance<UnifiedItem.GroupContainer>()
                                .find { container -> container.children.any { it.id == dropTarget.id } }
                            groupContainer?.groupId
                        }

                        else -> null
                    }
                } else {
                    null
                }
            }
        }.value
    }

    fun isDraggingUngroupedTab(): Boolean {
        val draggedItem = coordinator.dragState.value.draggedItem
        return draggedItem is DraggableItemType.Tab && draggedItem.groupId == null
    }

    fun isDraggingGroupedTab(): Boolean {
        val draggedItem = coordinator.dragState.value.draggedItem
        return draggedItem is DraggableItemType.Tab && draggedItem.groupId != null
    }

    fun shouldShowDivider(
        index: Int,
        currentItem: UnifiedItem,
        previousItem: UnifiedItem?
    ): Boolean {
        if (index == 0) return false // Never show before first item

        val isDraggingGroupedTab = isDraggingGroupedTab()

        return true // Always show dividers between top-level items
    }

    fun shouldShowGroupHeaderHover(groupId: String): Boolean {
        return isDraggingGroupedTab() && coordinator.isHoveringOver(groupId)
    }

    fun shouldShowGroupedTabHover(tabId: String, groupId: String, hoveredGroupId: String?): Boolean {
        val isDraggingUngrouped = isDraggingUngroupedTab()

        return if (isDraggingUngrouped) {
            groupId == hoveredGroupId
        } else {
            coordinator.isHoveringOver(tabId)
        }
    }

    fun shouldShowGroupRowTabHover(tabId: String): Boolean {
        return !isDraggingUngroupedTab() && coordinator.isHoveringOver(tabId)
    }
}

@Composable
fun rememberTabSheetHoverState(
    coordinator: DragCoordinator,
    uniqueItems: List<UnifiedItem>
): TabSheetHoverState {
    return remember(coordinator, uniqueItems) {
        TabSheetHoverState(coordinator, uniqueItems)
    }
}
