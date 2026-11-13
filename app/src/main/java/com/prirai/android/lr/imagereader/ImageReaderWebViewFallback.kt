package com.prirai.android.lr.imagereader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Second-tier fallback: renders the page in an off-screen system WebView so
 * its JavaScript really runs (lazy loaders, client-rendered galleries), then
 * runs the same collector as the extension's content script inside it.
 *
 * The WebView has its own cookie jar, separate from the browser engine's, so
 * logged-in pages may still differ from what the user sees.
 */
internal object ImageReaderWebViewFallback {

    private const val TAG = "LRImageReaderWV"
    private const val SETTLE_MS = 1_500L
    private const val VIEW_W = 1080
    private const val VIEW_H = 2000

    private class Bridge(private val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun done(json: String?) = onResult(json.orEmpty())
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(context: Context, pageUrl: String, userAgent: String?): ReaderPayload? {
        val appContext = context.applicationContext
        val script = try {
            appContext.assets.open("extensions/lr-image-reader/content.js")
                .bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "collector script missing", t)
            return null
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<ReaderPayload?> { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    val iface = "lrb" + UUID.randomUUID().toString().replace("-", "")
                    val wv = try {
                        WebView(appContext)
                    } catch (t: Throwable) {
                        Log.w(TAG, "WebView unavailable", t)
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    var finished = false
                    fun finish(result: ReaderPayload?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        handler.post {
                            runCatching {
                                wv.stopLoading()
                                wv.removeJavascriptInterface(iface)
                                wv.destroy()
                            }
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                    cont.invokeOnCancellation { handler.post { runCatching { wv.destroy() } } }

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = true
                        mediaPlaybackRequiresUserGesture = true
                        allowFileAccess = false
                        allowContentAccess = false
                        setSupportMultipleWindows(false)
                        userAgentString = userAgent?.takeIf { it.isNotBlank() } ?: userAgentString
                    }

                    // Give the page a real viewport, otherwise lazy loaders
                    // driven by IntersectionObserver never fire.
                    wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
                    )
                    wv.layout(0, 0, VIEW_W, VIEW_H)

                    wv.addJavascriptInterface(
                        Bridge { json ->
                            val payload = try {
                                if (json.isBlank()) null
                                else ReaderPayload.fromJson(JSONObject(json))
                            } catch (t: Throwable) {
                                Log.w(TAG, "bad payload", t)
                                null
                            }
                            finish(payload)
                        },
                        iface,
                    )

                    var started = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            return scheme != "http" && scheme != "https"
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            started = false
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (started) return
                            started = true
                            // Let client-side rendering settle, then collect.
                            handler.postDelayed({
                                view.evaluateJavascript(
                                    script + "\n;window.__lrImageReaderRun(true).then(function(r){" +
                                        iface + ".done(JSON.stringify(r));}).catch(function(){" +
                                        iface + ".done('');});",
                                    null,
                                )
                            }, SETTLE_MS)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) finish(null)
                        }
                    }

                    wv.loadUrl(pageUrl)
            }
        }
    }
}
