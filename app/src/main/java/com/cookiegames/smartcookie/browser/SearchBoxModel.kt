package com.cookiegames.smartcookie.browser

import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.Utils
import com.cookiegames.smartcookie.utils.isSpecialUrl
import android.app.Application
import dagger.Reusable
import javax.inject.Inject

/**
 * A UI model for the search box.
 */
@Reusable
class SearchBoxModel @Inject constructor(
    private val userPreferences: UserPreferences,
    application: Application
) {

    private val untitledTitle: String = application.getString(R.string.untitled)

    /**
     * Returns the contents of the search box based on a variety of factors.
     *
     *  - The user's preference to show either the URL, domain, or page title
     *  - Whether or not the current page is loading
     *  - Whether or not the current page is a Lightning generated page.
     *
     * This method uses the URL, title, and loading information to determine what
     * should be displayed by the search box.
     *
     * @param url       the URL of the current page.
     * @param title     the title of the current page, if known.
     * @param isLoading whether the page is currently loading or not.
     * @return the string that should be displayed by the search box.
     */
    fun getDisplayContent(url: String, title: String?, isLoading: Boolean): String {
        if (url.isSpecialUrl()) return ""
        val domain = safeDomain(url)
        val cleanDomain = if (domain.isNotBlank()) domain else url
        val cleanTitle = if (!title.isNullOrBlank() && title != url) title else cleanDomain

        return when (userPreferences.urlBoxContentChoice) {
            SearchBoxDisplayChoice.TITLE -> {
                if (isLoading) {
                    if (!title.isNullOrBlank() && title != url) cleanTitle else cleanDomain
                } else {
                    cleanTitle
                }
            }
            SearchBoxDisplayChoice.DOMAIN -> cleanDomain
            SearchBoxDisplayChoice.URL -> {
                if (isLoading) cleanDomain else url
            }
        }
    }

    private fun safeDomain(url: String) = Utils.getDomainName(url)

}
