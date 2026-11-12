// Copyright 2020 CookieJarApps MPL
package com.cookiegames.smartcookie.settings.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.preference.Preference
import com.cookiegames.smartcookie.DeviceCapabilities
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.adblock.BloomFilterAdBlocker
import com.cookiegames.smartcookie.adblock.source.HostsSourceType
import com.cookiegames.smartcookie.adblock.source.selectedHostsSource
import com.cookiegames.smartcookie.adblock.source.toPreferenceIndex
import com.cookiegames.smartcookie.browser.PasswordChoice
import com.cookiegames.smartcookie.database.history.HistoryRepository
import com.cookiegames.smartcookie.di.DatabaseScheduler
import com.cookiegames.smartcookie.di.DiskScheduler
import com.cookiegames.smartcookie.di.MainScheduler
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.BrowserDialog
import com.cookiegames.smartcookie.dialog.DialogItem
import com.cookiegames.smartcookie.extensions.toast
import com.cookiegames.smartcookie.extensions.withSingleChoiceItems
import com.cookiegames.smartcookie.isSupported
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.WebUtils
import com.cookiegames.smartcookie.view.SmartCookieView
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import okhttp3.HttpUrl
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import javax.inject.Inject

class PrivacySettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var historyRepository: HistoryRepository
    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject @field:MainScheduler internal lateinit var mainScheduler: Scheduler
    @Inject @field:DiskScheduler internal lateinit var diskScheduler: Scheduler
    @Inject internal lateinit var bloomFilterAdBlocker: BloomFilterAdBlocker

    private var toastMessage: Toast? = null
    private var recentSummaryUpdater: SummaryUpdater? = null
    private val compositeDisposable = CompositeDisposable()
    private var forceRefreshHostsPreference: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_privacy)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        // --- AdBlock & Malware ---
        switchPreference(
            preference = SETTINGS_BLOCK_ADS,
            isChecked = userPreferences.adBlockEnabled,
            onCheckChange = { userPreferences.adBlockEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_BLOCK_MALWARE,
            isChecked = userPreferences.blockMalwareEnabled,
            onCheckChange = { userPreferences.blockMalwareEnabled = it }
        )

        clickableDynamicPreference(
            preference = SETTINGS_HOSTS_SOURCE,
            summary = userPreferences.selectedHostsSource().toSummary(),
            onClick = ::showHostsSourceChooser
        )

        forceRefreshHostsPreference = clickableDynamicPreference(
            preference = SETTINGS_HOSTS_REFRESH_FORCE,
            isEnabled = isRefreshHostsEnabled(),
            onClick = {
                bloomFilterAdBlocker.populateAdBlockerFromDataSource(forceRefresh = true)
                activity?.toast(R.string.block_ad_refresh_now)
            }
        )

        // --- Cookies ---
        switchPreference(
            preference = SETTINGS_COOKIES,
            isChecked = userPreferences.cookiesEnabled,
            onCheckChange = { userPreferences.cookiesEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_THIRDPCOOKIES,
            isChecked = userPreferences.blockThirdPartyCookiesEnabled,
            isEnabled = DeviceCapabilities.THIRD_PARTY_COOKIE_BLOCKING.isSupported,
            onCheckChange = { userPreferences.blockThirdPartyCookiesEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_COOKIES_INCOGNITO,
            isChecked = userPreferences.incognitoCookiesEnabled,
            onCheckChange = { userPreferences.incognitoCookiesEnabled = it }
        )

        // --- Network Privacy (No false crash warnings!) ---
        switchPreference(
            preference = SETTINGS_DONOTTRACK,
            isChecked = userPreferences.doNotTrackEnabled,
            onCheckChange = { userPreferences.doNotTrackEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_IDENTIFYINGHEADERS,
            isChecked = userPreferences.removeIdentifyingHeadersEnabled,
            summary = "${SmartCookieView.HEADER_REQUESTED_WITH}, ${SmartCookieView.HEADER_WAP_PROFILE}",
            onCheckChange = { userPreferences.removeIdentifyingHeadersEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_WEBRTC,
            isChecked = userPreferences.webRtcEnabled && DeviceCapabilities.WEB_RTC.isSupported,
            isEnabled = DeviceCapabilities.WEB_RTC.isSupported,
            onCheckChange = { userPreferences.webRtcEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_LOCATION,
            isChecked = userPreferences.locationEnabled,
            onCheckChange = { userPreferences.locationEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_INCOGNITO,
            isChecked = userPreferences.incognito,
            onCheckChange = { userPreferences.incognito = it }
        )

        switchPreference(
            preference = SETTINGS_PREFERHTTPS,
            isChecked = userPreferences.preferHTTPSenabled,
            onCheckChange = { userPreferences.preferHTTPSenabled = it }
        )

        switchPreference(
            preference = SETTINGS_FORCEHTTPS,
            isChecked = userPreferences.forceHTTPSenabled,
            onCheckChange = { userPreferences.forceHTTPSenabled = it }
        )

        // --- Security & App Lock ---
        val stringArrayPassword = resources.getStringArray(R.array.password_set_array)
        clickableDynamicPreference(
            preference = SETTINGS_APP_LOCK,
            summary = stringArrayPassword[userPreferences.passwordChoiceLock.value],
            onClick = ::showPasswordPicker
        )

        switchPreference(
            preference = SETTINGS_SAVEPASSWORD,
            isChecked = userPreferences.savePasswordsEnabled,
            onCheckChange = { userPreferences.savePasswordsEnabled = it }
        )

        // --- Clear on Exit ---
        switchPreference(
            preference = SETTINGS_CACHEEXIT,
            isChecked = userPreferences.clearCacheExit,
            onCheckChange = { userPreferences.clearCacheExit = it }
        )

        switchPreference(
            preference = SETTINGS_HISTORYEXIT,
            isChecked = userPreferences.clearHistoryExitEnabled,
            onCheckChange = { userPreferences.clearHistoryExitEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_COOKIEEXIT,
            isChecked = userPreferences.clearCookiesExitEnabled,
            onCheckChange = { userPreferences.clearCookiesExitEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_WEBSTORAGEEXIT,
            isChecked = userPreferences.clearWebStorageExitEnabled,
            onCheckChange = { userPreferences.clearWebStorageExitEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_ONLY_CLOSE,
            isChecked = userPreferences.onlyForceClose,
            onCheckChange = { userPreferences.onlyForceClose = it }
        )

        // --- Manual Clear ---
        clickablePreference(preference = SETTINGS_CLEARCACHE, onClick = this::clearCache)
        clickablePreference(preference = SETTINGS_CLEARHISTORY, onClick = this::clearHistoryDialog)
        clickablePreference(preference = SETTINGS_CLEARCOOKIES, onClick = this::clearCookiesDialog)
        clickablePreference(preference = SETTINGS_CLEARWEBSTORAGE, onClick = this::clearWebStorage)
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.clear()
    }

    // --- AdBlock Source Chooser ---

    private fun updateRefreshHostsEnabledStatus() {
        forceRefreshHostsPreference?.isEnabled = isRefreshHostsEnabled()
    }

    private fun isRefreshHostsEnabled() = userPreferences.selectedHostsSource() is HostsSourceType.Remote

    private fun HostsSourceType.toSummary(): String = when (this) {
        HostsSourceType.Default -> getString(R.string.block_source_default)
        is HostsSourceType.Local -> getString(R.string.block_source_local_description, file.path)
        is HostsSourceType.Remote -> getString(R.string.block_source_remote_description, httpUrl)
    }

    private fun showHostsSourceChooser(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showListChoices(
            activity as Activity,
            R.string.block_ad_source,
            DialogItem(
                title = R.string.block_source_default,
                isConditionMet = userPreferences.selectedHostsSource() == HostsSourceType.Default,
                onClick = {
                    userPreferences.hostsSource = HostsSourceType.Default.toPreferenceIndex()
                    summaryUpdater.updateSummary(userPreferences.selectedHostsSource().toSummary())
                    updateForNewHostsSource()
                }
            ),
            DialogItem(
                title = R.string.block_source_local,
                isConditionMet = userPreferences.selectedHostsSource() is HostsSourceType.Local,
                onClick = {
                    showFileChooser(summaryUpdater)
                }
            ),
            DialogItem(
                title = R.string.block_source_remote,
                isConditionMet = userPreferences.selectedHostsSource() is HostsSourceType.Remote,
                onClick = {
                    showUrlChooser(summaryUpdater)
                }
            )
        )
    }

    private fun showFileChooser(summaryUpdater: SummaryUpdater) {
        this.recentSummaryUpdater = summaryUpdater
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = TEXT_MIME_TYPE
        }
        startActivityForResult(intent, FILE_REQUEST_CODE)
    }

    private fun showUrlChooser(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showEditText(
            activity as Activity,
            title = R.string.block_source_remote,
            hint = R.string.hint_url,
            currentText = userPreferences.hostsRemoteFile,
            action = R.string.action_ok,
            textInputListener = {
                val url = HttpUrl.parse(it)
                    ?: return@showEditText run { activity?.toast(R.string.problem_download) }
                userPreferences.hostsSource = HostsSourceType.Remote(url).toPreferenceIndex()
                userPreferences.hostsRemoteFile = it
                summaryUpdater.updateSummary(it)
                updateForNewHostsSource()
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                data?.data?.also { uri ->
                    compositeDisposable += readTextFromUri(uri)
                        .subscribeOn(diskScheduler)
                        .observeOn(mainScheduler)
                        .subscribeBy(
                            onComplete = { activity?.toast(R.string.action_message_canceled) },
                            onSuccess = { file ->
                                userPreferences.hostsSource = HostsSourceType.Local(file).toPreferenceIndex()
                                userPreferences.hostsLocalFile = file.path
                                recentSummaryUpdater?.updateSummary(userPreferences.selectedHostsSource().toSummary())
                                updateForNewHostsSource()
                            }
                        )
                }
            } else {
                activity?.toast(R.string.action_message_canceled)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun updateForNewHostsSource() {
        bloomFilterAdBlocker.populateAdBlockerFromDataSource(forceRefresh = true)
        updateRefreshHostsEnabledStatus()
    }

    private fun readTextFromUri(uri: Uri): Maybe<File> = Maybe.create {
        val externalFilesDir = activity?.getExternalFilesDir("")
            ?: return@create it.onComplete()
        val inputStream = activity?.contentResolver?.openInputStream(uri)
            ?: return@create it.onComplete()

        try {
            val outputFile = File(externalFilesDir, AD_HOSTS_FILE)
            val input = inputStream.source()
            val output = outputFile.sink().buffer()
            output.writeAll(input)
            return@create it.onSuccess(outputFile)
        } catch (exception: IOException) {
            return@create it.onComplete()
        }
    }

    // --- App Lock Password ---

    private fun PasswordChoice.toSummary(): String {
        val stringArray = resources.getStringArray(R.array.password_set_array)
        return when (this) {
            PasswordChoice.NONE -> stringArray[0]
            PasswordChoice.CUSTOM -> stringArray[1]
        }
    }

    private fun showPasswordPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.enter_password)
            val stringArray = resources.getStringArray(R.array.password_set_array)
            val values = PasswordChoice.values().map {
                Pair(it, when (it) {
                    PasswordChoice.NONE -> stringArray[0]
                    PasswordChoice.CUSTOM -> resources.getString(R.string.enter_password)
                })
            }
            withSingleChoiceItems(values, userPreferences.passwordChoiceLock) {
                updatePasswordChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updatePasswordChoice(choice: PasswordChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        if (choice == PasswordChoice.CUSTOM) {
            showPasswordTextPicker(activity, summaryUpdater)
            val prefs: SharedPreferences = activity.getSharedPreferences("com.cookiegames.smartcookie", Context.MODE_PRIVATE)
            val editor: SharedPreferences.Editor = prefs.edit()
            editor.putBoolean("noPassword", false)
            editor.apply()
        } else {
            val prefs: SharedPreferences = activity.getSharedPreferences("com.cookiegames.smartcookie", Context.MODE_PRIVATE)
            val editor: SharedPreferences.Editor = prefs.edit()
            editor.putBoolean("noPassword", true)
            editor.apply()
            summaryUpdater.updateSummary(resources.getString(R.string.none))
        }

        userPreferences.passwordChoiceLock = choice
        summaryUpdater.updateSummary(choice.toSummary())
    }

    private fun showPasswordTextPicker(activity: Activity, summaryUpdater: SummaryUpdater) {
        val v = activity.layoutInflater.inflate(R.layout.password, null)
        val passwordText = v.findViewById<TextView>(R.id.password)
        passwordText.text = userPreferences.passwordTextLock

        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.enter_password)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
                val passwordCode = passwordText.text.toString()
                userPreferences.passwordTextLock = passwordCode
            }
        }
    }

    // --- Clearing Dialogs ---

    private fun clearHistoryDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = activity as Activity,
            title = R.string.title_clear_history,
            message = R.string.dialog_history,
            positiveButton = DialogItem(title = R.string.action_yes) {
                clearHistory()
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe {
                        toastMessage?.cancel()
                        toastMessage = Toast.makeText(activity, R.string.message_clear_history, Toast.LENGTH_LONG)
                        toastMessage!!.show()
                    }
            },
            negativeButton = DialogItem(title = R.string.action_no) {},
            onCancel = {}
        )
    }

    private fun clearCookiesDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = activity as Activity,
            title = R.string.title_clear_cookies,
            message = R.string.dialog_cookies,
            positiveButton = DialogItem(title = R.string.action_yes) {
                clearCookies()
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe {
                        toastMessage?.cancel()
                        toastMessage = Toast.makeText(activity, R.string.message_cookies_cleared, Toast.LENGTH_LONG)
                        toastMessage!!.show()
                    }
            },
            negativeButton = DialogItem(title = R.string.action_no) {},
            onCancel = {}
        )
    }

    private fun clearCache() {
        WebView(requireNotNull(activity)).apply {
            clearCache(true)
            destroy()
        }
        deleteCache(requireContext())
        toastMessage?.cancel()
        toastMessage = Toast.makeText(activity, R.string.message_cache_cleared, Toast.LENGTH_LONG)
        toastMessage!!.show()
    }

    fun deleteCache(context: Context) {
        try {
            val dir = context.cacheDir
            deleteDir(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun deleteData(context: Context) {
        try {
            val dir = context.dataDir
            deleteDir(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteDir(dir: File?): Boolean {
        return if (dir != null && dir.isDirectory) {
            val children = dir.list()
            for (i in children.indices) {
                val success = deleteDir(File(dir, children[i]))
                if (!success) {
                    return false
                }
            }
            dir.delete()
        } else if (dir != null && dir.isFile) {
            dir.delete()
        } else {
            false
        }
    }

    private fun clearHistory(): Completable = Completable.fromAction {
        val activity = activity
        if (activity != null) {
            WebUtils.clearHistory(activity, historyRepository, databaseScheduler)
        } else {
            throw RuntimeException("Activity was null in clearHistory")
        }
    }

    private fun clearCookies(): Completable = Completable.fromAction {
        val activity = activity
        if (activity != null) {
            WebUtils.clearCookies(activity)
        } else {
            throw RuntimeException("Activity was null in clearCookies")
        }
    }

    private fun clearWebStorage() {
        WebView(requireNotNull(activity)).apply {
            clearFormData()
            clearSslPreferences()
            destroy()
        }
        context?.let { WebUtils.eraseWebStorage(it) }

        toastMessage?.cancel()
        toastMessage = Toast.makeText(activity, R.string.message_web_storage_cleared, Toast.LENGTH_LONG)
        toastMessage!!.show()
    }

    companion object {
        private const val FILE_REQUEST_CODE = 100
        private const val AD_HOSTS_FILE = "local_hosts.txt"
        private const val TEXT_MIME_TYPE = "text/*"

        private const val SETTINGS_BLOCK_ADS = "cb_block_ads"
        private const val SETTINGS_BLOCK_MALWARE = "block_malicious_sites"
        private const val SETTINGS_HOSTS_SOURCE = "preference_hosts_source"
        private const val SETTINGS_HOSTS_REFRESH_FORCE = "preference_hosts_refresh_force"

        private const val SETTINGS_COOKIES = "cookies"
        private const val SETTINGS_THIRDPCOOKIES = "third_party"
        private const val SETTINGS_COOKIES_INCOGNITO = "incognito_cookies"

        private const val SETTINGS_LOCATION = "location"
        private const val SETTINGS_SAVEPASSWORD = "password"
        private const val SETTINGS_CACHEEXIT = "clear_cache_exit"
        private const val SETTINGS_HISTORYEXIT = "clear_history_exit"
        private const val SETTINGS_COOKIEEXIT = "clear_cookies_exit"
        private const val SETTINGS_CLEARCACHE = "clear_cache"
        private const val SETTINGS_CLEARHISTORY = "clear_history"
        private const val SETTINGS_CLEARCOOKIES = "clear_cookies"
        private const val SETTINGS_CLEARWEBSTORAGE = "clear_webstorage"
        private const val SETTINGS_WEBSTORAGEEXIT = "clear_webstorage_exit"
        private const val SETTINGS_DONOTTRACK = "do_not_track"
        private const val SETTINGS_WEBRTC = "webrtc_support"
        private const val SETTINGS_IDENTIFYINGHEADERS = "remove_identifying_headers"
        private const val SETTINGS_FORCEHTTPS = "force_https"
        private const val SETTINGS_PREFERHTTPS = "prefer_https"
        private const val SETTINGS_INCOGNITO = "start_incognito"
        private const val SETTINGS_ONLY_CLOSE = "only_clear"
        private const val SETTINGS_APP_LOCK = "app_lock"
    }
}
