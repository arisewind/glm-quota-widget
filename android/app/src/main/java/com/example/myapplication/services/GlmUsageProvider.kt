package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageSnapshot

private const val GLM_PATH = "/api/monitor/usage/quota/limit"

/**
 * 智谱 GLM Coding Plan 用量查询（ADR-0001）。
 * Authorization 直接 Key（**不加 Bearer**）；过反爬关键是 Content-Type + Accept-Language。
 * 输出归一化 UsageSnapshot（ADR-0002）。
 */
class GlmUsageProvider(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Int = 15_000
) : ServiceProvider {

    override val providerId = ServiceProviderInfo.GLM_ID
    override val label = ServiceProviderInfo.GLM_LABEL
    override val supportsRegion = true

    private fun baseUrl(region: String?) =
        if (region == Region.INTL.name) "https://api.z.ai" else "https://open.bigmodel.cn"

    override suspend fun fetchUsage(credential: Credential, region: String?, http: HttpExecutor): UsageSnapshot {
        val key = (credential as? Credential.Raw)?.key
            ?: throw UsageProviderException(ErrorMapper.noPlan())
        val url = baseUrl(region) + GLM_PATH
        val headers = mapOf(
            "Authorization" to key,
            "Accept-Language" to "en-US,en",
            "Content-Type" to "application/json"
        )
        val resp = try {
            http.get(url, headers, timeoutMs)
        } catch (e: Exception) {
            throw UsageProviderException(ErrorMapper.network())
        }
        if (resp.status == 401 || resp.status == 403 || resp.status == 429 || resp.status !in 200..299) {
            throw UsageProviderException(ErrorMapper.httpStatus(resp.status))
        }
        return try {
            UsageParser.parseGlm(resp.bodyText, now())
        } catch (e: UpstreamChangedException) {
            throw UsageProviderException(ErrorMapper.upstreamChanged(e.message ?: ""))
        }
    }

    /** 连接测试：复用一次真实查询。 */
    suspend fun testConnection(credential: Credential, region: String?, http: HttpExecutor): UsageSnapshot =
        fetchUsage(credential, region, http)
}
