package com.prirai.android.lr.request

// import com.prirai.android.lr.browser.home.HomeFragmentDirections // Removed - using BrowserFragment for homepage
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat.startActivity
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.prirai.android.lr.addons.AddonsActivity
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse
import mozilla.components.browser.state.selector.selectedTab
import java.lang.ref.WeakReference


class AppRequestInterceptor(val context: Context) : RequestInterceptor {

    private var navController: WeakReference<NavController>? = null

    fun setNavController(navController: NavController) {
        this.navController = WeakReference(navController)
    }
    
    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean
    ): InterceptionResponse? {
        // Handle homepage type preference
        if (uri == "about:homepage" || uri == "about:blank") {
            val prefs = UserPreferences(context)
            return when (prefs.homepageType) {
                com.prirai.android.lr.settings.HomepageChoice.BLANK_PAGE.ordinal -> {
                    // Blank page - load about:blank
                    if (uri != "about:blank") {
                        InterceptionResponse.Url("about:blank")
                    } else {
                        null
                    }
                }
                else -> {
                    // VIEW and ONLY_SEARCH_BAR modes - use about:homepage (default)
                    if (uri == "about:blank") {
                        InterceptionResponse.Url("about:homepage")
                    } else {
                        null
                    }
                }
            }
        }
        
        // SECURITY: lr:// is not a scheme any in-app page emits (the
        // homepage is native Compose). It used to mutate shortcuts and open
        // tabs for ANY web page that navigated to it, so it is now dropped.
        if (uri.startsWith("lr://", ignoreCase = true)) {
            return InterceptionResponse.Deny
        }
        
        interceptXpiUrl(uri, hasUserGesture)?.let { response ->
            return response
        }

       val response = context.components.appLinksInterceptor.onLoadRequest(
           engineSession, uri, lastUri, hasUserGesture, isSameDomain, isRedirect,
           isDirectNavigation, isSubframeRequest
       )

        return response
    }
    
    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?
    ): RequestInterceptor.ErrorResponse {
        val riskLevel = getErrorCategory(errorType)

        // Skip interception for about:homepage - just return the default error page
        if (uri == "about:homepage") {
            return RequestInterceptor.ErrorResponse(uri)
        }

        val errorPageUri = ErrorPages.createUrlEncodedErrorPage(
            context = context,
            errorType = errorType,
            uri = uri,
            htmlResource = riskLevel.htmlRes
        )

        return RequestInterceptor.ErrorResponse(errorPageUri)
    }
    
    private fun interceptXpiUrl(
        uri: String,
        hasUserGesture: Boolean
    ): InterceptionResponse? {
        if (hasUserGesture && uri.startsWith("https://addons.mozilla.org") && !UserPreferences(
                context
            ).customAddonCollection) {

            val matchResult = "https://addons.mozilla.org/firefox/downloads/file/([^\\s]+)/([^\\s]+\\.xpi)".toRegex().matchEntire(uri)
            if (matchResult != null) {

                matchResult.groupValues.getOrNull(1)?.let { addonId ->
                    val intent = Intent(context, AddonsActivity::class.java)
                    intent.putExtra("ADDON_ID", addonId)
                    intent.putExtra("ADDON_URL", uri)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(context, intent, null)

                    return InterceptionResponse.Deny
                }
            }
        }

        // In all other case we let the original request proceed.
        return null
    }

    private fun getErrorCategory(errorType: ErrorType): ErrorCategory = when (errorType) {
        ErrorType.UNKNOWN,
        ErrorType.ERROR_CORRUPTED_CONTENT,
        ErrorType.ERROR_CONTENT_CRASHED,
        ErrorType.ERROR_CONNECTION_REFUSED,
        ErrorType.ERROR_NO_INTERNET,
        ErrorType.ERROR_NET_INTERRUPT,
        ErrorType.ERROR_NET_TIMEOUT,
        ErrorType.ERROR_NET_RESET,
        ErrorType.ERROR_UNSAFE_CONTENT_TYPE,
        ErrorType.ERROR_REDIRECT_LOOP,
        ErrorType.ERROR_INVALID_CONTENT_ENCODING,
        ErrorType.ERROR_MALFORMED_URI,
        ErrorType.ERROR_FILE_NOT_FOUND,
        ErrorType.ERROR_FILE_ACCESS_DENIED,
        ErrorType.ERROR_PROXY_CONNECTION_REFUSED,
        ErrorType.ERROR_OFFLINE,
        ErrorType.ERROR_UNKNOWN_HOST,
        ErrorType.ERROR_UNKNOWN_SOCKET_TYPE,
        ErrorType.ERROR_UNKNOWN_PROXY_HOST,
        ErrorType.ERROR_HTTPS_ONLY,
        ErrorType.ERROR_LOCAL_NETWORK_ACCESS_DENIED,
        ErrorType.ERROR_UNKNOWN_PROTOCOL -> ErrorCategory.Network

        ErrorType.ERROR_SECURITY_BAD_CERT,
        ErrorType.ERROR_SECURITY_SSL,
        ErrorType.ERROR_BAD_HSTS_CERT,
        ErrorType.ERROR_PORT_BLOCKED -> ErrorCategory.SSL

        ErrorType.ERROR_SAFEBROWSING_HARMFUL_URI,
        ErrorType.ERROR_SAFEBROWSING_PHISHING_URI,
        ErrorType.ERROR_SAFEBROWSING_MALWARE_URI,
        ErrorType.ERROR_SAFEBROWSING_UNWANTED_URI,
        ErrorType.ERROR_HARMFULADDON_URI -> ErrorCategory.Malware
    }

    internal enum class ErrorCategory(val htmlRes: String) {
        Network(NETWORK_ERROR_PAGE),
        SSL(SSL_ERROR_PAGE),
        Malware(MALWARE_ERROR_PAGE),
    }


    // REMOVED: generateHomepageHtml() - Replaced by homepage.html in assets
    // This function is no longer used - about:homepage now loads from assets/homepage.html

    

    /**
     * Handles cross-domain link clicks by creating new tabs instead of navigating current tab.
     * Same-domain links navigate in current tab, cross-domain links create new grouped tabs.
     */
    private fun handleCrossDomainLinkInterception(
        engineSession: EngineSession,
        newUri: String,
        lastUri: String?
    ): Boolean {
        // Skip internal URLs
        if (newUri.startsWith("about:") || 
            newUri.startsWith("chrome:") || 
            newUri.startsWith("file:") ||
            newUri.isBlank() ||
            lastUri.isNullOrBlank()) {
            return false
        }
        
        try {
            val store = context.components.store
            val currentTab = store.state.tabs.find { tab ->
                tab.engineState.engineSession == engineSession
            }
            
            if (currentTab != null) {
                val currentDomain = extractDomain(lastUri)
                val targetDomain = extractDomain(newUri)
                
                
                // If domains are different, open in new tab and group
                if (currentDomain != targetDomain && 
                    currentDomain != "unknown" && 
                    targetDomain != "unknown") {
                    
                    
                    // Create new tab for cross-domain link
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val tabsUseCases = context.components.tabsUseCases
                            val tabGroupManager = context.components.tabGroupManager
                            
                            val newTabId = tabsUseCases.addTab.invoke(
                                url = newUri,
                                selectTab = true,
                                source = mozilla.components.browser.state.state.SessionState.Source.Internal.UserEntered
                            )
                            
                            // Group with source tab
                            tabGroupManager.handleNewTabFromLink(
                                newTabId = newTabId,
                                newTabUrl = newUri,
                                sourceTabId = currentTab.id,
                                sourceTabUrl = lastUri
                            )

                        } catch (e: Exception) {
                            // Non-fatal: auto-grouping the newly opened cross-
                            // domain tab failed. The tab itself is still open
                            // and usable.
                            android.util.Log.w("AppRequestInterceptor", "Cross-domain auto-group failed", e)
                        }
                    }

                    // Return true to deny the original navigation (prevent same-tab navigation)
                    return true
                } else {
                    // Same domain - allow normal navigation in current tab
                    return false
                }
            }
        } catch (e: Exception) {
            // If the cross-domain heuristic itself throws, fall back to letting
            // the engine handle the navigation normally.
            android.util.Log.w("AppRequestInterceptor", "Cross-domain heuristic failed", e)
        }

        return false
    }

    /**
     * Extract domain from URL for comparison.
     */
    private fun extractDomain(url: String): String {
        return try {
            url.toUri().host?.replace("www.", "") ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    companion object {
        internal const val NETWORK_ERROR_PAGE = "network_error_page.html"
        internal const val SSL_ERROR_PAGE = "ssl_error_page.html"
        internal const val MALWARE_ERROR_PAGE = "malware_error_page.html"
    }
}
