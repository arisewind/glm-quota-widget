package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageSnapshot

private const val MINIMAX_PATH = "/v1/api/openplatform/coding_plan/remains"

/**
 * MiniMax Coding Plan 用量查询（v2-provider-research）。
 * Authorization **Bearer** Key；CN `api.minimaxi.com` / EN `api.minimax.io`。
 * 响应给**剩余百分比**（需 100 减）；周桶仅 `current_weekly_status==1` 才激活。
 */
class MiniMaxUsageProvider(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Int = 15_000
) : ServiceProvider {

    override val providerId = ServiceProviderInfo.MINIMAX_ID
    override val label = ServiceProviderInfo.MINIMAX_LABEL
    override val supportsRegion = true

    private fun baseUrl(region: String?) =
        if (region == Region.INTL.name) "https://api.minimax.io" else "https://api.minimaxi.com"

    override suspend fun fetchUsage(credential: Credential, region: String?, http: HttpExecutor): UsageSnapshot {
        val key = (credential as? Credential.Bearer)?.key
            ?: throw UsageProviderException(ErrorMapper.noPlan())
        val headers = mapOf(
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json"
        )
        val resp = try {
            http.get(baseUrl(region) + MINIMAX_PATH, headers, timeoutMs)
        } catch (e: Exception) {
            throw UsageProviderException(ErrorMapper.network())
        }
        if (resp.status == 401 || resp.status == 403 || resp.status == 429 || resp.status !in 200..299) {
            throw UsageProviderException(ErrorMapper.httpStatus(resp.status))
        }
        return try {
            UsageParser.parseMiniMax(resp.bodyText, now())
        } catch (e: UpstreamChangedException) {
            throw UsageProviderException(ErrorMapper.upstreamChanged(e.message ?: ""))
        }
    }
}
