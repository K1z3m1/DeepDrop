package com.firstt175.novaframe.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CACHE_BYTES = 2 * 1024 * 1024

private val iconCache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Composable
fun rememberAppIconPainter(packageName: String, sizePx: Int): BitmapPainter? {
    val ctx = LocalContext.current
    val effectiveSize = sizePx.coerceIn(48, 72)
    val cacheKey = remember(packageName, effectiveSize) { "$packageName@$effectiveSize" }
    var painter by remember(cacheKey) {
        val cached = iconCache.get(cacheKey)
        mutableStateOf(cached?.let { BitmapPainter(it.asImageBitmap()) })
    }
    LaunchedEffect(cacheKey) {
        if (painter != null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            iconCache.get(cacheKey)?.let { return@withContext it }
            val pm = ctx.packageManager
            val drawable = runCatching {
                pm.getApplicationIcon(packageName)
            }.getOrNull() ?: runCatching {
                pm.getDefaultActivityIcon()
            }.getOrNull() ?: return@withContext null
            val bm = runCatching {
                drawable.toBitmap(width = effectiveSize, height = effectiveSize)
            }.getOrNull() ?: return@withContext null
            iconCache.put(cacheKey, bm)
            bm
        }
        if (bitmap != null) {
            painter = BitmapPainter(bitmap.asImageBitmap())
        }
    }
    return painter
}

