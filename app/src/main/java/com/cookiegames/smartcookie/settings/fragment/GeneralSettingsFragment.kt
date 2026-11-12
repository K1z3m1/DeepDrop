package com.cookiegames.smartcookie.settings.fragment

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.browser.HomepageTypeChoice
import com.cookiegames.smartcookie.browser.SuggestionNumChoice
import com.cookiegames.smartcookie.constant.SCHEME_BLANK
import com.cookiegames.smartcookie.constant.SCHEME_BOOKMARKS
import com.cookiegames.smartcookie.constant.SCHEME_HOMEPAGE
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.BrowserDialog
import com.cookiegames.smartcookie.extensions.withSingleChoiceItems
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.search.SearchEngineProvider
import com.cookiegames.smartcookie.search.Suggestions
import com.cookiegames.smartcookie.search.engine.BaseSearchEngine
import com.cookiegames.smartcookie.search.engine.CustomSearch
import com.cookiegames.smartcookie.utils.FileUtils
import com.cookiegames.smartcookie.utils.LocaleHelper
import com.cookiegames.smartcookie.utils.ThemeUtils
import javax.inject.Inject

/**
 * The general settings of the app: search, homepage, downloads and identity.
 */
class GeneralSettingsFragment : AbstractSettingsFragment() {

    @Inject lateinit var searchEngineProvider: SearchEngineProvider
    @Inject lateinit var userPreferences: UserPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_general)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        // App language
        clickableDynamicPreference(
            preference = SETTINGS_LANGUAGE,
            summary = LocaleHelper.getCurrentLanguage(requireContext()).displayName,
            onClick = ::showLanguageDialog
        )

        // Search engine
        clickableDynamicPreference(
            preference = SETTINGS_SEARCH_ENGINE,
            summary = getSearchEngineSummary(searchEngineProvider.provideSearchEngine()),
            onClick = ::showSearchProviderDialog
        )

        // Search suggestions provider
        clickableDynamicPreference(
            preference = SETTINGS_SUGGESTIONS,
            summary = searchSuggestionChoiceToTitle(Suggestions.from(userPreferences.searchSuggestionChoice)),
            onClick = ::showSearchSuggestionsDialog
        )

        // Search suggestions number
        val stringArraySuggestions = resources.getStringArray(R.array.suggestion_name_array)
        clickableDynamicPreference(
            preference = SETTINGS_SUGGESTIONS_NUM,
            summary = stringArraySuggestions[userPreferences.suggestionChoice.value],
            onClick = ::showSuggestionNumPicker
        )

        // Homepage URL
        clickableDynamicPreference(
            preference = SETTINGS_HOME,
            summary = homePageUrlToDisplayTitle(userPreferences.homepage),
            onClick = ::showHomePageDialog
        )

        // Homepage style/type
        clickableDynamicPreference(
            preference = SETTINGS_HOMEPAGE_TYPE,
            isEnabled = userPreferences.homepage == SCHEME_HOMEPAGE,
            summary = homePageTypeToDisplayTitle(userPreferences.homepageType),
            onClick = ::showHomepageTypePicker
        )

        // Homepage shortcuts
        switchPreference(
            preference = SETTINGS_SHORTCUTS,
            isChecked = userPreferences.showShortcuts,
            onCheckChange = { userPreferences.showShortcuts = it }
        )

        // Download location
        clickableDynamicPreference(
            preference = SETTINGS_DOWNLOAD,
            summary = userPreferences.downloadDirectory,
            onClick = ::showDownloadLocationDialog
        )

        // Integrated downloader
        switchPreference(
            preference = SETTINGS_DOWNLOADER,
            isChecked = userPreferences.useNewDownloader,
            onCheckChange = { userPreferences.useNewDownloader = it }
        )

        // User Agent
        clickableDynamicPreference(
            preference = SETTINGS_USER_AGENT,
            summary = choiceToUserAgent(userPreferences.userAgentChoice),
            onClick = ::showUserAgentChooserDialog
        )
    }

    // --- Language ---

    private fun showLanguageDialog(summaryUpdater: SummaryUpdater) {
        val hostActivity = activity ?: return
        BrowserDialog.showCustomDialog(hostActivity) {
            setTitle(R.string.language)
            val languages = LocaleHelper.languages
            val checked = languages.indexOf(LocaleHelper.getCurrentLanguage(hostActivity))
            setSingleChoiceItems(languages.map { it.displayName }.toTypedArray(), checked) { dialog, which ->
                val selected = languages[which]
                if (selected != LocaleHelper.getCurrentLanguage(hostActivity)) {
                    LocaleHelper.setLanguage(hostActivity, selected.tag)
                    summaryUpdater.updateSummary(selected.displayName)
                    dialog.dismiss()
                    // Re-create the settings screen so it is shown in the new language.
                    hostActivity.recreate()
                } else {
                    dialog.dismiss()
                }
            }
            setNegativeButton(R.string.action_cancel, null)
        }
    }

    // --- Search suggestions ---

    private fun showSuggestionNumPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.suggestion)
            val stringArray = resources.getStringArray(R.array.suggestion_name_array)
            val values = SuggestionNumChoice.values().map {
                Pair(it, when (it) {
                    SuggestionNumChoice.THREE -> stringArray[0]
                    SuggestionNumChoice.FOUR -> stringArray[1]
                    SuggestionNumChoice.FIVE -> stringArray[2]
                    SuggestionNumChoice.SIX -> stringArray[3]
                    SuggestionNumChoice.SEVEN -> stringArray[4]
                    SuggestionNumChoice.EIGHT -> stringArray[5]
                    else -> stringArray[2]
                })
            }
            withSingleChoiceItems(values, userPreferences.suggestionChoice) {
                updateSearchNum(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updateSearchNum(choice: SuggestionNumChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        val stringArray = resources.getStringArray(R.array.suggestion_name_array)
        userPreferences.suggestionChoice = choice
        summaryUpdater.updateSummary(stringArray[choice.value])
    }

    private fun searchSuggestionChoiceToTitle(choice: Suggestions): String =
        when (choice) {
            Suggestions.GOOGLE -> getString(R.string.powered_by_google)
            Suggestions.DUCK -> getString(R.string.powered_by_duck)
            Suggestions.BAIDU -> getString(R.string.powered_by_baidu)
            Suggestions.NAVER -> getString(R.string.powered_by_naver)
            Suggestions.COOKIE -> getString(R.string.powered_by_naver)
            Suggestions.NONE -> getString(R.string.search_suggestions_off)
        }

    private fun showSearchSuggestionsDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.search_suggestions))

            val currentChoice = when (Suggestions.from(userPreferences.searchSuggestionChoice)) {
                Suggestions.GOOGLE -> 0
                Suggestions.DUCK -> 1
                Suggestions.BAIDU -> 2
                Suggestions.NAVER -> 3
                Suggestions.COOKIE -> 4
                Suggestions.NONE -> 5
            }

            setSingleChoiceItems(R.array.suggestions, currentChoice) { _, which ->
                val suggestionsProvider = when (which) {
                    0 -> Suggestions.GOOGLE
                    1 -> Suggestions.DUCK
                    2 -> Suggestions.BAIDU
                    3 -> Suggestions.NAVER
                    4 -> Suggestions.COOKIE
                    5 -> Suggestions.NONE
                    else -> Suggestions.GOOGLE
                }
                userPreferences.searchSuggestionChoice = suggestionsProvider.index
                summaryUpdater.updateSummary(searchSuggestionChoiceToTitle(suggestionsProvider))
                Toast.makeText(context, getText(R.string.please_restart), Toast.LENGTH_LONG).show()
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }
    }

    // --- Search Provider ---

    private fun getSearchEngineSummary(baseSearchEngine: BaseSearchEngine): String {
        return if (baseSearchEngine is CustomSearch) {
            baseSearchEngine.queryUrl
        } else {
            getString(baseSearchEngine.titleRes)
        }
    }

    private fun convertSearchEngineToString(searchEngines: List<BaseSearchEngine>): Array<CharSequence> =
        searchEngines.map { getString(it.titleRes) }.toTypedArray()

    private fun showSearchProviderDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.title_search_engine))

            val searchEngineList = searchEngineProvider.provideAllSearchEngines()
            val chars = convertSearchEngineToString(searchEngineList)
            val n = userPreferences.searchChoice

            setSingleChoiceItems(chars, n) { _, which ->
                val searchEngine = searchEngineList[which]
                val preferencesIndex = searchEngineProvider.mapSearchEngineToPreferenceIndex(searchEngine)
                userPreferences.searchChoice = preferencesIndex

                if (searchEngine is CustomSearch) {
                    showCustomSearchDialog(searchEngine, summaryUpdater)
                } else {
                    summaryUpdater.updateSummary(getSearchEngineSummary(searchEngine))
                }
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun showCustomSearchDialog(customSearch: CustomSearch, summaryUpdater: SummaryUpdater) {
        activity?.let {
            BrowserDialog.showEditText(
                it,
                R.string.search_engine_custom,
                R.string.search_engine_custom,
                userPreferences.searchUrl,
                R.string.action_ok
            ) { searchUrl ->
                userPreferences.searchUrl = searchUrl
                summaryUpdater.updateSummary(getSearchEngineSummary(customSearch))
            }
        }
    }

    // --- Homepage ---

    private fun homePageTypeToDisplayTitle(choice: HomepageTypeChoice): String = when (choice) {
        HomepageTypeChoice.DEFAULT -> resources.getString(R.string.agent_default)
        HomepageTypeChoice.FOCUSED -> resources.getString(R.string.focused)
        HomepageTypeChoice.INFORMATIVE -> resources.getString(R.string.informational)
        else -> choice.toString()
    }

    private fun homePageUrlToDisplayTitle(url: String): String = when (url) {
        SCHEME_HOMEPAGE -> resources.getString(R.string.action_homepage)
        SCHEME_BLANK -> resources.getString(R.string.action_blank)
        SCHEME_BOOKMARKS -> resources.getString(R.string.action_bookmarks)
        else -> url
    }

    private fun showHomePageDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.home)
            val n = when (userPreferences.homepage) {
                SCHEME_HOMEPAGE -> 0
                SCHEME_BLANK -> 1
                SCHEME_BOOKMARKS -> 2
                else -> 3
            }

            setSingleChoiceItems(R.array.homepage, n) { _, which ->
                when (which) {
                    0 -> {
                        userPreferences.homepage = SCHEME_HOMEPAGE
                        summaryUpdater.updateSummary(resources.getString(R.string.action_homepage))
                    }
                    1 -> {
                        userPreferences.homepage = SCHEME_BLANK
                        summaryUpdater.updateSummary(resources.getString(R.string.action_blank))
                    }
                    2 -> {
                        userPreferences.homepage = SCHEME_BOOKMARKS
                        summaryUpdater.updateSummary(resources.getString(R.string.action_bookmarks))
                    }
                    3 -> {
                        showCustomHomePagePicker(summaryUpdater)
                    }
                }
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }
    }

    private fun showCustomHomePagePicker(summaryUpdater: SummaryUpdater) {
        val currentHomepage: String = if (!URLUtil.isAboutUrl(userPreferences.homepage)) {
            userPreferences.homepage
        } else {
            "https://www.google.com"
        }

        activity?.let {
            BrowserDialog.showEditText(
                it,
                R.string.title_custom_homepage,
                R.string.title_custom_homepage,
                currentHomepage,
                R.string.action_ok
            ) { url ->
                if (url.startsWith("http") || url.startsWith("file")) {
                    userPreferences.homepage = url
                    summaryUpdater.updateSummary(url)
                } else {
                    userPreferences.homepage = "https://$url"
                    summaryUpdater.updateSummary("https://$url")
                }
            }
        }
    }

    private fun showHomepageTypePicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.homepage_type)
            val stringArray = resources.getStringArray(R.array.homepage_type)
            val values = HomepageTypeChoice.values().map {
                Pair(it, when (it) {
                    HomepageTypeChoice.DEFAULT -> stringArray[0]
                    HomepageTypeChoice.FOCUSED -> stringArray[1]
                    HomepageTypeChoice.INFORMATIVE -> stringArray[2]
                })
            }
            withSingleChoiceItems(values, userPreferences.homepageType) {
                userPreferences.homepageType = it
                summaryUpdater.updateSummary(homePageTypeToDisplayTitle(it))
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    // --- Downloads ---

    private fun showDownloadLocationDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.title_download_location))
            val n: Int = if (userPreferences.downloadDirectory.contains(Environment.DIRECTORY_DOWNLOADS)) {
                0
            } else {
                1
            }

            setSingleChoiceItems(R.array.download_folder, n) { _, which ->
                when (which) {
                    0 -> {
                        userPreferences.downloadDirectory = FileUtils.DEFAULT_DOWNLOAD_PATH
                        summaryUpdater.updateSummary(FileUtils.DEFAULT_DOWNLOAD_PATH)
                    }
                    1 -> {
                        showCustomDownloadLocationPicker(summaryUpdater)
                    }
                }
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }
    }

    private fun showCustomDownloadLocationPicker(summaryUpdater: SummaryUpdater) {
        activity?.let { act ->
            val dialogView = LayoutInflater.from(act).inflate(R.layout.dialog_edit_text, null)
            val getDownload = dialogView.findViewById<EditText>(R.id.dialog_edit_text)

            val errorColor = ContextCompat.getColor(act, R.color.error_red)
            val regularColor = ThemeUtils.getTextColor(act)
            getDownload.setTextColor(regularColor)
            getDownload.addTextChangedListener(DownloadLocationTextWatcher(getDownload, errorColor, regularColor))
            getDownload.setText(userPreferences.downloadDirectory)

            BrowserDialog.showCustomDialog(act) {
                setTitle(R.string.title_download_location)
                setView(dialogView)
                setPositiveButton(R.string.action_ok) { _, _ ->
                    var text = getDownload.text.toString()
                    text = FileUtils.addNecessarySlashes(text)
                    userPreferences.downloadDirectory = text
                    summaryUpdater.updateSummary(text)
                }
            }
        }
    }

    private class DownloadLocationTextWatcher(
        private val getDownload: EditText,
        private val errorColor: Int,
        private val regularColor: Int
    ) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (!FileUtils.isWriteAccessAvailable(s.toString())) {
                this.getDownload.setTextColor(this.errorColor)
            } else {
                this.getDownload.setTextColor(this.regularColor)
            }
        }
    }

    // --- User Agent ---

    private fun choiceToUserAgent(index: Int) = when (index) {
        1 -> resources.getString(R.string.agent_default)
        2 -> resources.getString(R.string.agent_android_phone)
        3 -> resources.getString(R.string.agent_android_tablet)
        4 -> resources.getString(R.string.agent_desktop)
        5 -> resources.getString(R.string.agent_ie11)
        6 -> resources.getString(R.string.agent_macos)
        7 -> resources.getString(R.string.agent_iphone)
        8 -> resources.getString(R.string.agent_ipad)
        9 -> resources.getString(R.string.agent_symbian)
        10 -> resources.getString(R.string.agent_custom)
        else -> resources.getString(R.string.agent_default)
    }

    private fun showUserAgentChooserDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.title_user_agent))
            val checked = (userPreferences.userAgentChoice - 1).coerceIn(0, 9)
            setSingleChoiceItems(R.array.user_agent, checked) { _, which ->
                userPreferences.userAgentChoice = which + 1
                summaryUpdater.updateSummary(choiceToUserAgent(userPreferences.userAgentChoice))
                if (which == 9) {
                    summaryUpdater.updateSummary(resources.getString(R.string.agent_custom))
                    showCustomUserAgentPicker(summaryUpdater)
                }
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }
    }

    private fun showCustomUserAgentPicker(summaryUpdater: SummaryUpdater) {
        activity?.let {
            BrowserDialog.showEditText(
                it,
                R.string.title_user_agent,
                R.string.title_user_agent,
                userPreferences.userAgentString,
                R.string.action_ok
            ) { s ->
                userPreferences.userAgentString = s
                summaryUpdater.updateSummary(it.getString(R.string.agent_custom))
            }
        }
    }

    companion object {
        private const val SETTINGS_LANGUAGE = "language"
        private const val SETTINGS_USER_AGENT = "agent"
        private const val SETTINGS_DOWNLOAD = "download"
        private const val SETTINGS_DOWNLOADER = "downloader"
        private const val SETTINGS_SEARCH_ENGINE = "search"
        private const val SETTINGS_SUGGESTIONS = "suggestions_choice"
        private const val SETTINGS_SUGGESTIONS_NUM = "suggestions_number"
        private const val SETTINGS_HOME = "home"
        private const val SETTINGS_HOMEPAGE_TYPE = "homepage_type"
        private const val SETTINGS_SHORTCUTS = "show_shortcuts"
    }
}
