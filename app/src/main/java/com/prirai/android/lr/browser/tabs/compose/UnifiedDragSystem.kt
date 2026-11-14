@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Context
import androidx.compose.foundation.background
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap

sealed class DraggableItemType {
    data class Tab(val tabId: String, val groupId: String? = null) : DraggableItemType()
    data class Group(val groupId: String) : DraggableItemType()
}

enum class DropTargetType {
    TAB,           // Drop on another tab
    GROUP_HEADER,  // Drop on group header (add to group)
    GROUP_BODY,    // Drop within group body (reorder within group)
    EMPTY_SPACE,   // Drop in empty space (root level)
    ROOT_POSITION  // Specific position at root level
}

enum class DropZone {
    BEFORE,  // First 15% - insert before
    CENTER,  // Middle 70% - group/merge
    AFTER    // Last 15% - insert after
}

data class DropTarget(
    val id: String,
    val bounds: Rect,
    val type: DropTargetType,
    val metadata: Map<String, Any> = emptyMap(),
    val dropZone: DropZone = DropZone.CENTER
)

data class UnifiedDragState(
    val draggedItem: DraggableItemType? = null,
    val dragOffset: Offset = Offset.Zero,
    val isDragging: Boolean = false,
    val currentDropTarget: DropTarget? = null,
    val dragStartPosition: Offset = Offset.Zero,
    val insertionIndicatorPosition: Offset? = null,
    val insertionIndicatorType: InsertionIndicatorType? = null,
    val currentDropZone: DropZone? = null  // Track current drop zone for visual feedback
)

enum class InsertionIndicatorType {
    HORIZONTAL_LINE,  // For list views
    VERTICAL_LINE,    // For horizontal layouts
    GAP               // For grid views
}

class DragCoordinator(
    private val scope: CoroutineScope,
    private val viewModel: TabViewModel,
    private val orderManager: TabOrderManager,
    private val context: Context? = null
) {
    private val _dragState = mutableStateOf(UnifiedDragState())
    val dragState: State<UnifiedDragState> = _dragState

    private val _dragLayerOffset = mutableStateOf(Offset.Zero)
    val dragLayerOffset: State<Offset> = _dragLayerOffset

    private val _dragLayerSize = mutableStateOf(IntSize.Zero)
    val dragLayerSize: State<IntSize> = _dragLayerSize

    private val _draggedItemBounds = mutableStateOf<Rect?>(null)

    private val dropTargets = ConcurrentHashMap<String, DropTarget>()

    private val itemPositions = ConcurrentHashMap<String, Offset>()
    private val itemSizes = ConcurrentHashMap<String, IntSize>()

    private var autoScrollJob: Job? = null
    private val _autoScrollVelocity = mutableStateOf(0f)

    private var dropOperationJob: Job? = null

    private var lastDropTargetUpdate = 0L

    private val vibrator: Vibrator? = context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var scrollContainerBounds: Rect? = null

    private fun getAutoScrollThreshold(): Float {
        val bounds = scrollContainerBounds ?: return 0f
        val containerHeight = bounds.bottom - bounds.top
        return containerHeight * 0.1f // 10% of container height
    }

    private var isScrolling = false

    fun setIsScrolling(scrolling: Boolean) {
        isScrolling = scrolling
    }

    fun registerDropTarget(target: DropTarget) {
        dropTargets[target.id] = target
    }

    fun updateItemPosition(itemId: String, position: Offset, size: IntSize) {
        if (!_dragState.value.isDragging && !isScrolling) {
            itemPositions[itemId] = position
            itemSizes[itemId] = size
        }
    }

    fun setScrollContainerBounds(bounds: Rect) {
        scrollContainerBounds = bounds
    }

    private fun performHapticFeedback(intensity: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(10, intensity))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(10)
        }
    }

    fun startDrag(item: DraggableItemType, startPosition: Offset, itemBounds: Rect, itemSize: IntSize) {
        _dragState.value = UnifiedDragState(
            draggedItem = item,
            isDragging = true,
            dragStartPosition = startPosition,
            dragOffset = Offset.Zero
        )
        _draggedItemBounds.value = itemBounds
        _dragLayerOffset.value = itemBounds.topLeft
        _dragLayerSize.value = itemSize

        performHapticFeedback()
    }

    fun updateDrag(dragAmount: Offset, pointerPosition: Offset) {
        val current = _dragState.value
        if (!current.isDragging) return

        val newOffset = current.dragOffset + dragAmount

        val itemBounds = _draggedItemBounds.value
        if (itemBounds != null) {
            _dragLayerOffset.value = pointerPosition - Offset(
                itemBounds.width / 2f,
                itemBounds.height * 0.3f  // 30% from top - keeps item visible under finger
            )
        }

        checkAutoScroll(pointerPosition)

        val now = System.currentTimeMillis()
        val target = if (now - lastDropTargetUpdate > 16) {
            lastDropTargetUpdate = now
            val draggedItemId = getDraggedItemId(current.draggedItem)
            findDropTargetAt(pointerPosition, draggedItemId)
        } else {
            current.currentDropTarget
        }

        if (target != null && target.id != current.currentDropTarget?.id) {
            performHapticFeedback(128) // Medium intensity (range: 1-255)
        }

        val (indicatorPos, indicatorType) = calculateInsertionIndicator(target, pointerPosition)

        _dragState.value = current.copy(
            dragOffset = newOffset,
            currentDropTarget = target,
            insertionIndicatorPosition = indicatorPos,
            insertionIndicatorType = indicatorType,
            currentDropZone = target?.dropZone
        )
    }

    private fun checkAutoScroll(pointerPosition: Offset) {
        val bounds = scrollContainerBounds ?: return

        val threshold = getAutoScrollThreshold()
        if (threshold == 0f) return // No scroll container set yet

        val distanceFromTop = pointerPosition.y - bounds.top
        val distanceFromBottom = bounds.bottom - pointerPosition.y

        val velocity = when {
            distanceFromTop < threshold && distanceFromTop > 0 -> {
                val intensity = (threshold - distanceFromTop) / threshold
                -intensity * 10f
            }

            distanceFromBottom < threshold && distanceFromBottom > 0 -> {
                val intensity = (threshold - distanceFromBottom) / threshold
                intensity * 10f
            }

            else -> 0f
        }

        if (velocity != _autoScrollVelocity.value) {
            _autoScrollVelocity.value = velocity

            if (velocity != 0f && autoScrollJob == null) {
                startAutoScroll()
            } else if (velocity == 0f) {
                stopAutoScroll()
            }
        }
    }

    private fun startAutoScroll() {
        autoScrollJob = scope.launch {
            while (true) {
                val velocity = _autoScrollVelocity.value
                if (velocity == 0f) break

                delay(16) // ~60fps
            }
        }
    }

    private fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        _autoScrollVelocity.value = 0f
    }

    private fun calculateInsertionIndicator(
        target: DropTarget?,
        pointerPosition: Offset
    ): Pair<Offset?, InsertionIndicatorType?> {
        if (target == null) return Pair(null, null)

        return when (target.dropZone) {
            DropZone.BEFORE -> {
                val bounds = target.bounds
                val isHorizontal = bounds.width > bounds.height

                if (isHorizontal) {
                    Pair(Offset(bounds.left, bounds.top + bounds.height / 2f), InsertionIndicatorType.VERTICAL_LINE)
                } else {
                    Pair(Offset(bounds.left + bounds.width / 2f, bounds.top), InsertionIndicatorType.HORIZONTAL_LINE)
                }
            }

            DropZone.AFTER -> {
                val bounds = target.bounds
                val isHorizontal = bounds.width > bounds.height

                if (isHorizontal) {
                    Pair(Offset(bounds.right, bounds.top + bounds.height / 2f), InsertionIndicatorType.VERTICAL_LINE)
                } else {
                    Pair(Offset(bounds.left + bounds.width / 2f, bounds.bottom), InsertionIndicatorType.HORIZONTAL_LINE)
                }
            }

            DropZone.CENTER -> {
                Pair(null, null)
            }
        }
    }

    fun endDrag() {
        val current = _dragState.value
        val draggedItem = current.draggedItem
        val target = current.currentDropTarget
        if (draggedItem != null && target != null) {
            performHapticFeedback()

            dropOperationJob?.cancel()

            dropOperationJob = scope.launch {
                try {
                    performDrop(draggedItem, target)
                } catch (e: Exception) {
                    Log.e("DragCoordinator", "Drop operation failed", e)
                }
            }
        }

        _dragState.value = UnifiedDragState()
        _draggedItemBounds.value = null
        stopAutoScroll()
    }

    fun cancelDrag() {
        _dragState.value = UnifiedDragState()
        _draggedItemBounds.value = null
        stopAutoScroll()
    }

    fun isDragging(itemId: String): Boolean {
        val draggedId = getDraggedItemId(_dragState.value.draggedItem)
        return _dragState.value.isDragging && draggedId == itemId
    }

    fun isHoveringOver(targetId: String): Boolean {
        return _dragState.value.currentDropTarget?.id == targetId
    }

    private fun getDraggedItemId(item: DraggableItemType?): String? {
        return when (item) {
            is DraggableItemType.Tab -> item.tabId
            is DraggableItemType.Group -> item.groupId
            null -> null
        }
    }

    private fun findDropTargetAt(position: Offset, excludeId: String?): DropTarget? {
        val candidateTargets = dropTargets.values.filter { target ->
            target.id != excludeId && target.bounds.contains(position)
        }

        val bestTarget = candidateTargets.maxByOrNull { target ->
            when (target.type) {
                DropTargetType.TAB -> 6
                DropTargetType.GROUP_HEADER -> 5  // Same priority as ROOT_POSITION, support zones for reordering
                DropTargetType.ROOT_POSITION -> 5
                DropTargetType.GROUP_BODY -> 4
                DropTargetType.EMPTY_SPACE -> 1
            }
        } ?: return null

        if (bestTarget.type == DropTargetType.TAB || bestTarget.type == DropTargetType.GROUP_HEADER) {
            val zone = calculateDropZone(position, bestTarget.bounds)
            return bestTarget.copy(dropZone = zone)
        }

        if (bestTarget.type == DropTargetType.ROOT_POSITION) {
        }

        return bestTarget
    }

    private fun calculateDropZone(position: Offset, bounds: Rect): DropZone {
        val isHorizontal = bounds.width > bounds.height

        if (isHorizontal) {
            val relativeX = position.x - bounds.left
            val width = bounds.width
            val threshold = relativeX / width

            return when {
                threshold < 0.25f -> DropZone.BEFORE
                threshold > 0.75f -> DropZone.AFTER
                else -> DropZone.CENTER
            }
        } else {
            val relativeY = position.y - bounds.top
            val height = bounds.height
            val threshold = relativeY / height

            return when {
                threshold < 0.25f -> DropZone.BEFORE
                threshold > 0.75f -> DropZone.AFTER
                else -> DropZone.CENTER
            }
        }
    }

    private suspend fun performDrop(item: DraggableItemType, target: DropTarget) {
        when (item) {
            is DraggableItemType.Tab -> handleTabDrop(item, target)
            is DraggableItemType.Group -> handleGroupDrop(item, target)
        }
    }

    private suspend fun handleTabDrop(tab: DraggableItemType.Tab, target: DropTarget) {
        when (target.type) {
            DropTargetType.TAB -> {
                val targetTabId = target.metadata["tabId"] as? String ?: return
                val targetGroupId = target.metadata["groupId"] as? String

                if (targetTabId != tab.tabId) {
                    val tabs = viewModel.tabs.value
                    val draggedTab = tabs.find { it.id == tab.tabId }
                    val targetTab = tabs.find { it.id == targetTabId }

                    if (draggedTab != null && targetTab != null) {
                        if (draggedTab.contextId == targetTab.contextId) {
                            if (targetGroupId != null) {
                                if (tab.groupId != null && tab.groupId != targetGroupId) {
                                    viewModel.removeTabFromGroup(tab.tabId)
                                    delay(50)
                                }

                                viewModel.addTabToGroup(tab.tabId, targetGroupId)
                                return
                            }

                            when (target.dropZone) {
                                DropZone.CENTER -> {
                                    if (tab.groupId != null) {
                                        viewModel.removeTabFromGroup(tab.tabId)
                                        delay(50)
                                    }

                                    val currentOrder = viewModel.currentOrder.value
                                    val targetPosition = currentOrder?.primaryOrder?.indexOfFirst { item ->
                                        when (item) {
                                            is UnifiedTabOrder.OrderItem.SingleTab -> item.tabId == targetTabId
                                            is UnifiedTabOrder.OrderItem.TabGroup -> item.tabIds.contains(targetTabId)
                                        }
                                    } ?: -1

                                    viewModel.createGroup(
                                        listOf(tab.tabId, targetTabId),
                                        contextId = draggedTab.contextId ?: targetTab.contextId
                                    )

                                    delay(100)

                                    if (targetPosition >= 0) {
                                        val newOrder = viewModel.currentOrder.value
                                        val newGroupId = viewModel.groups.value.find { group ->
                                            group.tabIds.containsAll(listOf(tab.tabId, targetTabId))
                                        }?.id

                                        if (newGroupId != null) {
                                            val currentGroupPosition = newOrder?.getItemPosition(newGroupId)
                                            if (currentGroupPosition != null && currentGroupPosition != targetPosition) {
                                                orderManager.reorderItem(currentGroupPosition, targetPosition)
                                            }
                                        }
                                    }
                                }

                                DropZone.BEFORE -> {
                                    if (tab.groupId != null) {
                                        viewModel.removeTabFromGroup(tab.tabId)
                                        delay(50)
                                    }

                                    val currentOrder = viewModel.currentOrder.value
                                    val targetPosition = currentOrder?.primaryOrder?.indexOfFirst { item ->
                                        when (item) {
                                            is UnifiedTabOrder.OrderItem.SingleTab -> item.tabId == targetTabId
                                            is UnifiedTabOrder.OrderItem.TabGroup -> item.tabIds.contains(targetTabId)
                                        }
                                    } ?: -1

                                    if (targetPosition >= 0) {
                                        viewModel.moveTabToPosition(tab.tabId, targetPosition)
                                    }
                                }

                                DropZone.AFTER -> {
                                    if (tab.groupId != null) {
                                        viewModel.removeTabFromGroup(tab.tabId)
                                        delay(50)
                                    }

                                    val currentOrder = viewModel.currentOrder.value
                                    val targetPosition = currentOrder?.primaryOrder?.indexOfFirst { item ->
                                        when (item) {
                                            is UnifiedTabOrder.OrderItem.SingleTab -> item.tabId == targetTabId
                                            is UnifiedTabOrder.OrderItem.TabGroup -> item.tabIds.contains(targetTabId)
                                        }
                                    } ?: -1

                                    if (targetPosition >= 0) {
                                        viewModel.moveTabToPosition(tab.tabId, targetPosition + 1)
                                    }
                                }
                            }
                        } else {
                            Log.w(
                                "DragCoordinator",
                                "Context mismatch: cannot group tabs from different profiles"
                            )
                        }
                    }
                }
            }

            DropTargetType.GROUP_HEADER -> {
                val groupId = target.metadata["groupId"] as? String ?: return
                val groupContextId = target.metadata["contextId"] as? String

                val tabs = viewModel.tabs.value
                val draggedTab = tabs.find { it.id == tab.tabId }

                if (draggedTab != null) {
                    if (draggedTab.contextId == groupContextId) {
                        when (target.dropZone) {
                            DropZone.CENTER -> {
                                if (tab.groupId != null && tab.groupId != groupId) {
                                    viewModel.removeTabFromGroup(tab.tabId)
                                    delay(50)
                                }

                                viewModel.addTabToGroup(tab.tabId, groupId)
                            }

                            DropZone.BEFORE -> {
                                if (tab.groupId != null) {
                                    viewModel.removeTabFromGroup(tab.tabId)
                                    delay(50)
                                }

                                val currentOrder = viewModel.currentOrder.value
                                val groupPosition = currentOrder?.getItemPosition(groupId)

                                if (groupPosition != null && groupPosition >= 0) {
                                    viewModel.moveTabToPosition(tab.tabId, groupPosition)
                                }
                            }

                            DropZone.AFTER -> {
                                if (tab.groupId != null) {
                                    viewModel.removeTabFromGroup(tab.tabId)
                                    delay(50)
                                }

                                val currentOrder = viewModel.currentOrder.value
                                val groupPosition = currentOrder?.getItemPosition(groupId)

                                if (groupPosition != null && groupPosition >= 0) {
                                    viewModel.moveTabToPosition(tab.tabId, groupPosition + 1)
                                }
                            }

                            null -> {
                                if (tab.groupId != null && tab.groupId != groupId) {
                                    viewModel.removeTabFromGroup(tab.tabId)
                                    delay(50)
                                }

                                viewModel.addTabToGroup(tab.tabId, groupId)
                            }
                        }
                    } else {
                        Log.w(
                            "DragCoordinator",
                            "Context mismatch: cannot add tab to group from different profile"
                        )
                    }
                }
            }

            DropTargetType.GROUP_BODY -> {
                val groupId = target.metadata["groupId"] as? String ?: return
                val targetTabId = target.metadata["targetTabId"] as? String ?: return

                if (tab.groupId == groupId && tab.tabId != targetTabId) {
                    when (target.dropZone) {
                        DropZone.BEFORE -> {
                            viewModel.reorderTabInGroup(tab.tabId, targetTabId, groupId, insertAfter = false)
                        }

                        DropZone.AFTER -> {
                            viewModel.reorderTabInGroup(tab.tabId, targetTabId, groupId, insertAfter = true)
                        }

                        DropZone.CENTER -> {
                            viewModel.reorderTabInGroup(tab.tabId, targetTabId, groupId, insertAfter = true)
                        }
                    }
                }
            }

            DropTargetType.ROOT_POSITION -> {
                val position = target.metadata["position"] as? Int ?: return
                if (tab.groupId != null) {
                    viewModel.removeTabFromGroup(tab.tabId)
                    delay(100)
                }

                viewModel.moveTabToPosition(tab.tabId, position)
            }

            DropTargetType.EMPTY_SPACE -> {
                if (tab.groupId != null) {
                    viewModel.removeTabFromGroup(tab.tabId)
                }
            }
        }
    }

    private suspend fun handleGroupDrop(group: DraggableItemType.Group, target: DropTarget) {
        when (target.type) {
            DropTargetType.TAB -> {
                val targetTabId = target.metadata["tabId"] as? String ?: return
                val tabs = viewModel.tabs.value
                val targetTab = tabs.find { it.id == targetTabId }

                if (targetTab != null) {
                    val groups = viewModel.groups.value
                    val draggedGroup = groups.find { it.id == group.groupId }

                    if (draggedGroup != null && draggedGroup.contextId == targetTab.contextId) {
                        when (target.dropZone) {
                            DropZone.CENTER -> {
                                viewModel.addTabToGroup(targetTabId, group.groupId)
                            }

                            DropZone.BEFORE, DropZone.AFTER -> {
                                val zone = if (target.dropZone == DropZone.BEFORE) "BEFORE" else "AFTER"
                                val currentOrder = viewModel.currentOrder.value
                                val targetPosition = currentOrder?.primaryOrder?.indexOfFirst { item ->
                                    when (item) {
                                        is UnifiedTabOrder.OrderItem.SingleTab -> item.tabId == targetTabId
                                        is UnifiedTabOrder.OrderItem.TabGroup -> item.tabIds.contains(targetTabId)
                                    }
                                } ?: -1

                                val currentPosition = currentOrder?.getItemPosition(group.groupId)

                                if (targetPosition >= 0 && currentPosition != null) {
                                    val finalPosition =
                                        if (target.dropZone == DropZone.AFTER) targetPosition + 1 else targetPosition
                                    orderManager.reorderItem(currentPosition, finalPosition)
                                }
                            }

                            null -> {
                                viewModel.addTabToGroup(targetTabId, group.groupId)
                            }
                        }
                    } else {
                        Log.w(
                            "DragCoordinator",
                            "Context mismatch: cannot add tab to group from different profile"
                        )
                    }
                }
            }

            DropTargetType.GROUP_HEADER -> {
                val targetGroupId = target.metadata["groupId"] as? String ?: return
                val targetContextId = target.metadata["contextId"] as? String

                val groups = viewModel.groups.value
                val draggedGroup = groups.find { it.id == group.groupId }

                if (draggedGroup != null && targetGroupId != group.groupId) {
                    if (draggedGroup.contextId == targetContextId) {
                        when (target.dropZone) {
                            DropZone.CENTER -> {
                                viewModel.mergeGroups(group.groupId, targetGroupId)
                            }

                            DropZone.BEFORE, DropZone.AFTER -> {
                                val zone = if (target.dropZone == DropZone.BEFORE) "BEFORE" else "AFTER"
                                val currentOrder = viewModel.currentOrder.value
                                val targetPosition = currentOrder?.getItemPosition(targetGroupId)
                                val currentPosition = currentOrder?.getItemPosition(group.groupId)

                                if (targetPosition != null && currentPosition != null) {
                                    val finalPosition =
                                        if (target.dropZone == DropZone.AFTER) targetPosition + 1 else targetPosition
                                    orderManager.reorderItem(currentPosition, finalPosition)
                                }
                            }

                            null -> {
                                viewModel.mergeGroups(group.groupId, targetGroupId)
                            }
                        }
                    } else {
                        Log.w(
                            "DragCoordinator",
                            "Context mismatch: cannot merge groups from different profiles"
                        )
                    }
                }
            }

            DropTargetType.ROOT_POSITION -> {
                val position = target.metadata["position"] as? Int ?: return
                val currentPosition = viewModel.currentOrder.value?.getItemPosition(group.groupId)

                if (currentPosition != null) {
                    orderManager.reorderItem(currentPosition, position)
                }
            }

            else -> {
            }
        }
    }

    fun cleanup() {
        autoScrollJob?.cancel()
        dropOperationJob?.cancel()
        dropTargets.clear()
        itemPositions.clear()
        itemSizes.clear()
        _dragState.value = UnifiedDragState()
        _draggedItemBounds.value = null
    }
}

@Composable
fun rememberDragCoordinator(
    scope: CoroutineScope = rememberCoroutineScope(),
    viewModel: TabViewModel,
    orderManager: TabOrderManager,
    context: Context = LocalContext.current
): DragCoordinator {
    val coordinator = remember(scope, viewModel, orderManager, context) {
        DragCoordinator(scope, viewModel, orderManager, context)
    }

    DisposableEffect(coordinator) {
        onDispose {
            coordinator.cleanup()
        }
    }

    return coordinator
}

fun Modifier.draggableItem(
    itemType: DraggableItemType,
    coordinator: DragCoordinator,
    enabled: Boolean = true
): Modifier = composed {
    var itemBounds by remember { mutableStateOf<Rect?>(null) }
    var itemSize by remember { mutableStateOf(IntSize.Zero) }

    this
        .onGloballyPositioned { coordinates ->
            val id = when (itemType) {
                is DraggableItemType.Tab -> itemType.tabId
                is DraggableItemType.Group -> itemType.groupId
            }

            itemBounds = Rect(
                offset = coordinates.positionInRoot(),
                size = coordinates.size.toSize()
            )
            itemSize = coordinates.size

            coordinator.updateItemPosition(id, coordinates.positionInRoot(), coordinates.size)
        }
        .then(
            if (enabled) {
                val id = when (itemType) {
                    is DraggableItemType.Tab -> itemType.tabId
                    is DraggableItemType.Group -> itemType.groupId
                }

                Modifier.pointerInput(id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            itemBounds?.let { bounds ->
                                val absolutePosition = bounds.topLeft + offset
                                coordinator.startDrag(itemType, absolutePosition, bounds, itemSize)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            itemBounds?.let { bounds ->
                                val absolutePointer = bounds.topLeft + change.position
                                coordinator.updateDrag(dragAmount, absolutePointer)
                            }
                        },
                        onDragEnd = {
                            coordinator.endDrag()
                        },
                        onDragCancel = {
                            coordinator.cancelDrag()
                        }
                    )
                }
            } else {
                Modifier
            }
        )
}

fun Modifier.dropTarget(
    id: String,
    type: DropTargetType,
    coordinator: DragCoordinator,
    metadata: Map<String, Any> = emptyMap()
): Modifier = this.onGloballyPositioned { coordinates ->
    val bounds = Rect(
        offset = coordinates.positionInRoot(),
        size = coordinates.size.toSize()
    )
    coordinator.registerDropTarget(
        DropTarget(
            id = id,
            bounds = bounds,
            type = type,
            metadata = metadata
        )
    )
}

fun Modifier.dragVisualFeedback(
    itemId: String,
    coordinator: DragCoordinator,
    isDragging: Boolean = coordinator.isDragging(itemId),
    isDropTarget: Boolean = coordinator.isHoveringOver(itemId),
    draggedScale: Float = 0.95f,
    hoverScale: Float = 1.08f  // Scale up when hovering to signify grouping
): Modifier = composed {
    val isInvolved = isDragging || isDropTarget

    if (!isInvolved) {
        return@composed this
    }

    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fastSpatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    LaunchedEffect(isDragging, isDropTarget) {
        when {
            isDragging -> {
                launch {
                    scale.animateTo(draggedScale, animationSpec = fastSpatialSpec)
                }
                launch {
                    alpha.animateTo(0.3f, animationSpec = fastSpatialSpec)
                }
            }

            isDropTarget -> {
                launch {
                    scale.animateTo(hoverScale, animationSpec = spatialSpec)
                }
                launch {
                    alpha.animateTo(1f, animationSpec = effectsSpec)
                }
            }

            else -> {
                launch {
                    scale.animateTo(1f, animationSpec = spatialSpec)
                }
                launch {
                    alpha.animateTo(1f, animationSpec = effectsSpec)
                }
            }
        }
    }

    this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
    }
}

@Composable
fun DragLayer(
    coordinator: DragCoordinator,
    modifier: Modifier = Modifier,
    content: @Composable (DraggableItemType) -> Unit
) {
    val dragState by coordinator.dragState
    val dragLayerOffset by coordinator.dragLayerOffset
    val dragLayerSize by coordinator.dragLayerSize

    if (dragState.isDragging && dragState.draggedItem != null) {
        Box(
            modifier = modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .zIndex(1000f) // Above everything
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = dragLayerOffset.x
                        translationY = dragLayerOffset.y
                        shadowElevation = 8.dp.toPx()
                        scaleX = 1.05f
                        scaleY = 1.05f
                    }
            ) {
                content(dragState.draggedItem!!)
            }
        }
    }
}

@Composable
fun InsertionIndicator(
    coordinator: DragCoordinator,
    modifier: Modifier = Modifier
) {
    val dragState by coordinator.dragState
    val indicatorPosition = dragState.insertionIndicatorPosition
    val indicatorType = dragState.insertionIndicatorType

    if (indicatorPosition != null && indicatorType != null && dragState.isDragging) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .zIndex(999f) // Just below drag layer
        ) {
            when (indicatorType) {
                InsertionIndicatorType.HORIZONTAL_LINE -> {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = indicatorPosition.x - 100f
                                translationY = indicatorPosition.y
                            }
                            .width(200.dp)
                            .height(3.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(1.5.dp)
                            )
                    )
                }

                InsertionIndicatorType.VERTICAL_LINE -> {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = indicatorPosition.x
                                translationY = indicatorPosition.y - 50f
                            }
                            .width(3.dp)
                            .height(100.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(1.5.dp)
                            )
                    )
                }

                InsertionIndicatorType.GAP -> {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = indicatorPosition.x
                                translationY = indicatorPosition.y - 50f
                            }
                            .width(3.dp)
                            .height(100.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(1.5.dp)
                            )
                    )
                }
            }
        }
    }
}
