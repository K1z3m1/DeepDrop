package com.cookiegames.smartcookie.preference

import com.cookiegames.smartcookie.constant.*
import android.app.Application
import android.webkit.WebSettings

/**
 * Return the user agent chosen by the user or the custom user agent entered by the user.
 */
fun UserPreferences.userAgent(application: Application): String =
    when (userAgentChoice) {
        1 -> WebSettings.getDefaultUserAgent(application)
        2 -> UA_ANDROID_PHONE
        3 -> UA_ANDROID_TABLET
        4 -> UA_WINDOWS_CHROME
        5 -> UA_WINDOWS_IE11
        6 -> UA_MACOS
        7 -> UA_IPHONE
        8 -> UA_IPAD
        9 -> UA_SYMBIAN
        10 -> userAgentString.takeIf(String::isNotEmpty) ?: WebSettings.getDefaultUserAgent(application)
        else -> WebSettings.getDefaultUserAgent(application)
    }

