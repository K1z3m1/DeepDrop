package com.prirai.android.lr.privacy.cookies

import android.content.Context
import kotlinx.coroutines.flow.Flow

class CookiePolicyRepository private constructor(
    private val dao: CookiePolicyDao,
) {
    fun observeAll(): Flow<List<CookiePolicy>> = dao.observeAll()

    suspend fun getAll(): List<CookiePolicy> = dao.getAll()

    suspend fun get(origin: String): CookiePolicy? = dao.get(origin)

    suspend fun setPolicy(origin: String, hoursUntilClear: Int) {
        val clamped = hoursUntilClear.coerceIn(MIN_HOURS, MAX_HOURS)
        dao.upsert(
            CookiePolicy(
                origin = origin,
                hoursUntilClear = clamped,
                savedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markSweptNow(origin: String) {
        dao.touch(origin, System.currentTimeMillis())
    }

    suspend fun remove(origin: String) {
        dao.delete(origin)
    }

    suspend fun removeAll() {
        dao.deleteAll()
    }

    companion object {
        const val MIN_HOURS = 0

        const val MAX_HOURS = 24 * 365

        @Volatile private var instance: CookiePolicyRepository? = null

        fun getInstance(context: Context): CookiePolicyRepository {
            return instance ?: synchronized(this) {
                instance ?: CookiePolicyRepository(
                    CookiePolicyDatabase.getInstance(context).cookiePolicyDao(),
                ).also { instance = it }
            }
        }
    }
}
