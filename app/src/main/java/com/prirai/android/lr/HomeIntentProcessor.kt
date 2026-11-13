package com.prirai.android.lr

import android.content.Intent
import androidx.navigation.NavController

/**
 * Processor for Android intents received in [com.prirai.android.lr.BrowserActivity].
 */
interface HomeIntentProcessor {
    fun process(intent: Intent, navController: NavController, out: Intent): Boolean
}
