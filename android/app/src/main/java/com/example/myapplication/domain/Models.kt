package com.example.myapplication.domain

enum class UsageStatus { UNCONFIGURED, LOADING, OK, STALE, ERROR }

enum class UsageErrorCode { NETWORK, AUTH, NO_PLAN, RATE_LIMITED, UPSTREAM_CHANGED, UNKNOWN }

enum class UsageSource { DIRECT, BRIDGE, MOCK }

/**
 * 服务商显示信息（v1.1 引入，为 v2.0 多服务商注册表铺路）。
 * 当前仅 GLM 一家；新增服务商时在此扩展 providerId 与显示名。
 */
object ServiceProviderInfo {
    const val GLM_ID = "glm"
    const val GLM_LABEL = "智谱 GLM Coding Plan"
}

/** 单个额度窗口。ADR 边界：5h 窗未消耗时 resetAt 为 null。 */
data class UsageWindow(
    val usedPercent: Int,        // 0..100（已 clamp）
    val remainingPercent: Int,   // 0..100
    val resetAt: Long? = null    // Unix 毫秒
)

data class ModelUsageItem(val modelCode: String, val usage: Int)

/** 跨 UI / 缓存 / Provider 的稳定契约（对应 TS 层 CodingPlanUsage）。 */
data class CodingPlanUsage(
    val session: UsageWindow,          // 5h 窗（unit:3）
    val weekly: UsageWindow,           // 周窗（unit:6）
    val updatedAt: Long,
    val source: UsageSource,
    val status: UsageStatus,
    val providerLabel: String? = null, // 服务商显示名（需求 5），渲染主标题；如 "智谱 GLM Coding Plan"
    val planName: String? = null,      // 套餐级（如 "pro"），渲染副标题
    val modelUsage: List<ModelUsageItem>? = null,   // unit:5 模型级用量，可选
    val errorCode: UsageErrorCode? = null,
    val errorMessage: String? = null
)
