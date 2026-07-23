package org.koitharu.kotatsu.settings.about

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.logs.AppLogger
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import javax.inject.Inject

@HiltViewModel
class AboutSettingsViewModel @Inject constructor(
	private val appUpdateRepository: AppUpdateRepository,
	private val settings: AppSettings,
	private val appLogger: AppLogger,
) : BaseViewModel() {

	val isUpdateSupported = flow {
		emit(appUpdateRepository.isUpdateSupported())
	}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

	val onUpdateAvailable = MutableEventFlow<AppVersion?>()
	val onExportLog = MutableEventFlow<String>()

	private val _isVerboseLogging = MutableStateFlow(settings.isVerboseLoggingEnabled)
	val isVerboseLogging: StateFlow<Boolean> = _isVerboseLogging

	init {
		if (settings.isVerboseLoggingEnabled) {
			appLogger.setEnabled(true)
		}
	}

	fun checkForUpdates() {
		launchLoadingJob {
			val update = appUpdateRepository.fetchUpdate()
			onUpdateAvailable.call(update)
		}
	}

	fun setVerboseLogging(enabled: Boolean) {
		settings.isVerboseLoggingEnabled = enabled
		_isVerboseLogging.value = enabled
		if (enabled) {
			appLogger.setEnabled(true)
		} else {
			launchJob(Dispatchers.Default) {
				val content = appLogger.stopAndDrainToString()
				if (content.isNotBlank()) {
					onExportLog.call(content)
				}
			}
		}
	}
}
