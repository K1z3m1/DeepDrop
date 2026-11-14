package com.prirai.android.lr.privacy.cookies

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CookiePolicy::class],
    version = 1,
    exportSchema = false,
)
abstract class CookiePolicyDatabase : RoomDatabase() {
    abstract fun cookiePolicyDao(): CookiePolicyDao

    companion object {
        @Volatile
        private var instance: CookiePolicyDatabase? = null

        fun getInstance(context: Context): CookiePolicyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CookiePolicyDatabase::class.java,
                    "lr_cookie_policies.db",
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
