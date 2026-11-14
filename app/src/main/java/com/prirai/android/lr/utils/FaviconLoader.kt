package com.prirai.android.lr.utils

import android.content.Context
import android.graphics.Bitmap
import com.prirai.android.lr.ext.components
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.icons.IconRequest
import mozilla.components.support.base.log.logger.Logger

object FaviconLoader {
    private val logger = Logger("FaviconLoader")

    suspend fun loadFavicon(context: Context, url: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val cache = FaviconCache.getInstance(context)

                cache.getFaviconFromMemory(url)?.let {
                    return@withContext it
                }

                cache.loadFavicon(url)?.let {
                    return@withContext it
                }

                val iconRequest = IconRequest(
                    url = url,
                    size = IconRequest.Size.DEFAULT,
                    resources = listOf(
                        IconRequest.Resource(
                            url = url,
                            type = IconRequest.Resource.Type.FAVICON
                        ),
                        IconRequest.Resource(
                            url = url,
                            type = IconRequest.Resource.Type.APPLE_TOUCH_ICON
                        ),
                        IconRequest.Resource(
                            url = url,
                            type = IconRequest.Resource.Type.IMAGE_SRC
                        )
                    )
                )

                val icon = context.components.icons.loadIcon(iconRequest).await()
                val bitmap = icon.bitmap
                cache.saveFaviconSync(url, bitmap)
                bitmap
            } catch (e: Exception) {
                logger.warn("Favicon load failed for $url", e)
                null
            }
        }
    }

    suspend fun preloadToMemory(context: Context, url: String) {
        FaviconCache.getInstance(context).preloadToMemory(url)
    }

    suspend fun saveFavicon(context: Context, url: String, bitmap: Bitmap) {
        FaviconCache.getInstance(context).saveFavicon(url, bitmap)
    }
}
