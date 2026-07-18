package com.example.myapplication.services

import com.example.myapplication.domain.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * 各 Provider 解析单测（无真实 API，用 cc-switch 标定的样例响应验证归一化正确性）。
 * 这是 v2.0 无真机端到端验证时的核心保障。
 */
class UsageParserTest {

    // ---------- GLM（ADR-0001）----------

    @Test
    fun parseGlm_success() {
        val body = """{"success":true,"code":200,"data":{"level":"pro","limits":[
            {"type":"TOKENS_LIMIT","unit":3,"percentage":5,"nextResetTime":1720000000000},
            {"type":"TOKENS_LIMIT","unit":6,"percentage":19},
            {"type":"TIME_LIMIT","unit":5,"usageDetails":[{"modelCode":"glm-4.6","usage":12345}]}
        ]}}"""
        val snap = UsageParser.parseGlm(body, 1000L)
        assertEquals("glm", snap.providerId)
        assertEquals("智谱 GLM Coding Plan", snap.providerLabel)
        assertEquals("pro", snap.planName)
        assertEquals(2, snap.windows.size)
        val five = snap.window(WindowKind.FIVE_HOUR)!!
        assertEquals(5, five.usedPercent)
        assertEquals(1720000000000L, five.resetAt)
        val weekly = snap.window(WindowKind.WEEKLY)!!
        assertEquals(19, weekly.usedPercent)
        assertNull(weekly.resetAt)
        assertEquals(1, snap.modelUsage?.size)
        assertEquals("glm-4.6", snap.modelUsage!![0].modelCode)
        assertEquals(12345, snap.modelUsage!![0].usage)
    }

    @Test
    fun parseGlm_upstreamChanged_throws() {
        try {
            UsageParser.parseGlm("""{"success":false}""", 1L); fail("应抛 UpstreamChangedException")
        } catch (e: UpstreamChangedException) { /* ok */ }
    }

    @Test
    fun parseGlm_missingWindow_throws() {
        try {
            UsageParser.parseGlm("""{"success":true,"data":{"limits":[]}}""", 1L); fail()
        } catch (e: UpstreamChangedException) { /* ok */ }
    }

    // ---------- Kimi（ADR-0003）----------

    @Test
    fun parseKimi_success() {
        val body = """{"limits":[{"detail":{"limit":100000,"remaining":95000}}],
            "usage":{"limit":500000,"remaining":405000}}"""
        val snap = UsageParser.parseKimi(body, 1L)
        assertEquals("kimi", snap.providerId)
        assertEquals("Kimi For Coding", snap.providerLabel)
        val five = snap.window(WindowKind.FIVE_HOUR)!!
        // (100000-95000)/100000 = 5%
        assertEquals(5, five.usedPercent)
        assertEquals(5000.0, five.usedValue!!, 0.001)
        assertEquals(100000.0, five.totalValue!!, 0.001)
        val weekly = snap.window(WindowKind.WEEKLY)!!
        // (500000-405000)/500000 = 19%
        assertEquals(19, weekly.usedPercent)
    }

    @Test
    fun parseKimi_missingUsage_throws() {
        try {
            UsageParser.parseKimi("""{"limits":[]}""", 1L); fail()
        } catch (e: UpstreamChangedException) { /* ok */ }
    }

    // ---------- MiniMax（v2-provider-research）----------

    @Test
    fun parseMiniMax_withWeekly() {
        val body = """{"base_resp":{"status_code":0},
            "model_remains":[{"model_name":"video"},{"model_name":"general",
            "current_interval_remaining_percent":95,"current_weekly_status":1,
            "current_weekly_remaining_percent":81,
            "end_time":1720000000000,"weekly_end_time":1720500000000}]}"""
        val snap = UsageParser.parseMiniMax(body, 1L)
        assertEquals("minimax", snap.providerId)
        assertEquals("MiniMax Coding Plan", snap.providerLabel)
        val five = snap.window(WindowKind.FIVE_HOUR)!!
        assertEquals(5, five.usedPercent)       // 100 - 95
        assertEquals(1720000000000L, five.resetAt)
        val weekly = snap.window(WindowKind.WEEKLY)!!
        assertEquals(19, weekly.usedPercent)    // 100 - 81
    }

    @Test
    fun parseMiniMax_status3_noWeekly() {
        // current_weekly_status==3 表示无周限额，周桶不应出现（别看到 100% 就当没用）
        val body = """{"base_resp":{"status_code":0},
            "model_remains":[{"model_name":"general","current_interval_remaining_percent":95,
            "current_weekly_status":3,"current_weekly_remaining_percent":100,
            "end_time":1720000000000}]}"""
        val snap = UsageParser.parseMiniMax(body, 1L)
        assertEquals(1, snap.windows.size)
        assertEquals(5, snap.window(WindowKind.FIVE_HOUR)!!.usedPercent)
        assertNull(snap.window(WindowKind.WEEKLY))
    }

    @Test
    fun parseMiniMax_baseError_throws() {
        val body = """{"base_resp":{"status_code":1004,"status_msg":"auth fail"},"model_remains":[]}"""
        try {
            UsageParser.parseMiniMax(body, 1L); fail()
        } catch (e: UpstreamChangedException) { /* ok */ }
    }

    @Test
    fun parseMiniMax_missingGeneral_throws() {
        val body = """{"base_resp":{"status_code":0},
            "model_remains":[{"model_name":"video","current_interval_remaining_percent":50}]}"""
        try {
            UsageParser.parseMiniMax(body, 1L); fail()
        } catch (e: UpstreamChangedException) { /* ok */ }
    }
}
