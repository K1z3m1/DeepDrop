package com.prirai.android.lr.browser.bookmark.items

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.IOException
import java.io.Serializable

class BookmarkSiteItem : BookmarkItem, Serializable {
    lateinit var url: String

    override val type: Int
        get() = BOOKMARK_ITEM_ID

    constructor(title: String, id: Long) : super(title, id)

    constructor(title: String, url: String, id: Long) : super(title, id) {
        this.url = url
    }

    @Throws(IOException::class)
    override fun writeMain(writer: JsonWriter): Boolean {
        writer.name(COLUMN_NAME_URL)
        writer.value(url)
        return true
    }

    @Throws(IOException::class)
    override fun readMain(name: String?, reader: JsonReader): Boolean {
        if (COLUMN_NAME_URL != name) return false
        if (reader.peek() != JsonToken.STRING) return false
        url = reader.nextString()
        return true
    }

    companion object {
        protected const val COLUMN_NAME_URL = "2"
        const val BOOKMARK_ITEM_ID = 2
    }
}