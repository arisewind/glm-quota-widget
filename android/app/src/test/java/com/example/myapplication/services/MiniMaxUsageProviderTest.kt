package com.example.myapplication.services

import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.WindowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** MiniMax Provider 全链路测试（mock HttpExecutor，验 CN/INTL URL + 剩余%反转）。 */
class MiniMaxUsageProviderTest {

    private class FakeHttp(val status: Int, val body: String) : HttpExecutor {
        var capturedUrl: String? = null
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            capturedUrl = url
            return HttpResponse(status, body)
        }
    }

    private val sample = """{"base_resp":{"status_code":0},"model_remains":[{"model_name":"general",
        "current_interval_remaining_percent":95,"current_weekly_status":1,
        "current_weekly_remaining_percent":81,"end_time":1720000000000,"weekly_end_time":1720500000000}]}"""

    @Test
    fun fetchUsage_cn_success() {
        val http = FakeHttp(200, sample)
        val snap = runBlocking {
            MiniMaxUsageProvider(now = { 1L }).fetchUsage(Credential.Bearer("k"), "CN", http)
        }
        assertEquals("minimax", snap.providerId)
        assertEquals(5, snap.window(WindowKind.FIVE_HOUR)!!.usedPercent) // 100 - 95
        assertEquals("https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains", http.capturedUrl)
    }

    @Test
    fun fetchUsage_intl_url() {
        val http = FakeHttp(200, sample)
        runBlocking { MiniMaxUsageProvider(now = { 1L }).fetchUsage(Credential.Bearer("k"), "INTL", http) }
        assertEquals("https://api.minimax.io/v1/api/openplatform/coding_plan/remains", http.capturedUrl)
    }
}
