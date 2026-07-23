package org.koitharu.kotatsu.widget.stats

import org.koitharu.kotatsu.core.db.MangaDatabase
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class StatsSnapshot(
	val todayMillis: Long,
	val weekMillis: Long,
	val streakDays: Int,
	val dailyMillis: LongArray,
	val todayBucket: Int,
) {
	val hasAny: Boolean get() = weekMillis > 0 || todayMillis > 0
}

suspend fun MangaDatabase.loadStatsSnapshot(): StatsSnapshot {
	val dao = getStatsDao()
	val now = System.currentTimeMillis()
	val startOfToday = startOfDayMillis(now)
	val startOfWeek = startOfWeekMillis(now)
	val weekEntries = dao.getDurationEntriesIntersecting(startOfWeek)

	// Layout: daily[0] = Monday … daily[6] = Sunday of the current ISO week.
	val daily = LongArray(7)
	for (entry in weekEntries) {
		addEntryDurationByDay(daily, startOfWeek, entry.startedAt, entry.duration, now)
	}
	val weekMillis = daily.sum()
	val todayBucket = ((startOfToday - startOfWeek) / TimeUnit.DAYS.toMillis(1))
		.toInt().coerceIn(0, 6)
	val todayMillis = daily[todayBucket]
	val streak = computeStreak(daily, todayBucket)
	return StatsSnapshot(
		todayMillis = todayMillis,
		weekMillis = weekMillis,
		streakDays = streak,
		dailyMillis = daily,
		todayBucket = todayBucket,
	)
}

private fun addEntryDurationByDay(
	daily: LongArray,
	startOfWeek: Long,
	startedAt: Long,
	duration: Long,
	now: Long,
) {
	val dayMs = TimeUnit.DAYS.toMillis(1)
	val entryEnd = (startedAt + duration).coerceAtMost(now)
	var cursor = startedAt.coerceAtLeast(startOfWeek)
	while (cursor < entryEnd) {
		val bucket = ((cursor - startOfWeek) / dayMs).toInt()
		if (bucket !in daily.indices) {
			break
		}
		val nextDay = startOfWeek + (bucket + 1) * dayMs
		val partEnd = entryEnd.coerceAtMost(nextDay)
		daily[bucket] += partEnd - cursor
		cursor = partEnd
	}
}

/**
 * Counts consecutive days with reading, ending at [todayBucket]. If today hasn't started
 * yet, we step back to yesterday so a streak isn't broken just because the user hasn't
 * read this morning.
 */
private fun computeStreak(daily: LongArray, todayBucket: Int): Int {
	if (daily.isEmpty()) return 0
	var start = todayBucket.coerceIn(0, daily.size - 1)
	if (daily[start] == 0L && start > 0) {
		start--
	}
	var streak = 0
	for (i in start downTo 0) {
		if (daily[i] > 0) streak++ else break
	}
	return streak
}

private fun startOfDayMillis(now: Long): Long {
	val cal = Calendar.getInstance()
	cal.timeInMillis = now
	cal.set(Calendar.HOUR_OF_DAY, 0)
	cal.set(Calendar.MINUTE, 0)
	cal.set(Calendar.SECOND, 0)
	cal.set(Calendar.MILLISECOND, 0)
	return cal.timeInMillis
}

/**
 * Returns the millis at the start of Monday for the week containing [now]. We force
 * Monday regardless of locale so the chart layout is consistent.
 */
private fun startOfWeekMillis(now: Long): Long {
	val cal = Calendar.getInstance()
	cal.timeInMillis = now
	cal.set(Calendar.HOUR_OF_DAY, 0)
	cal.set(Calendar.MINUTE, 0)
	cal.set(Calendar.SECOND, 0)
	cal.set(Calendar.MILLISECOND, 0)
	// Calendar weekdays: SUN=1, MON=2 … SAT=7. We want SUN→6, MON→0, TUE→1, …, SAT→5.
	val daysFromMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
	cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
	return cal.timeInMillis
}
