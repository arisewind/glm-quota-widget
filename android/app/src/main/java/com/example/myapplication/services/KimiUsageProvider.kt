package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageSnapshot

private const val KIMI_URL = "https://api.kimi.com/coding/v1/usages"

/**
 * Kimi For Coding 用量查询（ADR-0003，第二家）。
 * Authorization **Bearer** Key；单区域（无 CN/INTL）；响应绝对值 limit/remaining，需自算百分比。
 * 端点为 Kimi Code Console 内部接口（无公开文档，字段来自 cc-switch + 第三方工具对照）。
 */
class KimiUsageProvider(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Int = 15_000
) : ServiceProvider {

    override val providerId = ServiceProviderInfo.KIMI_ID
    override val label = ServiceProviderInfo.KIMI_LABEL
    override val supportsRegion = false

    override suspend fun fetchUsage(credential: Credential, region: String?, http: HttpExecutor): UsageSnapshot {
        val key = (credential as? Credential.Bearer)?.key
            ?: throw UsageProviderException(ErrorMapper.noPlan())
        val headers = mapOf(
            "Authorization" to "Bearer $key",
            "Accept" to "application/json"
        )
        val resp = try {
            http.get(KIMI_URL, headers, timeoutMs)
        } catch (e: Exception) {
            throw UsageProviderException(ErrorMapper.network())
        }
        if (resp.status == 401 || resp.status == 403 || resp.status == 429 || resp.status !in 200..299) {
            throw UsageProviderException(ErrorMapper.httpStatus(resp.status))
        }
        return try {
            UsageParser.parseKimi(resp.bodyText, now())
        } catch (e: UpstreamChangedException) {
            throw UsageProviderException(ErrorMapper.upstreamChanged(e.message ?: ""))
        }
    }
}
