package com.prirai.android.lr.settings.fragment

import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.prirai.android.lr.R
import com.prirai.android.lr.addons.AddonsActivity
import androidx.preference.Preference
import androidx.preference.PreferenceGroup

class SettingsFragment : BaseSettingsFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_headers)

        tintCategoryIcons()

        findPreference<Preference>("addons_settings")?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), AddonsActivity::class.java)
            startActivity(intent)
            true
        }
    }

    private fun tintCategoryIcons() {
        val tints = mapOf(
            "com.prirai.android.lr.settings.fragment.GeneralSettingsFragment" to R.color.settings_icon_general,
            "com.prirai.android.lr.settings.fragment.PrivacyAndSecuritySettingsFragment" to R.color.settings_icon_privacy,
            "com.prirai.android.lr.settings.fragment.CustomizationSettingsFragment" to R.color.settings_icon_customization,
            "addons_settings" to R.color.settings_icon_addons,
            "com.prirai.android.lr.settings.fragment.ImportExportSettingsFragment" to R.color.settings_icon_import_export,
            "com.prirai.android.lr.settings.fragment.AdvancedSettingsFragment" to R.color.settings_icon_advanced,
            "com.prirai.android.lr.settings.fragment.AboutSettingsFragment" to R.color.settings_icon_about,
        )
        fun tintGroup(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                when (preference) {
                    is PreferenceGroup -> tintGroup(preference)
                    is Preference -> {
                        val tintRes = tints[preference.fragment ?: preference.key] ?: continue
                        preference.icon = preference.icon?.mutate()?.apply {
                            setTint(ContextCompat.getColor(requireContext(), tintRes))
                        }
                    }
                }
            }
        }
        tintGroup(preferenceScreen)
    }
}