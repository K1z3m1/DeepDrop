package com.prirai.android.lr.settings.fragment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.Preference.OnPreferenceClickListener
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.prirai.android.lr.R
import androidx.preference.SeekBarPreference
import com.prirai.android.lr.settings.MaterialSwitchPreference

abstract class BaseSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferencesName = "scw_preferences"
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        listView.apply {
            setDivider(null)
            setDividerHeight(0)
            
            addItemDecoration(GroupedPreferenceItemDecoration())
        }
    }
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    }

    private inner class GroupedPreferenceItemDecoration : RecyclerView.ItemDecoration() {
        private val dividerPaint = Paint().apply {
            color = ContextCompat.getColor(requireContext(), R.color.preference_divider)
            strokeWidth = 1f
        }
        
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            
            val adapter = parent.adapter ?: return
            
            val isCategory = adapter.getItemViewType(position) == 0 // PreferenceCategory type
            
            if (isCategory) {
                outRect.top = if (position > 0) (16 * resources.displayMetrics.density).toInt() else 0
                return
            }
            
            val isFirstInGroup = position == 0 || adapter.getItemViewType(position - 1) == 0
            val isLastInGroup = position == adapter.itemCount - 1 || 
                                (position < adapter.itemCount - 1 && adapter.getItemViewType(position + 1) == 0)
            
            val horizontalMargin = (16 * resources.displayMetrics.density).toInt()
            outRect.left = horizontalMargin
            outRect.right = horizontalMargin
            
            val verticalMargin = (8 * resources.displayMetrics.density).toInt()
            outRect.top = if (isFirstInGroup && position > 0) verticalMargin else 0
            outRect.bottom = if (isLastInGroup) verticalMargin else 0
            
            applyBackground(view, isFirstInGroup, isLastInGroup)
        }
        
        override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        }

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter ?: return
            
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                
                if (position == RecyclerView.NO_POSITION) continue

                if (position == adapter.itemCount - 1) continue
                
                val isCurrentCategory = adapter.getItemViewType(position) == 0
                val isNextCategory = adapter.getItemViewType(position + 1) == 0

                if (isCurrentCategory || isNextCategory) continue
                
                val dividerLeft = child.left.toFloat()
                val dividerRight = child.right.toFloat()
                val dividerY = child.bottom.toFloat()
                
                canvas.drawLine(dividerLeft, dividerY, dividerRight, dividerY, dividerPaint)
            }
        }
        
        private fun applyBackground(view: View, isFirst: Boolean, isLast: Boolean) {
            val backgroundRes = when {
                isFirst && isLast -> R.drawable.preference_background_single
                isFirst -> R.drawable.preference_background_top
                isLast -> R.drawable.preference_background_bottom
                else -> R.drawable.preference_background_middle
            }
            view.setBackgroundResource(backgroundRes)
        }
    }

    protected fun switchPreference(
        preference: String,
        isChecked: Boolean,
        isEnabled: Boolean = true,
        summary: String? = null,
        onCheckChange: (Boolean) -> Unit
    ) = findPreference<MaterialSwitchPreference>(preference)?.apply {
        val prefs = preferenceManager.sharedPreferences
        if (prefs != null) {
            val currentValue = prefs.getBoolean(preference, !isChecked) // Use opposite as default to detect mismatch
            if (currentValue != isChecked) {
                prefs.edit().putBoolean(preference, isChecked).apply()
            }
        }
        
        this.isChecked = isChecked
        
        this.isEnabled = isEnabled
        summary?.let {
            this.summary = summary
        }
        onPreferenceChangeListener = OnPreferenceChangeListener { _, any: Any ->
            onCheckChange(any as Boolean)
            true
        }
    }

    protected fun clickablePreference(
        preference: String,
        isEnabled: Boolean = true,
        summary: String? = null,
        onClick: () -> Unit
    ) = clickableDynamicPreference(
        preference = preference,
        isEnabled = isEnabled,
        summary = summary,
        onClick = { onClick() }
    )

    protected fun clickableDynamicPreference(
        preference: String,
        isEnabled: Boolean = true,
        summary: String? = null,
        onClick: (SummaryUpdater) -> Unit
    ) = findPreference<Preference>(preference)?.apply {
        this.isEnabled = isEnabled
        summary?.let {
            this.summary = summary
        }
        val summaryUpdate = SummaryUpdater(this)
        onPreferenceClickListener = OnPreferenceClickListener {
            onClick(summaryUpdate)
            true
        }
    }

    protected fun seekbarPreference(
        preference: String,
        isEnabled: Boolean = true,
        summary: String? = null,
        onStateChanged: (Int) -> Unit
    ) = findPreference<SeekBarPreference>(preference)?.apply {
        this.isEnabled = isEnabled
        summary?.let {
            this.summary = summary
        }
        SummaryUpdater(this)
        onPreferenceChangeListener = OnPreferenceChangeListener { preference: Preference, newValue: Any ->
            onStateChanged(newValue as Int)
            true
        }
    }
}