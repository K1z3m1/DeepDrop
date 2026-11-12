package com.cookiegames.smartcookie.webapp

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cookiegames.smartcookie.R
import java.io.File
import java.util.Locale

/**
 * Standalone, immersive WebApp (PWA) container.
 * Runs independently without browser chrome (no address bar, no tabs),
 * with full hardware acceleration, custom fullscreen video support,
 * file upload support, and integrated downloads.
 */
class WebappActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.cookiegames.smartcookie.utils.LocaleHelper.wrap(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: android.content.res.Configuration?) {
        super.applyOverrideConfiguration(
            com.cookiegames.smartcookie.utils.LocaleHelper.keepLocale(baseContext, overrideConfiguration)
        )
    }


    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var customViewContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult
        val results: Array<Uri>? = when {
            result.resultCode == RESULT_OK && result.data != null -> {
                val data = result.data
                if (data?.clipData != null) {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                } else if (data?.data != null) {
                    arrayOf(data.data!!)
                } else null
            }
            else -> null
        }
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webapp)

        // Find views
        webView = findViewById(R.id.webapp_webview)
        progressBar = findViewById(R.id.webapp_progress)
        customViewContainer = findViewById(R.id.webapp_custom_view_container)

        // Retrieve initial target URL
        val targetUrl = intent.getStringExtra(EXTRA_URL)
            ?: intent.dataString
            ?: "https://www.google.com"

        val initialTitle = intent.getStringExtra(EXTRA_TITLE)
        if (!initialTitle.isNullOrBlank()) {
            title = initialTitle
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setTaskDescription(ActivityManager.TaskDescription(initialTitle))
            }
        }

        webView.resumeTimers()
        setupWebViewSettings()
        setupClients()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(targetUrl)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        webView.resumeTimers()

        val targetUrl = intent?.getStringExtra(EXTRA_URL)
            ?: intent?.dataString
        if (!targetUrl.isNullOrBlank() && targetUrl != webView.url) {
            webView.loadUrl(targetUrl)
        }
        val initialTitle = intent?.getStringExtra(EXTRA_TITLE)
        if (!initialTitle.isNullOrBlank()) {
            title = initialTitle
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setTaskDescription(ActivityManager.TaskDescription(initialTitle))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setGeolocationEnabled(true)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Clean user agent: strip "; wv" so modern web apps (YouTube, Twitter, Google) don't block features
        val currentUa = settings.userAgentString
        if (!currentUa.isNullOrEmpty() && currentUa.contains("; wv")) {
            settings.userAgentString = currentUa.replace("; wv", "")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webView.requestFocus()
        webView.resumeTimers()
    }

    private fun setupClients() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, webTitle: String?) {
                if (!webTitle.isNullOrBlank()) {
                    title = webTitle
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setTaskDescription(ActivityManager.TaskDescription(webTitle))
                    }
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback

                webView.visibility = View.GONE
                customViewContainer.visibility = View.VISIBLE
                customViewContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            override fun onHideCustomView() {
                if (customView == null) return

                customViewContainer.visibility = View.GONE
                customViewContainer.removeView(customView)
                customView = null

                webView.visibility = View.VISIBLE
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                try {
                    fileChooserLauncher.launch(intent)
                    return true
                } catch (e: ActivityNotFoundException) {
                    fileUploadCallback = null
                    Toast.makeText(this@WebappActivity, R.string.title_error, Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlOverride(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlOverride(url)
            }
        }

        // Integrated downloads within standalone WebApp
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun handleUrlOverride(url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")
            || url.startsWith("about:") || url.startsWith("data:")
            || url.startsWith("blob:") || url.startsWith("javascript:")) {
            return false // Let WebView load it inside the standalone app
        }

        // Launch external apps (e.g. mailto, tel, whatsapp, intent)
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String?, mimetype: String?) {
        try {
            val parsedUri = Uri.parse(url)
            var fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            if (fileName.isBlank() || fileName == "downloadfile") {
                fileName = "download_" + System.currentTimeMillis()
            }

            val request = DownloadManager.Request(parsedUri)
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("Cookie", cookies)
            }
            if (userAgent.isNotBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }
            if (!mimetype.isNullOrBlank() && mimetype != "application/octet-stream") {
                request.setMimeType(mimetype)
            }

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setTitle(fileName)
            request.setDescription(parsedUri.host ?: url)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(this, "${getString(R.string.download_pending)}: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_download, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.yload.browser.webapp.EXTRA_URL"
        const val EXTRA_TITLE = "com.yload.browser.webapp.EXTRA_TITLE"
    }
}