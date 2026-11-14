package com.prirai.android.lr.components.toolbar

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import mozilla.components.browser.menu.item.BrowserMenuImageText
import android.util.TypedValue
import mozilla.components.browser.menu.BrowserMenu

class ThemedBrowserMenuImageText(
    label: String,
    @DrawableRes imageResource: Int,
    listener: () -> Unit = {}
) : BrowserMenuImageText(
    label,
    imageResource,
    iconTintColorResource = android.R.color.transparent, // We'll apply color programmatically
    listener = listener
) {
    override fun bind(menu: BrowserMenu, view: View) {
        super.bind(menu, view)
        
        val context = view.context
        val m3Color = resolveM3Color(context)
        
        val iconView = view.findViewById<ImageView>(
            mozilla.components.browser.menu.R.id.image
        )
        iconView?.setColorFilter(m3Color)
        
        val textView = view.findViewById<TextView>(
            mozilla.components.browser.menu.R.id.text
        )
        textView?.setTextColor(m3Color)
    }
    
    private fun resolveM3Color(context: Context): Int {
        val typedValue = TypedValue()
        val resolved = context.theme.resolveAttribute(
            MaterialR.attr.colorOnSurface,
            typedValue,
            true
        )
        
        return if (resolved && typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else if (resolved) {
            typedValue.data
        } else {
            ContextCompat.getColor(context, com.prirai.android.lr.R.color.menu_text_color)
        }
    }
}
