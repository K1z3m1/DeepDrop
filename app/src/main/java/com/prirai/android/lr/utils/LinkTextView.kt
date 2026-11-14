
package com.prirai.android.lr.utils

import android.content.Context
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK
import androidx.appcompat.widget.AppCompatTextView

class LinkTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        val extras = info?.extras
        extras?.putCharSequence(
            "AccessibilityNodeInfo.roleDescription",
            "link",
        )
        info?.isLongClickable = false
        info?.removeAction(ACTION_LONG_CLICK)
    }
}
