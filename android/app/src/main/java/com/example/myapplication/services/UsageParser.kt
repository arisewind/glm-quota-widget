package com.example.myapplication.services

import com.example.myapplication.domain.CodingPlanUsage
import com.example.myapplication.domain.ModelUsageItem
import com.example.myapplication.domain.UsageSource
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.UsageWindow
import org.json.JSONObject

class UpstreamChangedException(message: String) : Exception(message)

/**
 * 上游响应 → CodingPlanUsage 解析（ADR-0001 数据契约）。
 * 用 Android 内置 org.json。unit 映射：TOKENS_LIMIT unit:3=5h, unit:6=周；TIME_LIMIT unit:5=模型用量。
 * 结构不符合契约时抛 UpstreamChangedException，由 Provider 映射为 UPSTREAM_CHANGED。
 */
object UsageParser {

    fun parse(body: String, now: Long): CodingPlanUsage {
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) {
            throw UpstreamChangedException("success=false, code=${root.opt("code")}, msg=${root.opt("msg")}")
        }
        val data = root.optJSONObject("data")
            ?: throw UpstreamChangedException("missing data")
        val limits = data.optJSONArray("limits")
            ?: throw UpstreamChangedException("missing limits")

        fun find(type: String, unit: Int): JSONObject? {
            for (i in 0 until limits.length()) {
                val l = limits.optJSONObject(i) ?: continue
                if (l.optString("type") == type && l.optInt("unit") == unit) return l
            }
            return null
        }

        val sessionLimit = find("TOKENS_LIMIT", 3)
            ?: throw UpstreamChangedException("missing 5h window (TOKENS_LIMIT unit:3)")
        val weeklyLimit = find("TOKENS_LIMIT", 6)
            ?: throw UpstreamChangedException("missing weekly window (TOKENS_LIMIT unit:6)")
        val modelLimit = find("TIME_LIMIT", 5)

        val modelUsage: List<ModelUsageItem>? = modelLimit?.optJSONArray("usageDetails")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { d ->
                    ModelUsageItem(
                        d.optString("modelCode", "unknown"),
                        d.optInt("usage", 0)
                    )
                }
            }
        }

        return CodingPlanUsage(
            planName = data.optString("level").takeIf { it.isNotEmpty() },
            session = toWindow(sessionLimit),
            weekly = toWindow(weeklyLimit),
            modelUsage = modelUsage,
            updatedAt = now,
            source = UsageSource.DIRECT,
            status = UsageStatus.OK
        )
    }

    private fun toWindow(limit: JSONObject): UsageWindow {
        val used = limit.optInt("percentage", 0).coerceIn(0, 100)
        val reset = if (limit.has("nextResetTime") && !limit.isNull("nextResetTime"))
            limit.optLong("nextResetTime") else null
        return UsageWindow(used, 100 - used, reset)
    }
}
