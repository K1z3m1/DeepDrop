package com.cookiegames.smartcookie.browser.tabs

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.extensions.desaturate

/**
 * Adapter for the modern bottom sheet tabs manager.
 */
class TabsBottomSheetAdapter(
    private val isDarkTheme: Boolean,
    private val onTabSelected: (position: Int) -> Unit,
    private val onTabClosed: (position: Int) -> Unit
) : RecyclerView.Adapter<TabsBottomSheetAdapter.ViewHolder>() {

    private var tabList: List<TabViewState> = emptyList()

    fun showTabs(tabs: List<TabViewState>) {
        val oldList = tabList
        tabList = tabs
        DiffUtil.calculateDiff(TabViewStateDiffCallback(oldList, tabList)).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab_bottom_sheet, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tab = tabList[position]

        holder.title.text = tab.title

        if (tab.favicon != null) {
            if (tab.isForegroundTab) {
                holder.favicon.setImageBitmap(tab.favicon)
            } else {
                holder.favicon.setImageBitmap(tab.favicon.desaturate())
            }
        } else {
            holder.favicon.setImageResource(R.drawable.ic_webpage)
        }

        val textColorActive = if (isDarkTheme) 0xFF5C87F7.toInt() else 0xFF2A60F5.toInt()
        val textColorInactive = if (isDarkTheme) 0xFFD4D8E2.toInt() else 0xFF202124.toInt()
        val closeTint = if (isDarkTheme) 0xFF8E929C.toInt() else 0xFF5F6368.toInt()

        holder.closeButton.setColorFilter(closeTint)

        if (tab.isForegroundTab) {
            holder.title.setTextColor(textColorActive)
            holder.title.typeface = Typeface.DEFAULT_BOLD
        } else {
            holder.title.setTextColor(textColorInactive)
            holder.title.typeface = Typeface.DEFAULT
        }

        holder.itemView.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onTabSelected(pos)
            }
        }

        holder.closeButton.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onTabClosed(pos)
            }
        }
    }

    override fun getItemCount(): Int = tabList.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val favicon: ImageView = view.findViewById(R.id.tab_bottom_favicon)
        val title: TextView = view.findViewById(R.id.tab_bottom_title)
        val closeButton: ImageView = view.findViewById(R.id.tab_bottom_close)
    }
}
