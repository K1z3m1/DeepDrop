/*
 * Copyright 2014 A.C.R. Development
 */

// Copyright (C) 2020 CookieJarApps
// MPL-2.0
package com.cookiegames.smartcookie.download

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.controller.UIController
import com.cookiegames.smartcookie.database.downloads.DownloadEntry
import com.cookiegames.smartcookie.database.downloads.DownloadsRepository
import com.cookiegames.smartcookie.di.DatabaseScheduler
import com.cookiegames.smartcookie.di.MainScheduler
import com.cookiegames.smartcookie.di.NetworkScheduler
import com.cookiegames.smartcookie.log.Logger
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.Utils
import io.reactivex.Scheduler
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handle download requests
 */
@Singleton
class DownloadHandler @Inject constructor(private val downloadsRepository: DownloadsRepository,
                                          private val downloadManager: DownloadManager,
                                          @param:DatabaseScheduler private val databaseScheduler: Scheduler,
                                          @param:NetworkScheduler private val networkScheduler: Scheduler,
                                          @param:MainScheduler private val mainScheduler: Scheduler,
                                          private val logger: Logger) {
    fun legacyDownloadStart(
        context: Activity,
        manager: UserPreferences,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String,
        contentSize: String
    ) {
        onDownloadStart(context, manager, url, userAgent, contentDisposition, mimeType, contentSize)
    }

    fun createAndSaveFileFromBase64Url(url: String, context: Context): String? {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val filetype = try {
            url.substring(url.indexOf("/") + 1, url.indexOf(";"))
        } catch (e: Exception) {
            "bin"
        }
        val filename = "download_${System.currentTimeMillis()}.$filetype"
        val file = File(path, filename)
        try {
            if (!path.exists()) path.mkdirs()
            if (!file.exists()) file.createNewFile()
            val base64EncodedString = url.substring(url.indexOf(",") + 1)
            val decodedBytes: ByteArray = android.util.Base64.decode(base64EncodedString, android.util.Base64.DEFAULT)
            val os: OutputStream = FileOutputStream(file)
            os.write(decodedBytes)
            os.close()

            MediaScannerConnection.scanFile(context, arrayOf(file.toString()), null, null)
            Toast.makeText(context, "${context.getString(R.string.download_successful)}: $filename", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.w(TAG, "Error writing data url file", e)
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
        return file.toString()
    }

    fun onDownloadStart(
        context: Activity,
        manager: UserPreferences,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String,
        contentSize: String
    ) {
        logger.log(TAG, "DOWNLOAD: url=$url, disposition=$contentDisposition, mimeType=$mimeType")

        if (url.startsWith("data:")) {
            createAndSaveFileFromBase64Url(url, context)
            return
        }

        val parsedUri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.cannot_download, Toast.LENGTH_SHORT).show()
            return
        }

        var fileName = getFileNameFromURL(url, contentDisposition, mimeType)
        if (fileName.isBlank() || fileName == "downloadfile") {
            fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        }

        val request = try {
            DownloadManager.Request(parsedUri)
        } catch (e: Exception) {
            logger.log(TAG, "Failed to create DownloadManager.Request for $url", e)
            Toast.makeText(context, R.string.cannot_download, Toast.LENGTH_SHORT).show()
            return
        }

        // Set cookies
        try {
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("Cookie", cookies)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching cookies for download", e)
        }

        // Set User-Agent
        if (userAgent.isNotBlank()) {
            request.addRequestHeader("User-Agent", userAgent)
        }

        // Set mimeType
        var effectiveMime = mimeType
        if (effectiveMime.isBlank() || effectiveMime == "application/octet-stream") {
            val ext = Utils.guessFileExtension(fileName)
            if (!ext.isNullOrBlank()) {
                val mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT))
                if (mapped != null) {
                    effectiveMime = mapped
                }
            }
        }
        if (effectiveMime.isNotBlank()) {
            request.setMimeType(effectiveMime)
        }

        // Configure destination in standard Downloads directory (Scoped Storage compatible)
        try {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not setDestinationInExternalPublicDir, fallback to setDestinationUri", e)
            val fallbackFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            request.setDestinationUri(Uri.fromFile(fallbackFile))
        }

        // Notification visibility
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setTitle(fileName)
        request.setDescription(parsedUri.host ?: url)

        // Enqueue
        try {
            downloadManager.enqueue(request)
            Toast.makeText(context, "${context.getString(R.string.download_pending)}: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logger.log(TAG, "Download enqueue error", e)
            Toast.makeText(context, R.string.cannot_download, Toast.LENGTH_SHORT).show()
            return
        }

        // Save to downloads repository
        try {
            if (context is UIController) {
                val currentTab = context.getTabModel().currentTab
                if (currentTab != null && !currentTab.isIncognito) {
                    downloadsRepository.addDownloadIfNotExists(DownloadEntry(url, fileName, contentSize))
                        .subscribeOn(databaseScheduler)
                        .subscribe({}, {})
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error saving to downloads repository", e)
        }
    }

    companion object {
        private const val TAG = "DownloadHandler"

        fun getFileNameFromURL(url: String?, contentDisposition: String?, mimeType: String?): String {
            var guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (guessed.isNullOrBlank() || guessed == "downloadfile") {
                val lastPathSegment = if (url != null) Uri.parse(url).lastPathSegment else null
                if (!lastPathSegment.isNullOrBlank()) {
                    guessed = try {
                        URLDecoder.decode(lastPathSegment, "UTF-8")
                    } catch (e: Exception) {
                        lastPathSegment
                    }
                }
            }
            return guessed ?: "download_${System.currentTimeMillis()}"
        }
    }
}