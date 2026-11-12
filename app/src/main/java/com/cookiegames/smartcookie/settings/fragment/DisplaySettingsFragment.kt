package com.cookiegames.smartcookie.settings.fragment

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.MainActivity
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.browser.ChooseNavbarCol
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.BrowserDialog
import com.cookiegames.smartcookie.extensions.resizeAndShow
import com.cookiegames.smartcookie.extensions.withSingleChoiceItems
import com.cookiegames.smartcookie.preference.UserPreferences
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject

/**
 * Display, theme and visual customization settings.
 */
class DisplaySettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_display)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        // Theme selection
        clickableDynamicPreference(
            preference = SETTINGS_THEME,
            summary = userPreferences.useTheme.toDisplayString(),
            onClick = ::showThemePicker
        )

        // Navbar color
        clickablePreference(
            preference = SETTINGS_NAVBAR_COL,
            onClick = ::showColorPicker
        )

        // Dark mode web
        switchPreference(
            preference = SETTINGS_DARK_MODE,
            isChecked = userPreferences.darkModeExtension,
            onCheckChange = {
                userPreferences.darkModeExtension = it
                Toast.makeText(activity, R.string.please_restart, Toast.LENGTH_LONG).show()
            }
        )

        // Black status bar (AMOLED)
        switchPreference(
            preference = SETTINGS_BLACK_STATUS,
            isChecked = userPreferences.useBlackStatusBar,
            onCheckChange = { userPreferences.useBlackStatusBar = it }
        )

        // Color mode
        switchPreference(
            preference = SETTINGS_COLOR_MODE,
            isChecked = userPreferences.colorModeEnabled,
            onCheckChange = { userPreferences.colorModeEnabled = it }
        )

        // Bottom toolbar
        switchPreference(
            preference = SETTINGS_BOTTOM_BAR,
            isChecked = userPreferences.bottomBar,
            onCheckChange = {
                userPreferences.bottomBar = it
                Toast.makeText(activity, R.string.please_restart, Toast.LENGTH_LONG).show()
            }
        )

        // Hide status bar
        switchPreference(
            preference = SETTINGS_HIDESTATUSBAR,
            isChecked = userPreferences.hideStatusBarEnabled,
            onCheckChange = { userPreferences.hideStatusBarEnabled = it }
        )

        // Hide toolbar while scrolling
        switchPreference(
            preference = SETTINGS_FULLSCREEN,
            isChecked = userPreferences.fullScreenEnabled,
            onCheckChange = { userPreferences.fullScreenEnabled = it }
        )

        // Text size
        findPreference<Preference>(SETTINGS_TEXTSIZE)?.summary = "${userPreferences.getTextZoomPercent()}%"
        clickablePreference(
            preference = SETTINGS_TEXTSIZE,
            onClick = ::showTextSizePicker
        )

        // Wide viewport
        switchPreference(
            preference = SETTINGS_VIEWPORT,
            isChecked = userPreferences.useWideViewPortEnabled,
            onCheckChange = { userPreferences.useWideViewPortEnabled = it }
        )

        // Overview mode
        switchPreference(
            preference = SETTINGS_OVERVIEWMODE,
            isChecked = userPreferences.overviewModeEnabled,
            onCheckChange = { userPreferences.overviewModeEnabled = it }
        )

        // Force zoom
        switchPreference(
            preference = SETTINGS_FORCE_ZOOM,
            isChecked = userPreferences.forceZoom,
            onCheckChange = { userPreferences.forceZoom = it }
        )
    }

    private fun AppTheme.toDisplayString(): String = getString(when (this) {
        AppTheme.LIGHT -> R.string.light_theme
        AppTheme.DARK -> R.string.dark_theme
        AppTheme.BLACK -> R.string.black_theme
    })

    private fun showThemePicker(summaryUpdater: SummaryUpdater) {
        val currentTheme = userPreferences.useTheme
        MaterialAlertDialogBuilder(requireContext()).apply {
            setTitle(resources.getString(R.string.theme))
            val values = AppTheme.values().map { Pair(it, it.toDisplayString()) }
            withSingleChoiceItems(values, userPreferences.useTheme) {
                userPreferences.useTheme = it
                summaryUpdater.updateSummary(it.toDisplayString())
            }
            setPositiveButton(resources.getString(R.string.action_ok)) { _, _ ->
                if (currentTheme != userPreferences.useTheme) {
                    val intent = Intent(activity, MainActivity::class.java)
                    startActivity(intent)
                }
            }
            setOnCancelListener {
                if (currentTheme != userPreferences.useTheme) {
                    activity?.onBackPressed()
                }
            }
        }.resizeAndShow()
    }

    private fun showColorPicker() {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.navbar_col)
            val stringArray = resources.getStringArray(R.array.navbar_col)
            val values = ChooseNavbarCol.values().map {
                Pair(it, when (it) {
                    ChooseNavbarCol.NONE -> stringArray[0]
                    ChooseNavbarCol.COLOR -> stringArray[1]
                })
            }
            withSingleChoiceItems(values, userPreferences.navbarColChoice) {
                userPreferences.navbarColChoice = it
            }
            setPositiveButton(R.string.action_ok) { _, _ ->
                updateNavbarCol(userPreferences.navbarColChoice)
            }
        }
    }

    private fun updateNavbarCol(choice: ChooseNavbarCol) {
        if (choice == ChooseNavbarCol.COLOR) {
            showNavbarColPicker()
        }
        userPreferences.navbarColChoice = choice
    }

    private fun showNavbarColPicker() {
        var initColor = userPreferences.colorNavbar
        if (userPreferences.navbarColChoice == ChooseNavbarCol.NONE) {
            initColor = Color.WHITE
        }
        ColorPickerDialogBuilder
            .with(activity)
            .setTitle("Choose color")
            .initialColor(initColor)
            .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
            .density(12)
            .setOnColorSelectedListener { }
            .setPositiveButton("ok") { _, selectedColor, _ -> userPreferences.colorNavbar = selectedColor }
            .setNegativeButton("cancel") { _, _ -> }
            .build()
            .show()
    }

    private fun showTextSizePicker() {
        val customView = layoutInflater.inflate(R.layout.dialog_seek_bar, null)
        val textPercent = customView.findViewById<TextView>(R.id.text_size_percent)
        val textSample = customView.findViewById<TextView>(R.id.text_size_sample)
        val seekBar = customView.findViewById<SeekBar>(R.id.text_size_seekbar)

        val currentZoom = userPreferences.getTextZoomPercent()
        seekBar.max = 150
        seekBar.progress = (currentZoom - 50).coerceIn(0, 150)

        val updateViews = { zoom: Int ->
            textPercent.text = if (zoom == 100) "100% (Predeterminado)" else "$zoom%"
            textSample.textSize = 15f * (zoom / 100f)
        }

        updateViews(currentZoom)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateViews(progress + 50)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_text_size)
            .setView(customView)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val zoom = seekBar.progress + 50
                userPreferences.textSize = zoom
                findPreference<Preference>(SETTINGS_TEXTSIZE)?.summary = "$zoom%"
            }
            .setNeutralButton("Restablecer") { _, _ ->
                userPreferences.textSize = 100
                findPreference<Preference>(SETTINGS_TEXTSIZE)?.summary = "100%"
            }
            .setNegativeButton(R.string.action_cancel, null)
            .resizeAndShow()
    }

    companion object {
        private const val SETTINGS_THEME = "app_theme"
        private const val SETTINGS_NAVBAR_COL = "navbar_col"
        private const val SETTINGS_DARK_MODE = "dark_mode"
        private const val SETTINGS_BLACK_STATUS = "black_status_bar"
        private const val SETTINGS_COLOR_MODE = "cb_colormode"
        private const val SETTINGS_BOTTOM_BAR = "bottom_bar"
        private const val SETTINGS_HIDESTATUSBAR = "fullScreenOption"
        private const val SETTINGS_FULLSCREEN = "fullscreen"
        private const val SETTINGS_TEXTSIZE = "text_size"
        private const val SETTINGS_VIEWPORT = "wideViewPort"
        private const val SETTINGS_OVERVIEWMODE = "overViewMode"
        private const val SETTINGS_FORCE_ZOOM = "force_zoom"
    }
}
