package com.prirai.android.lr.components.toolbar.modern

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import com.prirai.android.lr.components.toolbar.unified.UnifiedToolbar
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import mozilla.components.concept.engine.EngineView

class ModernScrollBehavior(
    context: Context,
    attrs: AttributeSet? = null,
) : CoordinatorLayout.Behavior<View>(context, attrs) {
    private var isScrollingEnabled = true

    private var scrollYAccumulator = 0

    private val scrollThresholdPx: Int =
        (56 * context.resources.displayMetrics.density).toInt()

    override fun onLayoutChild(
        parent: CoordinatorLayout,
        child: View,
        layoutDirection: Int,
    ): Boolean {
        findEngineView(parent)?.let { engine ->
            when (child) {
                is UnifiedToolbar -> child.setEngineView(engine)
                is ModernToolbarSystem -> child.setEngineView(engine)
            }
        }
        return super.onLayoutChild(parent, child, layoutDirection)
    }

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        directTargetChild: View,
        target: View,
        axes: Int,
        type: Int,
    ): Boolean {
        return isScrollingEnabled && axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0
    }

    override fun onNestedPreScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int,
    ) {
        if (!isScrollingEnabled) return
        accumulateAndMaybeEnterMinimal(child, dy)
    }

    override fun onNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
    ) {
        if (!isScrollingEnabled) return
        accumulateAndMaybeEnterMinimal(child, dyUnconsumed)
    }

    override fun onStopNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        target: View,
        type: Int,
    ) {
    }

    private fun accumulateAndMaybeEnterMinimal(child: View, dy: Int) {
        val toolbar = child as? UnifiedToolbar ?: return
        when {
            dy > 0 -> {
                if (scrollYAccumulator < 0) scrollYAccumulator = 0
                scrollYAccumulator += dy
                if (!toolbar.isMinimal() && scrollYAccumulator >= scrollThresholdPx) {
                    toolbar.enterMinimalState()
                    scrollYAccumulator = 0
                }
            }
            dy < 0 -> {
                if (scrollYAccumulator > 0) scrollYAccumulator = 0
                scrollYAccumulator += dy
                if (toolbar.isMinimal() && -scrollYAccumulator >= scrollThresholdPx) {
                    toolbar.exitMinimalState()
                    scrollYAccumulator = 0
                }
            }
        }
    }

    private fun findEngineView(coordinatorLayout: CoordinatorLayout): EngineView? {
        for (i in 0 until coordinatorLayout.childCount) {
            val child = coordinatorLayout.getChildAt(i)

            if (child is EngineView) {
                return child
            }

            if (child is FragmentContainerView) {
                return searchForEngineView(child)
            }
        }
        return null
    }

    private fun searchForEngineView(view: View): EngineView? {
        if (view is EngineView) return view

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                searchForEngineView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    fun enableScrolling() {
        isScrollingEnabled = true
    }

    fun disableScrolling() {
        isScrollingEnabled = false
    }
}
