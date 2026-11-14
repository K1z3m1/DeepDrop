package com.prirai.android.lr.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private object ImageCache {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val MAX_EDGE = 2048
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun load(context: Context, source: String): Bitmap? {
        cache.get(source)?.let { return it }
        val bytes = runCatching { open(context, source).use { it.readBytes() } }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { cache.put(source, it) }
    }

    private fun open(context: Context, source: String): InputStream = when {
        source.startsWith("http://") || source.startsWith("https://") -> {
            val conn = URL(source).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.inputStream
        }
        source.startsWith("content:") || source.startsWith("file:") || source.startsWith("android.resource:") ->
            context.contentResolver.openInputStream(Uri.parse(source))!!
        else -> File(source).inputStream()
    }
}

@Composable
fun RemoteImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(
        initialValue = (model as? Bitmap) ?: (model as? String)?.let(ImageCache::get),
        model,
    ) {
        value = when (model) {
            is Bitmap -> model
            is String -> if (model.isEmpty()) null else withContext(Dispatchers.IO) { ImageCache.load(context, model) }
            else -> null
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
        )
    }
}
