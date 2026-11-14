package com.prirai.android.lr.components.toolbar.modern

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.concept.engine.EngineView
import com.prirai.android.lr.theme.ThemeManager

class ModernToolbarSystem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), ScrollableToolbar {
    private var currentOffset = 0
    private var scrollingEnabled = true
    private val hideAnimator = ValueAnimator()
    private var engineView: EngineView? = null

    private var toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM

    private var tabGroupComponent: View? = null
    private var addressBarComponent: View? = null
    
    private var onOffsetChangedListener: ((Int, Int) -> Unit)? = null

    enum class ToolbarPosition {
        TOP, BOTTOM
    }

    init {
        orientation = VERTICAL
        clipToPadding = false
        clipChildren = false
        
        setBackgroundColor(
            ThemeManager.getToolbarBackgroundColor(
                context, 
                useElevation = true, 
                elevationDp = 3f
            )
        )
    }

    fun addComponent(component: View, type: ComponentType) {
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )

        when (type) {
            ComponentType.TAB_GROUP -> {
                tabGroupComponent = component
                addView(component, 0, layoutParams) // Top position
            }

            ComponentType.ADDRESS_BAR -> {
                addressBarComponent = component
                val index = if (tabGroupComponent != null) 1 else 0
                addView(component, index, layoutParams)
            }
        }

        updateEngineViewClipping()
    }

    fun setEngineView(engine: EngineView) {
        engineView = engine

        post {
            engine.setDynamicToolbarMaxHeight(0)
        }
    }

    fun setToolbarPosition(position: ToolbarPosition) {
        toolbarPosition = position
        
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            if (view.isVisible && view.alpha > 0f) {
                when (toolbarPosition) {
                    ToolbarPosition.BOTTOM -> {
                        view.setPadding(0, 0, 0, systemBars.bottom)
                    }
                    ToolbarPosition.TOP -> {
                        view.setPadding(0, systemBars.top, 0, 0)
                    }
                }
            } else {
                view.setPadding(0, 0, 0, 0)
            }
            
            insets
        }
    }

    fun getTotalHeight(): Int {
        var totalHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isVisible) {
                totalHeight += child.height
            }
        }

        return totalHeight
    }

    override fun enableScrolling() {
        scrollingEnabled = true
    }

    override fun disableScrolling() {
        scrollingEnabled = false
        expand()
    }

    override fun expand() {
        animateToOffset(0)
    }

    override fun collapse() {
        val totalHeight = getTotalHeight()
        if (totalHeight > 0) {
            animateToOffset(totalHeight)
        }
    }

    private fun animateToOffset(targetOffset: Int) {
        hideAnimator.cancel()
        hideAnimator.removeAllUpdateListeners()

        hideAnimator.apply {
            setIntValues(currentOffset, targetOffset)
            duration = 200
            addUpdateListener { animation ->
                val offset = animation.animatedValue as Int
                setToolbarOffset(offset)
            }
            start()
        }
    }

    fun setToolbarOffset(offset: Int) {
        val totalHeight = getTotalHeight()
        currentOffset = offset.coerceIn(0, totalHeight)

        translationY = when (toolbarPosition) {
            ToolbarPosition.TOP -> -currentOffset.toFloat()  // Negative moves UP (hiding)
            ToolbarPosition.BOTTOM -> currentOffset.toFloat()  // Positive moves DOWN (hiding)
        }

        onOffsetChangedListener?.invoke(currentOffset, totalHeight)

        updateEngineViewClipping()
        
        val hidePercentage = if (totalHeight > 0) {
            (currentOffset.toFloat() / totalHeight.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        
        alpha = 1f - hidePercentage
        
        visibility = VISIBLE
    }
    
    private fun updateEngineViewClipping() {
    }

    fun setOnOffsetChangedListener(listener: (Int, Int) -> Unit) {
        onOffsetChangedListener = listener
    }

    enum class ComponentType {
        TAB_GROUP,
        ADDRESS_BAR,
    }
}
