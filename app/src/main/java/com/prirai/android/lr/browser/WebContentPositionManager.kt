package com.prirai.android.lr.browser

import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import mozilla.components.concept.engine.EngineView

class WebContentPositionManager(
    private val engineView: EngineView,
    private val swipeRefreshView: View,
    private val rootView: View,
    private val getTopToolbarHeight: () -> Int,
    private val getBottomToolbarHeight: () -> Int
) {
    private var lastImeInset = 0
    private var statusBarHeight = 0
    private var navigationBarHeight = 0
    
    private var isDestroyed = false
    
    fun initialize() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            handleWindowInsets(insets)
            insets
        }
        
        setupKeyboardAnimation()
        
        rootView.post {
            ViewCompat.requestApplyInsets(rootView)
        }
    }
    
    private fun updateEnginePadding() {
        val engineViewInstance = (engineView as? View) ?: return
        val bottomSystemBarPadding = navigationBarHeight
        
        engineViewInstance.setPadding(
            0,
            statusBarHeight,  // Top padding for status bar
            0,
            bottomSystemBarPadding  // Avoid double padding when bottom toolbars handle nav bar
        )
    }
    
    private fun handleWindowInsets(insets: WindowInsetsCompat) {
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        statusBarHeight = systemBars.top
        navigationBarHeight = systemBars.bottom
        
        updateEnginePadding()
        
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        
        val visibleImeHeight = maxOf(0, ime.bottom - navigationBarHeight)
        
        if (visibleImeHeight > 0 && visibleImeHeight != lastImeInset) {
            val layoutParams = swipeRefreshView.layoutParams as? CoordinatorLayout.LayoutParams ?: return
            layoutParams.bottomMargin = visibleImeHeight
            swipeRefreshView.layoutParams = layoutParams
            swipeRefreshView.requestLayout()
            lastImeInset = visibleImeHeight
        } else if (visibleImeHeight == 0 && lastImeInset > 0) {
            val layoutParams = swipeRefreshView.layoutParams as? CoordinatorLayout.LayoutParams ?: return
            layoutParams.bottomMargin = 0
            swipeRefreshView.layoutParams = layoutParams
            swipeRefreshView.requestLayout()
            lastImeInset = 0
        }
    }
    
    private fun setupKeyboardAnimation() {
        ViewCompat.setWindowInsetsAnimationCallback(
            swipeRefreshView,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    handleWindowInsets(insets)
                    return insets
                }
            }
        )
    }
    
    fun requestUpdate() {
        updateEnginePadding()
    }
    
    fun destroy() {
        isDestroyed = true
        ViewCompat.setOnApplyWindowInsetsListener(rootView, null)
        ViewCompat.setWindowInsetsAnimationCallback(swipeRefreshView, null)
    }
}
