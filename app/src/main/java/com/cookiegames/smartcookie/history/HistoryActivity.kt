/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Created by CookieJarApps 10/01/2020 */

package com.cookiegames.smartcookie.history

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.MainActivity
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.database.HistoryEntry
import com.cookiegames.smartcookie.database.history.HistoryRepository
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.LightningDialogBuilder
import com.cookiegames.smartcookie.download.DownloadActivity
import com.cookiegames.smartcookie.favicon.FaviconModel
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.ThemeUtils
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

class HistoryActivity : AppCompatActivity() {

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

    @JvmField
    @Inject
    var dialogBuilder: LightningDialogBuilder? = null

    @Inject
    internal lateinit var historyRepository: HistoryRepository

    @Inject
    internal lateinit var faviconModel: FaviconModel

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var searchInput: EditText
    private lateinit var searchClear: ImageView
    private lateinit var backButton: ImageButton
    private lateinit var tabBookmarks: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabSavedPages: TextView

    private lateinit var bottomBarNormal: View
    private lateinit var bottomBarEdit: View
    private lateinit var btnTabs: TextView
    private lateinit var btnClearAll: TextView
    private lateinit var btnEdit: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnDeleteSelected: TextView
    private lateinit var btnDone: TextView

    private val compositeDisposable = CompositeDisposable()
    private val selectedUrls = HashSet<String>()
    private var isEditMode = false
    private var rawHistoryList: List<HistoryEntry> = emptyList()
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)

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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = color
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        initViews()
        setupListeners()
        loadHistory()
    }

    private fun initViews() {
        list = findViewById(R.id.history)
        emptyView = findViewById(R.id.empty_history_view)
        searchInput = findViewById(R.id.history_search_input)
        searchClear = findViewById(R.id.history_search_clear)
        backButton = findViewById(R.id.history_back_button)
        tabBookmarks = findViewById(R.id.tab_bookmarks)
        tabHistory = findViewById(R.id.tab_history)
        tabSavedPages = findViewById(R.id.tab_saved_pages)

        bottomBarNormal = findViewById(R.id.bottom_bar_normal)
        bottomBarEdit = findViewById(R.id.bottom_bar_edit)
        btnTabs = findViewById(R.id.btn_tabs)
        btnClearAll = findViewById(R.id.btn_clear_all)
        btnEdit = findViewById(R.id.btn_edit)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
        btnDone = findViewById(R.id.btn_done)

        list.layoutManager = LinearLayoutManager(this)
        historyAdapter = HistoryAdapter(
            faviconModel = faviconModel,
            onItemClick = { entry ->
                if (isEditMode) {
                    toggleItemSelection(entry.url)
                } else {
                    val i = Intent(ACTION_VIEW).apply {
                        data = Uri.parse(entry.url)
                        setPackage(packageName)
                    }
                    startActivity(i)
                }
            },
            onItemLongClick = { entry ->
                if (!isEditMode) {
                    enterEditMode()
                    selectedUrls.add(entry.url)
                    historyAdapter.notifyDataSetChanged()
                    updateEditButtons()
                } else {
                    toggleItemSelection(entry.url)
                }
            },
            isUrlSelected = { url -> selectedUrls.contains(url) }
        )
        list.adapter = historyAdapter
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            if (isEditMode) {
                exitEditMode()
            } else {
                finish()
            }
        }

        tabBookmarks.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_BOOKMARKS", true)
            }
            startActivity(intent)
            finish()
        }

        tabHistory.setOnClickListener {
            list.smoothScrollToPosition(0)
        }

        tabSavedPages.setOnClickListener {
            startActivity(Intent(this, DownloadActivity::class.java))
            finish()
        }

        // Search Bar Filtering
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                searchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilter(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        searchClear.setOnClickListener {
            searchInput.setText("")
        }

        // Bottom Bar Normal Actions
        btnTabs.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_TABS", true)
            }
            startActivity(intent)
            finish()
        }

        btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.title_clear_history)
                .setMessage("¿Deseas borrar todo el historial de navegación?")
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    compositeDisposable.add(
                        historyRepository.deleteHistory()
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe({
                                selectedUrls.clear()
                                loadHistory()
                                Toast.makeText(this, R.string.message_clear_history, Toast.LENGTH_SHORT).show()
                            }, { e ->
                                Log.e("HistoryActivity", "Error clearing history", e)
                            })
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        btnEdit.setOnClickListener {
            enterEditMode()
        }

        // Bottom Bar Edit Actions
        btnSelectAll.setOnClickListener {
            val visibleEntries = historyAdapter.getVisibleEntries()
            if (selectedUrls.size == visibleEntries.size && visibleEntries.isNotEmpty()) {
                selectedUrls.clear()
            } else {
                selectedUrls.clear()
                for (entry in visibleEntries) {
                    selectedUrls.add(entry.url)
                }
            }
            historyAdapter.notifyDataSetChanged()
            updateEditButtons()
        }

        btnDeleteSelected.setOnClickListener {
            if (selectedUrls.isEmpty()) return@setOnClickListener

            val urlsToDelete = selectedUrls.toList()
            compositeDisposable.add(
                historyRepository.deleteHistoryEntries(urlsToDelete)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        selectedUrls.clear()
                        loadHistory()
                        Toast.makeText(this, "Eliminado del historial", Toast.LENGTH_SHORT).show()
                        if (isEditMode) {
                            updateEditButtons()
                        }
                    }, { e ->
                        Log.e("HistoryActivity", "Error deleting selected entries", e)
                    })
            )
        }

        btnDone.setOnClickListener {
            exitEditMode()
        }
    }

    private fun enterEditMode() {
        isEditMode = true
        bottomBarNormal.visibility = View.GONE
        bottomBarEdit.visibility = View.VISIBLE
        historyAdapter.setEditMode(true)
        updateEditButtons()
    }

    private fun exitEditMode() {
        isEditMode = false
        selectedUrls.clear()
        bottomBarNormal.visibility = View.VISIBLE
        bottomBarEdit.visibility = View.GONE
        historyAdapter.setEditMode(false)
    }

    private fun toggleItemSelection(url: String) {
        if (selectedUrls.contains(url)) {
            selectedUrls.remove(url)
        } else {
            selectedUrls.add(url)
        }
        historyAdapter.notifyDataSetChanged()
        updateEditButtons()
    }

    private fun updateEditButtons() {
        val totalVisible = historyAdapter.getVisibleEntries().size
        btnSelectAll.text = if (selectedUrls.size == totalVisible && totalVisible > 0) "Deseleccionar" else "Seleccionar todo"
        btnDeleteSelected.text = if (selectedUrls.isNotEmpty()) "Eliminar (${selectedUrls.size})" else "Eliminar"
        btnDeleteSelected.isEnabled = selectedUrls.isNotEmpty()
        btnDeleteSelected.alpha = if (selectedUrls.isNotEmpty()) 1.0f else 0.4f
    }

    fun dataChanged() {
        loadHistory()
    }

    private fun loadHistory() {
        compositeDisposable.add(
            historyRepository
                .lastHundredVisitedHistoryEntries()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ listEntries ->
                    rawHistoryList = listEntries
                    applyFilter(searchInput.text?.toString()?.trim() ?: "")
                    if (rawHistoryList.isEmpty() && isEditMode) {
                        exitEditMode()
                    }
                }, { e ->
                    Log.e("HistoryActivity", "Error loading history", e)
                })
        )
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isEmpty()) {
            rawHistoryList
        } else {
            val queryLower = query.toLowerCase(Locale.getDefault())
            rawHistoryList.filter {
                it.title.toLowerCase(Locale.getDefault()).contains(queryLower) ||
                it.url.toLowerCase(Locale.getDefault()).contains(queryLower)
            }
        }

        val groupedItems = groupEntriesByDate(filtered)
        historyAdapter.submitItems(groupedItems)
        updateEmptyState()
        if (isEditMode) {
            updateEditButtons()
        }
    }

    private fun updateEmptyState() {
        val isEmpty = historyAdapter.itemCount == 0
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun getDayStartTimestamp(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getHeaderTitleForDay(dayStartMillis: Long): String {
        val now = Calendar.getInstance()
        val todayStart = getDayStartTimestamp(now.timeInMillis)
        val calYesterday = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayStart = calYesterday.timeInMillis

        return when (dayStartMillis) {
            todayStart -> "Hoy"
            yesterdayStart -> "Ayer"
            else -> {
                val sdf = SimpleDateFormat("EEE., MMM dd", Locale.getDefault())
                sdf.format(Date(dayStartMillis)).toLowerCase(Locale.getDefault())
            }
        }
    }

    private fun groupEntriesByDate(entries: List<HistoryEntry>): List<HistoryListItem> {
        val items = ArrayList<HistoryListItem>()
        var currentDay = -1L

        for (entry in entries) {
            val day = getDayStartTimestamp(entry.lastTimeVisited)
            if (day != currentDay) {
                currentDay = day
                items.add(HistoryListItem.Header(getHeaderTitleForDay(day)))
            }
            items.add(HistoryListItem.Entry(entry))
        }
        return items
    }

    override fun onBackPressed() {
        if (isEditMode) {
            exitEditMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.clear()
        historyAdapter.cleanup()
    }

    sealed class HistoryListItem {
        data class Header(val title: String) : HistoryListItem()
        data class Entry(val entry: HistoryEntry) : HistoryListItem()
    }

    class HistoryAdapter(
        private val faviconModel: FaviconModel,
        private val onItemClick: (HistoryEntry) -> Unit,
        private val onItemLongClick: (HistoryEntry) -> Unit,
        private val isUrlSelected: (String) -> Boolean
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<HistoryListItem> = emptyList()
        private var isEditMode: Boolean = false
        private val faviconSubscriptions = HashMap<String, Disposable>()

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ENTRY = 1
        }

        fun submitItems(newItems: List<HistoryListItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun setEditMode(edit: Boolean) {
            isEditMode = edit
            notifyDataSetChanged()
        }

        fun getVisibleEntries(): List<HistoryEntry> {
            return items.filterIsInstance<HistoryListItem.Entry>().map { it.entry }
        }

        fun cleanup() {
            for (sub in faviconSubscriptions.values) {
                sub.dispose()
            }
            faviconSubscriptions.clear()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is HistoryListItem.Header -> TYPE_HEADER
                is HistoryListItem.Entry -> TYPE_ENTRY
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                val view = inflater.inflate(R.layout.item_history_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.history_row, parent, false)
                EntryViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HistoryListItem.Header -> {
                    (holder as HeaderViewHolder).headerText.text = item.title
                }
                is HistoryListItem.Entry -> {
                    val entryHolder = holder as EntryViewHolder
                    val entry = item.entry

                    val displayTitle = if (entry.title.isNotBlank()) entry.title.trim() else formatHost(entry.url)
                    entryHolder.title.text = displayTitle
                    entryHolder.url.text = formatCleanUrl(entry.url)

                    // Favicon loading
                    entryHolder.favicon.tag = entry.url
                    val defaultIcon = faviconModel.createDefaultBitmapForTitle(displayTitle)
                    entryHolder.favicon.setImageBitmap(defaultIcon)

                    faviconSubscriptions[entry.url]?.dispose()
                    faviconSubscriptions[entry.url] = faviconModel
                        .faviconForUrl(entry.url, displayTitle)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ bitmap ->
                            if (entryHolder.favicon.tag == entry.url) {
                                entryHolder.favicon.setImageBitmap(bitmap)
                            }
                        }, {})

                    // Checkbox in edit mode
                    if (isEditMode) {
                        entryHolder.check.visibility = View.VISIBLE
                        val selected = isUrlSelected(entry.url)
                        entryHolder.check.setImageResource(
                            if (selected) R.drawable.ic_history_check_on else R.drawable.ic_history_check_off
                        )
                    } else {
                        entryHolder.check.visibility = View.GONE
                    }

                    entryHolder.itemView.setOnClickListener {
                        onItemClick(entry)
                    }

                    entryHolder.itemView.setOnLongClickListener {
                        onItemLongClick(entry)
                        true
                    }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            if (holder is EntryViewHolder) {
                (holder.favicon.tag as? String)?.let { url ->
                    faviconSubscriptions.remove(url)?.dispose()
                }
            }
        }

        override fun getItemCount(): Int = items.size

        private fun formatHost(url: String): String {
            return try {
                val host = Uri.parse(url).host?.removePrefix("www.")
                if (!host.isNullOrBlank()) host else url
            } catch (e: Exception) {
                url
            }
        }

        private fun formatCleanUrl(url: String): String {
            return try {
                url.removePrefix("https://").removePrefix("http://")
            } catch (e: Exception) {
                url
            }
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val headerText: TextView = view.findViewById(R.id.historyDateHeader)
        }

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val favicon: ImageView = view.findViewById(R.id.historyFavicon)
            val title: TextView = view.findViewById(R.id.historyTitle)
            val url: TextView = view.findViewById(R.id.historyUrl)
            val check: ImageView = view.findViewById(R.id.historyCheck)
        }
    }
}