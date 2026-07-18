package com.example.myapplication.services

import com.example.myapplication.domain.UsageErrorCode

/** 错误映射（架构 §4.3.1 / §5.2）。errorMessage 不透传上游敏感内容。 */
data class MappedError(val code: UsageErrorCode, val message: String)

object ErrorMapper {
    fun httpStatus(status: Int): MappedError = when (status) {
        401, 403 -> MappedError(UsageErrorCode.AUTH, "Key 无效、已失效，或不属于可用的 Coding Plan")
        429 -> MappedError(UsageErrorCode.RATE_LIMITED, "查询过于频繁，请稍后再试")
        else -> MappedError(UsageErrorCode.UNKNOWN, "暂时无法解析用量，请稍后再试")
    }
    fun network() = MappedError(UsageErrorCode.NETWORK, "网络连接失败，请检查网络后重试")
    fun upstreamChanged(@Suppress("UNUSED_PARAMETER") detail: String) =
        MappedError(UsageErrorCode.UPSTREAM_CHANGED, "暂时无法解析用量，请更新 App 或稍后再试")
    fun noPlan() = MappedError(UsageErrorCode.NO_PLAN, "尚未配置 Coding Plan Key")
}
