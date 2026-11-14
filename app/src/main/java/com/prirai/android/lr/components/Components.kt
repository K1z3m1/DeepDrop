package com.prirai.android.lr.components

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.core.app.NotificationManagerCompat
import com.prirai.android.lr.downloads.DownloadService
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.media.MediaSessionService
import com.prirai.android.lr.middleware.EnhancedStateCaptureMiddleware
import com.prirai.android.lr.middleware.FaviconMiddleware
import com.prirai.android.lr.perf.VisualCompletenessQueue
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.request.AppRequestInterceptor
import com.prirai.android.lr.settings.ThemeChoice
import com.prirai.android.lr.share.SaveToPDFMiddleware
import com.prirai.android.lr.utils.ClipboardHandler
import com.prirai.android.lr.utils.FaviconCache
import com.prirai.android.lr.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.ext.toContentBlockingSetting
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.browser.engine.gecko.permission.GeckoSitePermissionsStorage
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.DefaultDesktopModeAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.engine.middleware.SessionPrioritizationMiddleware
import mozilla.components.browser.state.engine.middleware.TranslationsMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.thumbnails.ThumbnailsMiddleware
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.browser.thumbnails.loader.ThumbnailLoader
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.amo.AMOAddonsProvider
import mozilla.components.feature.addons.update.DefaultAddonUpdater
import mozilla.components.feature.app.links.AppLinksInterceptor
import mozilla.components.feature.app.links.AppLinksUseCases
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import com.prirai.android.lr.downloads.DownloadConfirmationMiddleware
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.media.MediaSessionFeature
import mozilla.components.feature.media.middleware.RecordingDevicesMiddleware
import mozilla.components.feature.prompts.PromptMiddleware
import mozilla.components.feature.prompts.file.FileUploadsDirCleaner
import mozilla.components.feature.search.SearchApplicationName
import mozilla.components.feature.search.SearchDeviceType
import mozilla.components.feature.search.SearchUpdateChannel
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.search.middleware.SearchMiddleware
import mozilla.components.feature.search.storage.SearchEngineSelectorConfig
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.middleware.LastAccessMiddleware
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.base.worker.Frequency
import mozilla.appservices.remotesettings.RemoteSettingsServer
import mozilla.components.support.remotesettings.RemoteSettingsService
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.util.concurrent.TimeUnit
import android.os.Environment
import com.prirai.android.lr.browser.profile.DefaultProfileTabMigration
import com.prirai.android.lr.browser.profile.ProfileManager
import com.prirai.android.lr.browser.profile.ProfileMiddleware
import com.prirai.android.lr.browser.tabgroups.TabGroupMiddleware
import com.prirai.android.lr.browser.tabgroups.UnifiedTabGroupManager
import com.prirai.android.lr.browser.tabs.sleep.SleepingTabsManager
import com.prirai.android.lr.middleware.MediaSessionThrottleMiddleware
import mozilla.components.feature.tabs.CustomTabsUseCases
import mozilla.components.support.utils.DefaultDownloadFileUtils

private const val DAY_IN_MINUTES = 24 * 60L

open class Components(private val applicationContext: Context) {
    val visualCompletenessQueue by lazy { VisualCompletenessQueue() }
    companion object {
        const val BROWSER_PREFERENCES = "browser_preferences"
        const val PREF_LAUNCH_EXTERNAL_APP = "launch_external_app"
    }

    val publicSuffixList by lazy { PublicSuffixList(applicationContext) }

    val clipboardHandler by lazy { ClipboardHandler(applicationContext) }

    val faviconCache by lazy { FaviconCache.getInstance(applicationContext) }

    val tabGroupManager by lazy {
        UnifiedTabGroupManager.getInstance(applicationContext)
    }

    val fileSizeFormatter by lazy { DefaultFileSizeFormatter(applicationContext) }

    val dateTimeProvider by lazy { DefaultDateTimeProvider() }

    val downloadEstimator by lazy { DownloadEstimator(dateTimeProvider) }

    val packageNameProvider: () -> String = applicationContext::getPackageName

    val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(BROWSER_PREFERENCES, Context.MODE_PRIVATE)
    }

    fun darkEnabled(url: String? = null): PreferredColorScheme {
        val darkOn =
                (applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
        
        if (url != null) {
            val siteDarkMode = applicationContext.getSharedPreferences("site_dark_mode_override", Context.MODE_PRIVATE)
            if (siteDarkMode.contains(url)) {
                val override = siteDarkMode.getString(url, "")
                return when (override) {
                    "dark" -> PreferredColorScheme.Dark
                    "light" -> PreferredColorScheme.Light
                    else -> PreferredColorScheme.Dark // Fallback
                }
            }
        }
        
        return when {
            UserPreferences(applicationContext).webThemeChoice == ThemeChoice.DARK.ordinal -> PreferredColorScheme.Dark
            UserPreferences(applicationContext).webThemeChoice == ThemeChoice.LIGHT.ordinal -> PreferredColorScheme.Light
            darkOn -> PreferredColorScheme.Dark
            else -> PreferredColorScheme.Light
        }
    }

    val appRequestInterceptor by lazy {
        AppRequestInterceptor(applicationContext)
    }

    private val engineSettings: DefaultSettings by lazy {
        DefaultSettings().apply {
            historyTrackingDelegate = HistoryDelegate(lazyHistoryStorage)
            requestInterceptor = appRequestInterceptor
            remoteDebuggingEnabled = UserPreferences(applicationContext).remoteDebugging
            supportMultipleWindows = true
            enterpriseRootsEnabled = false // SECURITY: Third-party certs disabled
            if (!UserPreferences(applicationContext).autoFontSize) {
                fontSizeFactor = UserPreferences(applicationContext).fontSizeFactor
                automaticFontSizeAdjustment = false
            }
            preferredColorScheme = darkEnabled()
            javascriptEnabled = UserPreferences(applicationContext).javaScriptEnabled
            val prefs = UserPreferences(applicationContext)
            httpsOnlyMode = prefs.getHttpsOnlyMode()
            dohSettingsMode = prefs.getDohSettingsMode()
            dohProviderUrl = prefs.dohProviderUrl
            globalPrivacyControlEnabled = prefs.globalPrivacyControl
        }
    }

    fun speculativeConnect(url: String?) {
        if (url.isNullOrBlank()) return
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return
        if (!UserPreferences(applicationContext).fastPageLoad) return
        runCatching { engine.speculativeConnect(url) }
    }

    fun applyPrivacyEngineSettings() {
        val prefs = UserPreferences(applicationContext)
        engine.settings.httpsOnlyMode = prefs.getHttpsOnlyMode()
        engine.settings.dohSettingsMode = prefs.getDohSettingsMode()
        engine.settings.dohProviderUrl = prefs.dohProviderUrl
        engine.settings.globalPrivacyControlEnabled = prefs.globalPrivacyControl
    }

    private val notificationManagerCompat by lazy {
        NotificationManagerCompat.from(applicationContext)
    }

    val notificationsDelegate: NotificationsDelegate by lazy {
        NotificationsDelegate(
            notificationManagerCompat,
        )
    }

    val addonUpdater by lazy {
        DefaultAddonUpdater(
            applicationContext,
            Frequency(1, TimeUnit.DAYS),
            notificationsDelegate,
            Dispatchers.Main,
        )
    }

    open val engine: Engine by lazy {
        GeckoEngine(applicationContext, engineSettings, runtime)
    }

    open val client: Client by lazy { GeckoViewFetchClient(applicationContext, runtime) }

    val icons by lazy { BrowserIcons(applicationContext, client) }

    private val lazyHistoryStorage = lazy { PlacesHistoryStorage(applicationContext) }
    val historyStorage by lazy { lazyHistoryStorage.value }

    private val lazyBookmarksStorage = lazy { PlacesBookmarksStorage(applicationContext) }
    val bookmarksStorage by lazy { lazyBookmarksStorage.value }

    val sessionStorage by lazy { SessionStorage(applicationContext, engine) }

    val permissionStorage by lazy { GeckoSitePermissionsStorage(runtime, OnDiskSitePermissionsStorage(applicationContext)) }

    val thumbnailStorage by lazy { ThumbnailStorage(applicationContext) }
    val thumbnailLoader by lazy { ThumbnailLoader(thumbnailStorage) }
    
    val profileManager by lazy { 
        ProfileManager.getInstance(applicationContext)
    }
    
    val downloadConfirmationMiddleware by lazy {
        DownloadConfirmationMiddleware(applicationContext)
    }

    val profileMiddleware by lazy {
        ProfileMiddleware(profileManager)
    }

    val defaultProfileTabMigration by lazy {
        DefaultProfileTabMigration(applicationContext)
    }

    val remoteSettingsService by lazy {
        RemoteSettingsService(
            applicationContext,
            RemoteSettingsServer.Prod,
            channel = "release",
            isLargeScreenSize = Utils.isTablet(applicationContext),
        )
    }

    val store by lazy {
        BrowserStore(
                middleware = listOf(
                        defaultProfileTabMigration,
                        downloadConfirmationMiddleware,
                        DownloadMiddleware(
                            applicationContext,
                            DownloadService::class.java,
                            { true },
                            downloadFileUtils = DefaultDownloadFileUtils(
                                context = applicationContext,
                                downloadLocation = {
                                    Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS
                                    ).path
                                },
                            ),
                        ),
                        ThumbnailsMiddleware(thumbnailStorage),
                        FaviconMiddleware(faviconCache),
                        UndoMiddleware(),
                        LocaleRegionMiddleware(),
                        SearchMiddleware(
                            applicationContext,
                            searchEngineSelectorConfig = SearchEngineSelectorConfig(
                                appName = SearchApplicationName.FIREFOX_ANDROID,
                                appVersion = com.prirai.android.lr.BuildConfig.VERSION_NAME,
                                deviceType = if (Utils.isTablet(applicationContext)) {
                                    SearchDeviceType.TABLET
                                } else {
                                    SearchDeviceType.SMARTPHONE
                                },
                                experiment = "",
                                updateChannel = SearchUpdateChannel.RELEASE,
                                service = remoteSettingsService,
                            ),
                        ),
                        RecordingDevicesMiddleware(applicationContext, notificationsDelegate),
                        MediaSessionThrottleMiddleware(),
                        PromptMiddleware(),
                        LastAccessMiddleware(),
                        SaveToPDFMiddleware(applicationContext),
                        TabGroupMiddleware(
                            lazy { tabGroupManager }
                        ),
                        profileMiddleware,  // Use the exposed instance
                        SessionPrioritizationMiddleware(),
                        TranslationsMiddleware(
                            engine = engine,
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                            isTranslationsEnabled = { UserPreferences(applicationContext).translationsEnabled },
                        ),
                        EnhancedStateCaptureMiddleware(
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                            maxTabsToCapture = 3
                        )
                ) + EngineMiddleware.create(
                    engine,
                    trimMemoryAutomatically = false,
                )
        ).apply {
            val prefs = UserPreferences(applicationContext)
            val desktopDefault = if (prefs.hasDesktopModeDefault()) {
                prefs.desktopModeDefault
            } else {
                Utils.isTablet(applicationContext)
            }
            dispatch(DefaultDesktopModeAction.DesktopModeUpdated(desktopDefault))
        }
    }

    @Volatile
    private var storeBootstrapped = false

    fun bootstrapStore() {
        if (storeBootstrapped) return
        storeBootstrapped = true

        icons.install(engine, store)

        mediaSessionFeature.start()

        sleepingTabsManager.start(store)
    }

    val sleepingTabsManager by lazy {
        SleepingTabsManager.getInstance(applicationContext)
    }

    val mediaSessionFeature: MediaSessionFeature by lazy {
        MediaSessionFeature(applicationContext, MediaSessionService::class.java, store)
    }

    val sessionUseCases by lazy { SessionUseCases(store) }

    val addonManager by lazy {
        AddonManager(store, engine, addonCollectionProvider, addonUpdater)
    }

    val addonCollectionProvider by lazy {
        if(UserPreferences(applicationContext).customAddonCollection){
            AMOAddonsProvider(
                    applicationContext,
                    client,
                    collectionUser = UserPreferences(applicationContext).customAddonCollectionUser,
                    collectionName = UserPreferences(applicationContext).customAddonCollectionName,
                    maxCacheAgeInMinutes = 0,
            )
        }
        else{
            AMOAddonsProvider(
                    applicationContext,
                    client,
                    collectionUser = "16201230",
                    collectionName = "What-I-want-on-Fenix",
                    maxCacheAgeInMinutes = DAY_IN_MINUTES,
                    serverURL = "https://addons.mozilla.org"
            )
        }
    }

    val searchUseCases by lazy {
        SearchUseCases(store, tabsUseCases, sessionUseCases)
    }

    val appLinksUseCases by lazy { AppLinksUseCases(applicationContext) }

    val appLinksInterceptor by lazy {
        AppLinksInterceptor(
                applicationContext,
                launchInApp = {
                    applicationContext.components.preferences.getBoolean(
                            PREF_LAUNCH_EXTERNAL_APP,
                            false
                    )
                }
        )
    }

    val fileUploadsDirCleaner: FileUploadsDirCleaner by lazy {
        FileUploadsDirCleaner { applicationContext.cacheDir }
    }

    private val runtime by lazy {
        val builder = GeckoRuntimeSettings.Builder()

        UserJsPreferences.applyTo(applicationContext, builder)

        val runtimeSettings = builder
            .aboutConfigEnabled(true)
            .extensionsProcessEnabled(true)
            .debugLogging(false) // SECURITY: Debug logging disabled
            .extensionsWebAPIEnabled(true)
            .contentBlocking(trackingPolicy.toContentBlockingSetting())
            .build()

        UserJsPreferences.applyTypedSettings(applicationContext, runtimeSettings)

        runtimeSettings.contentBlocking.setSafeBrowsing(safeBrowsingPolicy)

        if(UserPreferences(applicationContext).safeBrowsing){
            runtimeSettings.contentBlocking.setSafeBrowsingProviders(
                ContentBlocking.GOOGLE_SAFE_BROWSING_PROVIDER,
                ContentBlocking.GOOGLE_LEGACY_SAFE_BROWSING_PROVIDER
            )
            runtimeSettings.contentBlocking.setSafeBrowsingMalwareTable(
                "goog-malware-proto",
                "goog-unwanted-proto"
            )
            runtimeSettings.contentBlocking.setSafeBrowsingPhishingTable(
                "goog-phish-proto"
            )
        }
        else {
            runtimeSettings.contentBlocking.setSafeBrowsingProviders()
            runtimeSettings.contentBlocking.setSafeBrowsingMalwareTable()
            runtimeSettings.contentBlocking.setSafeBrowsingPhishingTable()
        }

        GeckoRuntime.create(applicationContext, runtimeSettings)
    }

    private val trackingPolicy by lazy{
        val prefs = UserPreferences(applicationContext)
        when (prefs.etpLevel) {
            0 -> EngineSession.TrackingProtectionPolicy.none()
            2 -> EngineSession.TrackingProtectionPolicy.strict()
            3 -> { // Custom
                val categories = mutableListOf<EngineSession.TrackingProtectionPolicy.TrackingCategory>().apply {
                    if (prefs.etpTrackingAds) add(EngineSession.TrackingProtectionPolicy.TrackingCategory.AD)
                    if (prefs.etpTrackingContent) add(EngineSession.TrackingProtectionPolicy.TrackingCategory.ANALYTICS)
                    if (prefs.etpCryptominers) add(EngineSession.TrackingProtectionPolicy.TrackingCategory.CRYPTOMINING)
                    if (prefs.etpFingerprinters) add(EngineSession.TrackingProtectionPolicy.TrackingCategory.FINGERPRINTING)
                    if (prefs.etpSocialTracking) {
                        add(EngineSession.TrackingProtectionPolicy.TrackingCategory.SOCIAL)
                        add(EngineSession.TrackingProtectionPolicy.TrackingCategory.MOZILLA_SOCIAL)
                    }
                    if (prefs.etpEmailTracking) add(EngineSession.TrackingProtectionPolicy.TrackingCategory.EMAIL)
                }
                if (categories.isEmpty()) EngineSession.TrackingProtectionPolicy.none()
                else EngineSession.TrackingProtectionPolicy.select(trackingCategories = categories.toTypedArray())
            }
            else -> EngineSession.TrackingProtectionPolicy.recommended() // 1 = Standard
        }
    }

    private val safeBrowsingPolicy by lazy{
        if(UserPreferences(applicationContext).safeBrowsing) ContentBlocking.SafeBrowsing.DEFAULT
        else ContentBlocking.SafeBrowsing.NONE
    }

    val tabsUseCases: TabsUseCases by lazy { TabsUseCases(store) }
    val downloadsUseCases: DownloadsUseCases by lazy {
        DownloadsUseCases(
            store = store,
            downloadFileUtils = DefaultDownloadFileUtils(
                context = applicationContext,
                downloadLocation = {
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ).path
                },
            ),
        )
    }
    val contextMenuUseCases: ContextMenuUseCases by lazy { ContextMenuUseCases(store) }
    
    val customTabsStore by lazy { CustomTabsServiceStore() }
    
    val customTabsUseCases by lazy { 
        CustomTabsUseCases(store, sessionUseCases.loadUrl) 
    }
}
