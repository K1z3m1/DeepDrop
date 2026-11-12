package com.cookiegames.smartcookie.browser.activity

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Menu
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.iterator
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.preference.UserPreferences
import javax.inject.Inject

abstract class ThemableBrowserActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.cookiegames.smartcookie.utils.LocaleHelper.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        super.applyOverrideConfiguration(
            com.cookiegames.smartcookie.utils.LocaleHelper.keepLocale(baseContext, overrideConfiguration)
        )
    }


    // TODO reduce protected visibility
    @Inject protected lateinit var userPreferences: UserPreferences

    private var themeId: AppTheme = AppTheme.LIGHT
    private var showTabsInDrawer: Boolean = false
    private var shouldRunOnResumeActions = false
    private var languageTag: String? = null

    /**
     * Override this to provide an alternate theme that should be set for every instance of this
     * activity regardless of the user's preference.
     */
    @StyleRes
    protected open fun provideThemeOverride(): Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)
        languageTag = com.cookiegames.smartcookie.utils.LocaleHelper.getSavedTag(this)
        themeId = userPreferences.useTheme
        showTabsInDrawer = userPreferences.showTabsInDrawer

        // set the theme
        setTheme(provideThemeOverride() ?: when (userPreferences.useTheme) {
            AppTheme.LIGHT -> R.style.Theme_LightTheme
            AppTheme.DARK -> R.style.Theme_DarkTheme
            AppTheme.BLACK -> R.style.Theme_BlackTheme
        })
        super.onCreate(savedInstanceState)

        resetPreferences()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        withStyledAttributes(attrs = intArrayOf(R.attr.iconColorState)) {
            val iconTintList = getColorStateList(0)
            menu.iterator().forEach { menuItem ->
                menuItem.icon?.let { DrawableCompat.setTintList(DrawableCompat.wrap(it), iconTintList) }
            }
        }

        return super.onCreateOptionsMenu(menu)
    }

    private fun resetPreferences() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (userPreferences.useBlackStatusBar || !userPreferences.showTabsInDrawer) {
                window.statusBarColor = Color.BLACK
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && shouldRunOnResumeActions) {
            shouldRunOnResumeActions = false
            onWindowVisibleToUserAfterResume()
        }
    }

    /**
     * Called after the activity is resumed
     * and the UI becomes visible to the user.
     * Called by onWindowFocusChanged only if
     * onResume has been called.
     */
    protected open fun onWindowVisibleToUserAfterResume() = Unit

    override fun onResume() {
        super.onResume()
        resetPreferences()
        shouldRunOnResumeActions = true
        val drawerTabs = userPreferences.showTabsInDrawer
        val currentLanguageTag = com.cookiegames.smartcookie.utils.LocaleHelper.getSavedTag(this)
        if (themeId != userPreferences.useTheme || showTabsInDrawer != drawerTabs || languageTag != currentLanguageTag) {
            restart()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeId != userPreferences.useTheme) {
            restart()
        }
    }

    protected fun restart() {
        finish()
        startActivity(Intent(this, javaClass))
    }
}
