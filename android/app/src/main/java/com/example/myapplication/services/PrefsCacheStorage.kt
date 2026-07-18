package com.example.myapplication.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SharedPreferences 实现的 CacheStorage（存缓存 JSON）。 */
class PrefsCacheStorage(context: Context) : CacheStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences("glm_quota_cache", Context.MODE_PRIVATE)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        prefs.getString("usage_cache", null)
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString("usage_cache", text).apply()
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove("usage_cache").apply()
        Unit
    }
}
