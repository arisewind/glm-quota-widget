package com.example.myapplication.services

/** 可注入的 HTTP 执行器（OkHttp 实现见 OkHttpExecutor.kt）。 */
interface HttpExecutor {
    suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse
}

data class HttpResponse(val status: Int, val bodyText: String)

/** Provider 抛出的已映射错误（message 不透传上游敏感内容）。 */
class UsageProviderException(val mapped: MappedError) : Exception(mapped.message)

/** 区域标识（GLM/MiniMax 双站用；Kimi 等单区域 Provider 忽略）。 */
enum class Region { CN, INTL }
