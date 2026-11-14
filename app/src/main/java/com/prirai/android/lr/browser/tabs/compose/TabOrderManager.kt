package com.prirai.android.lr.browser.tabs.compose

import android.content.Context
import com.prirai.android.lr.browser.tabgroups.UnifiedTabGroupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.prirai.android.lr.ext.components
import android.util.Log
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.tabgroups.TabGroupData
import mozilla.components.browser.state.state.SessionState

class TabOrderManager private constructor(
    private val context: Context,
    private val groupManager: UnifiedTabGroupManager
) {
    companion object {
        @Volatile
        private var instance: TabOrderManager? = null
        
        fun getInstance(context: Context, groupManager: UnifiedTabGroupManager): TabOrderManager {
            return instance ?: synchronized(this) {
                instance ?: TabOrderManager(context.applicationContext, groupManager).also { instance = it }
            }
        }
    }
    
    private val prefs by lazy {
        context.getSharedPreferences("tab_order", Context.MODE_PRIVATE)
    }
    
    private val _currentOrder = MutableStateFlow<UnifiedTabOrder?>(null)
    val currentOrder: StateFlow<UnifiedTabOrder?> = _currentOrder.asStateFlow()
    
    suspend fun loadOrder(profileId: String): UnifiedTabOrder {
        val order = prefs.getString("order_$profileId", null)
            ?.let { UnifiedTabOrder.fromJson(it) }
            ?: UnifiedTabOrder(profileId, emptyList())

        val cleanedOrder = cleanupOrphanedGroups(order)
        _currentOrder.value = cleanedOrder
        return cleanedOrder
    }
    
    private suspend fun cleanupOrphanedGroups(order: UnifiedTabOrder): UnifiedTabOrder {
        val allGroups = groupManager.getAllGroups()
        val validGroupIds = allGroups.map { it.id }.toSet()
        
        val cleanedPrimaryOrder = order.primaryOrder.filter { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    validGroupIds.contains(item.groupId)
                }
                is UnifiedTabOrder.OrderItem.SingleTab -> true
            }
        }
        
        return if (cleanedPrimaryOrder.size != order.primaryOrder.size) {
            order.copy(primaryOrder = cleanedPrimaryOrder).also { cleaned ->
                saveOrder(cleaned)
            }
        } else {
            order
        }
    }
    
    suspend fun saveOrder(order: UnifiedTabOrder) {
        prefs.edit().putString("order_${order.profileId}", order.toJson()).apply()
        _currentOrder.value = order.copy(lastModified = System.currentTimeMillis())
    }
    
    suspend fun reorderItem(fromIndex: Int, toIndex: Int) {
        val current = _currentOrder.value ?: return
        if (fromIndex == toIndex) return
        
        val newOrder = current.primaryOrder.toMutableList()
        val size = newOrder.size
        
        if (fromIndex < 0 || fromIndex >= size) {
            Log.e("TabOrderManager", "Invalid fromIndex: $fromIndex, size: $size")
            return
        }
        if (toIndex < 0 || toIndex >= size) {
            Log.e("TabOrderManager", "Invalid toIndex: $toIndex, size: $size")
            return
        }
        
        val item = newOrder.removeAt(fromIndex)
        newOrder.add(toIndex, item)
        saveOrder(current.copy(primaryOrder = newOrder))
        syncToGroupManager()
    }

    suspend fun createGroup(tabIds: List<String>, groupName: String, color: Int) {
        val current = _currentOrder.value ?: return
        if (tabIds.isEmpty()) return
        
        val groupId = "group_${System.currentTimeMillis()}"
        
        val firstTabIndex = current.primaryOrder.indexOfFirst { item ->
            item is UnifiedTabOrder.OrderItem.SingleTab && item.tabId == tabIds.first()
        }
        
        val remainingOrder = current.primaryOrder.filterNot { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.SingleTab -> item.tabId in tabIds
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    val remaining = item.tabIds.filterNot { it in tabIds }
                    if (remaining.isEmpty()) return@filterNot true // Remove empty group
                    false
                }
            }
        }.map { item ->
            if (item is UnifiedTabOrder.OrderItem.TabGroup) {
                val remaining = item.tabIds.filterNot { it in tabIds }
                if (remaining.size == 1) {
                    UnifiedTabOrder.OrderItem.SingleTab(remaining.first())
                } else {
                    item.copy(tabIds = remaining)
                }
            } else {
                item
            }
        }.toMutableList()
        
        val newGroup = UnifiedTabOrder.OrderItem.TabGroup(
            groupId = groupId,
            groupName = groupName,
            color = color,
            isExpanded = true,
            tabIds = tabIds
        )
        
        val insertPos = firstTabIndex.coerceAtLeast(0).coerceAtMost(remainingOrder.size)
        remainingOrder.add(insertPos, newGroup)
        
        saveOrder(current.copy(primaryOrder = remainingOrder))
        syncToGroupManager()
    }
    
    suspend fun addTabToGroup(tabId: String, groupId: String, positionInGroup: Int? = null) {
        val current = _currentOrder.value ?: return
        
        val newOrder = current.primaryOrder.mapNotNull { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.SingleTab -> 
                    if (item.tabId == tabId) null else item
                    
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    if (item.groupId == groupId) {
                        val newTabIds = item.tabIds.toMutableList()
                        val insertIndex = positionInGroup?.coerceIn(0, newTabIds.size) ?: newTabIds.size
                        newTabIds.add(insertIndex, tabId)
                        item.copy(tabIds = newTabIds)
                    } else {
                        val filtered = item.tabIds.filter { it != tabId }
                        when {
                            filtered.isEmpty() -> null // Remove empty group
                            filtered.size == 1 -> UnifiedTabOrder.OrderItem.SingleTab(filtered.first()) // Ungroup
                            else -> item.copy(tabIds = filtered)
                        }
                    }
                }
            }
        }
        
        saveOrder(current.copy(primaryOrder = newOrder))
        syncToGroupManager()
    }
    
    suspend fun removeTabFromGroup(tabId: String, newPosition: Int? = null) {
        val current = _currentOrder.value ?: return
        
        val newOrder = current.primaryOrder.flatMap { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    if (tabId in item.tabIds) {
                        val remainingTabs = item.tabIds.filter { it != tabId }
                        when (remainingTabs.size) {
                            0 -> emptyList() // Remove empty group
                            1 -> listOf(UnifiedTabOrder.OrderItem.SingleTab(remainingTabs.first())) // Ungroup
                            else -> listOf(item.copy(tabIds = remainingTabs))
                        }
                    } else {
                        listOf(item)
                    }
                }
                else -> listOf(item)
            }
        }.toMutableList()
        
        val ungroupedTab = UnifiedTabOrder.OrderItem.SingleTab(tabId)
        val insertPos = newPosition?.coerceIn(0, newOrder.size) ?: newOrder.size
        newOrder.add(insertPos, ungroupedTab)
        
        saveOrder(current.copy(primaryOrder = newOrder))
        syncToGroupManager()
    }
    
    suspend fun removeTab(tabId: String) {
        val current = _currentOrder.value ?: return

        val newOrder = current.primaryOrder.mapNotNull { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.SingleTab -> {
                    if (item.tabId == tabId) null else item
                }
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    val remainingTabs = item.tabIds.filter { it != tabId }
                    when (remainingTabs.size) {
                        0 -> null // Remove empty group
                        else -> item.copy(tabIds = remainingTabs) // Keep group with remaining tabs
                    }
                }
            }
        }

        saveOrder(current.copy(primaryOrder = newOrder))

        try {
            syncToGroupManager()
        } catch (e: Exception) {
            Log.e("TabOrderManager", "Failed to sync group manager after removeTab: ${e.message}", e)
        }
    }
    
    suspend fun expandGroupContainingTab(tabId: String) {
        val current = _currentOrder.value ?: return
        var hasChanges = false
        val newOrder = current.primaryOrder.map { item ->
            if (item is UnifiedTabOrder.OrderItem.TabGroup && tabId in item.tabIds) {
                if (!item.isExpanded) {
                    hasChanges = true
                    item.copy(isExpanded = true)
                } else {
                    item
                }
            } else {
                item
            }
        }
        if (hasChanges) {
            saveOrder(current.copy(primaryOrder = newOrder))
        }
    }

    suspend fun initializeFromCurrentState(profileId: String, tabIds: List<String>, groups: List<TabGroupData>) {
        val items = mutableListOf<UnifiedTabOrder.OrderItem>()
        val processedTabIds = mutableSetOf<String>()
        
        val groupPositions = groups.mapNotNull { group ->
            val validTabIds = group.tabIds.filter { it in tabIds }
            if (validTabIds.isEmpty()) return@mapNotNull null
            
            val firstTabPosition = tabIds.indexOf(validTabIds.first())
            if (firstTabPosition == -1) return@mapNotNull null
            
            firstTabPosition to UnifiedTabOrder.OrderItem.TabGroup(
                groupId = group.id,
                groupName = group.name,
                color = group.color,
                isExpanded = true,
                tabIds = validTabIds
            )
        }.toMap()
        
        val groupedTabIds = groups.flatMap { it.tabIds }.toSet()
        
        tabIds.forEachIndexed { index, tabId ->
            if (tabId in processedTabIds) return@forEachIndexed
            
            if (index in groupPositions) {
                val groupItem = groupPositions[index]!!
                items.add(groupItem)
                groupItem.tabIds.forEach { processedTabIds.add(it) }
            } else if (tabId !in groupedTabIds) {
                items.add(UnifiedTabOrder.OrderItem.SingleTab(tabId))
                processedTabIds.add(tabId)
            }
        }
        
        val order = UnifiedTabOrder(
            profileId = profileId,
            primaryOrder = items,
            lastModified = System.currentTimeMillis()
        )
        saveOrder(order)
    }
    
    suspend fun syncToGroupManager() {
        val current = _currentOrder.value ?: return
        
        val contextId = BrowserProfile
            .contextIdFor(current.profileId, isPrivate = current.profileId == "private")

        current.primaryOrder.forEach { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.SingleTab -> {
                }
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    val existingGroup = groupManager.getAllGroups().find { it.id == item.groupId }
                    if (existingGroup != null) {
                        val currentTabIds = existingGroup.tabIds.toSet()
                        val newTabIds = item.tabIds.toSet()
                        
                        newTabIds.filter { it !in currentTabIds }.forEach { tabId ->
                            groupManager.addTabToGroup(tabId, item.groupId)
                        }
                        
                        currentTabIds.filter { it !in newTabIds }.forEach { tabId ->
                            groupManager.removeTabFromGroup(tabId)
                        }
                    } else {
                        groupManager.createGroup(
                            tabIds = item.tabIds,
                            name = item.groupName,
                            color = item.color,
                            contextId = contextId
                        )
                    }
                }
            }
        }
    }
    
    suspend fun rebuildOrderForProfile(profileId: String, tabs: List<SessionState>) {
        val allGroups = groupManager.getAllGroups()
        
        val contextId = BrowserProfile
            .contextIdFor(profileId, isPrivate = profileId == "private")

        val profileGroups = allGroups.filter { group ->
            when {
                profileId == "default" ->
                    group.contextId == null || group.contextId == "profile_default"
                else -> group.contextId == contextId
            }
        }
        
        val tabIds = tabs.map { it.id }
        
        val existingOrder = try {
            loadOrder(profileId)
        } catch (e: Exception) {
            UnifiedTabOrder(profileId, emptyList())
        }
        
        val existingPositions = mutableMapOf<String, Int>()
        val existingGroupStates = mutableMapOf<String, Boolean>() // Map groupId to isExpanded
        existingOrder.primaryOrder.forEachIndexed { index, item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.SingleTab -> existingPositions[item.tabId] = index
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    existingPositions["group_${item.groupId}"] = index
                    existingGroupStates[item.groupId] = item.isExpanded
                }
            }
        }
        
        val newOrder = mutableListOf<UnifiedTabOrder.OrderItem>()
        val processedTabIds = mutableSetOf<String>()
        
        val itemsWithPositions = mutableListOf<Pair<Int, UnifiedTabOrder.OrderItem>>()
        
        profileGroups.forEach { group ->
            val validTabIds = group.tabIds.filter { it in tabIds }
            if (validTabIds.isNotEmpty()) {
                val position = existingPositions["group_${group.id}"] ?: Int.MAX_VALUE
                val isExpanded = existingGroupStates[group.id] ?: true
                itemsWithPositions.add(
                    position to UnifiedTabOrder.OrderItem.TabGroup(
                        groupId = group.id,
                        groupName = group.name,
                        color = group.color,
                        isExpanded = isExpanded,
                        tabIds = validTabIds
                    )
                )
                processedTabIds.addAll(validTabIds)
            }
        }
        
        tabIds.forEach { tabId ->
            if (tabId !in processedTabIds) {
                val position = existingPositions[tabId] ?: Int.MAX_VALUE
                itemsWithPositions.add(
                    position to UnifiedTabOrder.OrderItem.SingleTab(tabId)
                )
                processedTabIds.add(tabId)
            }
        }
        
        newOrder.addAll(itemsWithPositions.sortedBy { it.first }.map { it.second })
        
        val updatedOrder = UnifiedTabOrder(
            profileId = profileId,
            primaryOrder = newOrder,
            lastModified = System.currentTimeMillis()
        )
        saveOrder(updatedOrder)
        _currentOrder.value = updatedOrder
    }
}
