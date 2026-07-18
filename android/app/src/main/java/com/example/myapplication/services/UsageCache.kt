package com.example.myapplication.services

import com.example.myapplication.domain.CodingPlanUsage
import com.example.myapplication.domain.ModelUsageItem
import com.example.myapplication.domain.UsageErrorCode
import com.example.myapplication.domain.UsageSource
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.UsageWindow
import org.json.JSONArray
import org.json.JSONObject

/** 缓存后端接口（Preferences/DataStore 实现见 PrefsCacheStorage.kt）。 */
interface CacheStorage {
    suspend fun read(): String?
    suspend fun write(text: String)
    suspend fun clear()
}

/** 带 schemaVersion 的缓存（架构 §4.5）。版本不匹配或损坏时自愈清除。 */
object UsageCache {
    private const val SCHEMA_VERSION = 1

    suspend fun load(storage: CacheStorage): CodingPlanUsage? {
        val text = storage.read() ?: return null
        return try {
            val wrapper = JSONObject(text)
            if (wrapper.optInt("schemaVersion") != SCHEMA_VERSION) {
                storage.clear(); return null
            }
            val u = wrapper.optJSONObject("usage") ?: run { storage.clear(); return null }
            fromJson(u)
        } catch (e: Exception) {
            storage.clear(); null
        }
    }

    suspend fun save(storage: CacheStorage, usage: CodingPlanUsage) {
        val wrapper = JSONObject()
        wrapper.put("schemaVersion", SCHEMA_VERSION)
        wrapper.put("usage", toJson(usage))
        storage.write(wrapper.toString())
    }

    private fun toJson(u: CodingPlanUsage): JSONObject = JSONObject().apply {
        put("providerLabel", u.providerLabel ?: JSONObject.NULL)
        put("planName", u.planName ?: JSONObject.NULL)
        put("updatedAt", u.updatedAt)
        put("source", u.source.name)
        put("status", u.status.name)
        put("session", windowToJson(u.session))
        put("weekly", windowToJson(u.weekly))
        u.errorCode?.let { put("errorCode", it.name) }
        u.errorMessage?.let { put("errorMessage", it) }
        u.modelUsage?.let { list ->
            val arr = JSONArray()
            list.forEach { m ->
                arr.put(JSONObject().put("modelCode", m.modelCode).put("usage", m.usage))
            }
            put("modelUsage", arr)
        }
    }

    private fun windowToJson(w: UsageWindow) = JSONObject().apply {
        put("usedPercent", w.usedPercent)
        put("remainingPercent", w.remainingPercent)
        w.resetAt?.let { put("resetAt", it) }
    }

    private fun fromJson(u: JSONObject): CodingPlanUsage {
        fun win(key: String) = u.optJSONObject(key)!!.let {
            UsageWindow(
                it.optInt("usedPercent"),
                it.optInt("remainingPercent"),
                if (it.has("resetAt")) it.optLong("resetAt") else null
            )
        }
        val modelUsage = u.optJSONArray("modelUsage")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    ModelUsageItem(it.optString("modelCode"), it.optInt("usage"))
                }
            }
        }
        val planName = if (u.isNull("planName")) null else u.optString("planName").takeIf { it.isNotEmpty() }
        val providerLabel = if (u.isNull("providerLabel")) null
            else u.optString("providerLabel").takeIf { it.isNotEmpty() }
        val errorCode = if (u.has("errorCode"))
            UsageErrorCode.valueOf(u.optString("errorCode")) else null
        return CodingPlanUsage(
            session = win("session"),
            weekly = win("weekly"),
            updatedAt = u.optLong("updatedAt"),
            source = UsageSource.valueOf(u.optString("source")),
            status = UsageStatus.valueOf(u.optString("status")),
            providerLabel = providerLabel,
            planName = planName,
            modelUsage = modelUsage,
            errorCode = errorCode,
            errorMessage = u.optString("errorMessage").takeIf { it.isNotEmpty() }
        )
    }
}
