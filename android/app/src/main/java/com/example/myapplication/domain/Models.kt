package com.example.myapplication.domain

enum class UsageStatus { UNCONFIGURED, LOADING, OK, STALE, ERROR }

enum class UsageErrorCode { NETWORK, AUTH, NO_PLAN, RATE_LIMITED, UPSTREAM_CHANGED, UNKNOWN }

enum class UsageSource { DIRECT, BRIDGE, MOCK }

/** 归一化窗口语义（ADR-0002）。必须按本字段定位窗口，不能按 resetAt 排序（cc-switch #3036：周期末周窗可能比 5h 更早重置）。 */
enum class WindowKind(val displayName: String) {
    FIVE_HOUR("5 小时额度"), WEEKLY("本周额度"), MONTHLY("本月额度"), TOOLS("工具调用额度")
}

/** 归一化后的单个额度窗口（各家 Provider 输出此结构）。 */
data class NormalizedWindow(
    val kind: WindowKind,
    val usedPercent: Int,        // 0..100，归一化已用百分比
    val resetAt: Long? = null,   // Unix 毫秒
    val usedValue: Double? = null,
    val totalValue: Double? = null,
    val unit: String? = null     // "tokens" / "flows" / "usd" / "points"
)

data class ModelUsageItem(val modelCode: String, val usage: Int)

/** 凭据形态（不同 Provider 用不同认证，ADR-0002）。 */
sealed class Credential {
    /** GLM：直接 Key（不加 Bearer）。 */
    data class Raw(val key: String) : Credential()
    /** Kimi / MiniMax / ZenMux：Bearer Key。 */
    data class Bearer(val key: String) : Credential()
    /** 火山方舟：AK/SK 签名 V4（v2.1+）。 */
    data class VolcAksk(val accessKeyId: String, val secretKey: String) : Credential()
}

/**
 * 跨 Provider 的通用用量快照（v2.0，取代 v1.x 的 CodingPlanUsage）。ADR-0002。
 * windows 为归一化窗口列表；缺失的窗口不出现在列表中（UI 按 [window] 查找）。
 */
data class UsageSnapshot(
    val providerId: String,
    val providerLabel: String,
    val windows: List<NormalizedWindow>,
    val planName: String? = null,
    val modelUsage: List<ModelUsageItem>? = null,   // GLM TIME_LIMIT unit:5，可选附加
    val updatedAt: Long,
    val source: UsageSource,
    val status: UsageStatus,
    val errorCode: UsageErrorCode? = null,
    val errorMessage: String? = null
) {
    /** 按 kind 查窗口；缺失返回 null。 */
    fun window(kind: WindowKind): NormalizedWindow? = windows.firstOrNull { it.kind == kind }
}

/** 一个已配置的服务商账户。 */
data class Account(
    val accountId: String,
    val providerId: String,
    val label: String,
    val credential: Credential,
    val region: String? = null,   // "CN"/"INTL"；单区域 Provider 为 null
    val isActive: Boolean = true
)

/**
 * 服务商稳定 ID / 显示名常量（domain 纯数据，被 [com.example.myapplication.services.ServiceProviders] 引用）。
 * 其余元数据（url / 认证头 / 品牌色 / parse）集中在 [com.example.myapplication.services.ServiceProviderConfig]，
 * 加服务商只动那一处 config 表。
 */
object ServiceProviderInfo {
    const val GLM_ID = "glm"
    const val KIMI_ID = "kimi"
    const val MINIMAX_ID = "minimax"
    const val GLM_LABEL = "智谱 GLM Coding Plan"
    const val KIMI_LABEL = "Kimi For Coding"
    const val MINIMAX_LABEL = "MiniMax Coding Plan"
}
