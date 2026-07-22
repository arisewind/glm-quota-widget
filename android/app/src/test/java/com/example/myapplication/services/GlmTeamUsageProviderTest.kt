package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageErrorCode
import com.example.myapplication.domain.WindowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GLM 团队版全链路测试：经 [ServiceProviders] glm_team config + mock HttpExecutor，
 * 验 URL(?type=2)/三件套头注入/凭据完整性/错误映射，无真实网络。
 *
 * 响应 shape 与个人版一致 → 复用 parseGlm，不另写 parseGlmTeam。
 */
class GlmTeamUsageProviderTest {

    private val team get() = ServiceProviders.byId(ServiceProviderInfo.GLM_TEAM_ID)

    private class FakeHttp(
        val status: Int,
        val body: String,
        val throwException: Boolean = false
    ) : HttpExecutor {
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            if (throwException) throw RuntimeException("network")
            capturedUrl = url
            capturedHeaders = headers
            return HttpResponse(status, body)
        }
    }

    // 文档样例（cc-switch query_zhipu_team）：level=max，5h 26% / 周 5%
    private val sample = """{"success":true,"data":{"level":"max","limits":[
        {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":26.0},
        {"type":"TOKENS_LIMIT","unit":6,"number":1,"percentage":5.0}]}}"""

    private val teamCred = Credential.ZhipuTeam("team-key", "org-123", "proj-456")

    @Test
    fun fetchUsage_team_success_urlAndHeaders() {
        val http = FakeHttp(200, sample)
        val snap = runBlocking {
            team.fetchUsage(teamCred, null, http, now = { 1000L })
        }
        // URL 仅国内站 + ?type=2
        assertEquals(
            "https://open.bigmodel.cn/api/monitor/usage/quota/limit?type=2",
            http.capturedUrl
        )
        // Authorization 直接 Key（团队版同个人版，不加 Bearer）
        assertEquals("team-key", http.capturedHeaders?.get("Authorization"))
        // 三件套注入两个 bigmodel-* 头
        assertEquals("org-123", http.capturedHeaders?.get("bigmodel-organization"))
        assertEquals("proj-456", http.capturedHeaders?.get("bigmodel-project"))
        // 反爬头仍在
        assertEquals("en-US,en", http.capturedHeaders?.get("Accept-Language"))
        // 解析结果 + config 覆盖 providerId/label
        assertEquals("glm_team", snap.providerId)
        assertEquals("智谱 GLM 团队版", snap.providerLabel)
        assertEquals("max", snap.planName)
        assertEquals(26, snap.window(WindowKind.FIVE_HOUR)!!.usedPercent)
        assertEquals(5, snap.window(WindowKind.WEEKLY)!!.usedPercent)
    }

    @Test
    fun fetchUsage_team_ignoresRegion() {
        // supportsRegion=false：传任何 region 都不影响 URL（固定国内站）
        val http = FakeHttp(200, sample)
        runBlocking { team.fetchUsage(teamCred, "INTL", http, now = { 1L }) }
        assertTrue(http.capturedUrl!!.startsWith("https://open.bigmodel.cn"))
        assertTrue(!http.capturedUrl!!.contains("z.ai"))
    }

    @Test
    fun fetchUsage_team_missingOrgId_throwsNoPlan() {
        // 三件套任一为空 → 凭据不完整 → NO_PLAN（类比 cc-switch zhipu_team_missing_creds_returns_not_found）
        try {
            runBlocking {
                team.fetchUsage(Credential.ZhipuTeam("key", "", "proj"), null, FakeHttp(200, sample))
            }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.NO_PLAN, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_team_missingProjectId_throwsNoPlan() {
        try {
            runBlocking {
                team.fetchUsage(Credential.ZhipuTeam("key", "org", "  "), null, FakeHttp(200, sample))
            }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.NO_PLAN, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_team_wrongCredential_throwsNoPlan() {
        // Team config 需 ZhipuTeam，传 Raw 应映射 NO_PLAN（凭据类型不符）
        try {
            runBlocking { team.fetchUsage(Credential.Raw("key"), null, FakeHttp(200, sample)) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.NO_PLAN, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_team_401_throwsAuth() {
        try {
            runBlocking { team.fetchUsage(teamCred, null, FakeHttp(401, "")) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.AUTH, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_team_network_throwsNetwork() {
        try {
            runBlocking { team.fetchUsage(teamCred, null, FakeHttp(0, "", throwException = true)) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.NETWORK, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_team_noToolsWindow_graceful() {
        // Team 下 TIME_LIMIT 工具窗存在性未实测，取不到应优雅降级（不抛异常，TOOLS 窗为 null）
        val snap = runBlocking { team.fetchUsage(teamCred, null, FakeHttp(200, sample), now = { 1L }) }
        assertNull(snap.window(WindowKind.TOOLS))
        assertEquals(2, snap.windows.size)  // 仅 5h + 周
    }
}
