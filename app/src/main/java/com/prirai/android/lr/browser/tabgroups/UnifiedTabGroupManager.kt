package com.prirai.android.lr.browser.tabgroups

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import androidx.core.net.toUri
import android.util.Log
import com.prirai.android.lr.theme.ColorConstants

class UnifiedTabGroupManager private constructor(private val context: Context) {
    private val database = TabGroupDatabase.getInstance(context)
    private val dao = database.tabGroupDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val groupsCache = ConcurrentHashMap<String, TabGroupData>()

    private val tabToGroupMap = ConcurrentHashMap<String, String>()

    private val membershipMutex = Mutex()

    private val _groupsState = MutableStateFlow<List<TabGroupData>>(emptyList())

    private val _groupEvents = MutableSharedFlow<GroupEvent>(replay = 0)
    val groupEvents: SharedFlow<GroupEvent> = _groupEvents.asSharedFlow()

    companion object {
        private val AVAILABLE_COLORS = listOf(
            0xFFE57373.toInt(), // Red
            0xFF81C784.toInt(), // Green
            0xFF64B5F6.toInt(), // Blue
            0xFFFFB74D.toInt(), // Orange
            0xFFBA68C8.toInt(), // Purple
            0xFFF06292.toInt(), // Pink
            0xFF4DD0E1.toInt(), // Cyan
            0xFFFFF176.toInt()  // Yellow
        )

        private fun nextAvailableColor(existingGroups: List<TabGroupData>): Int {
            val used = existingGroups.map { it.color }.toSet()
            AVAILABLE_COLORS.firstOrNull { it !in used }?.let { return it }
            return AVAILABLE_COLORS[existingGroups.size % AVAILABLE_COLORS.size]
        }

        @Volatile
        private var instance: UnifiedTabGroupManager? = null

        fun getInstance(context: Context): UnifiedTabGroupManager {
            return instance ?: synchronized(this) {
                instance ?: UnifiedTabGroupManager(context.applicationContext).also { 
                    instance = it
                    it.initialize()
                }
            }
        }
    }

    private fun initialize() {
        scope.launch {
            loadGroupsFromDatabase()
        }
    }

    private suspend fun loadGroupsFromDatabase() = membershipMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val groups = dao.getAllActiveGroups().first()
                val groupDataList = mutableListOf<TabGroupData>()

                for (dbGroup in groups) {
                    val tabIds = dao.getTabIdsInGroup(dbGroup.id)
                    val groupData = TabGroupData(
                        id = dbGroup.id,
                        name = dbGroup.name,
                        color = parseColor(dbGroup.color),
                        tabIds = tabIds,
                        createdAt = dbGroup.createdAt,
                        isCollapsed = dbGroup.isCollapsed,
                        contextId = dbGroup.contextId
                    )
                    groupDataList.add(groupData)
                }

                val seenTabs = mutableSetOf<String>()
                val healed = groupDataList
                    .sortedBy { it.createdAt }
                    .map { group ->
                        var changed = false
                        val kept = group.tabIds.filter { tabId ->
                            if (tabId in seenTabs) {
                                runCatching { dao.removeTabFromGroup(tabId, group.id) }
                                changed = true
                                false
                            } else {
                                seenTabs.add(tabId)
                                true
                            }
                        }
                        if (changed) group.copy(tabIds = kept) else group
                    }

                groupsCache.clear()
                tabToGroupMap.clear()

                healed.forEach { groupData ->
                    groupsCache[groupData.id] = groupData
                    groupData.tabIds.forEach { tabId ->
                        tabToGroupMap[tabId] = groupData.id
                    }
                }

                _groupsState.value = healed
            } catch (e: Exception) {
                Log.e("UnifiedTabGroupManager", "Failed to load groups from database", e)
            }
        }
    }

    suspend fun createGroup(
        tabIds: List<String>,
        name: String? = null,
        color: Int? = null,
        profileId: String? = null,
        contextId: String? = null
    ): TabGroupData = membershipMutex.withLock {
        withContext(Dispatchers.IO) {
            if (tabIds.isEmpty()) {
                throw IllegalArgumentException("Cannot create group with no tabs")
            }

            tabIds.forEach { tabId ->
                removeTabFromGroupLocked(tabId, notifyChange = false)
            }

        val groupId = UUID.randomUUID().toString()
        val groupName = name ?: ""
        val groupColor = color ?: nextAvailableColor(groupsCache.values.toList())
        val now = System.currentTimeMillis()

        val group = TabGroup(
            id = groupId,
            name = groupName,
            color = colorToString(groupColor),
            createdAt = now,
            isActive = true,
            contextId = contextId
        )

        dao.insertGroup(group)

        tabIds.forEachIndexed { index, tabId ->
            val member = TabGroupMember(
                tabId = tabId,
                groupId = groupId,
                position = index
            )
            dao.insertTabGroupMember(member)
        }

        val groupData = TabGroupData(
            id = groupId,
            name = groupName,
            color = groupColor,
            tabIds = tabIds,
            createdAt = now,
            contextId = contextId
        )

        groupsCache[groupId] = groupData
        tabIds.forEach { tabId ->
            tabToGroupMap[tabId] = groupId
        }

        emitStateUpdate()
        _groupEvents.emit(GroupEvent.GroupCreated(groupData))

        groupData
        }
    }

    suspend fun addTabToGroup(
        tabId: String,
        groupId: String,
        position: Int? = null,
        notifyChange: Boolean = true
    ): Boolean = membershipMutex.withLock {
        withContext(Dispatchers.IO) {
            addTabToGroupLocked(tabId, groupId, position, notifyChange)
        }
    }

    private suspend fun addTabToGroupLocked(
        tabId: String,
        groupId: String,
        position: Int? = null,
        notifyChange: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val group = groupsCache[groupId] ?: return@withContext false

        if (group.tabIds.contains(tabId)) {
            return@withContext true
        }

        removeTabFromGroupLocked(tabId, notifyChange = false)

        val insertPosition = position ?: group.tabIds.size
        val newTabIds = group.tabIds.toMutableList().apply {
            add(insertPosition.coerceIn(0, size), tabId)
        }

        val member = TabGroupMember(
            tabId = tabId,
            groupId = groupId,
            position = insertPosition
        )
        dao.insertTabGroupMember(member)

        val updatedGroup = group.copy(tabIds = newTabIds)
        groupsCache[groupId] = updatedGroup
        tabToGroupMap[tabId] = groupId

        if (notifyChange) {
            emitStateUpdate()
            _groupEvents.emit(GroupEvent.TabAddedToGroup(tabId, groupId))
        }

        true
    }

    suspend fun removeTabFromGroup(
        tabId: String,
        notifyChange: Boolean = true
    ): Boolean = membershipMutex.withLock {
        withContext(Dispatchers.IO) {
            removeTabFromGroupLocked(tabId, notifyChange)
        }
    }

    private suspend fun removeTabFromGroupLocked(
        tabId: String,
        notifyChange: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val groupId = tabToGroupMap[tabId] ?: return@withContext false
        val group = groupsCache[groupId] ?: return@withContext false

        dao.removeTabFromGroup(tabId, groupId)

        val newTabIds = group.tabIds - tabId
        tabToGroupMap.remove(tabId)

        if (newTabIds.isEmpty()) {
            deleteGroupLocked(groupId, notifyChange = false)
        } else {
            val updatedGroup = group.copy(tabIds = newTabIds)
            groupsCache[groupId] = updatedGroup
        }

        if (notifyChange) {
            emitStateUpdate()
            _groupEvents.emit(GroupEvent.TabRemovedFromGroup(tabId, groupId))
        }

        true
    }

    suspend fun renameGroup(groupId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val group = groupsCache[groupId] ?: return@withContext false
        val dbGroup = dao.getGroupById(groupId) ?: return@withContext false

        val updatedDbGroup = dbGroup.copy(name = newName)
        dao.updateGroup(updatedDbGroup)

        val updatedGroup = group.copy(name = newName)
        groupsCache[groupId] = updatedGroup

        emitStateUpdate()
        _groupEvents.emit(GroupEvent.GroupRenamed(groupId, newName))

        true
    }

    suspend fun changeGroupColor(groupId: String, newColor: Int): Boolean = withContext(Dispatchers.IO) {
        val group = groupsCache[groupId] ?: return@withContext false
        val dbGroup = dao.getGroupById(groupId) ?: return@withContext false

        val updatedDbGroup = dbGroup.copy(color = colorToString(newColor))
        dao.updateGroup(updatedDbGroup)

        val updatedGroup = group.copy(color = newColor)
        groupsCache[groupId] = updatedGroup

        emitStateUpdate()
        _groupEvents.emit(GroupEvent.GroupColorChanged(groupId, newColor))

        true
    }

    suspend fun toggleGroupCollapsed(groupId: String): Boolean = withContext(Dispatchers.IO) {
        val group = groupsCache[groupId] ?: return@withContext false
        val dbGroup = dao.getGroupById(groupId) ?: return@withContext false

        val newCollapsedState = !group.isCollapsed
        val updatedDbGroup = dbGroup.copy(isCollapsed = newCollapsedState)
        dao.updateGroup(updatedDbGroup)

        val updatedGroup = group.copy(isCollapsed = newCollapsedState)
        groupsCache[groupId] = updatedGroup

        emitStateUpdate()
        _groupEvents.emit(GroupEvent.GroupCollapsedChanged(groupId, newCollapsedState))

        true
    }

    suspend fun updateGroup(
        groupId: String,
        name: String? = null,
        color: Int? = null,
        tabIds: List<String>? = null,
        contextId: String? = null
    ): Boolean = membershipMutex.withLock {
        withContext(Dispatchers.IO) { updateGroupLocked(groupId, name, color, tabIds, contextId) }
    }

    private suspend fun updateGroupLocked(
        groupId: String,
        name: String? = null,
        color: Int? = null,
        tabIds: List<String>? = null,
        contextId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val group = groupsCache[groupId] ?: return@withContext false
        val dbGroup = dao.getGroupById(groupId) ?: return@withContext false

        val updatedName = name ?: group.name
        val updatedColor = color ?: group.color
        val updatedTabIds = tabIds ?: group.tabIds
        val updatedContextId = contextId ?: group.contextId

        val updatedDbGroup = dbGroup.copy(
            name = updatedName,
            color = colorToString(updatedColor),
            contextId = updatedContextId
        )
        dao.updateGroup(updatedDbGroup)

        if (tabIds != null) {
            dao.removeAllTabsFromGroup(groupId)
            tabIds.forEachIndexed { index, tabId ->
                val member = TabGroupMember(
                    tabId = tabId,
                    groupId = groupId,
                    position = index
                )
                dao.insertTabGroupMember(member)
            }
        }

        val updatedGroup = group.copy(
            name = updatedName,
            color = updatedColor,
            tabIds = updatedTabIds,
            contextId = updatedContextId
        )
        groupsCache[groupId] = updatedGroup

        if (tabIds != null) {
            group.tabIds.forEach { tabToGroupMap.remove(it) }
            updatedTabIds.forEach { tabToGroupMap[it] = groupId }
        }

        emitStateUpdate()
        _groupEvents.emit(GroupEvent.GroupUpdated(groupId))

        true
    }

    suspend fun deleteGroup(groupId: String, notifyChange: Boolean = true): Boolean =
        membershipMutex.withLock {
            withContext(Dispatchers.IO) { deleteGroupLocked(groupId, notifyChange) }
        }

    private suspend fun deleteGroupLocked(groupId: String, notifyChange: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val group = groupsCache[groupId] ?: return@withContext false

        dao.removeAllTabsFromGroup(groupId)
        dao.markGroupAsInactive(groupId)

        group.tabIds.forEach { tabId ->
            tabToGroupMap.remove(tabId)
        }
        groupsCache.remove(groupId)

        if (notifyChange) {
            emitStateUpdate()
            _groupEvents.emit(GroupEvent.GroupDeleted(groupId))
        }

        true
    }

    fun getGroupForTab(tabId: String): TabGroupData? {
        val groupId = tabToGroupMap[tabId] ?: return null
        return groupsCache[groupId]
    }

    fun getGroup(groupId: String): TabGroupData? {
        return groupsCache[groupId]
    }

    fun getAllGroups(): List<TabGroupData> {
        return groupsCache.values.sortedBy { it.createdAt }
    }

    suspend fun mergeGroups(sourceGroupId: String, targetGroupId: String): Boolean =
        membershipMutex.withLock {
            if (sourceGroupId == targetGroupId) return@withLock false

            val sourceGroup = groupsCache[sourceGroupId] ?: return@withLock false

            withContext(Dispatchers.IO) {
            sourceGroup.tabIds.forEach { tabId ->
                val member = TabGroupMember(
                    tabId = tabId,
                    groupId = targetGroupId,
                    position = groupsCache[targetGroupId]?.tabIds?.size ?: 0
                )
                dao.insertTabGroupMember(member)
                tabToGroupMap[tabId] = targetGroupId
            }

            dao.removeAllTabsFromGroup(sourceGroupId)
            dao.markGroupAsInactive(sourceGroupId)
            groupsCache.remove(sourceGroupId)

            val targetGroup = groupsCache[targetGroupId]
            if (targetGroup != null) {
                val newTabIds = targetGroup.tabIds + sourceGroup.tabIds
                groupsCache[targetGroupId] = targetGroup.copy(tabIds = newTabIds)
            }

            emitStateUpdate()
            _groupEvents.emit(GroupEvent.GroupsMerged(sourceGroupId, targetGroupId))
            }
            true
        }

    suspend fun onTabClosed(tabId: String) {
        removeTabFromGroup(tabId)
    }

    suspend fun handleNewTabFromLink(
        newTabId: String,
        newTabUrl: String,
        sourceTabId: String?,
        sourceTabUrl: String?
    ) = withContext(Dispatchers.IO) {
        if (sourceTabId == null || sourceTabUrl == null) {
            return@withContext
        }

        if (newTabUrl.startsWith("about:") || newTabUrl.startsWith("chrome:")) {
            return@withContext
        }

        val sourceGroupId = tabToGroupMap[sourceTabId]

        if (sourceGroupId != null) {
            addTabToGroup(newTabId, sourceGroupId)
        } else {
            createGroup(tabIds = listOf(sourceTabId, newTabId))
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            url.toUri().host?.replace("www.", "") ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private suspend fun emitStateUpdate() {
        _groupsState.value = groupsCache.values.sortedBy { it.createdAt }
    }

    private fun parseColor(colorString: String): Int {
        return ColorConstants.TabGroups.parseColor(colorString)
    }

    private fun colorToString(color: Int): String {
        return ColorConstants.TabGroups.getColorName(color)
    }
}

data class TabGroupData(
    val id: String,
    val name: String,
    val color: Int,
    val tabIds: List<String>,
    val createdAt: Long,
    val isCollapsed: Boolean = false,
    val contextId: String? = null
) {
    val tabCount: Int get() = tabIds.size
    val isEmpty: Boolean get() = tabIds.isEmpty()
    val isNotEmpty: Boolean get() = tabIds.isNotEmpty()
}

sealed class GroupEvent {
    data class GroupCreated(val group: TabGroupData) : GroupEvent()
    data class GroupDeleted(val groupId: String) : GroupEvent()
    data class GroupRenamed(val groupId: String, val newName: String) : GroupEvent()
    data class GroupColorChanged(val groupId: String, val newColor: Int) : GroupEvent()
    data class GroupCollapsedChanged(val groupId: String, val isCollapsed: Boolean) : GroupEvent()
    data class GroupUpdated(val groupId: String) : GroupEvent()
    data class TabAddedToGroup(val tabId: String, val groupId: String) : GroupEvent()
    data class TabRemovedFromGroup(val tabId: String, val groupId: String) : GroupEvent()
    data class GroupsMerged(val sourceGroupId: String, val targetGroupId: String) : GroupEvent()
}
