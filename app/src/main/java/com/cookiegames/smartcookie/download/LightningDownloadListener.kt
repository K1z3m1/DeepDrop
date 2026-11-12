/*
 * Copyright 2014 A.C.R. Development
 */
package com.cookiegames.smartcookie.download

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.widget.CheckBox
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.anthonycr.grant.PermissionsManager
import com.anthonycr.grant.PermissionsResultAction
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.database.downloads.DownloadsRepository
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.BrowserDialog.setDialogSize
import com.cookiegames.smartcookie.log.Logger
import com.cookiegames.smartcookie.preference.UserPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject

class LightningDownloadListener(context: Activity) : DownloadListener {
    private val mActivity: Activity

    @JvmField
    @Inject
    var userPreferences: UserPreferences? = null

    @JvmField
    @Inject
    var downloadHandler: DownloadHandler? = null

    @JvmField
    @Inject
    var downloadsRepository: DownloadsRepository? = null

    @JvmField
    @Inject
    var logger: Logger? = null

    override fun onDownloadStart(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        if (url.startsWith("file://") || url.startsWith("about:") || url.startsWith("data:")) {
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)

        // Safety: If navigation to a webpage or game failed and defaulted to downloadfile.bin without explicit attachment:
        if (fileName == "downloadfile.bin" && (contentDisposition == null || !contentDisposition.contains("attachment", ignoreCase = true))) {
            Log.w(TAG, "Prevented downloadfile.bin fallback for URL: $url")
            (mActivity as? com.cookiegames.smartcookie.browser.activity.BrowserActivity)?.let { act ->
                act.runOnUiThread {
                    act.tabsManager.currentTab?.loadUrl(url)
                }
            }
            return
        }

        val downloadSize: String = if (contentLength > 0) {
            Formatter.formatFileSize(mActivity, contentLength)
        } else {
            mActivity.getString(R.string.unknown_size)
        }

        val startDownloadAction = {
            val prefs = userPreferences
            val handler = downloadHandler
            if (prefs != null && handler != null) {
                if (prefs.showDownloadConfirmation) {
                    val checkBoxView = View.inflate(mActivity, R.layout.download_dialog, null)
                    val checkBox = checkBoxView.findViewById<View>(R.id.checkbox) as CheckBox
                    checkBox.setOnCheckedChangeListener { _, isChecked ->
                        prefs.showDownloadConfirmation = !isChecked
                    }
                    checkBox.text = mActivity.resources.getString(R.string.dont_ask_again)

                    val dialogClickListener = DialogInterface.OnClickListener { _, which ->
                        when (which) {
                            DialogInterface.BUTTON_POSITIVE -> {
                                handler.onDownloadStart(
                                    mActivity,
                                    prefs,
                                    url,
                                    userAgent,
                                    contentDisposition,
                                    mimetype ?: "application/octet-stream",
                                    downloadSize
                                )
                            }
                            DialogInterface.BUTTON_NEGATIVE -> {}
                        }
                    }

                    val builder = MaterialAlertDialogBuilder(mActivity)
                    val message = mActivity.getString(R.string.dialog_download, downloadSize)
                    val dialog: Dialog = builder.setTitle(fileName)
                        .setMessage(message)
                        .setView(checkBoxView)
                        .setPositiveButton(mActivity.resources.getString(R.string.action_download), dialogClickListener)
                        .setNegativeButton(mActivity.resources.getString(R.string.action_cancel), dialogClickListener)
                        .show()
                    setDialogSize(mActivity, dialog)
                    logger?.log(TAG, "Downloading: $fileName")
                } else {
                    Toast.makeText(mActivity, mActivity.resources.getString(R.string.download_pending), Toast.LENGTH_LONG).show()
                    handler.onDownloadStart(
                        mActivity,
                        prefs,
                        url,
                        userAgent,
                        contentDisposition,
                        mimetype ?: "application/octet-stream",
                        downloadSize
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses Scoped Storage / DownloadManager directly without storage permission
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(mActivity, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(mActivity, arrayOf("android.permission.POST_NOTIFICATIONS"), 101)
                }
            }
            startDownloadAction()
        } else {
            // Android 9 and lower requires external storage write permission
            PermissionsManager.getInstance().requestPermissionsIfNecessaryForResult(
                mActivity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                object : PermissionsResultAction() {
                    override fun onGranted() {
                        startDownloadAction()
                    }

                    override fun onDenied(permission: String) {
                        Toast.makeText(mActivity, "Permiso de almacenamiento denegado para descargar", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    companion object {
        private const val TAG = "LightningDownloader"
    }

    init {
        context.injector.inject(this)
        mActivity = context
    }
}