package com.prirai.android.lr.browser.home

import android.content.Context
import android.webkit.JavascriptInterface
import com.prirai.android.lr.browser.bookmark.items.BookmarkFolderItem
import com.prirai.android.lr.browser.bookmark.items.BookmarkSiteItem
import com.prirai.android.lr.browser.bookmark.repository.BookmarkManager
import com.prirai.android.lr.browser.shortcuts.ShortcutDatabase
import com.prirai.android.lr.browser.shortcuts.ShortcutEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONArray
import org.json.JSONObject

class HomepageJavaScriptInterface(private val context: Context) {
    private val logger = Logger("HomepageJavaScriptInterface")

    @JavascriptInterface
    fun getShortcuts(): String {
        val shortcuts = JSONArray()

        try {
            val dao = ShortcutDatabase.getInstance(context).shortcutDao()
            val items = runBlocking(Dispatchers.IO) { dao.getAll() }

            items.take(12).forEach { shortcut: ShortcutEntity ->
                val obj = JSONObject()
                obj.put("uid", shortcut.uid) // Add ID for deletion
                obj.put("title", shortcut.title ?: "")
                obj.put("url", shortcut.url ?: "")
                obj.put("icon", null)
                shortcuts.put(obj)
            }
        } catch (e: Exception) {
            logger.error("Failed to load shortcuts for homepage", e)
        }

        return shortcuts.toString()
    }
    
    @JavascriptInterface
    fun getBookmarks(): String {
        val bookmarks = JSONArray()
        
        try {
            val manager = BookmarkManager.getInstance(context)
            
            val allBookmarks = mutableListOf<BookmarkSiteItem>()
            collectBookmarks(manager.root, allBookmarks)
            
            allBookmarks.take(20).forEach { bookmark ->
                val obj = JSONObject()
                obj.put("title", bookmark.title ?: "")
                obj.put("url", bookmark.url ?: "")
                obj.put("id", bookmark.id)
                obj.put("icon", null)
                bookmarks.put(obj)
            }
        } catch (e: Exception) {
            logger.error("Failed to load bookmarks for homepage", e)
        }

        return bookmarks.toString()
    }
    
    private fun collectBookmarks(folder: BookmarkFolderItem, result: MutableList<BookmarkSiteItem>) {
        folder.list.forEach { item ->
            when (item) {
                is BookmarkSiteItem -> result.add(item)
                is BookmarkFolderItem -> collectBookmarks(item, result)
            }
        }
    }
}
