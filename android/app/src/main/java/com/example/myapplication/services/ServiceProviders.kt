package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageSnapshot

/**
 * 服务商配置（ADR-0002）。把每家的 fetch 模板差异（url / 认证头 / 凭据类型 / 解析）折叠成一张 config 表，
 * 原三家 Provider 各抄一遍的 5 步模板现在只有唯一实现。
 *
 * 加服务商 = [ServiceProviders.all] 加一个工厂函数 + [UsageParser] 加一个 parse 函数，全栈可见。
 *
 * fetch 五步：取 key(凭据类型不符→NO_PLAN) → 拼 url+headers → http.get(异常→NETWORK)
 * → status 检查(401/403/429/!2xx→AUTH/RATE_LIMITED/UNKNOWN) → parse(UpstreamChanged→UPSTREAM_CHANGED)。
 */
class ServiceProviderConfig(
    val providerId: String,
    val label: String,
    val supportsRegion: Boolean,
    val credentialType: CredentialType,
    val brandColor: Int,
    private val baseUrl: (region: String?) -> String,
    private val path: String,
    private val extraHeaders: Map<String, String>,
    private val parse: (body: String, now: Long) -> UsageSnapshot,
    private val timeoutMs: Int = 15_000
) {
    /** 凭据形态：GLM=Raw(直接 Key，不加 Bearer)；Kimi/MiniMax=Bearer。 */
    enum class CredentialType { RAW, BEARER }

    /** 按本服务商要求把原始 key 包成 [Credential]。 */
    fun credentialFor(key: String): Credential = when (credentialType) {
        CredentialType.RAW -> Credential.Raw(key)
        CredentialType.BEARER -> Credential.Bearer(key)
    }

    suspend fun fetchUsage(
        credential: Credential,
        region: String?,
        http: HttpExecutor,
        now: () -> Long = { System.currentTimeMillis() }
    ): UsageSnapshot {
        val key = extractKey(credential) ?: throw UsageProviderException(ErrorMapper.noPlan())
        val auth = when (credentialType) {
            CredentialType.RAW -> key
            CredentialType.BEARER -> "Bearer $key"
        }
        val url = baseUrl(region) + path
        val headers = extraHeaders + ("Authorization" to auth)
        val resp = try {
            http.get(url, headers, timeoutMs)
        } catch (e: Exception) {
            throw UsageProviderException(ErrorMapper.network())
        }
        if (resp.status == 401 || resp.status == 403 || resp.status == 429 || resp.status !in 200..299) {
            throw UsageProviderException(ErrorMapper.httpStatus(resp.status))
        }
        val parsed = try {
            parse(resp.bodyText, now())
        } catch (e: UpstreamChangedException) {
            throw UsageProviderException(ErrorMapper.upstreamChanged(e.message ?: ""))
        }
        // 真源是 config：覆盖 parse 内可能硬编码的 id/label，保证全栈一致。
        return parsed.copy(providerId = providerId, providerLabel = label)
    }

    private fun extractKey(credential: Credential): String? = when (credentialType) {
        CredentialType.RAW -> (credential as? Credential.Raw)?.key
        CredentialType.BEARER -> (credential as? Credential.Bearer)?.key
    }
}

/**
 * 服务商注册表（唯一元数据真源）。UI 选择器 / 凭据构造 / 列表色条 / Provider 工厂都查这里，
 * 消除原 Models·Providers·VM·Factory·credentialFor·parse 六处元数据发散。
 */
object ServiceProviders {
    fun all(): List<ServiceProviderConfig> = listOf(glm(), kimi(), minimax())

    /** 按 id 取 config；未知 id 抛 IllegalArgumentException（新增账户路径 providerId 必然合法）。 */
    fun byId(id: String): ServiceProviderConfig =
        all().firstOrNull { it.providerId == id } ?: throw IllegalArgumentException("未知 provider: $id")

    /** 容错查找（渲染路径用，数据损坏时返回 null 而非崩）。 */
    fun findById(id: String): ServiceProviderConfig? = all().firstOrNull { it.providerId == id }

    /** 取服务商显示名；未知 id 回退裸 id（渲染路径用，收敛 UI/widget/通知记录三处重复）。 */
    fun labelOf(id: String): String = findById(id)?.label ?: id

    private fun glm() = ServiceProviderConfig(
        providerId = ServiceProviderInfo.GLM_ID,
        label = ServiceProviderInfo.GLM_LABEL,
        supportsRegion = true,
        credentialType = ServiceProviderConfig.CredentialType.RAW,
        brandColor = 0xFF00C2B8.toInt(),      // 智谱 GLM 青
        baseUrl = { r -> if (r == Region.INTL.name) "https://api.z.ai" else "https://open.bigmodel.cn" },
        path = "/api/monitor/usage/quota/limit",
        extraHeaders = mapOf("Accept-Language" to "en-US,en", "Content-Type" to "application/json"),
        parse = UsageParser::parseGlm
    )

    private fun kimi() = ServiceProviderConfig(
        providerId = ServiceProviderInfo.KIMI_ID,
        label = ServiceProviderInfo.KIMI_LABEL,
        supportsRegion = false,
        credentialType = ServiceProviderConfig.CredentialType.BEARER,
        brandColor = 0xFF7C5CFF.toInt(),      // Kimi 紫
        baseUrl = { "https://api.kimi.com" },
        path = "/coding/v1/usages",
        extraHeaders = mapOf("Accept" to "application/json"),
        parse = UsageParser::parseKimi
    )

    private fun minimax() = ServiceProviderConfig(
        providerId = ServiceProviderInfo.MINIMAX_ID,
        label = ServiceProviderInfo.MINIMAX_LABEL,
        supportsRegion = true,
        credentialType = ServiceProviderConfig.CredentialType.BEARER,
        brandColor = 0xFFF59E0B.toInt(),      // MiniMax 橙
        baseUrl = { r -> if (r == Region.INTL.name) "https://api.minimax.io" else "https://api.minimaxi.com" },
        path = "/v1/api/openplatform/coding_plan/remains",
        extraHeaders = mapOf("Content-Type" to "application/json"),
        parse = UsageParser::parseMiniMax
    )
}
