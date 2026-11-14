@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.settings.fragment

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.preference.Preference
import com.prirai.android.lr.R
import com.prirai.android.lr.addons.WebExtensionPromptFeature
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.ui.components.LRAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import org.mozilla.gecko.util.ThreadUtils.runOnUiThread
import java.io.File
import java.io.FileOutputStream
import android.util.Log

class AdvancedSettingsFragment : BaseSettingsFragment() {
    private val webExtensionPromptFeature = ViewBoundFeatureWrapper<WebExtensionPromptFeature>()

    private val pickXpiFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleXpiFileSelection(it) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_advanced)

        preferenceScreen.findPreference<Preference>(resources.getString(R.string.key_collection_user))!!.isEnabled =
            UserPreferences(
                requireContext()
            ).customAddonCollection
        preferenceScreen.findPreference<Preference>(resources.getString(R.string.key_collection_name))!!.isEnabled =
            UserPreferences(
                requireContext()
            ).customAddonCollection

        clickablePreference(
            preference = resources.getString(R.string.key_sideload_xpi),
            onClick = { sideloadXpiLink() }
        )

        clickablePreference(
            preference = resources.getString(R.string.key_sideload_xpi_file),
            onClick = { sideloadXpiFile() }
        )

        switchPreference(
            preference = requireContext().resources.getString(R.string.key_remote_debugging),
            isChecked = UserPreferences(requireContext()).remoteDebugging,
        ) {            val prefs = UserPreferences(requireContext())
            prefs.remoteDebugging = it
            try {
                requireContext().components.engine.settings.remoteDebuggingEnabled = it
            } catch (t: Throwable) {
                Log.w(
                    "AdvancedSettings",
                    "Failed to apply remoteDebuggingEnabled at runtime",
                    t,
                )
            }
            Toast.makeText(
                context,
                if (it) "Remote debugging enabled. Connect from about:debugging on desktop Firefox."
                else "Remote debugging disabled.",
                Toast.LENGTH_LONG,
            ).show()
        }

        switchPreference(
            preference = requireContext().resources.getString(R.string.key_fast_page_load),
            isChecked = UserPreferences(requireContext()).fastPageLoad,
        ) {
            UserPreferences(requireContext()).fastPageLoad = it
            Toast.makeText(
                context,
                requireContext().resources.getText(R.string.app_restart),
                Toast.LENGTH_LONG
            ).show()
        }

        switchPreference(
            preference = requireContext().resources.getString(R.string.key_trust_third_party_certs),
            isChecked = false,
            isEnabled = false
        ) {
        }

        switchPreference(
            preference = requireContext().resources.getString(R.string.key_prompt_external_downloader),
            isChecked = UserPreferences(requireContext()).promptExternalDownloader
        ) {
            UserPreferences(requireContext()).promptExternalDownloader = it
            Toast.makeText(
                context,
                requireContext().resources.getText(R.string.app_restart),
                Toast.LENGTH_LONG
            ).show()
        }

        switchPreference(
            preference = requireContext().resources.getString(R.string.key_use_custom_collection),
            isChecked = UserPreferences(requireContext()).customAddonCollection
        ) {
            if (it && !UserPreferences(requireContext()).shownCollectionDisclaimer) {
                showCollectionDisclaimer(it)
                UserPreferences(requireContext()).shownCollectionDisclaimer = true
            } else {
                UserPreferences(requireContext()).customAddonCollection = it
                preferenceScreen.findPreference<Preference>(resources.getString(R.string.key_collection_user))!!.isEnabled =
                    it
                preferenceScreen.findPreference<Preference>(resources.getString(R.string.key_collection_name))!!.isEnabled =
                    it
                if (!it) Toast.makeText(
                    context,
                    requireContext().resources.getText(R.string.app_restart),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        clickablePreference(
            preference = resources.getString(R.string.key_collection_user),
            onClick = { pickCollectionUser() }
        )

        clickablePreference(
            preference = resources.getString(R.string.key_collection_name),
            onClick = { pickCollectionName() }
        )
    }

    private fun showCollectionDisclaimer(enabled: Boolean) {
        showLRDialog { onDismiss ->
            LRAlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_dialog_alert),
                        contentDescription = null,
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.custom_collection),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                },
                text = { Text(stringResource(R.string.use_custom_collection_disclaimer)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDismiss()
                            UserPreferences(requireContext()).customAddonCollection = enabled
                            preferenceScreen.findPreference<Preference>(resources.getString(R.string.key_collection_user))!!.isEnabled =
                                enabled
                            preferenceScreen.findPreference<Preference>(resources.getString(R.string.key_collection_name))!!.isEnabled =
                                enabled
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.no))
                    }
                },
            )
        }
    }

    private fun sideloadXpiLink() {
        showLRDialog { onDismiss ->
            XpiLinkDialog(
                onConfirm = { link ->
                    onDismiss()
                    installXpiFromLink(link)
                },
                onDismiss = onDismiss,
            )
        }
    }

    private fun installXpiFromLink(link: String) {
        val loadingDialog = ProgressDialog.show(
            activity, "",
            requireContext().resources.getString(R.string.loading), true
        )

        components.engine.installWebExtension(
            link, InstallationMethod.FROM_FILE, onSuccess = {
                CoroutineScope(Dispatchers.IO).launch {
                    val addons = requireContext().components.addonCollectionProvider.getFeaturedAddons()
                    for (i in addons) {
                        if (i.id == it.id) {
                            runOnUiThread {
                                showAlreadyAvailableDialog()
                                components.engine.uninstallWebExtension(it)
                            }
                            loadingDialog.dismiss()
                            return@launch
                        }
                    }
                    runOnUiThread {
                        loadingDialog.dismiss()
                        Toast.makeText(
                            requireContext(),
                            requireContext().resources.getString(R.string.installed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onError = { _ ->
                Toast.makeText(
                    requireContext(),
                    requireContext().resources.getString(R.string.error),
                    Toast.LENGTH_LONG
                ).show()
                loadingDialog.dismiss()
            })
    }

    private fun showAlreadyAvailableDialog() {
        showLRDialog { onDismiss ->
            LRAlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        text = stringResource(R.string.error),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                },
                text = { Text(stringResource(R.string.already_available)) },
                confirmButton = {
                    TextButton(
                        onClick = onDismiss,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.mozac_feature_prompts_ok))
                    }
                },
            )
        }
    }

    private fun sideloadXpiFile() {
        pickXpiFile.launch("application/x-xpinstall")
    }

    private fun handleXpiFileSelection(uri: Uri) {
        val loadingDialog = ProgressDialog.show(
            activity, "",
            requireContext().resources.getString(R.string.loading), true
        )

        try {
            val contentResolver = requireContext().contentResolver
            val inputStream = contentResolver.openInputStream(uri)

            val extensionsDir = File(requireContext().filesDir, "sideloaded_extensions")
            if (!extensionsDir.exists()) {
                extensionsDir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val permanentFile = File(extensionsDir, "extension_${timestamp}.xpi")

            inputStream?.use { input ->
                FileOutputStream(permanentFile).use { output ->
                    input.copyTo(output)
                }
            }

            components.engine.installWebExtension(
                "file://${permanentFile.absolutePath}",
                InstallationMethod.FROM_FILE,
                onSuccess = { extension ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val addons = requireContext().components.addonCollectionProvider.getFeaturedAddons()
                        for (addon in addons) {
                            if (addon.id == extension.id) {
                                runOnUiThread {
                                    showAlreadyAvailableDialog()
                                    components.engine.uninstallWebExtension(extension)
                                }
                                loadingDialog.dismiss()
                                permanentFile.delete()
                                return@launch
                            }
                        }
                        runOnUiThread {
                            loadingDialog.dismiss()
                            Toast.makeText(
                                requireContext(),
                                requireContext().resources.getString(R.string.installed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            requireContext().resources.getString(R.string.error),
                            Toast.LENGTH_LONG
                        ).show()
                        loadingDialog.dismiss()
                    }
                    permanentFile.delete()
                })
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(
                    requireContext(),
                    requireContext().resources.getString(R.string.error) + ": ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                loadingDialog.dismiss()
            }
        }
    }

    private fun pickCollectionUser() {
        showCollectionFieldDialog(
            title = getString(R.string.collection_user),
            initialValue = UserPreferences(requireContext()).customAddonCollectionUser,
            onConfirm = { text ->
                UserPreferences(requireContext()).customAddonCollectionUser = text
                Toast.makeText(
                    context,
                    requireContext().resources.getText(R.string.app_restart),
                    Toast.LENGTH_LONG
                ).show()
            },
        )
    }

    private fun pickCollectionName() {
        showCollectionFieldDialog(
            title = getString(R.string.collection_name),
            initialValue = UserPreferences(requireContext()).customAddonCollectionName,
            onConfirm = { text ->
                UserPreferences(requireContext()).customAddonCollectionName = text
                Toast.makeText(
                    context,
                    requireContext().resources.getText(R.string.app_restart),
                    Toast.LENGTH_LONG
                ).show()
            },
        )
    }

    private fun showCollectionFieldDialog(
        title: String,
        initialValue: String,
        onConfirm: (String) -> Unit,
    ) {
        showLRDialog { onDismiss ->
            TextFieldDialog(
                title = title,
                initialValue = initialValue,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webExtensionPromptFeature.set(
            feature = WebExtensionPromptFeature(
                store = components.store,
                provideAddons = { listOf() },
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                onLinkClicked = { url, _ ->
                    components.tabsUseCases.addTab(url, selectTab = true)
                },
                view = view,
            ),
            owner = this,
            view = view,
        )
    }
}

@Composable
private fun XpiLinkDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var link by remember { mutableStateOf("") }
    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.load_xpi_link),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
        },
        text = {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text(stringResource(R.string.url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(link) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.mozac_feature_prompts_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun TextFieldDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(text)
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.mozac_feature_prompts_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
