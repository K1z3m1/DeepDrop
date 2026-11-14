package com.prirai.android.lr.browser.profile

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.prirai.android.lr.browser.tabgroups.TabGroupData
import com.prirai.android.lr.browser.tabgroups.UnifiedTabGroupManager
import com.prirai.android.lr.browser.tabs.compose.TabOrderManager
import com.prirai.android.lr.browser.tabs.compose.UnifiedTabOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.prirai.android.lr.theme.ColorConstants
import java.io.ByteArrayOutputStream

class ProfileExportManager private constructor(private val context: Context) {
    companion object {
        private const val EXPORT_VERSION = 1
        private const val APP_VERSION = "lr-1.0"
        private const val PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
        private const val MAX_ZIP_ENTRIES = 32
        private const val MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024
        private const val EXPORT_DIR = "profile_exports"

        @Volatile
        private var instance: ProfileExportManager? = null

        fun getInstance(context: Context): ProfileExportManager =
            instance ?: synchronized(this) {
                instance ?: ProfileExportManager(context.applicationContext).also { instance = it }
            }
    }

    suspend fun exportProfile(profile: BrowserProfile): Uri = withContext(Dispatchers.IO) {
        val contextId = profileToContextId(profile)
        val timestamp = System.currentTimeMillis()
        val safeName = profile.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val fileName = "LR_profile_${safeName}_${timestamp}.zip"

        val exportDir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val exportFile = File(exportDir, fileName)

        ZipOutputStream(exportFile.outputStream().buffered()).use { zos ->
            writeEntry(zos, "manifest.json", buildManifestJson(timestamp))
            writeEntry(zos, "profile.json", buildProfileJson(profile))
            writeEntry(zos, "tabgroups.json", buildTabGroupsJson(contextId))
            writeEntry(zos, "tabs.json", buildTabsJson(profile))
        }

        val authority = context.packageName + PROVIDER_AUTHORITY_SUFFIX
        FileProvider.getUriForFile(context, authority, exportFile)
    }

    private fun buildManifestJson(timestamp: Long): String =
        JSONObject().apply {
            put("version", EXPORT_VERSION)
            put("exportedAt", timestamp)
            put("appVersion", APP_VERSION)
        }.toString()

    private fun buildProfileJson(profile: BrowserProfile): String =
        JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("color", profile.color)
            put("emoji", profile.emoji)
            put("isDefault", profile.isDefault)
            put("createdAt", profile.createdAt)
        }.toString()

    private fun buildTabGroupsJson(contextId: String?): String {
        val groupManager = UnifiedTabGroupManager.getInstance(context)
        val groups = groupManager.getAllGroups().filter { matchesContext(it, contextId) }

        val array = JSONArray()
        groups.forEach { group ->
            array.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("colorInt", group.color)
                put("colorName", colorToName(group.color))
                put("createdAt", group.createdAt)
                put("tabIds", JSONArray(group.tabIds))
            })
        }
        return array.toString()
    }

    private suspend fun buildTabsJson(profile: BrowserProfile): String {
        val groupManager = UnifiedTabGroupManager.getInstance(context)
        val tabOrderManager = TabOrderManager.getInstance(context, groupManager)
        val profileId = if (profile.isDefault || profile.id == "default") "default" else profile.id
        val order = tabOrderManager.loadOrder(profileId)

        val items = JSONArray()
        order.primaryOrder.forEach { item ->
            when (item) {
                is UnifiedTabOrder.OrderItem.SingleTab -> {
                    items.put(JSONObject().apply {
                        put("type", "tab")
                        put("tabId", item.tabId)
                    })
                }
                is UnifiedTabOrder.OrderItem.TabGroup -> {
                    items.put(JSONObject().apply {
                        put("type", "group")
                        put("groupId", item.groupId)
                        put("groupName", item.groupName)
                        put("colorInt", item.color)
                        put("isExpanded", item.isExpanded)
                        put("tabIds", JSONArray(item.tabIds))
                    })
                }
            }
        }

        return JSONObject().apply {
            put("profileId", order.profileId)
            put("lastModified", order.lastModified)
            put("order", items)
        }.toString()
    }

    suspend fun importProfile(zipUri: Uri): BrowserProfile = withContext(Dispatchers.IO) {
        val entries = readZipEntries(zipUri)

        val manifestJson = entries["manifest.json"]
            ?: throw IllegalArgumentException("Not a valid LR profile export")
        val profileJson = entries["profile.json"]
            ?: throw IllegalArgumentException("Not a valid LR profile export")

        val manifest = JSONObject(manifestJson)
        val version = manifest.optInt("version", 0)
        if (version < 1) throw IllegalArgumentException("Not a valid LR profile export")

        val profileObj = JSONObject(profileJson)
        val profileName = profileObj.optString("name", "Imported Profile")
            .filter { !it.isISOControl() }.trim().take(50).ifBlank { "Imported Profile" }
        val color = profileObj.optInt("color", BrowserProfile.PROFILE_COLORS.first())
        val emoji = profileObj.optString("emoji", "👤").take(8).ifBlank { "👤" }

        val profileManager = ProfileManager.getInstance(context)
        val existingNames = profileManager.getAllProfiles().map { it.name }
        val finalName = if (profileName in existingNames) "$profileName (imported)" else profileName

        val importedProfile = profileManager.createProfile(finalName, color, emoji)

        val tabGroupsJson = entries["tabgroups.json"]
        if (!tabGroupsJson.isNullOrBlank()) {
            importTabGroups(importedProfile, tabGroupsJson)
        }

        importedProfile
    }

    private suspend fun importTabGroups(profile: BrowserProfile, tabGroupsJson: String) {
    }

    private fun readZipEntries(zipUri: Uri): Map<String, String> {
        val inputStream = context.contentResolver.openInputStream(zipUri)
            ?: throw IllegalArgumentException("Cannot open file")

        val entries = mutableMapOf<String, String>()
        ZipInputStream(inputStream.buffered()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                if (++count > MAX_ZIP_ENTRIES) throw IllegalArgumentException("Too many entries in archive")
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = zis.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ZIP_ENTRY_BYTES) throw IllegalArgumentException("Archive entry too large")
                    buf.write(chunk, 0, n)
                }
                entries[entry.name] = buf.toString(Charsets.UTF_8.name())
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun profileToContextId(profile: BrowserProfile): String? =
        when {
            profile.isDefault || profile.id == "default" -> null // matches null and "profile_default"
            else -> "profile_${profile.id}"
        }

    private fun matchesContext(group: TabGroupData, contextId: String?): Boolean =
        if (contextId == null) {
            group.contextId == null || group.contextId == "profile_default"
        } else {
            group.contextId == contextId
        }

    private fun colorToName(color: Int): String =
        ColorConstants.TabGroups.getColorName(color)
}
