package com.example.myapplication.services

import com.example.myapplication.domain.CodingPlanUsage
import com.example.myapplication.domain.ServiceProviderInfo

/** 可注入的 HTTP 执行器（OkHttp 实现见 OkHttpExecutor.kt）。 */
interface HttpExecutor {
    suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse
}

data class HttpResponse(val status: Int, val bodyText: String)

interface UsageProvider {
    suspend fun fetchUsage(): CodingPlanUsage
}

class UsageProviderException(val mapped: MappedError) : Exception(mapped.message)

enum class Region(val baseUrl: String) {
    CN("https://open.bigmodel.cn"),
    INTL("https://api.z.ai")
}

private const val QUOTA_PATH = "/api/monitor/usage/quota/limit"

/**
 * ADR-0001 直连实现。
 * Authorization 直接 Key（不加 Bearer）；过反爬的关键是 Content-Type + Accept-Language，不是 Cookie。
 */
class DirectKeyUsageProvider(
    private val getRegion: suspend () -> Region,
    private val getKey: suspend () -> String?,
    private val http: HttpExecutor,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Int = 15_000,
    private val providerLabel: String = ServiceProviderInfo.GLM_LABEL
) : UsageProvider {

    override suspend fun fetchUsage(): CodingPlanUsage {
        val key = getKey() ?: throw UsageProviderException(ErrorMapper.noPlan())
        val url = getRegion().baseUrl + QUOTA_PATH
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
        if (resp.status == 401 || resp.status == 403 || resp.status == 429 ||
            resp.status !in 200..299
        ) {
            throw UsageProviderException(ErrorMapper.httpStatus(resp.status))
        }
        return try {
            UsageParser.parse(resp.bodyText, now()).copy(providerLabel = providerLabel)
        } catch (e: UpstreamChangedException) {
            throw UsageProviderException(ErrorMapper.upstreamChanged(e.message ?: ""))
        }
    }

    /** 连接测试：复用一次真实查询，避免重复请求。 */
    suspend fun testConnection(): CodingPlanUsage = fetchUsage()
}
