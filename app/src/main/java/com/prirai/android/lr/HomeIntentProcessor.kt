package com.prirai.android.lr

import android.content.Intent
import androidx.navigation.NavController

interface HomeIntentProcessor {
    fun process(intent: Intent, navController: NavController, out: Intent): Boolean
}
