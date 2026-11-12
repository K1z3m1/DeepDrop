/*
 * Copyright 2014 A.C.R. Development
 */
package com.cookiegames.smartcookie.settings.activity

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.settings.fragment.SettingsFragment
import com.cookiegames.smartcookie.utils.ThemeUtils
import javax.inject.Inject

class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.cookiegames.smartcookie.utils.LocaleHelper.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        super.applyOverrideConfiguration(
            com.cookiegames.smartcookie.utils.LocaleHelper.keepLocale(baseContext, overrideConfiguration)
        )
    }


    @Inject
    internal lateinit var userPreferences: UserPreferences

    private var currentTheme: AppTheme = AppTheme.LIGHT
    private lateinit var backButton: ImageButton
    private lateinit var titleText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)

        currentTheme = userPreferences.useTheme
        val color: Int
        when (currentTheme) {
            AppTheme.LIGHT -> {
                setTheme(R.style.Theme_SettingsTheme)
                color = ThemeUtils.getColorBackground(this)
                window.setBackgroundDrawable(ColorDrawable(color))
            }
            AppTheme.DARK -> {
                setTheme(R.style.Theme_SettingsTheme_Dark)
                color = ThemeUtils.getColorBackground(this)
                window.setBackgroundDrawable(ColorDrawable(color))
            }
            AppTheme.BLACK -> {
                setTheme(R.style.Theme_SettingsTheme_Black)
                color = ThemeUtils.getColorBackground(this)
                window.setBackgroundDrawable(ColorDrawable(color))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = color
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        backButton = findViewById(R.id.settings_back_button)
        titleText = findViewById(R.id.settings_title)

        backButton.setOnClickListener {
            onBackPressed()
        }

        if (savedInstanceState == null) {
            val defaultTitle = getString(R.string.settings)
            title = defaultTitle
            titleText.text = defaultTitle
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .commit()
        } else {
            val savedTitle = savedInstanceState.getCharSequence(TITLE_TAG, getString(R.string.settings))
            title = savedTitle
            titleText.text = savedTitle
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val count = supportFragmentManager.backStackEntryCount
            if (count == 0) {
                val defaultTitle = getString(R.string.settings)
                title = defaultTitle
                titleText.text = defaultTitle
            } else {
                val entry = supportFragmentManager.getBackStackEntryAt(count - 1)
                entry.name?.let {
                    title = it
                    titleText.text = it
                }
            }
        }

        overridePendingTransition(R.anim.slide_in_from_right, R.anim.fade_out_scale)
    }

    override fun onResume() {
        super.onResume()
        if (userPreferences.useTheme != currentTheme) {
            recreate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(TITLE_TAG, title)
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val fragmentName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            fragmentName
        ).apply {
            arguments = pref.extras
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,
                R.anim.fade_out_scale,
                R.anim.fade_in_scale,
                R.anim.slide_out_to_right
            )
            .replace(R.id.container, fragment)
            .addToBackStack(pref.title?.toString())
            .commit()

        pref.title?.let {
            title = it
            titleText.text = it
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
            finish()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in_scale, R.anim.slide_out_to_right)
    }

    companion object {
        private const val TITLE_TAG = "settings_title"
    }
}
