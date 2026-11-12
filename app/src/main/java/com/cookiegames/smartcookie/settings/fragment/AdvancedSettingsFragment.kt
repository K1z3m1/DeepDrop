package com.cookiegames.smartcookie.settings.fragment

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.browser.JavaScriptChoice
import com.cookiegames.smartcookie.browser.ProxyChoice
import com.cookiegames.smartcookie.browser.SearchBoxDisplayChoice
import com.cookiegames.smartcookie.constant.TEXT_ENCODINGS
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.BrowserDialog
import com.cookiegames.smartcookie.extensions.resizeAndShow
import com.cookiegames.smartcookie.extensions.withSingleChoiceItems
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.ProxyUtils
import com.cookiegames.smartcookie.view.RenderingMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject

/**
 * The advanced settings of the app: web engine, proxy, translator, and SSL.
 */
class AdvancedSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_advanced)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        // Web Engine
        switchPreference(
            preference = SETTINGS_NEW_WINDOW,
            isChecked = userPreferences.popupsEnabled,
            onCheckChange = { userPreferences.popupsEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_JAVASCRIPT,
            isChecked = userPreferences.javaScriptEnabled,
            onCheckChange = { userPreferences.javaScriptEnabled = it }
        )

        clickableDynamicPreference(
            preference = SETTINGS_BLOCK_JAVASCRIPT,
            summary = userPreferences.javaScriptChoice.toSummary(),
            onClick = ::showJavaScriptPicker
        )

        switchPreference(
            preference = SETTINGS_BLOCK_INTENT,
            isChecked = userPreferences.blockIntent,
            onCheckChange = { userPreferences.blockIntent = it }
        )

        clickableDynamicPreference(
            preference = SETTINGS_RENDERING_MODE,
            summary = userPreferences.renderingMode.toDisplayString(),
            onClick = this::showRenderingDialogPicker
        )

        clickableDynamicPreference(
            preference = SETTINGS_TEXT_ENCODING,
            summary = userPreferences.textEncoding,
            onClick = this::showTextEncodingDialogPicker
        )

        clickableDynamicPreference(
            preference = SETTINGS_URL_CONTENT,
            summary = userPreferences.urlBoxContentChoice.toDisplayString(),
            onClick = this::showUrlBoxDialogPicker
        )

        // Connectivity & Proxy
        clickableDynamicPreference(
            preference = SETTINGS_PROXY,
            summary = userPreferences.proxyChoice.toSummary(),
            onClick = ::showProxyPicker
        )

        // Translator
        switchPreference(
            preference = SETTINGS_TRANSLATE,
            isChecked = userPreferences.translateExtension,
            onCheckChange = { userPreferences.translateExtension = it }
        )

        clickableDynamicPreference(
            preference = SETTINGS_TRANSLATION_ENDPOINT,
            summary = userPreferences.translationEndpoint,
            onClick = this::showTranslationEndpointPicker
        )

        // SSL Dialogs
        switchPreference(
            preference = SETTINGS_SHOW_SSL,
            isChecked = userPreferences.ssl,
            onCheckChange = { userPreferences.ssl = it }
        )
    }

    // --- Proxy ---

    private fun ProxyChoice.toSummary(): String {
        val stringArray = resources.getStringArray(R.array.proxy_choices_array)
        return when (this) {
            ProxyChoice.NONE -> stringArray[0]
            ProxyChoice.ORBOT -> stringArray[1]
            ProxyChoice.I2P -> stringArray[2]
            ProxyChoice.MANUAL -> "${userPreferences.proxyHost}:${userPreferences.proxyPort}"
        }
    }

    private fun showProxyPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.http_proxy)
            val stringArray = resources.getStringArray(R.array.proxy_choices_array)
            val values = ProxyChoice.values().map {
                Pair(it, when (it) {
                    ProxyChoice.NONE -> stringArray[0]
                    ProxyChoice.ORBOT -> stringArray[1]
                    ProxyChoice.I2P -> stringArray[2]
                    ProxyChoice.MANUAL -> stringArray[3]
                })
            }
            withSingleChoiceItems(values, userPreferences.proxyChoice) {
                updateProxyChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updateProxyChoice(choice: ProxyChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        val sanitizedChoice = ProxyUtils.sanitizeProxyChoice(choice, activity)
        if (sanitizedChoice == ProxyChoice.MANUAL) {
            showManualProxyPicker(activity, summaryUpdater)
        }

        userPreferences.proxyChoice = sanitizedChoice
        summaryUpdater.updateSummary(sanitizedChoice.toSummary())
    }

    private fun showManualProxyPicker(activity: Activity, summaryUpdater: SummaryUpdater) {
        val v = activity.layoutInflater.inflate(R.layout.dialog_manual_proxy, null)
        val eProxyHost = v.findViewById<TextView>(R.id.proxyHost)
        val eProxyPort = v.findViewById<TextView>(R.id.proxyPort)

        val maxCharacters = Integer.MAX_VALUE.toString().length
        eProxyPort.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxCharacters - 1))

        eProxyHost.text = userPreferences.proxyHost
        eProxyPort.text = userPreferences.proxyPort.toString()

        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.manual_proxy)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
                val proxyHost = eProxyHost.text.toString()
                val proxyPort = try {
                    Integer.parseInt(eProxyPort.text.toString())
                } catch (ignored: NumberFormatException) {
                    userPreferences.proxyPort
                }

                userPreferences.proxyHost = proxyHost
                userPreferences.proxyPort = proxyPort
                summaryUpdater.updateSummary("$proxyHost:$proxyPort")
            }
        }
    }

    // --- JavaScript Block Rules ---

    private fun JavaScriptChoice.toSummary(): String {
        val stringArray = resources.getStringArray(R.array.block_javascript)
        return when (this) {
            JavaScriptChoice.NONE -> stringArray[0]
            JavaScriptChoice.WHITELIST -> userPreferences.siteBlockNames
            JavaScriptChoice.BLACKLIST -> userPreferences.siteBlockNames
        }
    }

    private fun showJavaScriptPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.block_javascript)
            val stringArray = resources.getStringArray(R.array.block_javascript)
            val values = JavaScriptChoice.values().map {
                Pair(it, when (it) {
                    JavaScriptChoice.NONE -> stringArray[0]
                    JavaScriptChoice.WHITELIST -> stringArray[1]
                    JavaScriptChoice.BLACKLIST -> stringArray[2]
                })
            }
            withSingleChoiceItems(values, userPreferences.javaScriptChoice) {
                updateJavaScriptChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updateJavaScriptChoice(choice: JavaScriptChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        if (choice == JavaScriptChoice.WHITELIST || choice == JavaScriptChoice.BLACKLIST) {
            showManualJavaScriptPicker(activity, summaryUpdater, choice)
        }

        userPreferences.javaScriptChoice = choice
        summaryUpdater.updateSummary(choice.toSummary())
    }

    private fun showManualJavaScriptPicker(activity: Activity, summaryUpdater: SummaryUpdater, choice: JavaScriptChoice) {
        val v = activity.layoutInflater.inflate(R.layout.site_block, null)
        val blockedSites = v.findViewById<TextView>(R.id.siteBlock)

        blockedSites.text = userPreferences.javaScriptBlocked

        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.block_javascript)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
                val proxyHost = blockedSites.text.toString()
                userPreferences.javaScriptBlocked = proxyHost
                if (choice.toString() == "BLACKLIST") {
                    summaryUpdater.updateSummary(getText(R.string.listed_javascript).toString())
                } else {
                    summaryUpdater.updateSummary(getText(R.string.unlisted_javascript).toString())
                }
            }
        }
    }

    // --- Rendering, Encoding & URL Content ---

    private fun showRenderingDialogPicker(summaryUpdater: SummaryUpdater) {
        activity?.let { MaterialAlertDialogBuilder(it) }?.apply {
            setTitle(resources.getString(R.string.rendering_mode))
            val values = RenderingMode.values().map { Pair(it, it.toDisplayString()) }
            withSingleChoiceItems(values, userPreferences.renderingMode) {
                userPreferences.renderingMode = it
                summaryUpdater.updateSummary(it.toDisplayString())
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }?.resizeAndShow()
    }

    private fun showTextEncodingDialogPicker(summaryUpdater: SummaryUpdater) {
        activity?.let {
            MaterialAlertDialogBuilder(it).apply {
                setTitle(resources.getString(R.string.text_encoding))
                val currentChoice = TEXT_ENCODINGS.indexOf(userPreferences.textEncoding)
                setSingleChoiceItems(TEXT_ENCODINGS, currentChoice) { _, which ->
                    userPreferences.textEncoding = TEXT_ENCODINGS[which]
                    summaryUpdater.updateSummary(TEXT_ENCODINGS[which])
                }
                setPositiveButton(resources.getString(R.string.action_ok), null)
            }.resizeAndShow()
        }
    }

    private fun showUrlBoxDialogPicker(summaryUpdater: SummaryUpdater) {
        activity?.let { MaterialAlertDialogBuilder(it) }?.apply {
            setTitle(resources.getString(R.string.url_contents))
            val items = SearchBoxDisplayChoice.values().map { Pair(it, it.toDisplayString()) }
            withSingleChoiceItems(items, userPreferences.urlBoxContentChoice) {
                userPreferences.urlBoxContentChoice = it
                summaryUpdater.updateSummary(it.toDisplayString())
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }?.resizeAndShow()
    }

    private fun SearchBoxDisplayChoice.toDisplayString(): String {
        val stringArray = resources.getStringArray(R.array.url_content_array)
        return when (this) {
            SearchBoxDisplayChoice.URL -> stringArray[0]
            SearchBoxDisplayChoice.DOMAIN -> stringArray[1]
            SearchBoxDisplayChoice.TITLE -> stringArray[2]
        }
    }

    private fun RenderingMode.toDisplayString(): String = getString(when (this) {
        RenderingMode.NORMAL -> R.string.name_normal
        RenderingMode.INVERTED -> R.string.name_inverted
        RenderingMode.GRAYSCALE -> R.string.name_grayscale
        RenderingMode.INVERTED_GRAYSCALE -> R.string.name_inverted_grayscale
        RenderingMode.INCREASE_CONTRAST -> R.string.name_increase_contrast
    })

    private fun showTranslationEndpointPicker(summaryUpdater: SummaryUpdater) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_text, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_edit_text)
        editText.setText(userPreferences.translationEndpoint)

        val editorDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.translation_endpoint)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.action_back) { _, _ -> }
            .setPositiveButton(R.string.action_ok) { _, _ ->
                userPreferences.translationEndpoint = editText.text.toString()
            }

        val dialog = editorDialog.show()
        BrowserDialog.setDialogSize(requireContext(), dialog)
        summaryUpdater.updateSummary(editText.text.toString())
    }

    companion object {
        private const val SETTINGS_NEW_WINDOW = "new_window"
        private const val SETTINGS_JAVASCRIPT = "cb_javascript"
        private const val SETTINGS_BLOCK_JAVASCRIPT = "block_javascript"
        private const val SETTINGS_BLOCK_INTENT = "block_intent"
        private const val SETTINGS_RENDERING_MODE = "rendering_mode"
        private const val SETTINGS_URL_CONTENT = "url_contents"
        private const val SETTINGS_TEXT_ENCODING = "text_encoding"
        private const val SETTINGS_PROXY = "proxy"
        private const val SETTINGS_TRANSLATE = "translate"
        private const val SETTINGS_TRANSLATION_ENDPOINT = "translation_endpoint"
        private const val SETTINGS_SHOW_SSL = "show_ssl"
    }
}
