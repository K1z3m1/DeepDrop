package com.cookiegames.smartcookie.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Handles the in-app language setting. The chosen language is stored in its own small
 * SharedPreferences file so it can be read very early (in attachBaseContext), before
 * dependency injection is available.
 */
object LocaleHelper {

    private const val PREFS_NAME = "app_language"
    private const val KEY_LANGUAGE = "language"

    /** Languages offered in Settings: language tag, name written in the language itself. */
    data class AppLanguage(val tag: String, val displayName: String, val locale: Locale)

    val languages: List<AppLanguage> = listOf(
        AppLanguage("th", "ไทย", Locale("th")),
        AppLanguage("en", "English", Locale("en")),
        AppLanguage("zh", "简体中文", Locale.SIMPLIFIED_CHINESE),
        AppLanguage("ja", "日本語", Locale("ja"))
    )

    /** The saved language tag, or null when the user never picked one (follow the system). */
    fun getSavedTag(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)

    /**
     * The language that is effectively in use: the saved one, otherwise the system language
     * if we support it, otherwise English.
     */
    fun getCurrentLanguage(context: Context): AppLanguage {
        val saved = getSavedTag(context)
        languages.firstOrNull { it.tag == saved }?.let { return it }
        val systemLang = Locale.getDefault().language
        return languages.firstOrNull { it.locale.language == systemLang }
            ?: languages.first { it.tag == "en" }
    }

    /** Saves the language and updates the application-level resources immediately. */
    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, tag).apply()
        updateAppResources(context.applicationContext, tag)
    }

    /** Wraps a context so that it uses the saved language. Call from attachBaseContext. */
    fun wrap(base: Context): Context {
        val tag = getSavedTag(base) ?: return base
        val language = languages.firstOrNull { it.tag == tag } ?: return base
        return applyLocale(base, language.locale)
    }

    private fun applyLocale(base: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return base.createConfigurationContext(config)
        }
        @Suppress("DEPRECATION")
        base.resources.updateConfiguration(config, base.resources.displayMetrics)
        return base
    }

    @Suppress("DEPRECATION")
    private fun updateAppResources(appContext: Context, tag: String) {
        val language = languages.firstOrNull { it.tag == tag } ?: return
        Locale.setDefault(language.locale)
        val config = Configuration(appContext.resources.configuration)
        config.setLocale(language.locale)
        appContext.resources.updateConfiguration(config, appContext.resources.displayMetrics)
    }

    /**
     * AppCompat 1.2+ re-applies its own configuration on top of ours, which resets the locale.
     * Activities call this from applyOverrideConfiguration to keep the chosen language.
     */
    fun keepLocale(base: Context?, override: Configuration?): Configuration? {
        if (override == null || base == null) return override
        val uiMode = override.uiMode
        override.setTo(base.resources.configuration)
        override.uiMode = uiMode
        return override
    }
}
