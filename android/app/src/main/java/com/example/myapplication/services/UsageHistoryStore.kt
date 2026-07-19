package com.example.myapplication.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 周窗用量历史（v3.1 趋势）：按 accountId 存周窗 usedPercent 时间序列，
 * 供 App 用量页画趋势折线。
 *
 * 保留 7 天；同一小时桶内多条仅留最后一条（去重 + 降噪，避免刷新抖动画成毛刺）。
 * 非敏感数据，存普通 SharedPreferences。
 */
class UsageHistoryStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("glm_quota_history", Context.MODE_PRIVATE)

    data class Point(val ts: Long, val percent: Int)

    /** 追加一个周窗采样点，自动裁剪到 [retentionMs] 内 + 同小时桶去重。 */
    fun append(accountId: String, weeklyPercent: Int, ts: Long, retentionMs: Long = RETENTION_7D) {
        val bucketHour = ts / HOUR_MS
        val list = read(accountId)
            .filter { it.ts >= ts - retentionMs }   // 裁剪保留期
            .filterNot { it.ts / HOUR_MS == bucketHour }  // 去掉同小时旧点
            .toMutableList()
        list.add(Point(ts, weeklyPercent))
        write(accountId, list)
    }

    fun read(accountId: String): List<Point> {
        val text = prefs.getString(key(accountId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Point(o.getLong("ts"), o.getInt("pct"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 账户删除时清历史，防残留。 */
    fun clearAccount(accountId: String) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun write(accountId: String, list: List<Point>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("ts", it.ts).put("pct", it.percent)) }
        prefs.edit().putString(key(accountId), arr.toString()).apply()
    }

    private fun key(accountId: String) = "hist_$accountId"

    companion object {
        private const val HOUR_MS = 3600_000L
        const val RETENTION_7D = 7L * 24 * 3600_000L
    }
}
