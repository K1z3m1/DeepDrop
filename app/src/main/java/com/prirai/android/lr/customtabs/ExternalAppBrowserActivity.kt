package com.prirai.android.lr.customtabs

import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsIntent
import androidx.navigation.fragment.NavHostFragment
import com.prirai.android.lr.BrowserActivity
import com.prirai.android.lr.R
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.ext.enableEdgeToEdgeMode
import mozilla.components.browser.state.action.CustomTabListAction
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.feature.customtabs.CustomTabIntentProcessor
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.support.utils.SafeIntent
import com.prirai.android.lr.browser.profile.BrowserProfile

open class ExternalAppBrowserActivity : BrowserActivity() {
    override fun navigateToBrowserOnColdStart() {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCalledOnCreate = true
        
        val toolbarColor = intent.getIntExtra(CustomTabsIntent.EXTRA_TOOLBAR_COLOR, -1)
        
        if (toolbarColor != -1) {
            enableEdgeToEdgeMode(statusBarColor = toolbarColor)
        } else {
            enableEdgeToEdgeMode()
        }
        
        if (savedInstanceState == null) {
            val processor = CustomTabIntentProcessor(
                addCustomTabUseCase = components.customTabsUseCases.add,
                resources = resources,
                isPrivate = false
            )
            
            if (processor.process(intent)) {
                val sessionId = SafeIntent(intent).getStringExtra(EXTRA_SESSION_ID)
                
                val profileId = SafeIntent(intent).getStringExtra("PROFILE_ID")
                if (!profileId.isNullOrEmpty() && sessionId != null) {
                    val contextId = BrowserProfile
                        .contextIdFor(profileId, isPrivate = false)
                    val customTab = components.store.state.findCustomTab(sessionId)
                    if (customTab != null && customTab.contextId != contextId) {
                        components.store.dispatch(CustomTabListAction.RemoveCustomTabAction(sessionId))
                        components.store.dispatch(
                            CustomTabListAction.AddCustomTabAction(customTab.copy(contextId = contextId))
                        )
                    }
                }
                
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.container) as? NavHostFragment
                navHostFragment?.let { host ->
                    val bundle = Bundle().apply {
                        putString("activeSessionId", sessionId)
                        putString(EXTRA_SESSION_ID, sessionId)
                        intent.extras?.let { extras ->
                            putAll(extras)
                        }
                    }
                    host.navController.navigate(R.id.externalAppBrowserFragment, bundle)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }
    
    private var hasCalledOnCreate = false

    override fun onDestroy() {
        if (isFinishing) {
            val tabId = getExternalTabId()
            val customTab = tabId?.let { components.store.state.findCustomTab(it) }
            if (tabId != null && customTab != null) {
                customTab.mediaSessionState?.controller?.pause()
                components.customTabsUseCases.remove(tabId)
            }
            finishAndRemoveTask()
        }
        super.onDestroy()
    }

    override fun getIntentSessionId(intent: SafeIntent): String? {
        return com.prirai.android.lr.ext.getIntentSessionId(intent)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun getExternalTabId(): String? {
        return getIntentSessionId(SafeIntent(intent))
    }
}
