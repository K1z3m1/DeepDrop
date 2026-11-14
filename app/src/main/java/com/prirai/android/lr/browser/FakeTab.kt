package com.prirai.android.lr.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.doOnNextLayout
import androidx.core.view.updateLayoutParams
import com.prirai.android.lr.R
import com.prirai.android.lr.components.toolbar.ToolbarPosition
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import mozilla.components.concept.base.images.ImageLoadRequest
import kotlin.math.max

class BrowserThumbnailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : AppCompatImageView(context, attrs, defStyle) {
    private var matrixDrawable: Drawable? = null
    private var matrixDrawableWidth = 0
    private var matrixViewWidth = 0

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onDraw(canvas: Canvas) {
        val currentDrawable = drawable
        val drawableWidth = currentDrawable?.intrinsicWidth ?: 0
        if (
            currentDrawable !== matrixDrawable ||
            drawableWidth != matrixDrawableWidth ||
            width != matrixViewWidth
        ) {
            matrixDrawable = currentDrawable
            matrixDrawableWidth = drawableWidth
            matrixViewWidth = width

            if (currentDrawable != null && width > 0 && drawableWidth > 0) {
                val scale = width.toFloat() / drawableWidth
                imageMatrix = Matrix().apply { setScale(scale, scale) }
            }
        }

        super.onDraw(canvas)
    }
}

class FakeTab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {
    private val fakeToolbar: View
    private val previewThumbnail: BrowserThumbnailView

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
