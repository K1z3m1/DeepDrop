package org.koitharu.kotatsu.core.network.webview.adblock

import androidx.annotation.CheckResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Very simple implementation of adblock list parser
 * Not all features are supported
 */
class RulesList {

	private val blockRules = ArrayList<Rule>()
	private val allowRules = ArrayList<Rule>()
	private val blockDomains = HashMap<String, MutableList<Rule>>()
	private val allowDomains = HashMap<String, MutableList<Rule>>()

	operator fun get(url: HttpUrl, baseUrl: HttpUrl?): Rule? {
		val domain = url.topPrivateDomain() ?: url.host
		val rule = blockDomains[domain]?.firstOrNull { it(url, baseUrl) }
			?: blockRules.firstOrNull { it(url, baseUrl) }
		return rule?.takeIf {
			allowDomains[domain]?.none { x -> x(url, baseUrl) } != false &&
				allowRules.none { x -> x(url, baseUrl) }
		}
	}

	fun add(line: String) {
		val normalized = line.trim()
		if (normalized.isEmpty() || "##" in normalized || "#@#" in normalized) return
		val parts = normalized.lowercase().split('$', limit = 2)
		parts.first().addImpl(isWhitelist = false, modifiers = parts.getOrNull(1))
	}

	fun trimToSize() {
		blockRules.trimToSize()
		allowRules.trimToSize()
	}

	private fun String.addImpl(isWhitelist: Boolean, modifiers: String?) {
		val list = if (isWhitelist) allowRules else blockRules

		when {
			startsWith('!') || startsWith('[') -> {
				// Comment, do nothing
			}

			startsWith("||") -> {
				// domain
				val domain = substring(2).substringBefore('^').trim()
				if (domain.isNotEmpty() && '*' !in domain && '/' !in domain) {
					val rule = Rule.Domain(domain).withModifiers(modifiers)
					val domains = if (isWhitelist) allowDomains else blockDomains
					domains.getOrPut(domain) { ArrayList(1) } += rule
				}
			}

			startsWith('|') -> {
				val url = substring(1).substringBefore('^').trim().toHttpUrlOrNull()
				if (url != null) {
					list += Rule.ExactUrl(url).withModifiers(modifiers)
				}
			}

			startsWith("@@") -> {
				substring(2).substringBefore('^').trim().addImpl(!isWhitelist, modifiers)
			}

			startsWith("##") -> {
				// TODO css rules
			}

			else -> {
				if (endsWith('*')) {
					list += Rule.Path(this.dropLast(1), contains = true).withModifiers(modifiers)
				} else if (!contains('*')) { // wildcards is not supported yet
					list += Rule.Path(this, contains = false).withModifiers(modifiers)
				}
			}
		}
	}

	@CheckResult
	private fun Rule.withModifiers(options: String?): Rule {
		if (options.isNullOrEmpty()) {
			return this
		}
		var script: Boolean? = null
		var thirdParty: Boolean? = null
		options.split(',').forEach {
			val isNot = it.startsWith('~')
			when (it.removePrefix("~")) {
				"script" -> script = !isNot
				"third-party" -> thirdParty = !isNot
			}
		}
		return Rule.WithModifiers(
			baseRule = this,
			script = script,
			thirdParty = thirdParty,
			domains = null, //TODO
			domainsNot = null, //TODO
		)
	}
}
