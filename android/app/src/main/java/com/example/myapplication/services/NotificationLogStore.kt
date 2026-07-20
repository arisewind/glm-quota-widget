package com.example.myapplication.services

import android.content.Context
import com.example.myapplication.domain.WindowKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知记录（v3.2）：留存 app 发出的告警/恢复通知事件，供通知记录页回看。
 *
 * 系统通知是瞬时的（用户划掉/被清理/IMPORTANCE_LOW 没注意到就没了），本 store 在 app 内留存事件流，
 * 用户可随时回看"何时告警过、何时恢复过"。与趋势图互补（趋势是用量数值曲线，这里是事件流）。
 *
 * 最近 [MAX] 条、按时间倒序（最新在前）。非敏感数据，普通 SharedPreferences + JSON 序列化。
 */
class NotificationLogStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("glm_quota_notif_log", Context.MODE_PRIVATE)

    fun append(entry: NotificationLogEntry) {
        synchronized(lock) {
            val list = readAll().toMutableList()
            list.add(0, entry)
            if (list.size > MAX) list.subList(MAX, list.size).clear()
            prefs.edit().putString(KEY, encode(list)).apply()
        }
    }

    fun readAll(): List<NotificationLogEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    fun clearAll() {
        prefs.edit().remove(KEY).apply()
    }

    private fun encode(list: List<NotificationLogEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("ts", e.timestamp)
                put("label", e.accountLabel)
                put("provider", e.providerId)
                put("window", e.windowKind.name)
                put("type", e.type.name)
                put("percent", e.percent)
            })
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<NotificationLogEntry> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NotificationLogEntry(
                timestamp = o.getLong("ts"),
                accountLabel = o.getString("label"),
                providerId = o.optString("provider"),
                windowKind = runCatching { WindowKind.valueOf(o.getString("window")) }.getOrDefault(WindowKind.WEEKLY),
                type = runCatching { NotificationType.valueOf(o.getString("type")) }.getOrDefault(NotificationType.LOW),
                percent = o.optInt("percent")
            )
        }
    }

    companion object {
        private const val KEY = "log"
        private const val MAX = 200
        private val lock = Any()  // append 跨实例（VM 主线程 + Worker 后台线程）同步，防 read-modify-write 丢更新
    }
}

data class NotificationLogEntry(
    val timestamp: Long,
    val accountLabel: String,
    val providerId: String,
    val windowKind: WindowKind,
    val type: NotificationType,
    val percent: Int
)

enum class NotificationType { LOW, EXHAUSTED, RECOVERY }
