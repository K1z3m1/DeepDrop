package com.prirai.android.lr.permissions

import android.content.Context
import android.util.Log
import com.prirai.android.lr.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.WebExtension
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class LRBgPlayController(
    private val applicationContext: Context,
    private val engine: Engine,
    private val allowlist: BackgroundPlaybackAllowlist,
    private val scope: CoroutineScope,
) {
    private val tag = "LRBgPlay"

    @Volatile
    private var current: WebExtension? = null

    @Volatile
    private var inFlight: Boolean = false

    private var observerJob: Job? = null

    fun start() {
        observerJob?.cancel()
        observerJob = scope.launch(Dispatchers.Main) {
            val prefs = UserPreferences(applicationContext)
            val allowlistEmpty = allowlist.origins.value.isEmpty()
            val staleCheckAlreadyDone = prefs.lrBgPlayStaleCheckDone

            if (allowlistEmpty && staleCheckAlreadyDone) {
            } else {
                try {
                    current = findExistingInstall()
                    if (current != null) {
                        Log.i(tag, "lr-bg-play already installed from a previous session")
                    }
                    prefs.lrBgPlayStaleCheckDone = true
                } catch (t: Throwable) {
                    Log.w(tag, "listInstalledWebExtensions failed", t)
                }
            }

            allowlist.origins
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { shouldBeInstalled ->
                    reconcile(shouldBeInstalled)
                }
        }
    }

    private suspend fun findExistingInstall(): WebExtension? = withContext(Dispatchers.Main) {
        suspendCoroutine<WebExtension?> { cont ->
            engine.listInstalledWebExtensions(
                onSuccess = { installed ->
                    cont.resume(installed.firstOrNull { it.id == EXTENSION_ID })
                },
                onError = { _ -> cont.resume(null) },
            )
        }
    }

    private suspend fun reconcile(shouldBeInstalled: Boolean) {
        if (inFlight) return
        if (shouldBeInstalled && current == null) {
            install()
        } else if (!shouldBeInstalled && current != null) {
            uninstall()
        }
    }

    private suspend fun install() {
        inFlight = true
        val installed = withContext(Dispatchers.Main) {
            suspendCoroutine<WebExtension?> { cont ->
                engine.installBuiltInWebExtension(
                    id = EXTENSION_ID,
                    url = EXTENSION_URL,
                    onSuccess = { ext ->
                        Log.i(tag, "lr-bg-play installed")
                        cont.resume(ext)
                    },
                    onError = { err ->
                        Log.w(tag, "install FAILED", err)
                        cont.resume(null)
                    },
                )
            }
        }
        inFlight = false
        if (installed != null) {
            current = installed
            try {
                LRBgPlayAllowlistBridge.attach(
                    extension = installed,
                    allowlist = allowlist,
                    scope = scope,
                )
            } catch (t: Throwable) {
                Log.w(tag, "bridge attach failed", t)
            }
        }
    }

    private suspend fun uninstall() {
        val ext = current ?: return
        inFlight = true
        withContext(Dispatchers.Main) {
            suspendCoroutine<Unit> { cont ->
                engine.uninstallWebExtension(
                    ext = ext,
                    onSuccess = {
                        Log.i(tag, "lr-bg-play uninstalled")
                        cont.resume(Unit)
                    },
                    onError = { id, err ->
                        Log.w(tag, "uninstall FAILED for $id", err)
                        cont.resume(Unit)
                    },
                )
            }
        }
        current = null
        inFlight = false
    }

    companion object {
        const val EXTENSION_ID = "lr-bg-play@prirai.android.lr"
        const val EXTENSION_URL = "resource://android/assets/extensions/lr-bg-play/"
    }
}
