@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prirai.android.lr.R
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabBarCompose(
    tabs: List<TabSessionState>,
    viewModel: TabViewModel,
    orderManager: TabOrderManager,
    selectedTabId: String?,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoScrollTrigger: Long = 0L
) {
    val scope = rememberCoroutineScope()
    val order by orderManager.currentOrder.collectAsState()
    val listState = rememberLazyListState()

    val coordinator = rememberDragCoordinator(
        scope = scope,
        viewModel = viewModel,
        orderManager = orderManager
    )

    val items = remember(order, tabs) {
        buildBarItems(order, tabs)
    }
    
    val uniqueItems = remember(items) {
        items.distinctBy { item ->
            when (item) {
                is BarItem.SingleTab -> "tab-${item.id}"
                is BarItem.Group -> "group-${item.id}"
            }
        }
    }

    var hasInitialScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(order, selectedTabId, listState.layoutInfo.viewportSize) {
        if (!hasInitialScrolled && order != null && selectedTabId != null) {
            val selectedLocation = findSelectedTabLocation(order!!, selectedTabId)
            if (selectedLocation != null) {
                if (selectedLocation.isGrouped) {
                    val isGroupVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedLocation.itemIndex }
                    if (!isGroupVisible) {
                        listState.scrollToItem(selectedLocation.itemIndex)
                    }
                    hasInitialScrolled = true
                } else {
                    val viewportSize = listState.layoutInfo.viewportSize.width
                    if (viewportSize > 0) {
                        val averageItemSize = 180 // Approximate tab width in pixels (150dp + spacing)
                        val scrollOffset = -(viewportSize / 2 - averageItemSize / 2)
                        listState.scrollToItem(selectedLocation.itemIndex, scrollOffset.coerceAtLeast(-viewportSize))
                        hasInitialScrolled = true
                    }
                }
            }
        }
    }

    var lastSelectedTabId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedTabId, order) {
        val currentOrder = order ?: return@LaunchedEffect
        val currentTabId = selectedTabId ?: return@LaunchedEffect
        if (currentTabId == lastSelectedTabId || !hasInitialScrolled) {
            lastSelectedTabId = currentTabId
            return@LaunchedEffect
        }

        if (!listState.isScrollInProgress) {
            val selectedLocation = findSelectedTabLocation(currentOrder, currentTabId)
            if (selectedLocation != null) {
                if (selectedLocation.isGrouped) {
                    val isGroupVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedLocation.itemIndex }
                    if (!isGroupVisible) {
                        listState.scrollToItem(selectedLocation.itemIndex)
                    }
                } else {
                    val viewportSize = listState.layoutInfo.viewportSize.width
                    if (viewportSize > 0) {
                        val averageItemSize = 180
                        val scrollOffset = -(viewportSize / 2 - averageItemSize / 2)
                        listState.scrollToItem(selectedLocation.itemIndex, scrollOffset.coerceAtLeast(-viewportSize))
                    }
                }
            }
        }

        lastSelectedTabId = currentTabId
    }

    LaunchedEffect(autoScrollTrigger, order) {
        if (autoScrollTrigger > 0L && System.currentTimeMillis() - autoScrollTrigger < 2000L) {
            val currentOrder = order ?: return@LaunchedEffect
            val currentTabId = selectedTabId ?: return@LaunchedEffect
            val selectedLocation = findSelectedTabLocation(currentOrder, currentTabId)
            if (selectedLocation != null) {
                if (selectedLocation.isGrouped) {
                    val isGroupVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedLocation.itemIndex }
                    if (!isGroupVisible) {
                        listState.animateScrollToItem(selectedLocation.itemIndex)
                    }
                } else {
                    val viewportSize = listState.layoutInfo.viewportSize.width
                    if (viewportSize > 0) {
                        val averageItemSize = 180 // Approximate tab width in pixels
                        val scrollOffset = -(viewportSize / 2 - averageItemSize / 2)
                        listState.animateScrollToItem(selectedLocation.itemIndex, scrollOffset.coerceAtLeast(-viewportSize))
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp
        ) {
          Column {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(
                    uniqueItems,
                    key = { _, item ->
                        when (item) {
                            is BarItem.SingleTab -> "tab-${item.id}"
                            is BarItem.Group -> "group-${item.id}"
                        }
                    }
                ) { index, item ->
                    when (item) {
                        is BarItem.SingleTab -> {
                            var offsetY by remember { mutableStateOf(0f) }
                            var showMenu by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .draggableItem(
                                        itemType = DraggableItemType.Tab(item.tab.id),
                                        coordinator = coordinator
                                    )
                                    .dropTarget(
                                        id = item.tab.id,
                                        type = DropTargetType.TAB,
                                        coordinator = coordinator,
                                        metadata = mapOf(
                                            "tabId" to item.tab.id,
                                            "contextId" to (item.tab.contextId ?: "")
                                        )
                                    )
                                    .dragVisualFeedback(item.tab.id, coordinator)
                                    .pointerInput(item.tab.id) {
                                        detectVerticalDragGestures(
                                            onDragEnd = {
                                                if (abs(offsetY) > 50) {
                                                    if (offsetY < 0) {
                                                        onTabClose(item.tab.id)
                                                    } else {
                                                        showMenu = true
                                                    }
                                                }
                                                offsetY = 0f
                                            },
                                            onDragCancel = { offsetY = 0f },
                                            onVerticalDrag = { _, dragAmount ->
                                                offsetY += dragAmount
                                            }
                                        )
                                    }
                            ) {
                                TabPill(
                                    tab = item.tab,
                                    isSelected = item.tab.id == selectedTabId,
                                    coordinator = coordinator,
                                    onTabClick = onTabClick,
                                    onTabClose = onTabClose,
                                    isDragging = false,
                                    modifier = Modifier
                                        .offset { IntOffset(0, offsetY.roundToInt()) }
                                        .zIndex(1f)
                                )

                                if (abs(offsetY) > 10) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                if (offsetY < 0)
                                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                                else
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            ),
                                        contentAlignment = if (offsetY < 0) Alignment.TopCenter else Alignment.BottomCenter
                                    ) {
                                        Icon(
                                            imageVector = if (offsetY < 0) Icons.Default.Delete else Icons.Default.MoreVert,
                                            contentDescription = null,
                                            tint = if (offsetY < 0)
                                                MaterialTheme.colorScheme.onErrorContainer
                                            else
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            if (showMenu) {
                                ShowTabMenu(
                                    tab = item.tab,
                                    isInGroup = false,
                                    onDismiss = { showMenu = false },
                                    viewModel = viewModel,
                                    scope = scope
                                )
                            }
                        }

                        is BarItem.Group -> {
                            var offsetY by remember { mutableStateOf(0f) }
                            var showMenu by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .draggableItem(
                                        itemType = DraggableItemType.Group(item.groupId),
                                        coordinator = coordinator
                                    )
                                    .dropTarget(
                                        id = item.groupId,
                                        type = DropTargetType.GROUP_HEADER,
                                        coordinator = coordinator,
                                        metadata = mapOf<String, Any>(
                                            "groupId" to item.groupId,
                                            "contextId" to (item.contextId ?: "")
                                        )
                                    )
                                    .dragVisualFeedback(item.groupId, coordinator)
                                    .pointerInput(item.groupId) {
                                        detectVerticalDragGestures(
                                            onDragEnd = {
                                                if (abs(offsetY) > 50) {
                                                    if (offsetY < 0) {
                                                        viewModel.ungroupAll(item.groupId)
                                                    } else {
                                                        showMenu = true
                                                    }
                                                }
                                                offsetY = 0f
                                            },
                                            onDragCancel = { offsetY = 0f },
                                            onVerticalDrag = { _, dragAmount ->
                                                offsetY += dragAmount
                                            }
                                        )
                                    }
                            ) {
                                GroupPill(
                                    group = item,
                                    isSelected = selectedTabId in item.tabIds,
                                    selectedTabId = selectedTabId,
                                    coordinator = coordinator,
                                    onTabClick = onTabClick,
                                    onTabClose = onTabClose,
                                    onGroupClick = { groupId ->
                                        viewModel.toggleGroupExpanded(groupId)
                                    },
                                    viewModel = viewModel,
                                    isDragging = false,
                                    modifier = Modifier
                                        .offset { IntOffset(0, offsetY.roundToInt()) }
                                        .zIndex(1f)
                                )

                                if (abs(offsetY) > 10) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                if (offsetY < 0)
                                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                                else
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            ),
                                        contentAlignment = if (offsetY < 0) Alignment.TopCenter else Alignment.BottomCenter
                                    ) {
                                        Icon(
                                            imageVector = if (offsetY < 0) Icons.Default.Close else Icons.Default.MoreVert,
                                            contentDescription = null,
                                            tint = if (offsetY < 0)
                                                MaterialTheme.colorScheme.onErrorContainer
                                            else
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            if (showMenu) {
                                ShowGroupMenu(
                                    groupId = item.groupId,
                                    groupName = item.groupName,
                                    onDismiss = { showMenu = false },
                                    viewModel = viewModel,
                                    scope = scope
                                )
                            }
                        }
                    }

                    if (index < uniqueItems.size - 1) {
                        TabDivider(
                            id = "divider_${index + 1}",
                            coordinator = coordinator,
                            position = index + 1
                        )
                    }
                }
            }
          }
        }

        DragLayer(coordinator = coordinator) { draggedItem ->
            when (draggedItem) {
                is DraggableItemType.Tab -> {
                    val tab = tabs.find { it.id == draggedItem.tabId }
                    if (tab != null) {
                        Surface(
                            modifier = Modifier
                                .height(36.dp)
                                .width(124.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 8.dp,
                            shadowElevation = 8.dp
                        ) {
                            TabPill(
                                tab = tab,
                                isSelected = false,
                                coordinator = coordinator,
                                onTabClick = {},
                                onTabClose = {},
                                isDragging = true
                            )
                        }
                    }
                }

                is DraggableItemType.Group -> {
                    val item = items.find {
                        it is BarItem.Group && it.groupId == draggedItem.groupId
                    } as? BarItem.Group
                    if (item != null) {
                        GroupPill(
                            group = item,
                            isSelected = false,
                            selectedTabId = null,
                            coordinator = coordinator,
                            onTabClick = {},
                            onTabClose = {},
                            onGroupClick = null,
                            viewModel = viewModel,
                            isDragging = true
                        )
                    }
                }
            }
        }
    }
}

private fun buildBarItems(order: UnifiedTabOrder?, tabs: List<TabSessionState>): List<BarItem> {
    if (order == null) return tabs.map { BarItem.SingleTab(it) }

    return order.primaryOrder.mapNotNull { orderItem ->
        when (orderItem) {
            is UnifiedTabOrder.OrderItem.SingleTab -> {
                tabs.find { it.id == orderItem.tabId }?.let { BarItem.SingleTab(it) }
            }

            is UnifiedTabOrder.OrderItem.TabGroup -> {
                val groupTabs = orderItem.tabIds.mapNotNull { tabId ->
                    tabs.find { it.id == tabId }
                }
                if (groupTabs.isNotEmpty()) {
                    BarItem.Group(
                        groupId = orderItem.groupId,
                        groupName = orderItem.groupName,
                        color = orderItem.color,
                        contextId = groupTabs.first().contextId,
                        tabs = groupTabs,
                        tabIds = orderItem.tabIds,
                        isExpanded = orderItem.isExpanded
                    )
                } else null
            }
        }
    }
}

sealed class BarItem {
    abstract val id: String

    data class SingleTab(val tab: TabSessionState) : BarItem() {
        override val id = tab.id
    }

    data class Group(
        val groupId: String,
        val groupName: String,
        val color: Int,
        val contextId: String?,
        val tabs: List<TabSessionState>,
        val tabIds: List<String>,
        val isExpanded: Boolean
    ) : BarItem() {
        override val id = groupId
    }
}

private data class SelectedTabLocation(
    val itemIndex: Int,
    val isGrouped: Boolean
)

private fun findSelectedTabLocation(order: UnifiedTabOrder, tabId: String): SelectedTabLocation? {
    val index = order.primaryOrder.indexOfFirst { item ->
        when (item) {
            is UnifiedTabOrder.OrderItem.SingleTab -> item.tabId == tabId
            is UnifiedTabOrder.OrderItem.TabGroup -> tabId in item.tabIds
        }
    }
    if (index < 0) return null
    return SelectedTabLocation(
        itemIndex = index,
        isGrouped = order.primaryOrder.getOrNull(index) is UnifiedTabOrder.OrderItem.TabGroup
    )
}

@Composable
private fun TabPill(
    tab: TabSessionState,
    isSelected: Boolean,
    coordinator: DragCoordinator,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(36.dp)
            .width(124.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
        },
        border = if (isSelected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onTabClick(tab.id) }
                .padding(start = 10.dp, end = if (isSelected && !isDragging) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FaviconImage(
                tab = tab,
                size = 16.dp,
                modifier = Modifier
            )

            Text(
                text = getTabDisplayTitle(tab, stringResource(R.string.tab_history_new_tab)),
                style =
                    if (isSelected) {
                        MaterialTheme.typography.labelMediumEmphasized
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isSelected && !isDragging) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onTabClose(tab.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabDivider(
    id: String,
    coordinator: DragCoordinator,
    position: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .width(4.dp)
            .dropTarget(
                id = id,
                type = DropTargetType.ROOT_POSITION,
                coordinator = coordinator,
                metadata = mapOf("position" to position)
            )
    ) {
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowTabMenu(
    tab: TabSessionState,
    isInGroup: Boolean,
    onDismiss: () -> Unit,
    viewModel: TabViewModel,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    TabContextMenu(
        tab = tab,
        isInGroup = isInGroup,
        onDismiss = onDismiss,
        viewModel = viewModel,
        scope = scope,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowGroupMenu(
    groupId: String,
    groupName: String,
    onDismiss: () -> Unit,
    viewModel: TabViewModel,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    GroupContextMenu(
        groupId = groupId,
        groupName = groupName,
        onDismiss = onDismiss,
        viewModel = viewModel,
        scope = scope,
        modifier = modifier
    )
}

@Composable
private fun GroupPill(
    group: BarItem.Group,
    isSelected: Boolean,
    selectedTabId: String?,
    coordinator: DragCoordinator,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onGroupClick: ((String) -> Unit)? = null,
    viewModel: TabViewModel,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val groupData = viewModel.getGroup(group.groupId)
    val expanded = groupData?.let { !it.isCollapsed } ?: true
    
    var menuTab by remember { mutableStateOf<TabSessionState?>(null) }
    var showTabMenu by remember { mutableStateOf(false) }

    val groupColor = Color(group.color)
    val onGroupColor = if (groupColor.luminance() > 0.5f) Color.Black else Color.White
    val groupContainer = groupColor.copy(alpha = 0.12f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)

    Surface(
        modifier = modifier
            .then(
                if (expanded) Modifier.wrapContentHeight().defaultMinSize(minHeight = 44.dp)
                else Modifier.height(44.dp)
            )
            .wrapContentWidth()
            .then(
                if (isDragging) Modifier else Modifier
            ),
        shape = RoundedCornerShape(22.dp),
        color = groupContainer,
        border = BorderStroke(1.dp, groupColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .then(if (expanded) Modifier.wrapContentHeight() else Modifier.fillMaxHeight())
                .clickable(
                    enabled = true,
                    onClick = {
                        viewModel.toggleGroupExpanded(group.groupId)
                        onGroupClick?.invoke(group.groupId)
                    }
                )
                .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = groupColor
            ) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                        tint = onGroupColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = group.groupName,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        maxLines = 1,
                        color = onGroupColor
                    )
                    Text(
                        text = "\u00B7 ${group.tabs.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = onGroupColor.copy(alpha = 0.8f)
                    )
                }
            }

            if (expanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    group.tabs.forEachIndexed { index, tab ->
                        key(tab.id) {
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(20.dp)
                                        .background(Color(group.color).copy(alpha = 0.3f))
                                )
                            }

                            val isTabSelected = selectedTabId == tab.id
                            val bringIntoViewRequester = remember { BringIntoViewRequester() }
                            LaunchedEffect(isTabSelected) {
                                if (isTabSelected) {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .draggableItem(
                                        itemType = DraggableItemType.Tab(tab.id),
                                        coordinator = coordinator
                                    )
                                    .bringIntoViewRequester(bringIntoViewRequester)
                                    .dropTarget(
                                        id = tab.id,
                                        type = DropTargetType.TAB,
                                        coordinator = coordinator,
                                        metadata = mapOf(
                                            "tabId" to tab.id,
                                            "groupId" to group.groupId,
                                            "isInGroup" to true
                                        )
                                    )
                            ) {
                                SwipeableTabPill(
                                    tab = tab,
                                    isSelected = isTabSelected,
                                    groupColor = group.color,
                                    onTabClick = { onTabClick(tab.id) },
                                    onTabClose = { onTabClose(tab.id) },
                                    onShowMenu = {
                                        menuTab = tab
                                        showTabMenu = true
                                    },
                                    modifier = Modifier,
                                    swipeThreshold = 40f
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(Color(group.color).copy(alpha = 0.3f))
                    )

                    IconButton(
                        onClick = {
                            viewModel.createNewTabInGroup(group.groupId, group.contextId)
                        },
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(groupColor.copy(alpha = 0.18f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_tab_to_group),
                            tint = groupColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    if (showTabMenu && menuTab != null) {
        ShowTabMenu(
            tab = menuTab!!,
            isInGroup = true,
            onDismiss = {
                showTabMenu = false
                menuTab = null
            },
            viewModel = viewModel,
            scope = rememberCoroutineScope()
        )
    }
}
