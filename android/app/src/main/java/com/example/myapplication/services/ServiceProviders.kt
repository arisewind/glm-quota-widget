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
    /** 凭据形态：GLM=Raw(直接 Key，不加 Bearer)；Kimi/MiniMax=Bearer；GLM Team=ZhipuTeam(三件套)。 */
    enum class CredentialType { RAW, BEARER, ZHIPU_TEAM }

    /** 按本服务商要求把原始 key 包成 [Credential]。Team 三件套走 VM 多参数路径，此处仅单 key provider 使用。 */
    fun credentialFor(key: String): Credential = when (credentialType) {
        CredentialType.RAW -> Credential.Raw(key)
        CredentialType.BEARER -> Credential.Bearer(key)
        CredentialType.ZHIPU_TEAM ->
            throw IllegalStateException("GLM Team 凭据为三件套，请用 addTeamAccount 路径构造 ZhipuTeam")
    }

    suspend fun fetchUsage(
        credential: Credential,
        region: String?,
        http: HttpExecutor,
        now: () -> Long = { System.currentTimeMillis() }
    ): UsageSnapshot {
        val authHeaders = authHeadersFor(credential) ?: throw UsageProviderException(ErrorMapper.noPlan())
        val url = baseUrl(region) + path
        val headers = extraHeaders + authHeaders
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

    /**
     * 按凭据类型构造认证相关 headers（返回 null → 凭据类型不符/缺失 → NO_PLAN）。
     * 返回 Map 以支持 GLM Team 的多 header（Authorization + bigmodel-organization/project）。
     * orgId/projectId 是账户维度，无法塞进 config.extraHeaders，故在此动态注入。
     */
    private fun authHeadersFor(credential: Credential): Map<String, String>? = when (credentialType) {
        CredentialType.RAW -> (credential as? Credential.Raw)?.let { mapOf("Authorization" to it.key) }
        CredentialType.BEARER -> (credential as? Credential.Bearer)?.let { mapOf("Authorization" to "Bearer ${it.key}") }
        CredentialType.ZHIPU_TEAM -> (credential as? Credential.ZhipuTeam)?.let { t ->
            // 三件套任一为空视为凭据不完整（纵深防御，VM 层已前置校验给更明确提示）
            if (t.apiKey.isBlank() || t.orgId.isBlank() || t.projectId.isBlank()) null
            else mapOf(
                "Authorization" to t.apiKey,                  // 团队 Key 直接用，不加 Bearer（同个人版）
                "bigmodel-organization" to t.orgId,
                "bigmodel-project" to t.projectId
            )
        }
    }
}

/**
 * 服务商注册表（唯一元数据真源）。UI 选择器 / 凭据构造 / 列表色条 / Provider 工厂都查这里，
 * 消除原 Models·Providers·VM·Factory·credentialFor·parse 六处元数据发散。
 */
object ServiceProviders {
    fun all(): List<ServiceProviderConfig> = listOf(glm(), glmTeam(), kimi(), minimax())

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

    /** GLM 团队版：同 path 加 ?type=2，Host 固定国内站，凭据三件套，响应 shape 与个人版一致 → 复用 parseGlm。 */
    private fun glmTeam() = ServiceProviderConfig(
        providerId = ServiceProviderInfo.GLM_TEAM_ID,
        label = ServiceProviderInfo.GLM_TEAM_LABEL,
        supportsRegion = false,                                          // 团队版仅国内站，z.ai 无 team
        credentialType = ServiceProviderConfig.CredentialType.ZHIPU_TEAM,
        brandColor = 0xFF2563EB.toInt(),                                 // 智谱团队蓝（区别于个人版青）
        baseUrl = { "https://open.bigmodel.cn" },
        path = "/api/monitor/usage/quota/limit?type=2",                  // 个人版同 path 加 ?type=2
        extraHeaders = mapOf("Accept-Language" to "en-US,en", "Content-Type" to "application/json"),
        parse = UsageParser::parseGlm                                    // 复用，不写 parseGlmTeam
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
