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

    private val sample = """{"limits":[{"detail":{"limit":100000,"remaining":95000}}],"usage":{"limit":500000,"remaining":405000}}"""

    @Test
    fun fetchUsage_success() {
        val http = FakeHttp(200, sample)
        val snap = runBlocking {
            kimi.fetchUsage(Credential.Bearer("k"), null, http, now = { 1L })
        }
        assertEquals("kimi", snap.providerId)
        assertEquals(5, snap.window(WindowKind.FIVE_HOUR)!!.usedPercent)
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
