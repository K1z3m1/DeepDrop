package com.prirai.android.lr.browser.bookmark

import android.content.Context
import com.prirai.android.lr.browser.bookmark.items.BookmarkSiteItem
import com.prirai.android.lr.browser.bookmark.repository.BookmarkManager
import mozilla.components.concept.storage.BookmarkInfo
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarksStorage
import java.util.UUID
import com.prirai.android.lr.browser.bookmark.items.BookmarkFolderItem
import com.prirai.android.lr.utils.BookmarkUtils
import mozilla.components.concept.storage.bookmarks.InsertableBookmarkTreeRoot

class CustomBookmarksStorage(context: Context): BookmarksStorage {
    private val manager = BookmarkManager.getInstance(context)

    override suspend fun addFolder(parentGuid: String, title: String, position: UInt?): Result<String> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun addItem(
        parentGuid: String,
        url: String,
        title: String,
        position: UInt?
    ): Result<String> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun addSeparator(parentGuid: String, position: UInt?): Result<String> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override fun cleanup() {
    }

    override suspend fun countBookmarksInTrees(guids: List<String>): UInt {
        var count = 0u
        fun countInFolder(folder: BookmarkFolderItem) {
            folder.list.forEach { item ->
                when (item) {
                    is BookmarkSiteItem -> count++
                    is BookmarkFolderItem -> countInFolder(item)
                }
            }
        }
        countInFolder(manager.root)
        return count
    }

    override suspend fun deleteNode(guid: String): Result<Boolean> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun getBookmark(guid: String): Result<BookmarkNode?> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun getBookmarksWithUrl(url: String): Result<List<BookmarkNode>> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun getRecentBookmarks(
        limit: Int,
        maxAge: Long?,
    ): Result<List<BookmarkNode>> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun getTree(guid: String, recursive: Boolean): Result<BookmarkNode?> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun runMaintenance(dbSizeLimit: UInt) {
        manager.save()
    }

    override suspend fun searchBookmarks(query: String, limit: Int): Result<List<BookmarkNode>> {
        return try {
            val bookmarks: MutableList<BookmarkNode> = emptyList<BookmarkNode>().toMutableList()
            for(i in manager.root.itemList){
                if(i is BookmarkSiteItem){
                    bookmarks.add(BookmarkNode(BookmarkNodeType.ITEM, UUID.randomUUID().toString(), "",
                        0u, i.title, i.url, 0, 0, null))
                }
            }

            val filteredBookmarks = bookmarks.filter { s -> s.title?.contains(query) == true || s.url?.contains(query) == true }.take(limit)
            Result.success(filteredBookmarks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateNode(guid: String, info: BookmarkInfo): Result<Unit> {
        return Result.failure(NotImplementedError("Not yet implemented"))
    }

    override suspend fun insertTree(tree: InsertableBookmarkTreeRoot): Result<String> {
        try {
            fun getStringProp(obj: Any?, names: List<String>): String? {
                if (obj == null) return null
                val cls = obj::class.java
                for (name in names) {
                    try {
                        val getter = cls.methods.firstOrNull { it.parameterCount == 0 && (it.name.equals(name, true) || it.name.equals("get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }, true)) }
                        if (getter != null) {
                            val v = getter.invoke(obj)
                            if (v is String) return v
                        }
                    } catch (_: Exception) { }

                    try {
                        val field = cls.getDeclaredField(name)
                        field.isAccessible = true
                        val v = field.get(obj)
                        if (v is String) return v
                    } catch (_: Exception) { }
                }
                return null
            }

            fun extractChildren(obj: Any?): List<Any> {
                if (obj == null) return emptyList()
                if (obj is Collection<*>) return obj.filterNotNull() as List<Any>
                if (obj.javaClass.isArray) return (obj as Array<*>).filterNotNull() as List<Any>

                val cls = obj::class.java
                val candidateNames = listOf("children", "items", "nodes", "entries", "list", "roots")
                for (name in candidateNames) {
                    try {
                        val getter = cls.methods.firstOrNull { it.parameterCount == 0 && (it.name.equals(name, true) || it.name.equals("get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }, true)) }
                        if (getter != null) {
                            val v = getter.invoke(obj)
                            if (v is Collection<*>) return v.filterNotNull() as List<Any>
                            if (v != null && v.javaClass.isArray) return (v as Array<*>).filterNotNull() as List<Any>
                        }
                    } catch (_: Exception) { }

                    try {
                        val field = cls.getDeclaredField(name)
                        field.isAccessible = true
                        val v = field.get(obj)
                        if (v is Collection<*>) return v.filterNotNull() as List<Any>
                        if (v != null && v.javaClass.isArray) return (v as Array<*>).filterNotNull() as List<Any>
                    } catch (_: Exception) { }
                }

                for (m in cls.methods) {
                    try {
                        if (m.parameterCount == 0) {
                            val v = m.invoke(obj)
                            if (v is Collection<*>) return v.filterNotNull() as List<Any>
                            if (v != null && v.javaClass.isArray) return (v as Array<*>).filterNotNull() as List<Any>
                        }
                    } catch (_: Exception) { }
                }

                return emptyList()
            }

            fun processNode(node: Any, parent: BookmarkFolderItem) {
                val title = getStringProp(node, listOf("title", "name", "label")) ?: ""
                val url = getStringProp(node, listOf("url", "href", "uri"))

                if (url != null && url.isNotBlank()) {
                    val id = BookmarkUtils.getNewId()
                    val site = BookmarkSiteItem(if (title.isNotBlank()) title else url, url, id)
                    manager.add(parent, site)
                } else {
                    val id = BookmarkUtils.getNewId()
                    val folder = BookmarkFolderItem(if (title.isNotBlank()) title else "Folder", parent, id)
                    manager.add(parent, folder)
                    val children = extractChildren(node)
                    for (child in children) {
                        processNode(child, folder)
                    }
                }
            }

            val topNodes: List<Any> = extractChildren(tree)

            val rootId = BookmarkUtils.getNewId()
            val rootFolder = BookmarkFolderItem("Imported", manager.root, rootId)

            for (node in topNodes) {
                processNode(node, rootFolder)
            }

            manager.add(manager.root, rootFolder)
            manager.save()

            return Result.success(rootId.toString())
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    override suspend fun warmUp() {
        manager.initialize()
    }
}