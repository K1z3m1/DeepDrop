package com.prirai.android.lr.integration

import android.content.Context
import android.view.View
import mozilla.components.concept.engine.HitResult
import mozilla.components.feature.contextmenu.ContextMenuCandidate
import mozilla.components.ui.widgets.SnackbarDelegate
import android.content.ClipData
import android.content.ClipboardManager

fun createCopyLinkTextCandidate(
    context: Context,
    parentView: View,
    snackbarDelegate: SnackbarDelegate
): ContextMenuCandidate {
    return ContextMenuCandidate(
        id = "copy-link-text",
        label = "Copy link",
        showFor = { _, hitResult ->
            when (hitResult) {
                is HitResult.IMAGE_SRC -> true
                is HitResult.IMAGE -> true
                else -> false
            }
        },
        action = { _, hitResult ->
            val linkText = when (hitResult) {
                is HitResult.IMAGE_SRC -> hitResult.uri
                is HitResult.IMAGE -> hitResult.src
                else -> ""
            }
            
            if (linkText.isNotEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("link", linkText)
                clipboard.setPrimaryClip(clip)
                
                snackbarDelegate.show(
                    snackBarParentView = parentView,
                    text = "Link copied",
                    duration = 3000
                )
            }
        }
    )
}
