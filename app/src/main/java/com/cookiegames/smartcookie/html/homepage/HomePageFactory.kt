package com.cookiegames.smartcookie.html.homepage

import android.app.Application
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.browser.HomepageTypeChoice
import com.cookiegames.smartcookie.constant.FILE
import com.cookiegames.smartcookie.constant.UTF8
import com.cookiegames.smartcookie.database.history.HistoryRepository
import com.cookiegames.smartcookie.html.HtmlPageFactory
import com.cookiegames.smartcookie.html.ListPageReader
import com.cookiegames.smartcookie.html.jsoup.*
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.search.SearchEngineProvider
import com.cookiegames.smartcookie.utils.DrawableUtils
import dagger.Reusable
import io.reactivex.Single
import org.jsoup.nodes.DataNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.util.Locale
import javax.inject.Inject


/**
 * A factory for the home page.
 */
@Reusable
class HomePageFactory @Inject constructor(
        private val application: Application,
        private val searchEngineProvider: SearchEngineProvider,
        private val homePageReader: HomePageReader,
        private var userPreferences: UserPreferences,
        private var resources: Resources,
        private val historyRepository: HistoryRepository,
        private val listPageReader: ListPageReader
) : HtmlPageFactory {

    private val title = application.getString(R.string.home)

    override fun buildPage(): Single<String> = Single
        .just(searchEngineProvider.provideSearchEngine())
        .map { (iconUrl, queryUrl, _) ->
            parse(homePageReader.provideHtml()) andBuild {
                title { title }
                charset { UTF8 }
                body {
                    // Add background image
                    if(userPreferences.imageUrlString != ""){ tag("body") { attr("style", "background: url('" + userPreferences.imageUrlString + "') no-repeat scroll;") } }

                    // Set search engine icon
                    id("search_engine_icon") { attr("src", iconUrl) }

                    // Fill params in scripts
                    tag("script") {
                        html(
                            html().replace("\${BASE_URL}", queryUrl)
                        )
                    }

                    if(userPreferences.homepageType == HomepageTypeChoice.FOCUSED){
                        id("image_url").remove()
                    }

                    // Shortcuts
                    if(userPreferences.showShortcuts){
                        val shortcuts = arrayListOf(userPreferences.link1, userPreferences.link2, userPreferences.link3, userPreferences.link4)

                        id("edit_shortcuts"){ text(resources.getString(R.string.edit_shortcuts)) }
                        id("apply"){ text(resources.getString(R.string.apply)) }
                        id("link1click"){ attr("href", shortcuts[0])}
                        id("link2click"){ attr("href", shortcuts[1])}
                        id("link3click"){ attr("href", shortcuts[2])}
                        id("link4click"){ attr("href", shortcuts[3])}

                        shortcuts.forEachIndexed { index, rawElement ->
                            val element = if (rawElement.isNotBlank() && !rawElement.startsWith("http://") && !rawElement.startsWith("https://")) {
                                "https://$rawElement"
                            } else {
                                rawElement
                            }

                            id("link" + (index + 1) + "click") { attr("href", element) }
                            id("link" + (index + 1) + "inp") { attr("value", element) }

                            if(!URLUtil.isValidUrl(element)){
                                val icon = createIconByName('?')
                                val encoded = bitmapToBase64(icon)

                                id("link" + (index + 1)){ attr("src",
                                        "data:image/png;base64,$encoded"
                                )}

                                return@forEachIndexed
                            }

                            val host = try { URI(element).host?.replaceFirst("www.", "")?.toLowerCase(Locale.ROOT) } catch(e: Exception) { null } ?: ""
                            val fallbackLetter = if (host.isNotEmpty()) host.first().toUpperCase() else '?'
                            val encoded = bitmapToBase64(createIconByName(fallbackLetter))
                            val iconSrc = getCachedOrDownloadIcon(host, fallbackLetter)
                            id("link" + (index + 1)){ attr("src", iconSrc)}
                            id("link" + (index + 1)){ attr("onerror", "this.src = 'data:image/png;base64,$encoded';")}
                        }

                        id("search_input"){ attr("placeholder", resources.getString(R.string.search_homepage))}
                    }
                    else{
                        id("shortcuts"){ attr("style", "display: none;")}
                    }

                }
            }
        }
        .map { content -> Pair(createHomePage(), content) }
        .doOnSuccess { (page, content) ->
            FileWriter(page, false).use {
                if(userPreferences.startPageThemeEnabled && userPreferences.useTheme == AppTheme.LIGHT){
                    it.write(content + "<style>body { background-color: #F8F9FA !important; color: #202124 !important; } .search_bar { background-color: #FFFFFF !important; border: 1px solid rgba(0,0,0,0.12) !important; box-shadow: 0 2px 8px rgba(0,0,0,0.06) !important; } #search_input { color: #202124 !important; } .link { background: #FFFFFF !important; border: 1px solid rgba(0,0,0,0.08) !important; } .modal-content { background: #FFFFFF !important; color: #202124 !important; } .modal-content input { background: #F1F3F4 !important; color: #202124 !important; border-color: rgba(0,0,0,0.1) !important; }</style>")
                }
                else if(userPreferences.startPageThemeEnabled && userPreferences.useTheme == AppTheme.BLACK){
                    it.write(content + "<style>body { background-color: #000000 !important; color: #ffffff !important; } .search_bar { background-color: #121316 !important; border: 1px solid rgba(255,255,255,0.14) !important; box-shadow: none !important; } #search_input { color: #ffffff !important; } .link { background: #121316 !important; border: 1px solid rgba(255,255,255,0.08) !important; }</style>")
                }
                else{
                    it.write(content + "<style>body { background-color: #000000 !important; color: #ffffff !important; } .search_bar { background-color: #16171B !important; border: 1px solid rgba(255,255,255,0.12) !important; box-shadow: none !important; } #search_input { color: #ffffff !important; } .link { background: #16171B !important; border: 1px solid rgba(255,255,255,0.08) !important; }</style>")
                }
            }
        }
        .map { (page, _) -> "$FILE$page" }

    /**
     * Create the home page file.
     */
    fun createHomePage() = File(application.filesDir, FILENAME)

    fun createIconByName(name: Char): Bitmap{
        val icon = DrawableUtils.createRoundedLetterImage(
                name,
                64,
                64,
                Color.GRAY
        )
        return icon
    }

    fun bitmapToBase64(bitmap: Bitmap): String{
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
        val byteArray: ByteArray = byteArrayOutputStream.toByteArray()
        val encoded: String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        return encoded
    }

    private fun getCachedOrDownloadIcon(host: String, fallbackLetter: Char = '?'): String {
        if (host.isEmpty()) return ""
        val cleanHost = host.replaceFirst("www.", "").toLowerCase(Locale.ROOT)

        // 1. Check bundled assets in shortcut_favicons (curated, official 128x128 icons)
        try {
            application.assets.open("shortcut_favicons/$cleanHost.png").use { stream ->
                val bytes = stream.readBytes()
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                return "data:image/png;base64,$encoded"
            }
        } catch (e: Exception) {}

        // 2. Check local persistent disk cache
        try {
            val cacheDir = File(application.filesDir, "shortcut_cache").apply { mkdirs() }
            val cacheFile = File(cacheDir, "$cleanHost.png")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val bytes = cacheFile.readBytes()
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                return "data:image/png;base64,$encoded"
            }
        } catch (e: Exception) {}

        // 3. Download high-res 128px icon in background to persist in cache for next load
        Thread {
            try {
                val s2Url = "https://www.google.com/s2/favicons?domain=$cleanHost&sz=128"
                val conn = URL(s2Url).openConnection()
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.getInputStream().use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isNotEmpty()) {
                        val cacheDir = File(application.filesDir, "shortcut_cache").apply { mkdirs() }
                        val cacheFile = File(cacheDir, "$cleanHost.png")
                        cacheFile.writeBytes(bytes)
                    }
                }
            } catch (e: Exception) {}
        }.start()

        // Return local vector/letter avatar immediately so WebView finishes rendering in 0ms!
        return "data:image/png;base64," + bitmapToBase64(createIconByName(fallbackLetter))
    }

    companion object {

        const val FILENAME = "homepage.html"

    }

}
