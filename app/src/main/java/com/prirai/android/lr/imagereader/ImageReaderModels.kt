package com.prirai.android.lr.imagereader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * One image found on the page. [candidates] is ordered best (original
 * resolution) first; the viewer tries them in order and falls back on error.
 * [previewUrl], when available, is a distinct URL suitable for showing while
 * the best candidate loads. [width]/[height] are the natural size when the
 * page had already loaded the image, otherwise 0.
 */
data class ReaderImage(
    val candidates: List<String>,
    val width: Int,
    val height: Int,
    val previewUrl: String? = null,
)

data class ReaderPayload(
    val pageUrl: String,
    val title: String,
    val userAgent: String,
    val images: List<ReaderImage>,
) {
    companion object {
        private const val MAX_IMAGES = 1500
        private const val MAX_CANDIDATES = 12

        /** Parses the (untrusted) message sent by the content script. */
        fun fromJson(json: JSONObject): ReaderPayload {
            val array = json.optJSONArray("images")
            val images = ArrayList<ReaderImage>()
            if (array != null) {
                for (i in 0 until minOf(array.length(), MAX_IMAGES)) {
                    val obj = array.optJSONObject(i) ?: continue
                    val cands = obj.optJSONArray("c") ?: continue
                    val list = ArrayList<String>()
                    for (j in 0 until minOf(cands.length(), MAX_CANDIDATES)) {
                        val url = cands.optString(j, "")
                        if (isLoadable(url)) list.add(url)
                    }
                    if (list.isEmpty()) continue
                    images.add(
                        ReaderImage(
                            candidates = list,
                            width = obj.optInt("w", 0).coerceAtLeast(0),
                            height = obj.optInt("h", 0).coerceAtLeast(0),
                            previewUrl = obj.optString("p", "")
                                .takeIf { isLoadable(it) && it != list.first() },
                        )
                    )
                }
            }
            return ReaderPayload(
                pageUrl = json.optString("pageUrl", ""),
                title = json.optString("title", "").take(200),
                userAgent = json.optString("userAgent", "").take(400),
                images = images,
            )
        }

        // SECURITY: only network images or inline data images may be loaded.
        private fun isLoadable(url: String): Boolean =
            url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("data:image/", ignoreCase = true)
    }
}

/** In-memory hand-off between the browser and [ImageReaderActivity]. */
object ImageReaderSession {
    private val _payload = MutableStateFlow<ReaderPayload?>(null)
    val payload: StateFlow<ReaderPayload?> = _payload

    fun publish(payload: ReaderPayload) {
        _payload.value = payload
    }

    fun clear() {
        _payload.value = null
    }
}
