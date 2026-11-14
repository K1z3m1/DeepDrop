package com.prirai.android.lr.privacy.cookies

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CookiePolicyDao {
    @Query("SELECT * FROM site_cookie_policies ORDER BY origin ASC")
    fun observeAll(): Flow<List<CookiePolicy>>

    @Query("SELECT * FROM site_cookie_policies")
    suspend fun getAll(): List<CookiePolicy>

    @Query("SELECT * FROM site_cookie_policies WHERE origin = :origin LIMIT 1")
    suspend fun get(origin: String): CookiePolicy?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: CookiePolicy)

    @Query("UPDATE site_cookie_policies SET savedAt = :now WHERE origin = :origin")
    suspend fun touch(origin: String, now: Long)

    @Query("DELETE FROM site_cookie_policies WHERE origin = :origin")
    suspend fun delete(origin: String)

    @Query("DELETE FROM site_cookie_policies")
    suspend fun deleteAll()
}
