package com.prirai.android.lr.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.Checkable
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat

open class MaterialSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.switchPreferenceCompatStyle,
    defStyleRes: Int = 0
) : SwitchPreferenceCompat(context, attrs, defStyleAttr, defStyleRes) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        val switchView = holder.findViewById(android.R.id.switch_widget)
        if (switchView is Checkable) {
            switchView.isChecked = isChecked
            
            switchView.isClickable = false
            switchView.isFocusable = false
        }
    }
}
