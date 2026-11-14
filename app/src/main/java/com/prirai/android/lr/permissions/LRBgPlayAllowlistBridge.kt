package com.prirai.android.lr.permissions

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONArray
import org.json.JSONObject

internal object LRBgPlayAllowlistBridge {
    private const val TAG = "LRBgPlayBridge"
    private const val PORT_NAME = "lr.bgplay.allowlist"

    @Volatile
    private var activePort: Port? = null

    @Volatile
    private var collectorJob: Job? = null

    fun attach(
        extension: WebExtension,
        allowlist: BackgroundPlaybackAllowlist,
        scope: CoroutineScope,
    ) {
        collectorJob?.cancel()
        activePort = null

        val handler = object : MessageHandler {
            override fun onPortConnected(port: Port) {
                activePort = port
                try {
                    port.postMessage(buildMessage(allowlist.origins.value))
                } catch (t: Throwable) {
                    Log.w(TAG, "initial postMessage failed", t)
                }
            }

            override fun onPortDisconnected(port: Port) {
                if (activePort === port) activePort = null
            }
        }

        extension.registerBackgroundMessageHandler(PORT_NAME, handler)

        collectorJob = scope.launch(Dispatchers.Main) {
            allowlist.origins.collect { origins ->
                val port = activePort ?: return@collect
                try {
                    port.postMessage(buildMessage(origins))
                } catch (t: Throwable) {
                    Log.w(TAG, "postMessage on change failed", t)
                }
            }
        }
    }

    private fun buildMessage(origins: Set<String>): JSONObject {
        val array = JSONArray()
        origins.forEach { array.put(it) }
        return JSONObject().apply {
            put("type", "set")
            put("origins", array)
        }
    }
}
