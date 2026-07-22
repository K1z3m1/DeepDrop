package org.koitharu.kotatsu.mihon.model

import kotlinx.serialization.Serializable

/**
 * Authoritative metadata for an extension repo, read from its `repo.json` (`meta`). [fingerprint] is
 * the repo's signingKeyFingerprint — an installed extension whose signature matches it belongs to
 * this repo, which is how we attribute installed (incl. previously-installed) extensions to a repo
 * without recording anything at install time.
 */
@Serializable
data class ExternalRepoInfo(
	val url: String,
	val name: String,
	val shortName: String? = null,
	val fingerprint: String,
) {
	val displayName: String
		get() = shortName?.takeIf { it.isNotBlank() } ?: name

	companion object {
		// The mainstream community repos, recognised out of the box so their (often preinstalled)
		// extensions get a name and updates before any repo.json is fetched. A user-configured copy of
		// the same repo (same fingerprint) still overrides these via its own repo.json. Names +
		// fingerprints are taken verbatim from each repo's live repo.json; add more here the same way.

		val KEIYOUSHI = ExternalRepoInfo(
			url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
			name = "Keiyoushi",
			shortName = "Keiyoushi",
			fingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
		)

		val YUZONO = ExternalRepoInfo(
			url = "https://raw.githubusercontent.com/komikku-app/extensions/repo/index.min.json",
			name = "Yūzōnō",
			shortName = "Yūzōnō",
			fingerprint = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905",
		)

		/** Repos recognised without any user configuration. */
		val BUILT_IN = listOf(KEIYOUSHI, YUZONO)
	}
}
