package com.prirai.android.nira.browser

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.updateLayoutParams
import com.prirai.android.nira.ext.components
import com.prirai.android.nira.preferences.UserPreferences
import mozilla.components.browser.thumbnails.loader.ThumbnailLoader
import mozilla.components.concept.base.images.ImageLoadRequest
import com.prirai.android.nira.components.toolbar.ToolbarPosition
import android.view.View
import com.prirai.android.nira.R
import mozilla.components.browser.tabstray.thumbnail.TabThumbnailView
import kotlin.math.max

class FakeTab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val fakeToolbar: View
    private val previewThumbnail: TabThumbnailView

    init {
        LayoutInflater.from(context).inflate(R.layout.tab_preview, this, true)
        fakeToolbar = findViewById(R.id.fake_toolbar)
        previewThumbnail = findViewById(R.id.previewThumbnail)
    }
    private val thumbnailLoader = context.components.thumbnailLoader
    private val preferences = UserPreferences(context)

    init {
        if (preferences.toolbarPosition != ToolbarPosition.BOTTOM.ordinal) {
            fakeToolbar.updateLayoutParams<LayoutParams> {
                gravity = Gravity.TOP
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        previewThumbnail.translationY = if (preferences.shouldUseBottomToolbar) {
            fakeToolbar.height.toFloat()
        } else {
            0f
        }
    }

    fun loadPreviewThumbnail(thumbnailId: String) {
        doOnNextLayout {
            val thumbnailSize = max(previewThumbnail.height, previewThumbnail.width)
            thumbnailLoader.loadIntoView(
                previewThumbnail,
                ImageLoadRequest(thumbnailId, thumbnailSize, false)
            )
        }
    }
}
