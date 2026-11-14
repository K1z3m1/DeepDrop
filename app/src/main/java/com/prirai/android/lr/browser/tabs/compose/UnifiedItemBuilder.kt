package com.prirai.android.lr.browser.tabs.compose

import mozilla.components.browser.state.state.TabSessionState
import com.prirai.android.lr.browser.tabgroups.TabGroupData
import android.util.Log

sealed class UnifiedItem {
    abstract val id: String
    abstract val sortKey: String  // For stable sorting

    data class SingleTab(
        override val id: String,
        override val sortKey: String,
        val tab: TabSessionState,
        val contextId: String?
    ) : UnifiedItem()

    @Deprecated("Use GroupContainer instead")
    data class GroupHeader(
        override val id: String,
        override val sortKey: String,
        val groupId: String,
        val title: String,
        val color: Int,
        val contextId: String?,
        val tabCount: Int,
        val isExpanded: Boolean
    ) : UnifiedItem()

    @Deprecated("Use GroupContainer with children instead")
    data class GroupedTab(
        override val id: String,
        override val sortKey: String,
        val tab: TabSessionState,
        val groupId: String,
        val contextId: String?,
        val isLastInGroup: Boolean
    ) : UnifiedItem()

    @Deprecated("Use GroupContainer instead")
    data class GroupRow(
        override val id: String,
        override val sortKey: String,
        val groupId: String,
        val tabs: List<TabSessionState>,
        val contextId: String?
    ) : UnifiedItem()

    data class GroupContainer(
        override val id: String,
        override val sortKey: String,
        val groupId: String,
        val title: String,
        val color: Int,
        val contextId: String?,
        val isExpanded: Boolean,
        val children: List<TabSessionState>
    ) : UnifiedItem() {
        val tabCount: Int get() = children.size
    }
}

object UnifiedItemBuilder {
    fun buildItems(
        order: UnifiedTabOrder?,
        tabs: List<TabSessionState>,
        groups: List<TabGroupData>,
        expandedGroups: Set<String>
    ): List<UnifiedItem> {
        if (order == null) {
            return buildFallbackItems(tabs, groups, expandedGroups)
        }

        val items = mutableListOf<UnifiedItem>()
        val addedTabIds = mutableSetOf<String>()

        val tabsById = tabs.associateBy { it.id }
        val groupsById = groups.associateBy { it.id }

        order.primaryOrder.forEachIndexed { index, orderItem ->
            when (orderItem) {
                is UnifiedTabOrder.OrderItem.SingleTab -> {
                    val tab = tabsById[orderItem.tabId]
                    if (tab != null && tab.id !in addedTabIds) {
                        items.add(
                            UnifiedItem.SingleTab(
                                id = "tab_${tab.id}",
                                sortKey = "root_$index",
                                tab = tab,
                                contextId = tab.contextId
                            )
                        )
                        addedTabIds.add(tab.id)
                    }
                }

                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    val group = groupsById[orderItem.groupId]
                    if (group != null) {
                        val isExpanded = expandedGroups.contains(group.id)

                        val validTabIds = orderItem.tabIds.filter { tabId ->
                            tabsById.containsKey(tabId) && tabId !in addedTabIds
                        }

                        if (validTabIds.isNotEmpty()) {
                            val groupTabs = validTabIds.mapNotNull { tabsById[it] }
                            items.add(
                                UnifiedItem.GroupContainer(
                                    id = "group_${group.id}",
                                    sortKey = "root_$index",
                                    groupId = group.id,
                                    title = group.name,
                                    color = group.color,
                                    contextId = group.contextId,
                                    isExpanded = isExpanded,
                                    children = groupTabs
                                )
                            )
                            addedTabIds.addAll(validTabIds)
                        }
                    }
                }
            }
        }

        tabs.filter { it.id !in addedTabIds }.forEachIndexed { index, tab ->
            items.add(
                UnifiedItem.SingleTab(
                    id = "tab_${tab.id}",
                    sortKey = "orphan_$index",
                    tab = tab,
                    contextId = tab.contextId
                )
            )
        }

        return items
    }

    private fun buildFallbackItems(
        tabs: List<TabSessionState>,
        groups: List<TabGroupData>,
        expandedGroups: Set<String>
    ): List<UnifiedItem> {
        val items = mutableListOf<UnifiedItem>()
        val addedTabIds = mutableSetOf<String>()

        val tabsById = tabs.associateBy { it.id }

        groups.forEachIndexed { groupIndex, group ->
            val isExpanded = expandedGroups.contains(group.id)
            val validTabIds = group.tabIds.filter { tabsById.containsKey(it) }

            if (validTabIds.isNotEmpty()) {
                val groupTabs = validTabIds.mapNotNull { tabsById[it] }
                items.add(
                    UnifiedItem.GroupContainer(
                        id = "group_${group.id}",
                        sortKey = "group_$groupIndex",
                        groupId = group.id,
                        title = group.name,
                        color = group.color,
                        contextId = group.contextId,
                        isExpanded = isExpanded,
                        children = groupTabs
                    )
                )
                addedTabIds.addAll(validTabIds)
            }
        }

        tabs.filter { it.id !in addedTabIds }.forEachIndexed { index, tab ->
            items.add(
                UnifiedItem.SingleTab(
                    id = "tab_${tab.id}",
                    sortKey = "single_$index",
                    tab = tab,
                    contextId = tab.contextId
                )
            )
        }

        return items
    }

    fun deduplicateItems(items: List<UnifiedItem>): List<UnifiedItem> {
        val seen = mutableSetOf<String>()
        return items.filter { item ->
            if (item.id in seen) {
                Log.w("UnifiedItemBuilder", "Duplicate item ID: ${item.id}")
                false
            } else {
                seen.add(item.id)
                true
            }
        }
    }
}
