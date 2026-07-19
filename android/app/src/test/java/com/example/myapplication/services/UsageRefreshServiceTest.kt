package com.example.myapplication.services

import com.example.myapplication.domain.Account
import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.UsageErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UsageRefreshService 停止策略测试（候选 3）。
 *
 * [UsageRefreshService.isStopCode] 是 Worker 跳过决策与 RefreshService 停止策略共享的 stop-code 真源
 * （AUTH / NO_PLAN / UPSTREAM_CHANGED）。本测试覆盖：
 * 1. isStopCode 的分类正确性（Worker skip 的依据）；
 * 2. AUTH 失败（∈ STOP_CODES）→ 真的进入停止态，前台/后台不再自动刷新；
 * 3. NETWORK 失败（∉ STOP_CODES）→ 不停止，仅按连续失败退避——对照证明 stop-code 判定的必要性。
 * 第 2/3 条是 STOP_CODES 的真正出处，也是候选 3 修复的 bug 根源。
 */
class UsageRefreshServiceTest {

    @Test
    fun isStopCode_需用户介入的错误码返回true() {
        assertTrue(UsageRefreshService.isStopCode(UsageErrorCode.AUTH))
        assertTrue(UsageRefreshService.isStopCode(UsageErrorCode.NO_PLAN))
        assertTrue(UsageRefreshService.isStopCode(UsageErrorCode.UPSTREAM_CHANGED))
    }

    @Test
    fun isStopCode_可重试的错误码返回false() {
        assertFalse(UsageRefreshService.isStopCode(UsageErrorCode.NETWORK))
        assertFalse(UsageRefreshService.isStopCode(UsageErrorCode.RATE_LIMITED))
        assertFalse(UsageRefreshService.isStopCode(UsageErrorCode.UNKNOWN))
    }

    @Test
    fun isStopCode_null返回false() {
        assertFalse(UsageRefreshService.isStopCode(null))
    }

    // ---------- 停止策略根源：stop-code 失败停止 vs 非 stop-code 仅退避 ----------

    /** 简单 fake：固定 status/body，或抛异常模拟网络错误。 */
    private class FakeHttp(val status: Int, val body: String, val throwOnCall: Boolean = false) : HttpExecutor {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            if (throwOnCall) throw RuntimeException("network")
            return HttpResponse(status, body)
        }
    }

    /** 简单 fake：内存 map 模拟按 accountId 分键缓存。 */
    private class FakeCache : CacheStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun read(accountId: String): String? = map[accountId]
        override suspend fun write(accountId: String, text: String) { map[accountId] = text }
        override suspend fun clear(accountId: String) { map.remove(accountId) }
    }

    private val glmAccount = Account("a1", "glm", "GLM", Credential.Raw("k"), "CN")

    @Test
    fun refresh_AUTH失败_停止前台与后台自动刷新() = runBlocking {
        val rs = UsageRefreshService(
            account = glmAccount,
            provider = ServiceProviders.byId(glmAccount.providerId),
            http = FakeHttp(401, ""),  // 401 → AUTH ∈ STOP_CODES
            cacheStorage = FakeCache(),
            getSettings = { UsageRefreshService.RefreshSettings(autoRefreshEnabled = true) }
        )
        rs.refresh(UsageRefreshService.Reason.MANUAL)

        assertFalse(rs.canRefresh(UsageRefreshService.Reason.FOREGROUND))
        assertFalse(rs.canRefresh(UsageRefreshService.Reason.BACKGROUND))
    }

    @Test
    fun refresh_NETWORK失败_不停止_前台仍可刷新() = runBlocking {
        val rs = UsageRefreshService(
            account = glmAccount,
            provider = ServiceProviders.byId(glmAccount.providerId),
            http = FakeHttp(0, "", throwOnCall = true),  // 抛异常 → NETWORK ∉ STOP_CODES
            cacheStorage = FakeCache(),
            getSettings = { UsageRefreshService.RefreshSettings(autoRefreshEnabled = true) }
        )
        rs.refresh(UsageRefreshService.Reason.MANUAL)

        // NETWORK 非 stop-code：不进入停止态；失败一次未达退避阈值，故前台仍可刷
        assertTrue(rs.canRefresh(UsageRefreshService.Reason.FOREGROUND))
    }
}
