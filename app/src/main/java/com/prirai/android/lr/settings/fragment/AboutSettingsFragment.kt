package com.prirai.android.lr.settings.fragment

import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import com.prirai.android.lr.R
import org.mozilla.geckoview.BuildConfig as GeckoBuildConfig


class AboutSettingsFragment : BaseSettingsFragment() {


    override fun onCreatePreferences(savedInstanceState: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_about)

        val packageInfo =
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)

        clickablePreference(
            preference = "pref_version",
            summary = "${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})",
            onClick = { }
        )

        clickablePreference(
            preference = "pref_version_geckoview",
            summary = GeckoBuildConfig.MOZ_APP_VERSION + "-" + GeckoBuildConfig.MOZ_APP_BUILDID,
            onClick = { }
        )

        clickablePreference(
            preference = "pref_version_mozac",
            summary = mozilla.components.Build.VERSION + ", " + mozilla.components.Build.GIT_HASH,
            onClick = { }
        )
    }
}
