package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReadingTime
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.stats.data.StatsRepository
import javax.inject.Inject

class ReadingTimeUseCase @Inject constructor(
	private val settings: AppSettings,
	private val statsRepository: StatsRepository,
) {

	suspend operator fun invoke(manga: MangaDetails?, branch: String?, history: MangaHistory?): ReadingTime? {
		if (!settings.isReadingTimeEstimationEnabled) {
			return null
		}
		val chapters = manga?.chapters?.get(branch)
		if (chapters.isNullOrEmpty()) {
			return null
		}
		val currentChapterIndex = if (history != null) {
			chapters.indexOfFirst { it.id == history.chapterId }
		} else {
			-1
		}
		val remainingChapters = ReadingTimeEstimator.getRemainingChapters(
			totalChapters = chapters.size,
			currentChapterIndex = currentChapterIndex,
			isCompleted = history?.percent?.let { ReadingProgress.isCompleted(it) } == true,
		)
		val stats = statsRepository.getChapterReadingStats()
		return ReadingTimeEstimator.estimate(
			remainingChapters = remainingChapters,
			totalDurationMillis = stats.totalDuration,
			chaptersRead = stats.chapters,
			isContinue = currentChapterIndex >= 0,
		)
	}
}
