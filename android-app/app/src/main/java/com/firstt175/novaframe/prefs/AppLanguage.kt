package com.firstt175.novaframe.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * UI language override. Deliberately kept separate from [NovaPreferences]/[NovaConfig] —
 * this only affects which string resources resolve (via [AppLanguagePrefs.wrap] in
 * every Activity's attachBaseContext), never session/native state, so it doesn't need
 * to round-trip through [produceConfigState].
 */
enum class AppLanguage(val tag: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    THAI("th"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

private const val PREFS_NAME = "lsfg_language"
private const val KEY_LANGUAGE = "language"

object AppLanguagePrefs {
    fun get(ctx: Context): AppLanguage {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppLanguage.fromTag(sp.getString(KEY_LANGUAGE, null))
    }

    fun set(ctx: Context, language: AppLanguage) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }

    /**
     * Wraps [base] with a Configuration forced to the saved language, or returns
     * [base] unchanged when the preference is [AppLanguage.SYSTEM]. Call from every
     * Activity's attachBaseContext, before super.attachBaseContext, so the override
     * is in place before any resource lookup happens.
     */
    fun wrap(base: Context): Context {
        val lang = get(base)
        if (lang == AppLanguage.SYSTEM) return base
        val locale = Locale(lang.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        val newContext = base.createConfigurationContext(config)
        return ContextWrapper(newContext)
    }
}
