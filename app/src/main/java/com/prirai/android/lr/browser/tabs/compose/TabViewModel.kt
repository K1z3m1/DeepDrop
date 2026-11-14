package com.prirai.android.lr.browser.tabs.compose

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prirai.android.lr.browser.tabgroups.UnifiedTabGroupManager
import com.prirai.android.lr.browser.tabgroups.TabGroupData
import com.prirai.android.lr.ext.components
import mozilla.components.browser.state.state.TabSessionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.util.Log
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.preferences.UserPreferences

class TabViewModel(
    private val context: Context,
    private val groupManager: UnifiedTabGroupManager
) : ViewModel() {
    private val _tabs = MutableStateFlow<List<TabSessionState>>(emptyList())
    val tabs: StateFlow<List<TabSessionState>> = _tabs.asStateFlow()

    private val _groups = MutableStateFlow<List<TabGroupData>>(emptyList())
    val groups: StateFlow<List<TabGroupData>> = _groups.asStateFlow()

    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    private val _selectedTabId = MutableStateFlow<String?>(null)
    val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    private val _currentProfileId = MutableStateFlow<String?>(null)
    val currentProfileId: StateFlow<String?> = _currentProfileId.asStateFlow()

    private val orderManager by lazy { TabOrderManager.getInstance(context, groupManager) }
    private var loadJob: Job? = null
    private var rebuildJob: Job? = null

    val currentOrder: StateFlow<UnifiedTabOrder?> = orderManager.currentOrder

    var onTabRemove: ((String) -> Unit)? = null

    private fun contextIdForProfile(profileId: String): String? =
        BrowserProfile.contextIdFor(
            profileId = profileId,
            isPrivate = profileId == "private",
        )

    private fun groupMatchesProfile(group: TabGroupData, profileId: String): Boolean =
        when (profileId) {
            "default" -> group.contextId == null || group.contextId == "profile_default"
            else -> group.contextId == contextIdForProfile(profileId)
        }

    init {
        viewModelScope.launch {
            groupManager.groupEvents.collect { event ->
                rebuildJob?.cancel()
                rebuildJob = launch {
                    delay(150) // Debounce for 150ms
                    _currentProfileId.value?.let { profileId ->
                        refreshGroupsForProfile(profileId)
                        orderManager.rebuildOrderForProfile(profileId, _tabs.value)
                    }
                }
            }
        }
    }

    private suspend fun refreshGroupsForProfile(profileId: String) {
        val allGroups = groupManager.getAllGroups()

        val groupsList = allGroups.filter { group ->
            groupMatchesProfile(group, profileId)
        }.filter { group ->
            group.tabIds.any { tabId -> _tabs.value.any { it.id == tabId } }
        }

        _groups.value = groupsList

        val savedOrder = orderManager.currentOrder.value
        val savedExpandedStates = savedOrder?.primaryOrder
            ?.filterIsInstance<UnifiedTabOrder.OrderItem.TabGroup>()
            ?.associate { it.groupId to it.isExpanded }
            ?: emptyMap()
        
        val currentExpanded = _expandedGroups.value
        val updatedExpanded = groupsList.mapNotNull { group ->
            when {
                savedExpandedStates.containsKey(group.id) -> if (savedExpandedStates[group.id] == true) group.id else null
                currentExpanded.contains(group.id) -> group.id
                else -> group.id // New groups default to expanded
            }
        }.toSet()
        _expandedGroups.value = updatedExpanded
    }

    fun loadTabsForProfile(profileId: String, tabs: List<TabSessionState>, selectedTabId: String?) {
        loadJob?.cancel()

        _currentProfileId.value = profileId

        loadJob = viewModelScope.launch {
            val allGroups = groupManager.getAllGroups()

            val groupsList = allGroups.filter { group ->
                groupMatchesProfile(group, profileId)
            }.filter { group ->
                group.tabIds.any { tabId -> tabs.any { it.id == tabId } }
            }

            val orderedTabs = getOrderedTabs(tabs, profileId, groupsList)

            orderManager.rebuildOrderForProfile(profileId, tabs)

            val savedOrder = orderManager.currentOrder.value
            val expandedGroupIds = if (savedOrder != null) {
                savedOrder.primaryOrder
                    .filterIsInstance<UnifiedTabOrder.OrderItem.TabGroup>()
                    .filter { it.isExpanded }
                    .map { it.groupId }
                    .toSet()
            } else {
                groupsList.map { it.id }.toSet()
            }

            _tabs.value = orderedTabs
            _groups.value = groupsList
            _expandedGroups.value = expandedGroupIds
            _selectedTabId.value = selectedTabId

            selectedTabId?.let { tabId ->
                orderManager.expandGroupContainingTab(tabId)
            }
        }
    }

    private suspend fun getOrderedTabs(
        tabs: List<TabSessionState>,
        profileId: String,
        groups: List<TabGroupData>
    ): List<TabSessionState> {
        orderManager.loadOrder(profileId)

        val order = orderManager.currentOrder.value
        if (order == null || order.primaryOrder.isEmpty()) {
            orderManager.initializeFromCurrentState(profileId, tabs.map { it.id }, groups)
            return tabs
        }

        val tabMap = tabs.associateBy { it.id }
        val orderedTabs = mutableListOf<TabSessionState>()
        val processedIds = mutableSetOf<String>()

        val groupsById = groups.associateBy { it.id }
        order.primaryOrder.forEach { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.SingleTab -> {
                    tabMap[item.tabId]?.let {
                        orderedTabs.add(it)
                        processedIds.add(item.tabId)
                    }
                }

                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    groupsById[item.groupId]?.tabIds?.forEach { tabId ->
                        tabMap[tabId]?.let {
                            orderedTabs.add(it)
                            processedIds.add(tabId)
                        }
                    }
                }
            }
        }

        tabs.forEach { tab ->
            if (!processedIds.contains(tab.id)) {
                orderedTabs.add(tab)
            }
        }

        return orderedTabs
    }

    fun updateTabs(tabs: List<TabSessionState>, selectedTabId: String?) {
        _currentProfileId.value?.let { profileId ->
            loadJob?.cancel()
            loadTabsForProfile(profileId, tabs, selectedTabId)
        }
    }

    fun toggleGroupExpanded(groupId: String) {
        viewModelScope.launch {
            try {
                groupManager.toggleGroupCollapsed(groupId)
                _currentProfileId.value?.let { profileId ->
                    saveCurrentOrder(profileId)
                }
            } catch (e: Exception) {
                Log.e("TabViewModel", "Error toggling group collapsed state", e)
            }
        }
    }
    
    fun getGroup(groupId: String): TabGroupData? {
        return groupManager.getGroup(groupId)
    }

    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            groupManager.renameGroup(groupId, newName)
        }
    }

    fun changeGroupColor(groupId: String, color: Int) {
        viewModelScope.launch {
            groupManager.changeGroupColor(groupId, color)
        }
    }

    fun ungroupAll(groupId: String) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                val group = groupManager.getGroup(groupId)
                group?.tabIds?.forEach { tabId ->
                    groupManager.removeTabFromGroup(tabId, notifyChange = true)
                }

                saveCurrentOrder(profileId)
            }
        }
    }

    private suspend fun saveCurrentOrder(profileId: String) {
        val currentOrder = orderManager.currentOrder.value

        if (currentOrder != null && currentOrder.profileId == profileId) {
            val groupsById = _groups.value.associateBy { it.id }
            val existingTabIds = mutableSetOf<String>()

            val updatedItems = currentOrder.primaryOrder.mapNotNull { item ->
                when (item) {
                    is UnifiedTabOrder.OrderItem.TabGroup -> {
                        val group = groupsById[item.groupId]
                        if (group != null) {
                            existingTabIds.addAll(group.tabIds)
                            UnifiedTabOrder.OrderItem.TabGroup(
                                groupId = group.id,
                                groupName = group.name,
                                color = group.color,
                                isExpanded = _expandedGroups.value.contains(group.id),
                                tabIds = group.tabIds
                            )
                        } else {
                            null // Remove group if it no longer exists
                        }
                    }

                    is UnifiedTabOrder.OrderItem.SingleTab -> {
                        val tabExists = _tabs.value.any { it.id == item.tabId }
                        val inGroup = _groups.value.any { group -> group.tabIds.contains(item.tabId) }
                        if (tabExists && !inGroup) {
                            existingTabIds.add(item.tabId)
                            item
                        } else {
                            null // Remove if tab doesn't exist or is now in a group
                        }
                    }
                }
            }.toMutableList()

            _groups.value.forEach { group ->
                val exists = updatedItems.any {
                    it is UnifiedTabOrder.OrderItem.TabGroup && it.groupId == group.id
                }
                if (!exists) {
                    updatedItems.add(
                        UnifiedTabOrder.OrderItem.TabGroup(
                            groupId = group.id,
                            groupName = group.name,
                            color = group.color,
                            isExpanded = true,
                            tabIds = group.tabIds
                        )
                    )
                    existingTabIds.addAll(group.tabIds)
                }
            }

            _tabs.value.forEach { tab ->
                val inGroup = _groups.value.any { group -> group.tabIds.contains(tab.id) }
                if (!existingTabIds.contains(tab.id) && !inGroup) {
                    updatedItems.add(UnifiedTabOrder.OrderItem.SingleTab(tab.id))
                }
            }

            orderManager.saveOrder(
                currentOrder.copy(
                    primaryOrder = updatedItems,
                    lastModified = System.currentTimeMillis()
                )
            )
        } else {
            val items = mutableListOf<UnifiedTabOrder.OrderItem>()
            val processedTabs = mutableSetOf<String>()

            _groups.value.forEach { group ->
                items.add(
                    UnifiedTabOrder.OrderItem.TabGroup(
                        groupId = group.id,
                        groupName = group.name,
                        color = group.color,
                        isExpanded = true,
                        tabIds = group.tabIds
                    )
                )
                processedTabs.addAll(group.tabIds)
            }

            _tabs.value.forEach { tab ->
                if (!processedTabs.contains(tab.id)) {
                    items.add(UnifiedTabOrder.OrderItem.SingleTab(tab.id))
                }
            }

            val order = UnifiedTabOrder(
                profileId = profileId,
                primaryOrder = items,
                lastModified = System.currentTimeMillis()
            )

            orderManager.saveOrder(order)
        }
    }

    fun createGroup(tabIds: List<String>, name: String? = null, contextId: String? = null) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                groupManager.createGroup(
                    tabIds = tabIds,
                    name = name,
                    profileId = profileId,
                    contextId = contextId
                )

                saveCurrentOrder(profileId)
            }
        }
    }

    fun addTabToGroup(tabId: String, groupId: String) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                groupManager.addTabToGroup(tabId, groupId)

                saveCurrentOrder(profileId)
            }
        }
    }

    fun removeTabFromGroup(tabId: String) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                groupManager.removeTabFromGroup(tabId)

                saveCurrentOrder(profileId)

                TabSheetStateManager.notifyTabSheetDismissed()
            }
        }
    }

    fun mergeGroups(sourceGroupId: String, targetGroupId: String) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                val sourceGroup = groupManager.getGroup(sourceGroupId)
                sourceGroup?.tabIds?.forEach { tabId ->
                    groupManager.removeTabFromGroup(tabId, notifyChange = true)
                    groupManager.addTabToGroup(tabId, targetGroupId, notifyChange = true)
                }

                saveCurrentOrder(profileId)
            }
        }
    }

    fun reorderTabInGroup(draggedTabId: String, targetTabId: String, groupId: String, insertAfter: Boolean = true) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                val group = groupManager.getGroup(groupId) ?: return@launch
                val tabIds = group.tabIds.toMutableList()

                val fromIndex = tabIds.indexOf(draggedTabId)
                val toIndex = tabIds.indexOf(targetTabId)

                if (fromIndex != -1 && toIndex != -1) {
                    tabIds.removeAt(fromIndex)

                    val adjustedToIndex = tabIds.indexOf(targetTabId)

                    val insertIndex = if (insertAfter) {
                        adjustedToIndex + 1
                    } else {
                        adjustedToIndex
                    }

                    tabIds.add(insertIndex, draggedTabId)

                    groupManager.updateGroup(
                        groupId = groupId,
                        name = group.name,
                        color = group.color,
                        tabIds = tabIds
                    )

                    saveCurrentOrder(profileId)
                }
            }
        }
    }

    fun reorderGroup(groupId: String, targetIndex: Int) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                orderManager.loadOrder(profileId)
                val current = orderManager.currentOrder.value ?: return@launch

                val groupIndex = current.primaryOrder.indexOfFirst {
                    it is UnifiedTabOrder.OrderItem.TabGroup && it.groupId == groupId
                }

                if (groupIndex != -1 && groupIndex != targetIndex) {
                    val size = current.primaryOrder.size
                    val validTargetIndex = targetIndex.coerceIn(0, size - 1)
                    if (groupIndex != validTargetIndex) {
                        orderManager.reorderItem(groupIndex, validTargetIndex)
                        saveCurrentOrder(profileId)
                    }
                }
            }
        }
    }

    fun moveTabToPosition(tabId: String, targetIndex: Int) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                orderManager.loadOrder(profileId)
                val current = orderManager.currentOrder.value ?: return@launch

                var tabIndex = -1
                var isInGroup = false

                current.primaryOrder.forEachIndexed { index, item ->
                    when (item) {
                        is UnifiedTabOrder.OrderItem.SingleTab -> {
                            if (item.tabId == tabId) {
                                tabIndex = index
                            }
                        }

                        is UnifiedTabOrder.OrderItem.TabGroup -> {
                            if (tabId in item.tabIds) {
                                tabIndex = index
                                isInGroup = true
                            }
                        }
                    }
                }

                if (tabIndex != -1) {
                    val size = current.primaryOrder.size
                    val validTargetIndex = targetIndex.coerceIn(0, size)

                    if (isInGroup && tabIndex != validTargetIndex) {
                        orderManager.removeTabFromGroup(tabId, validTargetIndex)
                        saveCurrentOrder(profileId)
                    } else if (!isInGroup && tabIndex != validTargetIndex) {
                        orderManager.reorderItem(tabIndex, validTargetIndex)
                        saveCurrentOrder(profileId)
                    }
                }
            }
        }
    }

    fun duplicateTab(tabId: String) {
        viewModelScope.launch {
            val tab = _tabs.value.find { it.id == tabId }
            if (tab == null) {
                Log.e("TabViewModel", "Cannot duplicate - tab not found: $tabId")
                return@launch
            }

            val components = context.components
            val newTabId = components.tabsUseCases.addTab(
                url = tab.content.url,
                private = tab.content.private,
                contextId = tab.contextId,
                selectTab = false,
            )

            _groups.value.find { group -> group.tabIds.contains(tabId) }?.let { group ->
                groupManager.addTabToGroup(group.id, newTabId)
            }

            components.tabsUseCases.selectTab(newTabId)
        }
    }

    fun closeTab(tabId: String, showUndo: Boolean = true) {
        viewModelScope.launch {
            val tab = _tabs.value.find { it.id == tabId } ?: return@launch
            val tabsUseCases = context.components.tabsUseCases

            tabsUseCases.removeTab(tabId)
            onTabRemove?.invoke(tabId)

            if (showUndo) {
                GlobalSnackbarManager.getInstance().showUndoSnackbar(
                    message = "Tab closed",
                    onUndo = {
                        tabsUseCases.undo()
                    },
                    onConfirm = {
                    }
                )
            }
        }
    }

    fun pinGroup(groupId: String) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                reorderGroup(groupId, 0)
            }
        }
    }

    fun closeAllTabsInGroup(groupId: String) {
        viewModelScope.launch {
            val group = _groups.value.find { it.id == groupId } ?: return@launch
        }
    }

    fun createNewTabInGroup(groupId: String, contextId: String?) {
        viewModelScope.launch {
            try {
                val prefs = UserPreferences(context)
                
                val newTabId = context.components.tabsUseCases.addTab(
                    url = prefs.getHomepageUrl(),
                    contextId = contextId,
                    selectTab = false
                )

                delay(100) // Wait for tab to be fully created
                addTabToGroup(newTabId, groupId)
            } catch (e: Exception) {
                Log.e("TabViewModel", "Failed to create new tab in group", e)
            }
        }
    }

    fun moveGroupToProfile(groupId: String, profileId: String) {
        viewModelScope.launch {
            val group = groupManager.getGroup(groupId) ?: return@launch

            val newContextId = contextIdForProfile(profileId)

            groupManager.updateGroup(
                groupId = groupId,
                name = group.name,
                color = group.color,
                tabIds = group.tabIds,
                contextId = newContextId
            )

            _currentProfileId.value?.let { currentProfile ->
                refreshGroupsForProfile(currentProfile)
            }
        }
    }

    fun ungroupAllTabs(groupId: String) {
        ungroupAll(groupId)
    }

    fun addTabToGroup(tabId: String, groupId: String, fromGroupId: String?) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                if (fromGroupId != null) {
                    groupManager.removeTabFromGroup(tabId, notifyChange = false)
                }

                groupManager.addTabToGroup(tabId, groupId, position = null, notifyChange = true)

                saveCurrentOrder(profileId)
            }
        }
    }

    fun reorderGroup(groupId: String, targetGroupId: String) {
        viewModelScope.launch {
            _currentProfileId.value?.let { profileId ->
                val order = orderManager.currentOrder.value ?: return@launch

                val fromIndex = order.primaryOrder.indexOfFirst {
                    it is UnifiedTabOrder.OrderItem.TabGroup && it.groupId == groupId
                }
                val toIndex = order.primaryOrder.indexOfFirst {
                    it is UnifiedTabOrder.OrderItem.TabGroup && it.groupId == targetGroupId
                }

                if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                    orderManager.reorderItem(fromIndex, toIndex)
                    saveCurrentOrder(profileId)
                }
            }
        }
    }

    fun selectTab(tabId: String) {
        _selectedTabId.value = tabId
    }
}
