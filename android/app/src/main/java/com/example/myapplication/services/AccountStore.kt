package com.example.myapplication.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.myapplication.domain.Account
import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 多账户存储（v2.0，ADR-0002）。Account 列表经 EncryptedSharedPreferences 加密保存。
 *
 * 迁移：v1.x 在同加密文件存单 `api_key`/`region`，首次访问时自动转成一个 GLM [Account]。
 */
class AccountStore(context: Context) {

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

    /** v1.0 明文 glm_quota_creds → 加密存储（仅一次）。 */
    private fun migrateLegacyIfNeeded(ctx: Context, target: SharedPreferences) {
        if (target.contains(LEGACY_KEY_API) || target.contains(KEY_ACCOUNTS)) return
        val legacy = ctx.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val legacyKey = legacy.getString(LEGACY_KEY_API, null) ?: return
        target.edit()
            .putString(LEGACY_KEY_API, legacyKey)
            .putString(LEGACY_KEY_REGION, legacy.getString(LEGACY_KEY_REGION, Region.CN.name))
            .apply()
        legacy.edit().clear().apply()
        Log.i(TAG, "已从明文存储迁移凭据至加密存储")
    }

    /** v1.x 单 Key（api_key/region）→ 首个 GLM Account（仅一次）。 */
    private suspend fun migrateV1SingleKeyIfNeeded() {
        if (prefs.contains(KEY_ACCOUNTS)) return
        val key = prefs.getString(LEGACY_KEY_API, null) ?: return
        val region = prefs.getString(LEGACY_KEY_REGION, Region.CN.name)
        val account = Account(
            accountId = "${ServiceProviderInfo.GLM_ID}-${UUID.randomUUID()}",
            providerId = ServiceProviderInfo.GLM_ID,
            label = ServiceProviderInfo.GLM_LABEL,
            credential = Credential.Raw(key),
            region = region,
            isActive = true
        )
        prefs.edit()
            .putString(KEY_ACCOUNTS, serializeAccounts(listOf(account)))
            .remove(LEGACY_KEY_API).remove(LEGACY_KEY_REGION)
            .apply()
        Log.i(TAG, "已迁移 v1.x 单 Key 至多账户")
    }

    suspend fun listAccounts(): List<Account> = withContext(Dispatchers.IO) {
        migrateV1SingleKeyIfNeeded()
        val text = prefs.getString(KEY_ACCOUNTS, null) ?: return@withContext emptyList()
        parseAccounts(text)
    }

    suspend fun saveAccount(account: Account) = withContext(Dispatchers.IO) {
        val list = currentList().toMutableList()
        val idx = list.indexOfFirst { it.accountId == account.accountId }
        if (idx >= 0) list[idx] = account else list.add(account)
        prefs.edit().putString(KEY_ACCOUNTS, serializeAccounts(list)).apply()
        Unit
    }

    suspend fun removeAccount(accountId: String) = withContext(Dispatchers.IO) {
        val list = currentList().filterNot { it.accountId == accountId }
        prefs.edit().putString(KEY_ACCOUNTS, serializeAccounts(list)).apply()
        Unit
    }

    suspend fun getAccount(accountId: String): Account? =
        listAccounts().firstOrNull { it.accountId == accountId }

    /** 凭据脱敏（如 ****...ABCD），供 UI 显示。 */
    fun maskKey(credential: Credential): String {
        val key = when (credential) {
            is Credential.Raw -> credential.key
            is Credential.Bearer -> credential.key
            is Credential.VolcAksk -> credential.accessKeyId
            is Credential.ZhipuTeam -> credential.apiKey
        }
        if (key.isEmpty()) return ""
        return if (key.length <= 8) "****" else "****..." + key.takeLast(4)
    }

    private fun currentList(): List<Account> =
        prefs.getString(KEY_ACCOUNTS, null)?.let { parseAccounts(it) } ?: emptyList()

    private fun serializeAccounts(list: List<Account>): String {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("accountId", a.accountId)
                put("providerId", a.providerId)
                put("label", a.label)
                put("credential", credentialToJson(a.credential))
                if (a.region != null) put("region", a.region)
                put("isActive", a.isActive)
            })
        }
        return arr.toString()
    }

    private fun parseAccounts(text: String): List<Account> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                Account(
                    accountId = o.optString("accountId"),
                    providerId = o.optString("providerId"),
                    label = o.optString("label"),
                    credential = credentialFromJson(o.optJSONObject("credential") ?: JSONObject()),
                    region = if (o.has("region") && !o.isNull("region")) o.optString("region") else null,
                    isActive = o.optBoolean("isActive", true)
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun credentialToJson(c: Credential) = JSONObject().apply {
        when (c) {
            is Credential.Raw -> put("type", "raw").put("key", c.key)
            is Credential.Bearer -> put("type", "bearer").put("key", c.key)
            is Credential.VolcAksk -> put("type", "volc_aksk")
                .put("accessKeyId", c.accessKeyId).put("secretKey", c.secretKey)
            is Credential.ZhipuTeam -> put("type", "zhipu_team")
                .put("apiKey", c.apiKey).put("orgId", c.orgId).put("projectId", c.projectId)
        }
    }

    private fun credentialFromJson(o: JSONObject): Credential = when (o.optString("type")) {
        "bearer" -> Credential.Bearer(o.optString("key"))
        "volc_aksk" -> Credential.VolcAksk(o.optString("accessKeyId"), o.optString("secretKey"))
        "zhipu_team" -> Credential.ZhipuTeam(
            o.optString("apiKey"), o.optString("orgId"), o.optString("projectId")
        )
        else -> Credential.Raw(o.optString("key"))
    }

    companion object {
        private const val TAG = "AccountStore"
        private const val SECURE_FILE = "glm_quota_creds_secure"
        private const val FALLBACK_FILE = "glm_quota_creds_fallback"
        private const val LEGACY_FILE = "glm_quota_creds"
        private const val KEY_ACCOUNTS = "accounts"
        private const val LEGACY_KEY_API = "api_key"
        private const val LEGACY_KEY_REGION = "region"
    }
}
