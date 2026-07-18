package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.UsageErrorCode
import com.example.myapplication.domain.WindowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** GLM Provider 全链路测试（mock HttpExecutor，验 URL/认证头/错误映射，无真实网络）。 */
class GlmUsageProviderTest {

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

    private val sample = """{"success":true,"data":{"level":"pro","limits":[
        {"type":"TOKENS_LIMIT","unit":3,"percentage":5,"nextResetTime":1720000000000},
        {"type":"TOKENS_LIMIT","unit":6,"percentage":19}]}}"""

    @Test
    fun fetchUsage_cn_success() {
        val http = FakeHttp(200, sample)
        val snap = runBlocking {
            GlmUsageProvider(now = { 1000L }).fetchUsage(Credential.Raw("key"), "CN", http)
        }
        assertEquals("glm", snap.providerId)
        assertEquals(5, snap.window(WindowKind.FIVE_HOUR)!!.usedPercent)
        assertEquals("https://open.bigmodel.cn/api/monitor/usage/quota/limit", http.capturedUrl)
        assertEquals("key", http.capturedHeaders?.get("Authorization")) // 直接 Key，不加 Bearer
    }

    @Test
    fun fetchUsage_intl_url() {
        val http = FakeHttp(200, sample)
        runBlocking { GlmUsageProvider(now = { 1L }).fetchUsage(Credential.Raw("key"), "INTL", http) }
        assertEquals("https://api.z.ai/api/monitor/usage/quota/limit", http.capturedUrl)
    }

    @Test
    fun fetchUsage_401_throwsAuth() {
        try {
            runBlocking { GlmUsageProvider().fetchUsage(Credential.Raw("k"), "CN", FakeHttp(401, "")) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.AUTH, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_network_throwsNetwork() {
        try {
            runBlocking { GlmUsageProvider().fetchUsage(Credential.Raw("k"), "CN", FakeHttp(0, "", throwException = true)) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.NETWORK, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_wrongCredential_throwsNoPlan() {
        // GLM 需 Raw 凭据，传 Bearer 应映射 NO_PLAN
        try {
            runBlocking { GlmUsageProvider().fetchUsage(Credential.Bearer("k"), "CN", FakeHttp(200, sample)) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.NO_PLAN, e.mapped.code)
        }
    }
}
