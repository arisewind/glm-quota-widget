package com.example.myapplication.services

import com.example.myapplication.domain.ModelUsageItem
import com.example.myapplication.domain.NormalizedWindow
import com.example.myapplication.domain.UsageErrorCode
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.UsageSource
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import org.json.JSONArray
import org.json.JSONObject

/** 缓存后端接口（按 accountId 分键）。 */
interface CacheStorage {
    suspend fun read(accountId: String): String?
    suspend fun write(accountId: String, text: String)
    suspend fun clear(accountId: String)
}

/** 带 schemaVersion 的缓存（ADR-0002，v2 schema=2）。版本不匹配或损坏时自愈清除。 */
object UsageCache {
    private const val SCHEMA_VERSION = 2

    suspend fun load(storage: CacheStorage, accountId: String): UsageSnapshot? {
        val text = storage.read(accountId) ?: return null
        return try {
            val wrapper = JSONObject(text)
            if (wrapper.optInt("schemaVersion") != SCHEMA_VERSION) {
                storage.clear(accountId); return null
            }
            val s = wrapper.optJSONObject("snapshot") ?: run { storage.clear(accountId); return null }
            fromJson(s)
        } catch (e: Exception) {
            storage.clear(accountId); null
        }
    }

    suspend fun save(storage: CacheStorage, accountId: String, snapshot: UsageSnapshot) {
        val wrapper = JSONObject()
        wrapper.put("schemaVersion", SCHEMA_VERSION)
        wrapper.put("snapshot", toJson(snapshot))
        storage.write(accountId, wrapper.toString())
    }

    private fun toJson(s: UsageSnapshot): JSONObject = JSONObject().apply {
        put("providerId", s.providerId)
        put("providerLabel", s.providerLabel)
        put("planName", s.planName ?: JSONObject.NULL)
        put("updatedAt", s.updatedAt)
        put("source", s.source.name)
        put("status", s.status.name)
        val wins = JSONArray()
        s.windows.forEach { wins.put(windowToJson(it)) }
        put("windows", wins)
        s.modelUsage?.let { list ->
            val arr = JSONArray()
            list.forEach { m -> arr.put(JSONObject().put("modelCode", m.modelCode).put("usage", m.usage)) }
            put("modelUsage", arr)
        }
        s.errorCode?.let { put("errorCode", it.name) }
        s.errorMessage?.let { put("errorMessage", it) }
    }

    private fun windowToJson(w: NormalizedWindow) = JSONObject().apply {
        put("kind", w.kind.name)
        put("usedPercent", w.usedPercent)
        w.resetAt?.let { put("resetAt", it) }
        w.usedValue?.let { put("usedValue", it) }
        w.totalValue?.let { put("totalValue", it) }
        w.unit?.let { put("unit", it) }
    }

    private fun fromJson(s: JSONObject): UsageSnapshot {
        val windows = s.optJSONArray("windows")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { w ->
                    NormalizedWindow(
                        kind = runCatching { WindowKind.valueOf(w.optString("kind")) }.getOrNull()
                            ?: return@mapNotNull null,
                        usedPercent = w.optInt("usedPercent", 0).coerceIn(0, 100),
                        resetAt = if (w.has("resetAt")) w.optLong("resetAt") else null,
                        usedValue = if (w.has("usedValue")) w.optDouble("usedValue") else null,
                        totalValue = if (w.has("totalValue")) w.optDouble("totalValue") else null,
                        unit = if (w.has("unit")) w.optString("unit") else null
                    )
                }
            }
        } ?: emptyList()
        val modelUsage = s.optJSONArray("modelUsage")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { ModelUsageItem(it.optString("modelCode"), it.optInt("usage")) }
            }
        }
        val planName = if (s.isNull("planName")) null else s.optString("planName").takeIf { it.isNotEmpty() }
        val errorCode = if (s.has("errorCode"))
            runCatching { UsageErrorCode.valueOf(s.optString("errorCode")) }.getOrNull() else null
        return UsageSnapshot(
            providerId = s.optString("providerId"),
            providerLabel = s.optString("providerLabel"),
            windows = windows,
            planName = planName,
            modelUsage = modelUsage,
            updatedAt = s.optLong("updatedAt"),
            source = runCatching { UsageSource.valueOf(s.optString("source")) }.getOrDefault(UsageSource.DIRECT),
            status = runCatching { UsageStatus.valueOf(s.optString("status")) }.getOrDefault(UsageStatus.OK),
            errorCode = errorCode,
            errorMessage = s.optString("errorMessage").takeIf { it.isNotEmpty() }
        )
    }
}
