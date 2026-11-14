package com.prirai.android.lr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.prirai.android.lr.customtabs.ExternalAppBrowserActivity
import com.prirai.android.lr.ext.components
import mozilla.components.feature.intent.processing.TabIntentProcessor
import mozilla.components.support.utils.SafeIntent

class IntentReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent?.let { Intent(it) } ?: Intent()
        val safeIntent = SafeIntent(intent)

        if (intent.action == Intent.ACTION_VIEW &&
            !intent.hasExtra(BrowserActivity.OPEN_TO_BROWSER)
        ) {
            val scheme = intent.data?.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                finish()
                return
            }
        }

        if (intent.action == Intent.ACTION_VIEW) {
            components.speculativeConnect(intent.dataString)
        }

        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK.inv()
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.inv()

        val hasCustomTabExtra = safeIntent.hasExtra("android.support.customtabs.extra.SESSION") ||
                               safeIntent.hasExtra("androidx.browser.customtabs.extra.SESSION")
        
        val isCustomTab = hasCustomTabExtra && 
                         intent.action == Intent.ACTION_VIEW && 
                         intent.data != null &&
                         !intent.hasExtra(BrowserActivity.OPEN_TO_BROWSER)

        val activityClass = if (isCustomTab) {
            ExternalAppBrowserActivity::class
        } else {
            val processor = TabIntentProcessor(components.tabsUseCases, components.searchUseCases.newTabSearch, isPrivate = false)
            processor.process(intent)
            BrowserActivity::class
        }

        intent.setClassName(applicationContext, activityClass.java.name)

        startActivity(intent)
        finish()
    }
}