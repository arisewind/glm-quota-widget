package com.example.myapplication.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SharedPreferences 实现的 CacheStorage（按 accountId 分键存缓存 JSON）。 */
class PrefsCacheStorage(context: Context) : CacheStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences("glm_quota_cache", Context.MODE_PRIVATE)

    private fun key(accountId: String) = "usage_cache_$accountId"

    override suspend fun read(accountId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(key(accountId), null)
    }

    override suspend fun write(accountId: String, text: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key(accountId), text).apply()
        Unit
    }

    override suspend fun clear(accountId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key(accountId)).apply()
        Unit
    }
}
