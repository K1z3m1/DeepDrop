package com.prirai.android.lr.privacy.cookies

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.prirai.android.lr.ext.components
import mozilla.components.concept.engine.Engine
import mozilla.components.support.ktx.kotlin.tryGetHostFromUrl

class CookiePolicyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repo = CookiePolicyRepository.getInstance(applicationContext)
        val engine: Engine = applicationContext.components.engine

        val policies = repo.getAll()
        if (policies.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        var swept = 0
        policies.forEach { policy ->
            val dueAt = policy.savedAt + policy.hoursUntilClear.toLong() * HOUR_MILLIS
            if (now < dueAt) return@forEach

            val host = policy.origin.tryGetHostFromUrl().orEmpty()
            if (host.isEmpty()) {
                Log.w(TAG, "Skipping policy with un-parseable origin: ${policy.origin}")
                return@forEach
            }

            try {
                engine.clearData(
                    data = Engine.BrowsingData.select(
                        Engine.BrowsingData.COOKIES,
                        Engine.BrowsingData.DOM_STORAGES,
                    ),
                    host = host,
                )
                repo.markSweptNow(policy.origin)
                swept++
            } catch (t: Throwable) {
                Log.w(TAG, "clearData failed for ${policy.origin}", t)
            }
        }

        if (swept > 0) Log.i(TAG, "Cookie-policy sweep cleared $swept origin(s)")
        return Result.success()
    }

    companion object {
        private const val TAG = "CookiePolicyWorker"
        private const val HOUR_MILLIS = 60L * 60L * 1000L
    }
}
