package com.prirai.android.lr.components.toolbar

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.ui.widgets.behavior.EngineViewScrollingGesturesBehavior
import com.google.android.material.elevation.ElevationOverlayProvider

class BottomToolbarContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), ScrollableToolbar {
    private var initialPaddingBottom = 0
    
    init {
        val elevationDp = 3f * resources.displayMetrics.density
        val elevatedColor = ElevationOverlayProvider(context)
            .compositeOverlayWithThemeSurfaceColorIfNeeded(elevationDp)
        setBackgroundColor(elevatedColor)
        
        initialPaddingBottom = paddingBottom
        
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            
            view.setPadding(
                paddingLeft,
                paddingTop,
                paddingRight,
                initialPaddingBottom + navigationBars.bottom
            )
            
            insets
        }
    }
    
    override fun enableScrolling() {
        (layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            (behavior as? EngineViewScrollingGesturesBehavior)?.enableScrolling()
        }
    }

    override fun disableScrolling() {
        (layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            (behavior as? EngineViewScrollingGesturesBehavior)?.disableScrolling()
        }
    }

    override fun expand() {
        (layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            (behavior as? EngineViewScrollingGesturesBehavior)?.forceExpand()
        }
    }

    override fun collapse() {
        (layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            (behavior as? EngineViewScrollingGesturesBehavior)?.forceCollapse()
        }
    }
}