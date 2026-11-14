package com.prirai.android.lr.preferences

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.prirai.android.lr.settings.HomepageBackgroundChoice
import com.prirai.android.lr.settings.HomepageChoice
import com.prirai.android.lr.settings.ThemeChoice
import com.prirai.android.lr.components.toolbar.ToolbarPosition
import mozilla.components.concept.engine.Engine
import mozilla.components.support.ktx.android.content.booleanPreference
import mozilla.components.support.ktx.android.content.floatPreference
import mozilla.components.support.ktx.android.content.intPreference
import mozilla.components.support.ktx.android.content.longPreference
import mozilla.components.support.ktx.android.content.stringPreference
import com.prirai.android.lr.browser.tabs.sleep.SleepingTabsMode
import mozilla.components.support.ktx.android.content.PreferencesHolder

class UserPreferences(appContext: Context) : PreferencesHolder {
    override val preferences: SharedPreferences =
        appContext.getSharedPreferences(SCW_PREFERENCES, MODE_PRIVATE)

    var bookmarkFolder by booleanPreference("save_bookmark_folder", false)
    var bookmarkFolderId by longPreference("save_bookmark_folder_id", -1L)
    var lastKnownPrivate by booleanPreference("last_known_mode_private", false)

    var defaultProfileMigrationDone by booleanPreference(LR_TABS_PROFILE_DEFAULT_MIGRATE, false)

    var pendingDefaultMigrationPopup by booleanPreference(LR_TABS_PROFILE_DEFAULT_MIGRATE_PENDING_POPUP, false)

    var pendingDefaultMigrationTabCount by intPreference(LR_TABS_PROFILE_DEFAULT_MIGRATE_TAB_COUNT, 0)

    var lrBgPlayStaleCheckDone by booleanPreference(LR_BG_PLAY_STALE_CHECK_DONE, false)

    var javaScriptEnabled by booleanPreference(JAVA_SCRIPT_ENABLED, true)
    var searchEngineChoice by intPreference(SEARCH_ENGINE, 0)
    var customSearchEngine by booleanPreference(CUSTOM_SEARCH_ENGINE, false)
    var customSearchEngineURL by stringPreference(CUSTOM_SEARCH_ENGINE_URL, "")
    var privateSearchEngineChoice by intPreference(PRIVATE_SEARCH_ENGINE, -1)
    var desktopModeDefault by booleanPreference(DESKTOP_MODE_DEFAULT, false)
    var translationsEnabled by booleanPreference(TRANSLATIONS_ENABLED, true)

    var backgroundPlaybackYoutube by booleanPreference(BACKGROUND_PLAYBACK_YOUTUBE, false)

    fun hasDesktopModeDefault(): Boolean = preferences.contains(DESKTOP_MODE_DEFAULT)
    var toolbarPosition by intPreference(TOOLBAR_POSITION, ToolbarPosition.BOTTOM.ordinal)
    var homepageType by intPreference(HOMEPAGE_TYPE, HomepageChoice.VIEW.ordinal)
    
    fun getHomepageUrl(): String {
        return "about:homepage"
    }
    var appThemeChoice by intPreference(APP_THEME_CHOICE, ThemeChoice.SYSTEM.ordinal)
    var webThemeChoice by intPreference(WEB_THEME_CHOICE, ThemeChoice.SYSTEM.ordinal)
    var homepageBackgroundChoice by intPreference(HOMEPAGE_BACKGROUND_CHOICE, HomepageBackgroundChoice.NONE.ordinal)
    var homepageBackgroundUrl by stringPreference(HOMEPAGE_BACKGROUND_URL, "")
    var launchInApp by booleanPreference(LAUNCH_IN_APP, true)
    var customAddonCollection by booleanPreference(CUSTOM_ADDON_BOOL, false)
    var shownCollectionDisclaimer by booleanPreference(SHOWN_ADDON_DISCLAIMER, false)
    var customAddonCollectionUser by stringPreference(COLLECTION_USER, "")
    var customAddonCollectionName by stringPreference(COLLECTION_NAME, "")
    var autoFontSize by booleanPreference(AUTO_FONT_SIZE, true)
    var fontSizeFactor by floatPreference(FONT_SIZE_FACTOR, 1f)

    var stackFromBottom by booleanPreference(STACK_FROM_BOTTOM, false)
    var showTabGroupBar by booleanPreference(SHOW_TAB_GROUP_BAR, true)
    var swipeToRefresh by booleanPreference(SWIPE_TO_REFRESH, true)

    var remoteDebugging by booleanPreference(REMOTE_DEBUGGING, false)
    var resistFingerprinting by booleanPreference(RESIST_FINGERPRINTING, false)

    var fastPageLoad by booleanPreference(FAST_PAGE_LOAD, true)
    var promptExternalDownloader by booleanPreference(PROMPT_EXTERNAL_DOWNLOADER, false)
    var showUrlProtocol by booleanPreference(SHOW_URL_PROTOCOL, false)
    var searchSuggestionsEnabled by booleanPreference(SEARCH_SUGGESTIONS, true)
    var searchSuggestionCount by intPreference(SEARCH_SUGGESTION_COUNT, 5)
    var safeBrowsing by booleanPreference(SAFE_BROWSING, true)
    var trackingProtection: Boolean
        get() = etpLevel != 0
        set(value) {
            preferences.edit().putBoolean(TRACKING_PROTECTION, value).apply()
            etpLevel = if (value) {
                maxOf(etpLevel, 1)
            } else {
                0
            }
        }
    var showShortcuts by booleanPreference(SHOW_SHORTCUTS, true)
    var loadShortcutIcons by booleanPreference(LOAD_SHORTCUT_ICONS, true)

    var etpLevel: Int
        get() {
            val value = preferences.all[ETP_LEVEL] ?: return if (preferences.getBoolean(TRACKING_PROTECTION, true)) 1 else 0
            return when (value) {
                is Int -> value
                is String -> value.toIntOrNull() ?: 1
                else -> 1
            }
        }
        set(value) = preferences.edit().putInt(ETP_LEVEL, value).apply()
    var etpCryptominers by booleanPreference(ETP_CRYPTOMINERS, true)
    var etpFingerprinters by booleanPreference(ETP_FINGERPRINTERS, true)
    var etpTrackingContent by booleanPreference(ETP_TRACKING_CONTENT, true)
    var etpTrackingAds by booleanPreference(ETP_TRACKING_ADS, true)
    var etpSocialTracking by booleanPreference(ETP_SOCIAL_TRACKING, true)
    var etpEmailTracking by booleanPreference(ETP_EMAIL_TRACKING, true)

    var httpsOnlyMode by intPreference(HTTPS_ONLY_MODE, HTTPS_ONLY_ALL)
    var dohMode by intPreference(DOH_MODE, DOH_INCREASED)
    var dohProviderUrl by stringPreference(DOH_PROVIDER_URL, CLOUDFLARE_DOH_URI)
    var globalPrivacyControl by booleanPreference(GLOBAL_PRIVACY_CONTROL, true)

    var sleepingTabsMode by intPreference(SLEEPING_TABS_MODE, SLEEPING_TABS_SINGLE)

    fun getSleepingTabsMode(): SleepingTabsMode =
        when (sleepingTabsMode) {
            SLEEPING_TABS_SINGLE -> SleepingTabsMode.SingleTab
            SLEEPING_TABS_AGGRESSIVE -> SleepingTabsMode.Aggressive
            SLEEPING_TABS_OFF -> SleepingTabsMode.Off
            else -> SleepingTabsMode.Balanced
        }

    fun getHttpsOnlyMode(): Engine.HttpsOnlyMode = when (httpsOnlyMode) {
        HTTPS_ONLY_PRIVATE -> Engine.HttpsOnlyMode.ENABLED_PRIVATE_ONLY
        HTTPS_ONLY_ALL -> Engine.HttpsOnlyMode.ENABLED
        else -> Engine.HttpsOnlyMode.DISABLED
    }

    fun getDohSettingsMode(): Engine.DohSettingsMode = when (dohMode) {
        DOH_INCREASED -> Engine.DohSettingsMode.INCREASED
        DOH_MAX -> Engine.DohSettingsMode.MAX
        DOH_OFF -> Engine.DohSettingsMode.OFF
        else -> Engine.DohSettingsMode.DEFAULT
    }

    fun isDohProviderSelectable(): Boolean = dohMode == DOH_INCREASED || dohMode == DOH_MAX

    var interfaceFontScale by floatPreference(INTERFACE_FONT_SCALE, 1.0f)
    
    var amoledMode by booleanPreference(AMOLED_MODE, false)
    
    var dynamicColors by booleanPreference(DYNAMIC_COLORS, false)
    
    var keepWallpaperDimmed by booleanPreference(KEEP_WALLPAPER_DIMMED, true)

    var shouldUseBottomToolbar: Boolean
        get() {
            return toolbarPosition == ToolbarPosition.BOTTOM.ordinal
        }
        set(value) {
            toolbarPosition = if (value) ToolbarPosition.BOTTOM.ordinal else ToolbarPosition.TOP.ordinal
        }

    companion object {
        const val SCW_PREFERENCES = "scw_preferences"

        const val JAVA_SCRIPT_ENABLED = "java_script_enabled"
        const val SEARCH_ENGINE = "search_engine"
        const val CUSTOM_SEARCH_ENGINE = "custom_search_engine"
        const val CUSTOM_SEARCH_ENGINE_URL = "custom_search_engine_url"
        const val TOOLBAR_POSITION = "toolbar_position"
        const val HOMEPAGE_TYPE = "homepage_type"
        const val APP_THEME_CHOICE = "app_theme_choice"
        const val WEB_THEME_CHOICE = "web_theme_choice"
        const val LAUNCH_IN_APP = "launch_in_app"
        const val CUSTOM_ADDON_BOOL = "custom_addon_bool"
        const val SHOWN_ADDON_DISCLAIMER = "shown_disclaimer"
        const val COLLECTION_NAME = "collection_name"
        const val COLLECTION_USER = "collection_user"
        const val AUTO_FONT_SIZE = "auto_font_size"
        const val FONT_SIZE_FACTOR = "font_size_factor"
        const val STACK_FROM_BOTTOM = "stack_from_bottom"
        const val SHOW_TAB_GROUP_BAR = "show_tab_group_bar"
        const val SWIPE_TO_REFRESH = "swipe_to_refresh"
        const val REMOTE_DEBUGGING = "remote_debugging"
        const val RESIST_FINGERPRINTING = "resist_fingerprinting"
        const val FAST_PAGE_LOAD = "fast_page_load"
        const val PROMPT_EXTERNAL_DOWNLOADER = "prompt_external_downloader"
        const val SHOW_URL_PROTOCOL = "show_url_protocol"
        const val SEARCH_SUGGESTIONS = "search_suggestions"
        const val SEARCH_SUGGESTION_COUNT = "search_suggestion_count"
        const val SAFE_BROWSING = "safe_browsing"
        const val TRACKING_PROTECTION = "tracking_protection"
        const val SHOW_SHORTCUTS = "show_shortcuts"
        const val HOMEPAGE_BACKGROUND_CHOICE = "homepage_background_choice"
        const val HOMEPAGE_BACKGROUND_URL = "homepage_background_url"
        const val LOAD_SHORTCUT_ICONS = "load_shortcut_icons"
        const val INTERFACE_FONT_SCALE = "interface_font_scale"
        const val AMOLED_MODE = "amoled_mode"
        const val DYNAMIC_COLORS = "dynamic_colors"
        const val KEEP_WALLPAPER_DIMMED = "keep_wallpaper_dimmed"
        const val ETP_LEVEL = "etp_level"
        const val ETP_CRYPTOMINERS = "etp_cryptominers"
        const val ETP_FINGERPRINTERS = "etp_fingerprinters"
        const val ETP_TRACKING_CONTENT = "etp_tracking_content"
        const val ETP_TRACKING_ADS = "etp_tracking_ads"
        const val ETP_SOCIAL_TRACKING = "etp_social_tracking"
        const val ETP_EMAIL_TRACKING = "etp_email_tracking"
        const val HTTPS_ONLY_MODE = "https_only_mode"
        const val DOH_MODE = "doh_mode"
        const val DOH_PROVIDER_URL = "doh_provider_url"
        const val GLOBAL_PRIVACY_CONTROL = "global_privacy_control"
        const val SLEEPING_TABS_MODE = "sleeping_tabs_mode"
        const val PRIVATE_SEARCH_ENGINE = "private_search_engine"

        const val SLEEPING_TABS_OFF = 0
        const val SLEEPING_TABS_AGGRESSIVE = 2
        const val SLEEPING_TABS_SINGLE = 3

        const val DESKTOP_MODE_DEFAULT = "desktop_mode_default"
        const val TRANSLATIONS_ENABLED = "translations_enabled"
        const val BACKGROUND_PLAYBACK_YOUTUBE = "background_playback_youtube"

        const val LR_TABS_PROFILE_DEFAULT_MIGRATE = "lr.tabs.profile.defaultMigrate.v2"
        const val LR_TABS_PROFILE_DEFAULT_MIGRATE_PENDING_POPUP =
            "lr.tabs.profile.defaultMigrate.v2.pendingPopup"
        const val LR_TABS_PROFILE_DEFAULT_MIGRATE_TAB_COUNT =
            "lr.tabs.profile.defaultMigrate.v2.tabCount"

        const val LR_BG_PLAY_STALE_CHECK_DONE =
            "lr.permissions.bgPlay.staleCheckDone"

        const val HTTPS_ONLY_PRIVATE = 1
        const val HTTPS_ONLY_ALL = 2

        const val DOH_INCREASED = 1
        const val DOH_MAX = 2
        const val DOH_OFF = 3

        const val CLOUDFLARE_DOH_URI = "https://mozilla.cloudflare-dns.com/dns-query"
        const val NEXTDNS_DOH_URI = "https://firefox.dns.nextdns.io/"
    }
}
