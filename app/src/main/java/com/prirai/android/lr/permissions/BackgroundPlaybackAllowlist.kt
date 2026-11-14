package com.prirai.android.lr.permissions

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackgroundPlaybackAllowlist private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _origins = MutableStateFlow(loadOrigins())

    val origins: StateFlow<Set<String>> = _origins.asStateFlow()

    fun isAllowed(origin: String): Boolean = origin in _origins.value

    fun allow(origin: String) {
        val current = _origins.value
        if (origin in current) return
        val updated = current + origin
        persist(updated)
    }

    fun block(origin: String) {
        val current = _origins.value
        if (origin !in current) return
        val updated = current - origin
        persist(updated)
    }

    fun migrateFromLegacyGlobalPref(enabledBefore: Boolean) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        prefs.edit { putBoolean(KEY_MIGRATED, true) }
        if (!enabledBefore) return
        val seeded = _origins.value + LEGACY_YOUTUBE_ORIGINS
        persist(seeded)
    }

    private fun persist(updated: Set<String>) {
        prefs.edit { putStringSet(KEY_ORIGINS, updated) }
        _origins.value = updated
    }

    private fun loadOrigins(): Set<String> =
        prefs.getStringSet(KEY_ORIGINS, emptySet())?.toSet() ?: emptySet()

    companion object {
        private const val PREFS_NAME = "lr_bg_playback_allowlist"
        private const val KEY_ORIGINS = "allowed_origins"
        private const val KEY_MIGRATED = "legacy_global_pref_migrated"

        private val LEGACY_YOUTUBE_ORIGINS = setOf(
            "https://www.youtube.com:443",
            "https://m.youtube.com:443",
            "https://music.youtube.com:443",
            "https://youtube.com:443",
        )

        @Volatile private var instance: BackgroundPlaybackAllowlist? = null

        fun getInstance(context: Context): BackgroundPlaybackAllowlist =
            instance ?: synchronized(this) {
                instance ?: BackgroundPlaybackAllowlist(context).also { instance = it }
            }
    }
}
