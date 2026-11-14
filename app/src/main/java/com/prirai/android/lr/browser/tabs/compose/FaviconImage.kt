package com.prirai.android.lr.browser.tabs.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.icons.IconRequest
import com.prirai.android.lr.ext.components
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException

@Composable
fun FaviconImage(
    tab: TabSessionState,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    val faviconBitmap by produceState<Bitmap?>(initialValue = tab.content.icon, tab.id, tab.content.icon, tab.content.url) {
        value = loadFavicon(tab, context)
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (faviconBitmap != null) {
            Image(
                bitmap = faviconBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size)
            )
        }
    }
}

private suspend fun loadFavicon(tab: TabSessionState, context: Context): Bitmap? {
    return try {
        withContext(Dispatchers.IO) {
            tab.content.icon?.let { return@withContext it }

            val icons = context.components.icons

            val iconRequest = IconRequest(
                url = tab.content.url,
                size = IconRequest.Size.DEFAULT,
                resources = listOf(
                    IconRequest.Resource(
                        url = tab.content.url,
                        type = IconRequest.Resource.Type.FAVICON
                    )
                )
            )

            val icon = icons.loadIcon(iconRequest).await()
            icon.bitmap?.let { return@withContext it }

            val faviconCache = context.components.faviconCache
            faviconCache.loadFavicon(tab.content.url)?.let { return@withContext it }

            null
        }
    } catch (e: Exception) {
        if (e !is CancellationException) {
            Log.e("FaviconImage", "Error loading favicon for ${tab.content.url}", e)
        }
        null
    }
}

@Composable
fun FaviconImageFromUrl(
    url: String,
    title: String = "",
    size: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    val faviconBitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = loadFaviconFromUrl(url, context)
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (faviconBitmap != null) {
            Image(
                bitmap = faviconBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size)
            )
        }
    }
}

private suspend fun loadFaviconFromUrl(url: String, context: Context): Bitmap? {
    return try {
        withContext(Dispatchers.IO) {
            val icons = context.components.icons

            val iconRequest = IconRequest(
                url = url,
                size = IconRequest.Size.DEFAULT,
                resources = listOf(
                    IconRequest.Resource(
                        url = url,
                        type = IconRequest.Resource.Type.FAVICON
                    )
                )
            )

            val icon = icons.loadIcon(iconRequest).await()
            icon.bitmap?.let { return@withContext it }

            val faviconCache = context.components.faviconCache
            faviconCache.loadFavicon(url)
        }
    } catch (e: Exception) {
        if (e !is CancellationException) {
            Log.e("FaviconImage", "Error loading favicon from URL: $url", e)
        }
        null
    }
}
