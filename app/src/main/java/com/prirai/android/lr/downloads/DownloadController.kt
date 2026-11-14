package com.prirai.android.lr.downloads

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.prirai.android.lr.ext.components
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.INTENT_EXTRA_DOWNLOAD_ID
import mozilla.components.support.utils.DefaultDownloadFileUtils
import android.app.DownloadManager

object DownloadController {
    private const val TAG = "DownloadController"

    const val PARALLEL_MIN_BYTES = 8L * 1024 * 1024
    const val PARALLEL_THREADS = 4

    const val ACTION_PAUSE = "com.prirai.android.lr.downloads.PAUSE"
    const val ACTION_RESUME = "com.prirai.android.lr.downloads.RESUME"
    const val ACTION_CANCEL = "com.prirai.android.lr.downloads.CANCEL"

    fun shouldUseParallel(context: Context, download: DownloadState): Boolean {
        val length = download.contentLength ?: 0L
        val http = download.url.startsWith("http://") || download.url.startsWith("https://")
        return http && (
            length >= PARALLEL_MIN_BYTES ||
                ParallelDownloadService.hasSidecar(context, download)
            )
    }

    fun start(context: Context, incoming: DownloadState) {
        val app = context.applicationContext

        val download = ensureFileName(app, incoming).also { resolved ->
            if (resolved !== incoming) {
                app.components.store.dispatch(DownloadAction.UpdateDownloadAction(resolved))
            }
        }

        val http = download.url.startsWith("http://") || download.url.startsWith("https://")

        if (!http) {
            startService(app, DownloadService::class.java, download.id)
            return
        }

        if (shouldUseParallel(app, download)) {
            startService(app, ParallelDownloadService::class.java, download.id)
            return
        }

        if (download.contentLength == null) {
            CoroutineScope(Dispatchers.IO).launch {
                val probed = probeContentLength(app, download)
                if (probed != null && probed >= PARALLEL_MIN_BYTES) {
                    Log.i(TAG, "Probe found length=$probed for ${download.id}; switching to parallel")
                    app.sendBroadcast(
                        Intent(AbstractFetchDownloadService.ACTION_CANCEL).apply {
                            setPackage(app.packageName)
                            putExtra(INTENT_EXTRA_DOWNLOAD_ID, download.id)
                        }
                    )
                    val current = app.components.store.state.downloads[download.id] ?: download
                    app.components.store.dispatch(
                        DownloadAction.UpdateDownloadAction(current.copy(contentLength = probed))
                    )
                    startService(app, ParallelDownloadService::class.java, download.id)
                }
            }
        }

        startService(app, DownloadService::class.java, download.id)
    }

    fun startFallback(context: Context, downloadId: String) {
        startService(context.applicationContext, DownloadService::class.java, downloadId)
    }

    fun pause(context: Context, downloadId: String) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(AbstractFetchDownloadService.ACTION_PAUSE).apply {
                setPackage(app.packageName)
                putExtra(INTENT_EXTRA_DOWNLOAD_ID, downloadId)
            }
        )
        app.sendBroadcast(
            Intent(ACTION_PAUSE).apply {
                setPackage(app.packageName)
                putExtra(INTENT_EXTRA_DOWNLOAD_ID, downloadId)
            }
        )
    }

    fun resume(context: Context, incoming: DownloadState) {
        val app = context.applicationContext
        val download = ensureFileName(app, incoming).also { resolved ->
            if (resolved !== incoming) {
                app.components.store.dispatch(DownloadAction.UpdateDownloadAction(resolved))
            }
        }
        if (shouldUseParallel(app, download)) {
            app.sendBroadcast(
                Intent(ACTION_RESUME).apply {
                    setPackage(app.packageName)
                    putExtra(INTENT_EXTRA_DOWNLOAD_ID, download.id)
                }
            )
            startService(app, ParallelDownloadService::class.java, download.id)
        } else {
            app.sendBroadcast(
                Intent(AbstractFetchDownloadService.ACTION_RESUME).apply {
                    setPackage(app.packageName)
                    putExtra(INTENT_EXTRA_DOWNLOAD_ID, download.id)
                }
            )
            startService(app, DownloadService::class.java, download.id)
        }
    }

    fun cancel(context: Context, download: DownloadState) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(AbstractFetchDownloadService.ACTION_CANCEL).apply {
                setPackage(app.packageName)
                putExtra(INTENT_EXTRA_DOWNLOAD_ID, download.id)
            }
        )
        app.sendBroadcast(
            Intent(ACTION_CANCEL).apply {
                setPackage(app.packageName)
                putExtra(INTENT_EXTRA_DOWNLOAD_ID, download.id)
            }
        )
        app.components.store.dispatch(DownloadAction.RemoveDownloadAction(download.id))
    }

    private fun probeContentLength(context: Context, download: DownloadState): Long? {
        return try {
            val headers = MutableHeaders()
            headers.append("Range", "bytes=0-0")
            val request = Request(
                url = download.url,
                headers = headers,
                private = download.private,
                referrerUrl = download.referrerUrl,
            )
            context.components.client.fetch(request).use { response ->
                if (response.status != 206) return null
                ParallelDownloadService.parseTotal(response.headers["Content-Range"])
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Content-length probe failed for ${download.url}", t)
            null
        }
    }

    private fun ensureFileName(context: Context, download: DownloadState): DownloadState {
        if (!download.fileName.isNullOrBlank()) return download
        val utils = DefaultDownloadFileUtils(
            context = context.applicationContext,
            downloadLocation = { download.directoryPath },
        )
        val guessed = utils.guessFileName(
            contentDisposition = null,
            url = download.url,
            mimeType = download.contentType,
        )
        return download.copy(fileName = guessed)
    }

    private fun startService(context: Context, service: Class<*>, downloadId: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, service).apply {
                putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(INTENT_EXTRA_DOWNLOAD_ID, downloadId)
            }
        )
    }
}
