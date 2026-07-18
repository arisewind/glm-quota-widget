package com.example.myapplication.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 凭据存储（API Key + 区域）。v1.1 起改用 EncryptedSharedPreferences（AES256），
 * 满足 PRD「Key 安全存储、不进入普通 Preferences」的要求。
 *
 * - 首次创建加密存储时，把 v1.0 的明文凭据一次性迁移进来并清除旧文件。
 * - 极少数设备 Keystore 不可用时降级为普通私有存储（仅记日志，不崩溃）。
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { createSecurePrefs(appContext) }

    private fun createSecurePrefs(ctx: Context): SharedPreferences {
        val secure = try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences 不可用，降级为普通私有存储", e)
            ctx.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
        migrateLegacyIfNeeded(ctx, secure)
        return secure
    }

    /** 把 v1.0 明文 glm_quota_creds 迁移进新存储（仅一次），迁移后清除旧文件。 */
    private fun migrateLegacyIfNeeded(ctx: Context, target: SharedPreferences) {
        if (target.all.containsKey(KEY_API_KEY)) return
        val legacy = ctx.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val legacyKey = legacy.getString(KEY_API_KEY, null) ?: return
        target.edit()
            .putString(KEY_API_KEY, legacyKey)
            .putString(KEY_REGION, legacy.getString(KEY_REGION, Region.CN.name))
            .apply()
        legacy.edit().clear().apply()
        Log.i(TAG, "已从明文存储迁移凭据至加密存储")
    }

    suspend fun getKey(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_API_KEY, null)
    }

    suspend fun getRegion(): Region = withContext(Dispatchers.IO) {
        val name = prefs.getString(KEY_REGION, Region.CN.name) ?: Region.CN.name
        runCatching { Region.valueOf(name) }.getOrDefault(Region.CN)
    }

    suspend fun save(key: String, region: Region) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_API_KEY, key).putString(KEY_REGION, region.name).apply()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        Unit
    }

    /** 脱敏标识，仅用于 UI 显示（如 ****...ABCD）。 */
    fun maskKey(key: String?): String {
        if (key.isNullOrEmpty()) return ""
        return if (key.length <= 8) "****" else "****..." + key.takeLast(4)
    }

    companion object {
        private const val TAG = "CredentialStore"
        private const val SECURE_FILE = "glm_quota_creds_secure"
        private const val FALLBACK_FILE = "glm_quota_creds_fallback"
        private const val LEGACY_FILE = "glm_quota_creds"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_REGION = "region"
    }
}
