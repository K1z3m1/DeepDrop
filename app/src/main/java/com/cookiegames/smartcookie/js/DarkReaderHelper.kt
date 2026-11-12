package com.cookiegames.smartcookie.js

import android.content.Context
import android.webkit.WebView
import android.util.Log

/**
 * Helper to inject and manage Dark Reader in WebViews.
 * Uses official darkreader.min.js bundled in assets for high-fidelity dark mode.
 */
object DarkReaderHelper {
    private const val TAG = "DarkReaderHelper"
    private var cachedScript: String? = null

    fun getScript(context: Context): String {
        if (cachedScript == null) {
            try {
                cachedScript = context.assets.open("darkreader.min.js").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load darkreader.min.js", e)
                cachedScript = ""
            }
        }
        return cachedScript ?: ""
    }

    /**
     * Injects and enables Dark Reader on the given WebView.
     */
    fun enable(webView: WebView?) {
        webView?.let { wv ->
            val script = getScript(wv.context)
            if (script.isNotBlank()) {
                val js = "(function() {" +
                    "try {" +
                    "if (typeof DarkReader === 'undefined') {" +
                    script +
                    "}" +
                    "if (typeof DarkReader !== 'undefined') {" +
                    "if (typeof window.fetch !== 'undefined') {" +
                    "DarkReader.setFetchMethod(window.fetch);" +
                    "}" +
                    "DarkReader.enable({" +
                    "brightness: 95," +
                    "contrast: 90," +
                    "sepia: 10" +
                    "});" +
                    "}" +
                    "} catch(e) {" +
                    "console.error('DarkReader error:', e);" +
                    "}" +
                    "})();"
                wv.evaluateJavascript(js, null)
            }
        }
    }

    /**
     * Disables Dark Reader on the given WebView.
     */
    fun disable(webView: WebView?) {
        webView?.let { wv ->
            val js = "(function() {" +
                "try {" +
                "if (typeof DarkReader !== 'undefined') {" +
                "DarkReader.disable();" +
                "}" +
                "} catch(e) {" +
                "console.error('DarkReader disable error:', e);" +
                "}" +
                "})();"
            wv.evaluateJavascript(js, null)
        }
    }

    /**
     * Toggles Dark Reader on the given WebView.
     */
    fun toggle(webView: WebView?, enable: Boolean) {
        if (enable) {
            enable(webView)
        } else {
            disable(webView)
        }
    }
}
