package com.prirai.android.lr.auth

import android.app.Activity
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger

class PasskeyAuthFeature(
    @Suppress("unused") private val engine: Engine,
    @Suppress("unused") private val activity: Activity,
    @Suppress("unused") private val store: BrowserStore,
    @Suppress("unused") private val onGetTabId: () -> String?,
) : LifecycleAwareFeature {
    private val logger = Logger("PasskeyAuthFeature")

    override fun start() {
        logger.debug("PasskeyAuthFeature started")
    }

    override fun stop() {
        logger.debug("PasskeyAuthFeature stopped")
    }
}
