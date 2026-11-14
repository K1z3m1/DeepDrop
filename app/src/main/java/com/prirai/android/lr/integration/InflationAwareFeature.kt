package com.prirai.android.lr.integration

import android.view.View
import android.view.ViewStub
import androidx.annotation.UiThread
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import java.lang.ref.WeakReference

abstract class InflationAwareFeature(
    private val stub: ViewStub
) : LifecycleAwareFeature, UserInteractionHandler {
    internal lateinit var view: WeakReference<View>
    internal var feature: LifecycleAwareFeature? = null
    private val stubListener = ViewStub.OnInflateListener { _, inflated ->
        view = WeakReference(inflated)
        feature = onViewInflated(inflated).also {
            it.start()
            onLaunch(inflated, it)
        }
    }

    @UiThread
    fun launch() {
        if (feature != null && view.get() != null) {
            onLaunch(view.get()!!, feature!!)
        } else {
            stub.apply {
                setOnInflateListener(stubListener)
                inflate()
            }
        }
    }

    override fun start() {
        feature?.start()
    }

    override fun stop() {
        feature?.stop()
    }

    override fun onBackPressed(): Boolean {
        return (feature as? UserInteractionHandler)?.onBackPressed() ?: false
    }

    abstract fun onViewInflated(view: View): LifecycleAwareFeature

    abstract fun onLaunch(view: View, feature: LifecycleAwareFeature)
}
