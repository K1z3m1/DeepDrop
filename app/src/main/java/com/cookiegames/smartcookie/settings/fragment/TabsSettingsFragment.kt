package com.cookiegames.smartcookie.settings.fragment

import android.os.Bundle
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.preference.UserPreferences
import javax.inject.Inject

/**
 * Settings for browser tabs behavior, startup and content reflow.
 */
class TabsSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_tabs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        switchPreference(
            preference = SETTINGS_FOREGROUND,
            isChecked = userPreferences.tabsToForegroundEnabled,
            onCheckChange = { userPreferences.tabsToForegroundEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_LAST_TAB,
            isChecked = userPreferences.closeOnLastTab,
            onCheckChange = { userPreferences.closeOnLastTab = it }
        )

        switchPreference(
            preference = SETTINGS_RESTORE_TABS,
            isChecked = userPreferences.restoreLostTabsEnabled,
            onCheckChange = { userPreferences.restoreLostTabsEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_ALL_TABS,
            isChecked = userPreferences.allTabs,
            onCheckChange = { userPreferences.allTabs = it }
        )

        switchPreference(
            preference = SETTINGS_IMAGES,
            isChecked = userPreferences.blockImagesEnabled,
            onCheckChange = { userPreferences.blockImagesEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_REFLOW,
            isChecked = userPreferences.textReflowEnabled,
            onCheckChange = { userPreferences.textReflowEnabled = it }
        )
    }

    companion object {
        private const val SETTINGS_FOREGROUND = "new_tabs_foreground"
        private const val SETTINGS_LAST_TAB = "last_tab"
        private const val SETTINGS_RESTORE_TABS = "restore_tabs"
        private const val SETTINGS_ALL_TABS = "load_tabs"
        private const val SETTINGS_IMAGES = "cb_images"
        private const val SETTINGS_REFLOW = "text_reflow"
    }
}
