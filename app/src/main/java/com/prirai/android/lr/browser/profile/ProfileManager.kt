package com.prirai.android.lr.browser.profile

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.prirai.android.lr.browser.SearchEnginePreferences
import com.prirai.android.lr.ext.components
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import android.util.Log
import java.util.UUID
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction

class ProfileManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val PREFS_NAME = "profile_manager"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_LAST_PRIVATE_PROFILE = "last_private_profile"

        @Volatile
        private var instance: ProfileManager? = null

        fun getInstance(context: Context): ProfileManager {
            return instance ?: synchronized(this) {
                instance ?: ProfileManager(context.applicationContext).also { instance = it }
            }
        }

        private const val F_ID = "id"
        private const val F_NAME = "name"
        private const val F_COLOR = "color"
        private const val F_EMOJI = "emoji"
        private const val F_IS_DEFAULT = "isDefault"
        private const val F_CREATED_AT = "createdAt"

        private fun BrowserProfile.toJson(): JSONObject = JSONObject().apply {
            put(F_ID, id)
            put(F_NAME, name)
            put(F_COLOR, color)
            put(F_EMOJI, emoji)
            put(F_IS_DEFAULT, isDefault)
            put(F_CREATED_AT, createdAt)
        }

        private fun JSONObject.toProfile(): BrowserProfile = BrowserProfile(
            id = optString(F_ID, UUID.randomUUID().toString()),
            name = optString(F_NAME, ""),
            color = optInt(F_COLOR, 0),
            emoji = optString(F_EMOJI, "\uD83D\uDC64"), // 👤
            isDefault = optBoolean(F_IS_DEFAULT, false),
            createdAt = optLong(F_CREATED_AT, System.currentTimeMillis()),
        )
    }

    fun getAllProfiles(): List<BrowserProfile> {
        val json = prefs.getString(KEY_PROFILES, null)
        val profiles = if (json != null) {
            parseProfilesJson(json)
        } else {
            emptyList()
        }

        val defaultProfile = BrowserProfile.getDefaultProfile()
        return if (profiles.none { it.id == defaultProfile.id }) {
            listOf(defaultProfile) + profiles
        } else {
            profiles
        }
    }

    private fun parseProfilesJson(json: String): List<BrowserProfile> {
        return try {
            val array = JSONArray(json)
            val out = ArrayList<BrowserProfile>(array.length())
            for (i in 0 until array.length()) {
                out.add(array.getJSONObject(i).toProfile())
            }
            out
        } catch (e: JSONException) {
            Log.w("ProfileManager", "Malformed profiles JSON, dropping value", e)
            emptyList()
        }
    }

    private fun List<BrowserProfile>.toJsonString(): String {
        val array = JSONArray()
        forEach { array.put(it.toJson()) }
        return array.toString()
    }
    
    fun getActiveProfile(): BrowserProfile {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, "default")
        return getAllProfiles().find { it.id == activeId } ?: BrowserProfile.getDefaultProfile()
    }
    
    fun setActiveProfile(profile: BrowserProfile) {
        prefs.edit { putString(KEY_ACTIVE_PROFILE_ID, profile.id) }
        ioScope.launch {
            ProfileAddonPolicy.applyForProfile(context, profile.id)
            SearchEnginePreferences.apply(context, private = false)
        }
    }
    
    fun createProfile(name: String, color: Int, emoji: String): BrowserProfile {
        val newProfile = BrowserProfile(
            name = name,
            color = color,
            emoji = emoji,
            isDefault = false
        )
        
        val profiles = getAllProfiles().toMutableList()
        profiles.add(newProfile)
        saveProfiles(profiles)
        
        return newProfile
    }
    
    fun updateProfile(profile: BrowserProfile) {
        if (profile.isDefault) {
            return
        }
        
        val profiles = getAllProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            profiles[index] = profile
            saveProfiles(profiles)
        }
    }
    
    fun deleteProfile(profileId: String) {
        if (profileId == "default") {
            throw IllegalArgumentException("Cannot delete default profile")
        }
        
        val profiles = getAllProfiles().toMutableList()
        profiles.removeAll { it.id == profileId }
        saveProfiles(profiles)
        
        if (getActiveProfile().id == profileId) {
            setActiveProfile(BrowserProfile.getDefaultProfile())
        }
        
        cleanupProfileStorage(profileId)
    }
    
    fun isPrivateMode(): Boolean {
        return prefs.getBoolean(KEY_LAST_PRIVATE_PROFILE, false)
    }
    
    fun setPrivateMode(isPrivate: Boolean) {
        prefs.edit { putBoolean(KEY_LAST_PRIVATE_PROFILE, isPrivate) }
    }
    
    private fun saveProfiles(profiles: List<BrowserProfile>) {
        val toSave = profiles.filter { !it.isDefault }
        val json = toSave.toJsonString()
        prefs.edit { putString(KEY_PROFILES, json) }
    }
    
    private fun cleanupProfileStorage(profileId: String) {
        val profileDir = context.getDir("profile_$profileId", Context.MODE_PRIVATE)
        profileDir.deleteRecursively()
    }

    fun migrateTabToProfile(tabId: String, targetProfileId: String): Boolean {
        val store = context.components.store
        val tab = store.state.tabs.find { it.id == tabId } ?: return false

        val targetContextId = BrowserProfile.contextIdFor(
            profileId = targetProfileId,
            isPrivate = targetProfileId == "private",
        )
        if (tab.contextId == targetContextId) return false

        val isTargetPrivate = targetProfileId == "private"
        val wasSelected = store.state.selectedTabId == tabId
        val existingEngineSession = tab.engineState.engineSession

        val rewritten = tab.copy(
            contextId = targetContextId,
            content = tab.content.copy(private = isTargetPrivate),
            engineState = tab.engineState.copy(engineSession = null),
        )

        if (existingEngineSession != null) {
            store.dispatch(
                EngineAction.UnlinkEngineSessionAction(tabId),
            )
        }

        store.dispatch(
            TabListAction.RemoveTabAction(
                tabId = tabId,
                selectParentIfExists = false,
            ),
        )

        store.dispatch(
            TabListAction.AddTabAction(
                tab = rewritten,
                select = wasSelected,
            ),
        )

        if (existingEngineSession != null) {
            store.dispatch(
                EngineAction.LinkEngineSessionAction(
                    tabId = tabId,
                    engineSession = existingEngineSession,
                ),
            )
        }

        return true
    }
    
    fun migrateTabsToProfile(tabIds: List<String>, targetProfileId: String): Int {
        var successCount = 0
        tabIds.forEach { tabId ->
            if (migrateTabToProfile(tabId, targetProfileId)) {
                successCount++
            }
        }
        return successCount
    }
}
