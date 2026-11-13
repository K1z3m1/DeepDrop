package com.prirai.android.lr.browser.tabs.compose

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for tab ordering across all views.
 * Represents the hierarchical structure of tabs and groups.
 */
data class UnifiedTabOrder(
    val profileId: String,
    val primaryOrder: List<OrderItem>,
    val lastModified: Long = System.currentTimeMillis()
) {
    sealed class OrderItem {
        data class SingleTab(val tabId: String) : OrderItem()

        data class TabGroup(
            val groupId: String,
            val groupName: String,
            val color: Int,
            val isExpanded: Boolean,
            val tabIds: List<String>
        ) : OrderItem()
    }
    
    /**
     * Get all tab IDs in flat order
     */
    fun getAllTabIds(): List<String> {
        return primaryOrder.flatMap { item ->
            when (item) {
                is OrderItem.SingleTab -> listOf(item.tabId)
                is OrderItem.TabGroup -> item.tabIds
            }
        }
    }
    
    /**
     * Find which group contains a specific tab
     */
    fun findGroupContaining(tabId: String): OrderItem.TabGroup? {
        return primaryOrder.filterIsInstance<OrderItem.TabGroup>()
            .find { tabId in it.tabIds }
    }
    
    /**
     * Get the flat position of a tab (across all groups)
     */
    fun getTabPosition(tabId: String): Int? {
        val allTabs = getAllTabIds()
        return allTabs.indexOf(tabId).takeIf { it >= 0 }
    }
    
    /**
     * Get the position of an item in the primary order
     */
    fun getItemPosition(itemId: String): Int? {
        return primaryOrder.indexOfFirst { item ->
            when (item) {
                is OrderItem.SingleTab -> item.tabId == itemId
                is OrderItem.TabGroup -> item.groupId == itemId
            }
        }.takeIf { it >= 0 }
    }

    /** Serialises this order to JSON (replaces kotlinx.serialization). */
    fun toJson(): String {
        val items = JSONArray()
        primaryOrder.forEach { item ->
            items.put(
                when (item) {
                    is OrderItem.SingleTab -> JSONObject()
                        .put("type", "tab")
                        .put("tabId", item.tabId)
                    is OrderItem.TabGroup -> JSONObject()
                        .put("type", "group")
                        .put("groupId", item.groupId)
                        .put("groupName", item.groupName)
                        .put("color", item.color)
                        .put("isExpanded", item.isExpanded)
                        .put("tabIds", JSONArray(item.tabIds))
                }
            )
        }
        return JSONObject()
            .put("profileId", profileId)
            .put("lastModified", lastModified)
            .put("primaryOrder", items)
            .toString()
    }

    companion object {
        /** Parses JSON written by [toJson]; returns null if it is malformed. */
        fun fromJson(json: String): UnifiedTabOrder? = try {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("primaryOrder")
            val items = ArrayList<OrderItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "group") {
                    val ids = o.getJSONArray("tabIds")
                    items.add(
                        OrderItem.TabGroup(
                            groupId = o.getString("groupId"),
                            groupName = o.getString("groupName"),
                            color = o.getInt("color"),
                            isExpanded = o.optBoolean("isExpanded", true),
                            tabIds = List(ids.length()) { ids.getString(it) }
                        )
                    )
                } else {
                    items.add(OrderItem.SingleTab(o.getString("tabId")))
                }
            }
            UnifiedTabOrder(
                profileId = obj.getString("profileId"),
                primaryOrder = items,
                lastModified = obj.optLong("lastModified", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }
    }
}
