package org.koitharu.kotatsu.extensions.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoEntry
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoRepository
import org.koitharu.kotatsu.settings.sources.catalog.SourcesCatalogActivity
import org.koitharu.kotatsu.settings.sources.catalog.isNewerThan
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class ExtensionUpdateWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val settings: AppSettings,
	private val repoRepository: ExternalExtensionRepoRepository,
	private val extensionLoader: MihonExtensionLoader,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		val shouldNotify = settings.isExtensionUpdateNotificationsEnabled
		if (!shouldNotify) {
			return@withContext Result.success()
		}

		try {
			val installed = extensionLoader.getInstalledExtensions(applicationContext)
				.associateBy { it.pkgName }
			if (installed.isEmpty()) return@withContext Result.success()

			// Update each extension from the repo it was installed from, not just the active one, so
			// extensions from every repo the user has used keep updating. Un-attributed packages
			// (installed before provenance tracking, or restored from backup) fall back to the active repo.
			val provenance = settings.getExtensionRepoUrls()
			val activeRepo = settings.externalExtensionsRepoUrl
			val pkgsByRepo = installed.values
				.groupBy { info ->
					// Attribute by signing fingerprint first (authoritative, survives reinstalls),
					// then install-time provenance, then the active repo as a last resort.
					settings.findRepoInfoBySignatures(info.signatures)?.url
						?: provenance[info.pkgName]
						?: activeRepo
				}
				.filterKeys { !it.isNullOrBlank() }
				.mapValues { (_, infos) -> infos.map { it.pkgName } }
			if (pkgsByRepo.isEmpty()) return@withContext Result.success()

			var retryNeeded = false
			val pendingUpdates = ArrayList<ExternalExtensionRepoEntry>()
			repoLoop@ for ((repoUrl, pkgNames) in pkgsByRepo) {
				val nonNullRepoUrl = repoUrl ?: continue@repoLoop
				val wanted = pkgNames.toHashSet()
				val updates = try {
					repoRepository.getExtensions(nonNullRepoUrl, forceRefresh = true)
						.filter { entry ->
							entry.packageName in wanted &&
								installed[entry.packageName]?.let(entry::isNewerThan) == true
						}
						.sortedBy { it.name.lowercase() }
				} catch (_: IOException) {
					// One unreachable repo shouldn't block updates for the others.
					retryNeeded = true
					continue@repoLoop
				}
				pendingUpdates += updates
			}
			if (pendingUpdates.isNotEmpty()) {
				val now = System.currentTimeMillis()
				if (now - settings.lastExtensionUpdateNotificationTime >= TimeUnit.DAYS.toMillis(1)) {
					notifyUpdatesAvailable(pendingUpdates.size)
					settings.lastExtensionUpdateNotificationTime = now
				}
			}
			if (retryNeeded) Result.retry() else Result.success()
		} catch (e: Exception) {
			Log.e(TAG, "Extension auto-update failed", e)
			Result.failure()
		}
	}

	private fun notifyUpdatesAvailable(count: Int) {
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return
		}
		val notificationManager = NotificationManagerCompat.from(applicationContext)
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.extension_updates_available))
			.build()
		notificationManager.createNotificationChannel(channel)

		val intent = Intent(applicationContext, SourcesCatalogActivity::class.java)
			.putExtra(AppRouter.KEY_SOURCE_CATALOG_EXTERNAL_ONLY, true)
		val contentIntent = PendingIntentCompat.getActivity(
			applicationContext,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT,
			false,
		)
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setSmallIcon(R.drawable.general_notification)
			.setContentTitle(applicationContext.getString(R.string.extension_updates_available))
			.setContentText(
				applicationContext.resources.getQuantityString(
					R.plurals.extension_updates_available_message,
					count,
					count,
				),
			)
			.setAutoCancel(true)
			.setContentIntent(contentIntent)
			.build()
		notificationManager.notify(TAG, NOTIFICATION_ID, notification)
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(1, TimeUnit.DAYS)
				.setConstraints(periodicConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(
				PERIODIC_WORK_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				request,
			).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(PERIODIC_WORK_NAME).await()
			workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME).await()
		}

		override suspend fun isScheduled(): Boolean = workManager
			.awaitUniqueWorkInfoByName(PERIODIC_WORK_NAME)
			.any { !it.state.isFinished }

		suspend fun startNow() {
			val request = OneTimeWorkRequestBuilder<ExtensionUpdateWorker>()
				.setConstraints(immediateConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniqueWork(
				IMMEDIATE_WORK_NAME,
				ExistingWorkPolicy.KEEP,
				request,
			).await()
		}

		private fun immediateConstraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()

		private fun periodicConstraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(true)
			.build()
	}

	private companion object {
		const val TAG = "ExtensionUpdateWorker"
		const val CHANNEL_ID = "extension_updates"
		const val NOTIFICATION_ID = 39
		const val PERIODIC_WORK_NAME = "extension_auto_updates"
		const val IMMEDIATE_WORK_NAME = "extension_auto_updates_now"
	}
}
