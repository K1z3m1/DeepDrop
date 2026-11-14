package com.prirai.android.lr.addons

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.prirai.android.lr.R
import com.prirai.android.lr.ext.enableEdgeToEdgeMode
import com.prirai.android.lr.ext.applyPersistentInsets
import com.prirai.android.lr.theme.ThemeManager
import com.prirai.android.lr.theme.applyCompleteTheme

class AddonsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_base)
        
        applyCompleteTheme(this)
        
        enableEdgeToEdgeMode()

        val addonId = intent.getStringExtra("ADDON_ID")
        val addonUrl = intent.getStringExtra("ADDON_URL")

        supportActionBar?.elevation = 0f
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.extensions)

        if (savedInstanceState == null) {
            val fm: FragmentManager = supportFragmentManager
            val arguments = Bundle()
            if(addonId != null) arguments.putString("ADDON_ID", addonId)
            if(addonUrl != null) arguments.putString("ADDON_URL", addonUrl)

            val addonFragment = ComposeAddonsFragment()
            addonFragment.arguments = arguments

            fm.beginTransaction().replace(R.id.container, addonFragment).commit()
        }
        
        findViewById<View>(R.id.container)?.applyPersistentInsets()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applySystemBarsTheme(this, false)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
