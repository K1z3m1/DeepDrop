package com.prirai.android.lr

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.view.Choreographer
import com.prirai.android.lr.components.Components
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.feature.addons.update.GlobalAddonDependencyProvider
import mozilla.components.support.base.facts.Facts
import mozilla.components.support.base.facts.processor.LogFactProcessor
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.webextensions.WebExtensionSupport
import java.util.concurrent.TimeUnit
import com.prirai.android.lr.browser.profile.ProfileAddonPolicy
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.permissions.BackgroundPlaybackAllowlist
import com.prirai.android.lr.permissions.BackgroundPlaybackGuard
import com.prirai.android.lr.permissions.LRBgPlayController
import com.prirai.android.lr.privacy.cookies.CookiePolicyScheduler
import com.prirai.android.lr.utils.FaviconCache
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.rusthttp.RustHttpConfig

class BrowserApp : Application() {
    private val logger = Logger("BrowserApp")

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val components by lazy { Components(this) }
    
    private var appStartTime = 0L

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            Thread({
                runCatching { base.getSharedPreferences("scw_preferences", MODE_PRIVATE) }
                runCatching { base.getSharedPreferences(Components.BROWSER_PREFERENCES, MODE_PRIVATE) }
                runCatching { base.getSharedPreferences("profile_manager", MODE_PRIVATE) }
            }, "lr-prefs-warmup").apply { isDaemon = true }.start()
        }
    }

    override fun onCreate() {
        appStartTime = System.currentTimeMillis()
        super.onCreate()

        if (!isMainProcess()) {
            return
        }

        if (BuildConfig.DEBUG) {
            Facts.registerProcessor(LogFactProcessor())
        }

        try {
            System.loadLibrary("mozglue");
            System.loadLibrary("nss3");
            System.loadLibrary("freebl3");
            System.loadLibrary("softokn3");

            AppServicesInitializer.init(
                AppServicesInitializer.Config(
                    crashReporting = null,
                    logLevel = if (BuildConfig.DEBUG) {
                        mozilla.components.support.base.log.Log.Priority.DEBUG
                    } else {
                        mozilla.components.support.base.log.Log.Priority.WARN
                    },
                )
            )
            RustHttpConfig.setClient(
                lazy { HttpURLConnectionClient() }
            )
        } catch (e: Exception) {
            android.util.Log.w("BrowserApp", "Rust/NSS init failed (sync may be unavailable)", e)
        }

        components.engine.warmUp()

        logger.info("App onCreate completed in ${System.currentTimeMillis() - appStartTime}ms")

        restoreBrowserState()

        Choreographer.getInstance().postFrameCallback {
            applicationScope.launch(Dispatchers.Main) {
                initializeAfterFirstFrame()
            }
        }
    }

    private fun initializeAfterFirstFrame() {
        logger.info("Starting deferred initialization (${System.currentTimeMillis() - appStartTime}ms after app start)")

        components.bootstrapStore()

        applicationScope.launch(Dispatchers.Main) {
            initializeWebExtensions()
        }
        
        applicationScope.launch(Dispatchers.Main) {
            try {
                val allowlist =
                    BackgroundPlaybackAllowlist
                        .getInstance(this@BrowserApp)
                val legacyPref = com.prirai.android.lr.preferences
                    .UserPreferences(this@BrowserApp).backgroundPlaybackYoutube
                allowlist.migrateFromLegacyGlobalPref(enabledBefore = legacyPref)

                LRBgPlayController(
                    applicationContext = this@BrowserApp,
                    engine = components.engine,
                    allowlist = allowlist,
                    scope = applicationScope,
                ).start()

                BackgroundPlaybackGuard
                    .install(components.store, allowlist)
            } catch (e: Exception) {
                android.util.Log.w("LRBgPlay", "controller setup exception", e)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                CookiePolicyScheduler
                    .ensureScheduled(this@BrowserApp)
            } catch (t: Throwable) {
                logger.warn("CookiePolicyScheduler.ensureScheduled failed", t)
            }
        }

        queueStorageWarmup()
        
        applicationScope.launch(Dispatchers.IO) {
            components.publicSuffixList.prefetch()
        }
        
        applicationScope.launch(Dispatchers.Main) {
            components.downloadsUseCases.restoreDownloads()
        }
        
        applicationScope.launch(Dispatchers.Main) {
            components.visualCompletenessQueue.ready()
            logger.info("Visual completeness queue ready")
        }
    }
    
    private fun queueStorageWarmup() {
        applicationScope.launch(Dispatchers.IO) {
            components.historyStorage
            logger.info("Storage warmup completed")
        }
    }

    private fun initializeWebExtensions() {
        try {
            GlobalAddonDependencyProvider.initialize(
                components.addonManager,
                components.addonUpdater,
                onCrash = { logger.error("Addon dependency provider crashed", it) },
            )
            WebExtensionSupport.initialize(
            components.engine,
            components.store,
            onNewTabOverride = { _, engineSession, url, selected, isPrivate ->
                components.tabsUseCases.addTab(
                    url = url,
                    selectTab = selected,
                    engineSession = engineSession,
                    private = isPrivate,
                )
            },
                onCloseTabOverride = { _, sessionId ->
                    components.tabsUseCases.removeTab(sessionId)
                },
                onSelectTabOverride = { _, sessionId ->
                    components.tabsUseCases.selectTab(sessionId)
                },
                onExtensionsLoaded = { extensions ->
                    components.addonUpdater.registerForFutureUpdates(extensions)
                    applicationScope.launch(Dispatchers.IO) {
                        val profileId = ProfileManager
                            .getInstance(this@BrowserApp).getActiveProfile().id
                        ProfileAddonPolicy
                            .applyForProfile(this@BrowserApp, profileId)
                    }
                },
                onUpdatePermissionRequest = components.addonUpdater::onUpdatePermissionRequest,
            )
        } catch (e: UnsupportedOperationException) {
            Logger.error("Failed to initialize web extension support", e)
        }
    }

    private fun restoreBrowserState() {
        applicationScope.launch(Dispatchers.IO) {
            components.tabsUseCases.restore(components.sessionStorage)
            withContext(Dispatchers.Main) {
                components.sessionStorage.autoSave(components.store)
                    .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
                    .whenGoingToBackground()
                    .whenSessionsChange()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runOnlyInMainProcess {
            components.icons.onTrimMemory(level)
            FaviconCache.getInstance(this).onTrimMemory(level)
            components.store.dispatch(SystemAction.LowMemoryAction(level))
            components.sleepingTabsManager.onTrimMemory(components.store, level)
        }
    }
}
