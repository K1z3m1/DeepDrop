package com.prirai.android.lr.settings.activity

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.prirai.android.lr.R
import com.prirai.android.lr.ext.enableEdgeToEdgeMode
import com.prirai.android.lr.ext.applyPersistentInsets
import com.prirai.android.lr.settings.fragment.SettingsFragment
import com.prirai.android.lr.theme.applyCompleteTheme
import com.prirai.android.lr.theme.ThemeManager

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        applyCompleteTheme(this)
        
        enableEdgeToEdgeMode()
        
        val bgColor = ThemeManager.getBackgroundColor(this)
        window.decorView.setBackgroundColor(bgColor)
        findViewById<View>(R.id.container)?.setBackgroundColor(bgColor)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .commit()
        }

        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            supportActionBar!!.setDisplayShowHomeEnabled(true)
        }

        findViewById<View>(R.id.container)?.applyPersistentInsets()
        
        supportActionBar?.elevation = 0f

        title = getString(R.string.settings)
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                title = getString(R.string.settings)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val target = pref.fragment ?: return false
        if (!target.startsWith("com.prirai.android.lr.settings.fragment.")) return false

        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment ?: return false
        ).apply {
            arguments = args
            setTargetFragment(caller, 0)
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
        
        title = pref.title
        
        return true
    }
}