/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Created by CookieJarApps 10/01/2020 */

package com.cookiegames.smartcookie.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.BuildConfig
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.database.downloads.DownloadsRepository
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.ThemeUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.*
import javax.inject.Inject

data class DownloadItem(
    val id: Long = -1L,
    val title: String,
    val filePath: String,
    val uri: Uri?,
    val totalSize: Long,
    val downloadedBytes: Long,
    val status: Int,
    val mimeType: String?,
    val timestamp: Long
)

class DownloadActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.cookiegames.smartcookie.utils.LocaleHelper.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        super.applyOverrideConfiguration(
            com.cookiegames.smartcookie.utils.LocaleHelper.keepLocale(baseContext, overrideConfiguration)
        )
    }


    @JvmField
    @Inject
    var mUserPreferences: UserPreferences? = null

    @Inject
    lateinit var downloadManager: DownloadManager

    @Inject
    lateinit var downloadsRepository: DownloadsRepository

    private var downloadAdapter: DownloadAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            loadDownloads()
            if (isPolling) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        this.injector.inject(this)

        val color: Int
        if (mUserPreferences?.useTheme === AppTheme.LIGHT) {
            setTheme(R.style.Theme_SettingsTheme)
            color = ThemeUtils.getColorBackground(this)
            window.setBackgroundDrawable(ColorDrawable(color))
        } else if (mUserPreferences?.useTheme === AppTheme.DARK) {
            setTheme(R.style.Theme_SettingsTheme_Dark)
            color = ThemeUtils.getColorBackground(this)
            window.setBackgroundDrawable(ColorDrawable(color))
        } else {
            setTheme(R.style.Theme_SettingsTheme_Black)
            color = ThemeUtils.getColorBackground(this)
            window.setBackgroundDrawable(ColorDrawable(color))
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val list = findViewById<RecyclerView>(R.id.downloads)
        list.layoutManager = LinearLayoutManager(this)

        downloadAdapter = DownloadAdapter(
            onOpen = { item -> openFile(this, item.filePath, item.mimeType) },
            onCancel = { item -> cancelDownload(item) },
            onDelete = { item -> deleteItem(item) },
            onShare = { item -> shareFile(this, item.filePath, item.mimeType) }
        )
        list.adapter = downloadAdapter
    }

    override fun onResume() {
        super.onResume()
        isPolling = true
        loadDownloads()
    }

    override fun onPause() {
        super.onPause()
        isPolling = false
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        handler.removeCallbacks(pollRunnable)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun loadDownloads() {
        val items = mutableListOf<DownloadItem>()
        val seenPaths = HashSet<String>()
        var hasActiveDownloads = false

        // 1. Query DownloadManager
        try {
            val query = DownloadManager.Query()
            downloadManager.query(query)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val lastModCol = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

                while (cursor.moveToNext()) {
                    val id = if (idCol != -1) cursor.getLong(idCol) else -1L
                    val title = if (titleCol != -1) cursor.getString(titleCol) ?: "download" else "download"
                    val uriStr = if (uriCol != -1) cursor.getString(uriCol) else null
                    val status = if (statusCol != -1) cursor.getInt(statusCol) else DownloadManager.STATUS_SUCCESSFUL
                    val bytes = if (bytesCol != -1) cursor.getLong(bytesCol) else 0L
                    val total = if (totalCol != -1) cursor.getLong(totalCol) else 0L
                    val mime = if (mimeCol != -1) cursor.getString(mimeCol) else null
                    val lastMod = if (lastModCol != -1) cursor.getLong(lastModCol) else 0L

                    var path = ""
                    var fileUri: Uri? = null
                    if (!uriStr.isNullOrEmpty()) {
                        fileUri = Uri.parse(uriStr)
                        path = fileUri.path ?: ""
                    }
                    if (path.isEmpty()) {
                        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        path = File(publicDir, title).absolutePath
                    }

                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
                        hasActiveDownloads = true
                    }

                    seenPaths.add(path)
                    items.add(DownloadItem(id, title, path, fileUri, total, bytes, status, mime, lastMod))
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadActivity", "Error querying DownloadManager", e)
        }

        // 2. Scan Downloads directory for existing files
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists() && downloadDir.isDirectory) {
                val files = downloadDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isFile && !file.name.startsWith(".") && !seenPaths.contains(file.absolutePath)) {
                            val ext = file.extension.toLowerCase(Locale.ROOT)
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                            items.add(
                                DownloadItem(
                                    id = -1L,
                                    title = file.name,
                                    filePath = file.absolutePath,
                                    uri = Uri.fromFile(file),
                                    totalSize = file.length(),
                                    downloadedBytes = file.length(),
                                    status = DownloadManager.STATUS_SUCCESSFUL,
                                    mimeType = mime,
                                    timestamp = file.lastModified()
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadActivity", "Error scanning Downloads folder", e)
        }

        items.sortByDescending { it.timestamp }
        downloadAdapter?.updateList(items)

        // Adjust polling if downloads are running
        handler.removeCallbacks(pollRunnable)
        if (hasActiveDownloads && isPolling) {
            handler.postDelayed(pollRunnable, 1000)
        }
    }

    private fun cancelDownload(item: DownloadItem) {
        if (item.id != -1L) {
            try {
                downloadManager.remove(item.id)
            } catch (e: Exception) {
                Log.e("DownloadActivity", "Error cancelling download", e)
            }
        }
        loadDownloads()
    }

    private fun deleteItem(item: DownloadItem) {
        if (item.id != -1L) {
            try {
                downloadManager.remove(item.id)
            } catch (e: Exception) {
                Log.e("DownloadActivity", "Error removing download", e)
            }
        }
        try {
            val file = File(item.filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e("DownloadActivity", "Error deleting file", e)
        }
        loadDownloads()
    }

    private fun openFile(context: Context, filePath: String, mimeType: String?) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, R.string.cannot_download, Toast.LENGTH_SHORT).show()
            return
        }

        val contentUri = try {
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e("DownloadActivity", "FileProvider error", e)
            Uri.fromFile(file)
        }

        val ext = file.extension.toLowerCase(Locale.ROOT)
        val effectiveMime = mimeType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

        if (ext == "apk") {
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            try {
                context.startActivity(install)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.title_error, Toast.LENGTH_SHORT).show()
            }
        } else {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, effectiveMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(Intent.createChooser(viewIntent, file.name))
            } catch (e: Exception) {
                Toast.makeText(context, R.string.title_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareFile(context: Context, filePath: String, mimeType: String?) {
        val file = File(filePath)
        if (!file.exists()) return

        val contentUri = try {
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        val ext = file.extension.toLowerCase(Locale.ROOT)
        val effectiveMime = mimeType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = effectiveMime
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(shareIntent, file.name))
        } catch (e: Exception) {
            Toast.makeText(context, R.string.title_error, Toast.LENGTH_SHORT).show()
        }
    }

    class DownloadAdapter(
        private val onOpen: (DownloadItem) -> Unit,
        private val onCancel: (DownloadItem) -> Unit,
        private val onDelete: (DownloadItem) -> Unit,
        private val onShare: (DownloadItem) -> Unit
    ) : RecyclerView.Adapter<DownloadViewHolder>() {

        private var allItems: MutableList<DownloadItem> = mutableListOf()
        private var displayedItems: MutableList<DownloadItem> = mutableListOf()
        private var currentQuery: String = ""

        fun updateList(newList: List<DownloadItem>) {
            allItems.clear()
            allItems.addAll(newList)
            applyFilter()
        }

        fun filter(query: String) {
            currentQuery = query
            applyFilter()
        }

        private fun applyFilter() {
            displayedItems.clear()
            if (currentQuery.isBlank()) {
                displayedItems.addAll(allItems)
            } else {
                val q = currentQuery.toLowerCase(Locale.ROOT)
                for (item in allItems) {
                    if (item.title.toLowerCase(Locale.ROOT).contains(q)) {
                        displayedItems.add(item)
                    }
                }
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.download_item, parent, false)
            return DownloadViewHolder(v, onOpen, onCancel, onDelete, onShare)
        }

        override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
            holder.bind(displayedItems[position])
        }

        override fun getItemCount(): Int = displayedItems.size
    }

    class DownloadViewHolder(
        itemView: View,
        private val onOpen: (DownloadItem) -> Unit,
        private val onCancel: (DownloadItem) -> Unit,
        private val onDelete: (DownloadItem) -> Unit,
        private val onShare: (DownloadItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val dlIcon: ImageView = itemView.findViewById(R.id.dl_icon)
        private val dlName: TextView = itemView.findViewById(R.id.dl_name)
        private val dlProgress: ProgressBar = itemView.findViewById(R.id.dl_progress)
        private val dlSpeed: TextView = itemView.findViewById(R.id.dl_speed)
        private val dlDownload: TextView = itemView.findViewById(R.id.dl_download)
        private val dlStatus: Button = itemView.findViewById(R.id.dl_status)

        fun bind(item: DownloadItem) {
            dlName.text = item.title
            dlName.isSelected = true

            // Set icon by extension
            val ext = item.filePath.substringAfterLast('.', "").toLowerCase(Locale.ROOT)
            when (ext) {
                "pdf" -> dlIcon.setImageResource(R.drawable.icon_pdf)
                "zip", "rar", "7z", "tar", "gz" -> dlIcon.setImageResource(R.drawable.icon_zip)
                "apk" -> dlIcon.setImageResource(R.drawable.icon_apk)
                "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> dlIcon.setImageResource(R.drawable.icon_txt)
                "jpg", "jpeg", "gif", "png", "webp", "bmp" -> dlIcon.setImageResource(R.drawable.icon_img)
                else -> dlIcon.setImageResource(R.drawable.ic_file_download_black)
            }

            val isRunning = item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING
            val isPaused = item.status == DownloadManager.STATUS_PAUSED
            val isFailed = item.status == DownloadManager.STATUS_FAILED

            if (isRunning || isPaused) {
                dlProgress.visibility = View.VISIBLE
                val progress = if (item.totalSize > 0) {
                    ((item.downloadedBytes * 100) / item.totalSize).toInt()
                } else 0
                dlProgress.progress = progress
                dlSpeed.text = "$progress%"
                dlDownload.text = "${DownloadUtil.getDataSize(item.downloadedBytes)} / ${DownloadUtil.getDataSize(item.totalSize)}"
                dlStatus.text = itemView.context.getString(android.R.string.cancel)
                dlStatus.setOnClickListener { onCancel(item) }
            } else if (isFailed) {
                dlProgress.visibility = View.GONE
                dlSpeed.text = "Error"
                dlDownload.text = ""
                dlStatus.text = itemView.context.getString(R.string.action_delete)
                dlStatus.setOnClickListener { onDelete(item) }
            } else {
                // Successful or local file
                dlProgress.visibility = View.GONE
                dlSpeed.text = ""
                val sizeToDisplay = if (item.totalSize > 0) item.totalSize else File(item.filePath).length()
                dlDownload.text = DownloadUtil.getDataSize(sizeToDisplay)
                dlStatus.text = itemView.context.getString(R.string.action_open)
                dlStatus.setOnClickListener { onOpen(item) }
            }

            itemView.setOnClickListener {
                if (!isRunning && !isPaused && !isFailed) {
                    onOpen(item)
                }
            }

            itemView.setOnLongClickListener {
                val context = itemView.context
                val options = arrayOf(
                    context.getString(R.string.action_open),
                    context.getString(R.string.action_share),
                    context.getString(R.string.action_delete)
                )
                MaterialAlertDialogBuilder(context)
                    .setTitle(item.title)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> onOpen(item)
                            1 -> onShare(item)
                            2 -> {
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.confirm_delete)
                                    .setPositiveButton(R.string.yes) { _, _ -> onDelete(item) }
                                    .setNegativeButton(R.string.no, null)
                                    .show()
                            }
                        }
                    }
                    .show()
                true
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.download, menu)

        val searchItem: MenuItem = menu.findItem(R.id.action_search)
        val searchView: SearchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(this)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onQueryTextChange(query: String?): Boolean {
        downloadAdapter?.filter(query.orEmpty())
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }
}