package com.prirai.android.lr.imagereader

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.prirai.android.lr.R
import com.prirai.android.lr.ext.components
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.selector.findTab
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Drives the `lr-image-reader` built-in WebExtension.
 *
 * The extension is installed lazily the first time the reader is used, so
 * users who never open it pay nothing. The content script opens a native
 * port ("lrImageReader") when a page finishes loading; we send it a scan
 * request over that port and receive the image list back on the same port.
 */
object ImageReaderController {

    private const val TAG = "LRImageReader"
    const val EXTENSION_ID = "lr-image-reader@prirai.android.lr"
    const val EXTENSION_URL = "resource://android/assets/extensions/lr-image-reader/"
    private const val PORT_NAME = "lrImageReader"

    private const val SCAN_TIMEOUT_MS = 15_000L
    private const val PORT_WAIT_MS = 20_000L

    private val scope = MainScope()

    @Volatile
    private var extension: WebExtension? = null

    private val ports: MutableMap<EngineSession, Port> =
        Collections.synchronizedMap(WeakHashMap())
    private val registered: MutableSet<EngineSession> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    @Volatile
    private var pendingId: String? = null

    @Volatile
    private var pending: CompletableDeferred<ReaderPayload>? = null

    @Volatile
    private var busy = false

    /** Scan the page of [tabId] and open the full-screen reader. */
    fun open(context: Context, tabId: String) {
        if (busy) return
        busy = true
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Main) {
            try {
                run(appContext, tabId)
            } catch (t: Throwable) {
                Log.w(TAG, "image reader failed", t)
                toast(appContext, R.string.image_reader_failed)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun run(context: Context, tabId: String) {
        val components = context.components
        val tab = components.store.state.findTab(tabId)
        val session = tab?.engineState?.engineSession
        if (tab == null || session == null || !tab.content.url.startsWith("http")) {
            toast(context, R.string.image_reader_unavailable)
            return
        }

        toast(context, R.string.image_reader_scanning)

        val ext = ensureExtension(components.engine)
        if (ext == null) {
            toast(context, R.string.image_reader_failed)
            return
        }

        var justRegistered = false
        if (registered.add(session)) {
            ext.registerContentMessageHandler(session, PORT_NAME, SessionHandler(session))
            justRegistered = true
        }

        // The content script connects when a page finishes loading. If our
        // handler was registered after that, the connection was dropped, so
        // reload once to get a fresh connection.
        if (ports[session] == null) {
            if (justRegistered) {
                components.sessionUseCases.reload.invoke(tabId)
                delay(600)
            }
            waitForPort(context, tabId, session)
        }

        val port = ports[session]
        if (port == null) {
            toast(context, R.string.image_reader_failed)
            return
        }

        val payload = request(port)
        when {
            payload == null -> toast(context, R.string.image_reader_failed)
            payload.images.isEmpty() -> toast(context, R.string.image_reader_none_found)
            else -> {
                ImageReaderSession.publish(payload)
                context.startActivity(
                    Intent(context, ImageReaderActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private suspend fun waitForPort(context: Context, tabId: String, session: EngineSession) {
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ports[session] != null) {
                // Let the page settle after a reload before scanning.
                val loading = context.components.store.state.findTab(tabId)?.content?.loading ?: false
                if (!loading) return
            }
            delay(250)
        }
    }

    private suspend fun request(port: Port): ReaderPayload? {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ReaderPayload>()
        pendingId = id
        pending = deferred
        try {
            port.postMessage(
                JSONObject()
                    .put("type", "lrImageReader:collect")
                    .put("requestId", id)
                    .put("warm", true)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "postMessage failed", t)
            pending = null
            return null
        }
        val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() }
        pending = null
        pendingId = null
        return result
    }

    private suspend fun ensureExtension(engine: Engine): WebExtension? {
        extension?.let { return it }
        val existing = withContext(Dispatchers.Main) {
            suspendCoroutine<WebExtension?> { cont ->
                engine.listInstalledWebExtensions(
                    onSuccess = { list -> cont.resume(list.firstOrNull { it.id == EXTENSION_ID }) },
                    onError = { _ -> cont.resume(null) },
                )
            }
        }
        val ext = existing ?: withContext(Dispatchers.Main) {
            suspendCoroutine<WebExtension?> { cont ->
                engine.installBuiltInWebExtension(
                    id = EXTENSION_ID,
                    url = EXTENSION_URL,
                    onSuccess = { cont.resume(it) },
                    onError = { err ->
                        Log.w(TAG, "install failed", err)
                        cont.resume(null)
                    },
                )
            }
        } ?: return null

        // Allow it in private tabs too, otherwise the reader silently
        // fails there.
        try {
            engine.setAllowedInPrivateBrowsing(
                extension = ext,
                allowed = true,
                onSuccess = { },
                onError = { err -> Log.w(TAG, "private browsing allow failed", err) },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setAllowedInPrivateBrowsing threw", t)
        }
        extension = ext
        return ext
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private class SessionHandler(private val session: EngineSession) : MessageHandler {
        override fun onPortConnected(port: Port) {
            ports[session] = port
        }

        override fun onPortDisconnected(port: Port) {
            if (ports[session] === port) ports.remove(session)
        }

        override fun onPortMessage(message: Any, port: Port) {
            // SECURITY: page-controlled data. Parse defensively and accept
            // only the response to the request we sent.
            val json = message as? JSONObject ?: return
            if (json.optString("type") != "images") return
            if (json.optString("requestId") != pendingId) return
            val payload = try {
                ReaderPayload.fromJson(json)
            } catch (t: Throwable) {
                Log.w(TAG, "bad payload", t)
                return
            }
            pending?.complete(payload)
        }
    }
}
