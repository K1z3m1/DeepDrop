package com.prirai.android.lr.privacy.cookies

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CookiePolicyScheduler {
    const val WORK_NAME = "lr.privacy.cookies.sweep"

    val SWEEP_INTERVAL_MINUTES: Long = 60

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<CookiePolicyWorker>(
            SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
