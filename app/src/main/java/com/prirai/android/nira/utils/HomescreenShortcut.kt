package com.prirai.android.nira.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import mozilla.components.browser.state.state.SessionState

/** Pins a regular shortcut that opens the page in the main browser. */
object HomescreenShortcut {
    fun add(context: Context, session: SessionState, icon: Bitmap?) {
        val url = session.content.url
        val title = session.content.title?.takeIf { it.isNotBlank() } ?: url

        val intent = Intent(context, com.prirai.android.nira.BrowserActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = url.toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val shortcut = ShortcutInfoCompat.Builder(context, "shortcut_$url")
            .setShortLabel(title)
            .setLongLabel(title)
            .setIntent(intent)
            .apply {
                if (icon != null) {
                    setIcon(IconCompat.createWithBitmap(icon))
                } else {
                    setIcon(IconCompat.createWithResource(context, com.prirai.android.nira.R.mipmap.ic_launcher))
                }
            }
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}
