package com.prirai.android.lr.privacy.cookies

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "site_cookie_policies")
data class CookiePolicy(
    @PrimaryKey val origin: String,
    val hoursUntilClear: Int,
    val savedAt: Long,
)
