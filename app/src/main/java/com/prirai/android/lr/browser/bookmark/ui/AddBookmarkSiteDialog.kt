@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.bookmark.ui

import android.content.Context
import android.content.DialogInterface
import android.view.ViewGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import com.prirai.android.lr.browser.bookmark.items.BookmarkSiteItem
import com.prirai.android.lr.browser.bookmark.repository.BookmarkManager
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.ui.theme.LRTheme

class AddBookmarkSiteDialog private constructor(
    private val context: Context,
    private val manager: BookmarkManager?,
    private val item: BookmarkSiteItem?,
    private val initialTitle: String,
    private val initialUrl: String,
) {
    constructor(context: Context, manager: BookmarkManager, item: BookmarkSiteItem) :
        this(context, manager, item, item.title ?: "", item.url)

    constructor(context: Context, title: String, url: String) :
        this(context, null, null, title, url)

    private var clickListener: DialogInterface.OnClickListener? = null

    fun setOnClickListener(l: DialogInterface.OnClickListener): AddBookmarkSiteDialog {
        clickListener = l
        return this
    }

    inline fun setOnClickListener(crossinline l: (DialogInterface, Int) -> Unit): AddBookmarkSiteDialog {
        return setOnClickListener(DialogInterface.OnClickListener { dialog, which -> l(dialog, which) })
    }

    fun show() {
        val activity = context as? FragmentActivity ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val composeView = ComposeView(activity)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val prefs = UserPreferences(context)
            LRTheme(
                darkTheme = ThemeManager.isDarkMode(context),
                amoledMode = prefs.amoledMode,
                dynamicColor = prefs.dynamicColors,
            ) {
                BookmarkSiteEditDialog(
                    manager = manager ?: BookmarkManager.getInstance(context),
                    item = item,
                    initialTitle = initialTitle,
                    initialUrl = initialUrl,
                    onDismiss = { detach(composeView) },
                    onSaved = {
                        detach(composeView)
                        clickListener?.onClick(DialogSentinel, DialogInterface.BUTTON_POSITIVE)
                    },
                )
            }
        }
        content.addView(composeView)
    }

    private fun detach(composeView: ComposeView) {
        (composeView.parent as? ViewGroup)?.removeView(composeView)
    }

    private object DialogSentinel : DialogInterface {
        override fun dismiss() {
        }

        override fun cancel() {
        }
    }
}
