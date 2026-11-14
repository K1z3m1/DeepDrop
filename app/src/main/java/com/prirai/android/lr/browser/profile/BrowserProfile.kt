package com.prirai.android.lr.browser.profile

import java.util.UUID
import com.prirai.android.lr.theme.ColorConstants

data class BrowserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int,
    val emoji: String = "👤",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun getDefaultProfile() = BrowserProfile(
            id = "default",
            name = "Default",
            color = ColorConstants.Profiles.DEFAULT_COLOR,
            emoji = "👤",
            isDefault = true
        )
        
        val PROFILE_COLORS = ColorConstants.Profiles.COLORS
        
        val PROFILE_EMOJIS = ProfileIcons.KEYS

        fun contextIdFor(profileId: String, isPrivate: Boolean = false): String? = when {
            isPrivate -> "private"
            profileId == "default" -> null
            else -> "profile_$profileId"
        }

        fun contextIdFor(profile: BrowserProfile, isPrivate: Boolean = false): String? =
            contextIdFor(profile.id, isPrivate)
    }
}
