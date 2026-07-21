package com.example.myapplication.services

import com.example.myapplication.domain.ModelUsageItem
import com.example.myapplication.domain.NormalizedWindow
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.UsageSource
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import org.json.JSONObject
import java.time.Instant

class UpstreamChangedException(message: String) : Exception(message)

/**
 * 各 Provider 的响应解析（ADR-0001 GLM / ADR-0003 Kimi / v2-provider-research MiniMax）。
 * 每家一个 parse 函数，统一输出归一化 [UsageSnapshot]（ADR-0002）。
 */
object UsageParser {

    /**
     * GLM（ADR-0001）：`data.limits` 按 type+unit 映射——
     * TOKENS_LIMIT unit:3 = 5h、unit:6 = 周；TIME_LIMIT unit:5 = 模型用量（附加 modelUsage）。
     * 必须按 unit 分类窗口，不能按 nextResetTime 排序（cc-switch #3036）。
     */
    fun parseGlm(body: String, now: Long): UsageSnapshot {
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) {
            throw UpstreamChangedException("success=false, code=${root.opt("code")}, msg=${root.opt("msg")}")
        }
        val data = root.optJSONObject("data") ?: throw UpstreamChangedException("missing data")
        val limits = data.optJSONArray("limits") ?: throw UpstreamChangedException("missing limits")

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
        val toolsLimit = find("TIME_LIMIT", 5)   // 工具调用额度：usage=总配额/currentValue=已用/percentage=已用%/usageDetails=按工具细分

        val windows = buildList {
            add(toGlmWindow(sessionLimit, WindowKind.FIVE_HOUR))
            add(toGlmWindow(weeklyLimit, WindowKind.WEEKLY))
            toolsLimit?.let { add(toGlmToolsWindow(it)) }
        }
        val modelUsage: List<ModelUsageItem>? = toolsLimit?.optJSONArray("usageDetails")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { d ->
                    ModelUsageItem(d.optString("modelCode", "unknown"), d.optInt("usage", 0))
                }
            }
        }

        return UsageSnapshot(
            providerId = ServiceProviderInfo.GLM_ID,
            providerLabel = ServiceProviderInfo.GLM_LABEL,
            windows = windows,
            planName = data.optString("level").takeIf { it.isNotEmpty() },
            modelUsage = modelUsage,
            updatedAt = now,
            source = UsageSource.DIRECT,
            status = UsageStatus.OK
        )
    }

    private fun toGlmWindow(limit: JSONObject, kind: WindowKind): NormalizedWindow {
        val used = limit.optInt("percentage", 0).coerceIn(0, 100)
        val reset = if (limit.has("nextResetTime") && !limit.isNull("nextResetTime"))
            limit.optLong("nextResetTime") else null
        return NormalizedWindow(kind, used, reset)
    }

    /** 工具调用额度窗（TIME_LIMIT unit:5）。usage=总配额次数、currentValue=已用次数、percentage=已用%；按工具的 usageDetails 由 modelUsage 另存。 */
    private fun toGlmToolsWindow(limit: JSONObject): NormalizedWindow {
        val used = limit.optInt("percentage", 0).coerceIn(0, 100)
        val reset = if (limit.has("nextResetTime") && !limit.isNull("nextResetTime"))
            limit.optLong("nextResetTime") else null
        return NormalizedWindow(
            kind = WindowKind.TOOLS,
            usedPercent = used,
            resetAt = reset,
            usedValue = limit.optDouble("currentValue", 0.0).takeIf { it > 0 },
            totalValue = limit.optDouble("usage", 0.0).takeIf { it > 0 },
            unit = "次"
        )
    }

    /**
     * Kimi（ADR-0003）：`limits[].detail` = 5h 窗，`usage` = 周窗（结构不对称）。
     * 字段为绝对值 limit/remaining，需自算百分比。端点为 Kimi Code Console 内部接口（无公开文档）。
     */
    fun parseKimi(body: String, now: Long): UsageSnapshot {
        val root = JSONObject(body)
        val limits = root.optJSONArray("limits") ?: throw UpstreamChangedException("missing limits")
        val usage = root.optJSONObject("usage") ?: throw UpstreamChangedException("missing usage")
        val fiveHour = (0 until limits.length())
            .mapNotNull { limits.optJSONObject(it) }
            .firstOrNull { obj ->                                  // 优先按 5h=300 分钟锁定，防 upstream 多窗口取错
                val w = obj.optJSONObject("window")
                w != null && w.optInt("duration") == 300 && w.optString("timeUnit") == "TIME_UNIT_MINUTE"
            }?.optJSONObject("detail")
            ?: (0 until limits.length())                            // 回退首个 detail（兼容无 window 字段的旧响应）
                .mapNotNull { limits.optJSONObject(it)?.optJSONObject("detail") }
                .firstOrNull()
            ?: throw UpstreamChangedException("missing 5h detail")

        val windows = listOf(
            toKimiWindow(fiveHour, WindowKind.FIVE_HOUR),
            toKimiWindow(usage, WindowKind.WEEKLY)
        )
        return UsageSnapshot(
            providerId = ServiceProviderInfo.KIMI_ID,
            providerLabel = ServiceProviderInfo.KIMI_LABEL,
            windows = windows,
            updatedAt = now,
            source = UsageSource.DIRECT,
            status = UsageStatus.OK
        )
    }

    private fun toKimiWindow(o: JSONObject, kind: WindowKind): NormalizedWindow {
        val limit = o.optDouble("limit", 0.0)
        val remaining = o.optDouble("remaining", 0.0)
        val usedRaw = (limit - remaining).coerceAtLeast(0.0)
        val usedPercent = if (limit > 0) (usedRaw / limit * 100).toInt().coerceIn(0, 100) else 0
        return NormalizedWindow(
            kind = kind,
            usedPercent = usedPercent,
            resetAt = extractResetTime(o, "resetTime"),
            usedValue = usedRaw.takeIf { it > 0 },
            totalValue = limit.takeIf { it > 0 },
            unit = null   // Kimi coding plan limit=100 为百分比/点数额度，非 token；UI 走 % 分支不显示
        )
    }

    /**
     * MiniMax（v2-provider-research）：`model_remains[]` 找 `model_name=="general"`。
     * 字段为**剩余百分比**，需用 100 减；周桶仅当 `current_weekly_status==1` 才激活（==3 无周限额，跳过）。
     */
    fun parseMiniMax(body: String, now: Long): UsageSnapshot {
        val root = JSONObject(body)
        val base = root.optJSONObject("base_resp")
        val statusCode = base?.optInt("status_code", 0) ?: 0
        if (statusCode != 0) {
            throw UpstreamChangedException("minimax base_resp status=$statusCode msg=${base?.opt("status_msg")}")
        }
        val remains = root.optJSONArray("model_remains")
            ?: throw UpstreamChangedException("missing model_remains")
        val general = (0 until remains.length())
            .mapNotNull { remains.optJSONObject(it) }
            .firstOrNull { it.optString("model_name") == "general" }
            ?: throw UpstreamChangedException("missing general bucket")

        val windows = mutableListOf<NormalizedWindow>()
        val intervalRemaining = general.optInt("current_interval_remaining_percent", 0).coerceIn(0, 100)
        windows.add(
            NormalizedWindow(
                WindowKind.FIVE_HOUR,
                (100 - intervalRemaining).coerceIn(0, 100),
                resetAt = general.optLong("end_time").takeIf { it > 0 }
            )
        )
        if (general.optInt("current_weekly_status", 0) == 1) {
            val weeklyRemaining = general.optInt("current_weekly_remaining_percent", 0).coerceIn(0, 100)
            windows.add(
                NormalizedWindow(
                    WindowKind.WEEKLY,
                    (100 - weeklyRemaining).coerceIn(0, 100),
                    resetAt = general.optLong("weekly_end_time").takeIf { it > 0 }
                )
            )
        }

        return UsageSnapshot(
            providerId = ServiceProviderInfo.MINIMAX_ID,
            providerLabel = ServiceProviderInfo.MINIMAX_LABEL,
            windows = windows,
            updatedAt = now,
            source = UsageSource.DIRECT,
            status = UsageStatus.OK
        )
    }

    /** 兼容 ISO 字符串 / 秒 / 毫秒的重置时间解析（Kimi 等 resetTime 格式不固定）。<1e12 视为秒。 */
    private fun extractResetTime(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> {
                val n = v.toDouble()
                when { n <= 0 -> null; n < 1e12 -> (n * 1000).toLong(); else -> n.toLong() }
            }
            is String -> parseIsoTime(v) ?: v.toDoubleOrNull()?.let { n ->
                when { n <= 0 -> null; n < 1e12 -> (n * 1000).toLong(); else -> n.toLong() }
            }
            else -> null
        }
    }

    private fun parseIsoTime(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
}
