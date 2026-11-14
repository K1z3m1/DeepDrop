package com.prirai.android.lr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import android.content.ComponentCallbacks2

class FaviconCache private constructor(private val context: Context) {
    private val memoryCache: LruCache<String, Bitmap>
    private val cacheDir: File
    
    companion object {
        @Volatile
        private var INSTANCE: FaviconCache? = null
        
        fun getInstance(context: Context): FaviconCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FaviconCache(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        private const val CACHE_SIZE = 4 * 1024 * 1024
        private const val FAVICON_CACHE_DIR = "favicon_cache"
    }
    
    init {
        memoryCache = object : LruCache<String, Bitmap>(CACHE_SIZE) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount
            }
        }
        
        cacheDir = File(context.filesDir, FAVICON_CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }
    
    suspend fun saveFavicon(url: String, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                val key = generateKey(url)
                
                memoryCache.put(key, bitmap)
                
                val file = File(cacheDir, "$key.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) // 100 quality for crisp icons
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
    
    fun saveFaviconSync(url: String, bitmap: Bitmap) {
        try {
            val key = generateKey(url)
            
            memoryCache.put(key, bitmap)
            
            val file = File(cacheDir, "$key.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    
    suspend fun loadFavicon(url: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val key = generateKey(url)
                
                memoryCache.get(key)?.let { return@withContext it }
                
                val file = File(cacheDir, "$key.png")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        memoryCache.put(key, bitmap)
                        return@withContext bitmap
                    }
                }
                
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    fun getFaviconFromMemory(url: String): Bitmap? {
        val key = generateKey(url)
        return memoryCache.get(key)
    }
    
    suspend fun preloadToMemory(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val key = generateKey(url)
                
                if (memoryCache.get(key) != null) return@withContext
                
                val file = File(cacheDir, "$key.png")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        memoryCache.put(key, bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            try {
                memoryCache.evictAll()
                cacheDir.listFiles()?.forEach { it.delete() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> memoryCache.evictAll()

            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                val snapshot = memoryCache.snapshot()
                var evicted = 0
                val target = snapshot.size / 2
                for (key in snapshot.keys) {
                    if (evicted >= target) break
                    if (memoryCache.remove(key) != null) evicted++
                }
            }
        }
    }
    
    private fun generateKey(url: String): String {
        return try {
            val domain = extractDomain(url)
            val bytes = MessageDigest.getInstance("MD5").digest(domain.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toString()
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            var domain = url.lowercase().trim()
            
            domain = when {
                domain.startsWith("http://") -> domain.substring(7)
                domain.startsWith("https://") -> domain.substring(8)
                else -> domain
            }
            
            domain = domain.split("/", "?", "#")[0]
            
            domain = domain.split(":")[0]
            
            if (domain.startsWith("www.") && domain.count { it == '.' } > 1) {
                domain = domain.substring(4)
            }
            
            domain
        } catch (e: Exception) {
            url
        }
    }
}