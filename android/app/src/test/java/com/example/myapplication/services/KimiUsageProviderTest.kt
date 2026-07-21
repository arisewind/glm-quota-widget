package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.ServiceProviderInfo
import com.example.myapplication.domain.UsageErrorCode
import com.example.myapplication.domain.WindowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Kimi 全链路测试（ADR-0003）：经 [ServiceProviders] Kimi config + mock HttpExecutor。 */
class KimiUsageProviderTest {

    private val kimi get() = ServiceProviders.byId(ServiceProviderInfo.KIMI_ID)

    private class FakeHttp(val status: Int, val body: String) : HttpExecutor {
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            capturedUrl = url
            capturedHeaders = headers
            return HttpResponse(status, body)
        }
    }

    // 真实返回快照（2026-07-21 抓取）：usage=周窗（resetTime 下周一）、limits[0]=5h 窗（window.duration 300 分钟）
    private val sample = """{"usage":{"limit":"100","used":"24","remaining":"76","resetTime":"2026-07-28T01:55:04.262116Z"},"limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"100","used":"67","remaining":"33","resetTime":"2026-07-21T08:55:04.262116Z"}}]}"""

    @Test
    fun fetchUsage_success() {
        val http = FakeHttp(200, sample)
        val snap = runBlocking {
            kimi.fetchUsage(Credential.Bearer("k"), null, http, now = { 1L })
        }
        assertEquals("kimi", snap.providerId)
        assertEquals(67, snap.window(WindowKind.FIVE_HOUR)!!.usedPercent)   // 5h：(100-33)/100
        assertEquals(24, snap.window(WindowKind.WEEKLY)!!.usedPercent)      // 周：(100-76)/100
        assertEquals("https://api.kimi.com/coding/v1/usages", http.capturedUrl)
        assertEquals("Bearer k", http.capturedHeaders?.get("Authorization")) // Bearer
    }

    @Test
    fun fetchUsage_401_throwsAuth() {
        try {
            runBlocking { kimi.fetchUsage(Credential.Bearer("k"), null, FakeHttp(401, "")) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.AUTH, e.mapped.code)
        }
    }

    @Test
    fun fetchUsage_wrongCredential_throwsNoPlan() {
        // Kimi 需 Bearer，传 Raw 应 NO_PLAN
        try {
            runBlocking { kimi.fetchUsage(Credential.Raw("k"), null, FakeHttp(200, sample)) }
            fail()
        } catch (e: UsageProviderException) {
            assertEquals(UsageErrorCode.NO_PLAN, e.mapped.code)
        }
    }
}
