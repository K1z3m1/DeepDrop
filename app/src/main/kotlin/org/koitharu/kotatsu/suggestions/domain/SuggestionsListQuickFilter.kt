package org.koitharu.kotatsu.suggestions.domain

import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import javax.inject.Inject

class SuggestionsListQuickFilter @Inject constructor(
	private val settings: AppSettings,
	private val suggestionRepository: SuggestionRepository,
	private val mihonExtensionManager: MihonExtensionManager,
) : MangaListQuickFilter(settings) {

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList(6) {
		suggestionRepository.getTopTags(5).mapTo(this) {
			ListFilterOption.Tag(it)
		}
		if (!settings.isNsfwContentDisabled && !settings.isSuggestionsExcludeNsfw) {
			add(ListFilterOption.Macro.NSFW)
			add(ListFilterOption.SFW)
		}
		mihonExtensionManager.ensureReady()
		suggestionRepository.getTopSources(3).mapTo(this) {
			ListFilterOption.Source(it)
		}
	}
}
