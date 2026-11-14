package com.prirai.android.lr

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.navigation.NavOptions
import com.prirai.android.lr.preferences.UserPreferences
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineView
import java.lang.ref.WeakReference
import com.prirai.android.lr.components.toolbar.ToolbarPosition as OldToolbarPosition

class BrowserAnimator(
    private val fragment: WeakReference<Fragment>,
    private val engineView: WeakReference<EngineView>,
    private val swipeRefresh: WeakReference<View>?,
    private val viewLifecycleScope: WeakReference<LifecycleCoroutineScope>
) {
    private val unwrappedEngineView: EngineView?
        get() = engineView.get()

    private val unwrappedSwipeRefresh: View?
        get() = swipeRefresh?.get()

    fun beginAnimateInIfNecessary() {
        unwrappedEngineView?.asView()?.visibility = View.VISIBLE
        unwrappedSwipeRefresh?.background = null
    }

    fun captureEngineViewAndDrawStatically(onComplete: () -> Unit) {
        unwrappedEngineView?.asView()?.context.let {
            viewLifecycleScope.get()?.launch {
                if (!fragment.isAdded()) {
                    return@launch
                }
                unwrappedEngineView?.captureThumbnail { bitmap ->
                    if (!fragment.isAdded()) {
                        return@captureThumbnail
                    }

                    unwrappedSwipeRefresh?.apply {
                        background = bitmap?.toDrawable(context.resources)
                            ?: Color.TRANSPARENT.toDrawable()
                    }

                    unwrappedEngineView?.asView()?.visibility = View.GONE

                    onComplete()
                }
            }
        }
    }

    private fun WeakReference<Fragment>.isAdded(): Boolean {
        val unwrapped = get()
        return unwrapped != null && unwrapped.isAdded
    }

    companion object {
        fun getToolbarNavOptions(context: Context): NavOptions {
            val navOptions = NavOptions.Builder()

            when (UserPreferences(context).toolbarPosition) {
                OldToolbarPosition.TOP.ordinal -> {
                    navOptions.setEnterAnim(R.anim.fade_in)
                    navOptions.setExitAnim(R.anim.fade_out)
                }
                OldToolbarPosition.BOTTOM.ordinal -> {
                    navOptions.setEnterAnim(R.anim.fade_in_up)
                    navOptions.setExitAnim(R.anim.fade_out_down)
                }
            }

            return navOptions.build()
        }
    }
}