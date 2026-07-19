package com.example.myapplication.services

import com.example.myapplication.domain.Account
import com.example.myapplication.domain.Credential
import com.example.myapplication.domain.NormalizedWindow
import com.example.myapplication.domain.UsageSource
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AccountRepository 读 facade 的纯逻辑测试（候选 2）。
 *
 * [AccountRepository.AccountSnapshot.primaryPercent] 是从 QuotaListFactory 下沉的 window fallback
 * 业务规则（5h 窗优先，缺失取所有窗最大值，无快照 0）。本测试覆盖该规则的全部分支。
 *
 * Repository 主体（active 选择 / 缓存读取）依赖 SharedPreferences + Context，完整测试需 Robolectric
 * 或 instrumented，不在本 host JVM 单测范围。
 */
class AccountRepositoryTest {

    private val account = Account("a1", "glm", "工作号", Credential.Raw("k"), "CN")

    private fun snapshot(windows: List<NormalizedWindow>) = com.example.myapplication.domain.UsageSnapshot(
        providerId = "glm",
        providerLabel = "GLM",
        windows = windows,
        updatedAt = 0L,
        source = UsageSource.DIRECT,
        status = UsageStatus.OK
    )

    @Test
    fun primaryPercent_有5h窗_取5h值_即使周窗更大() {
        val item = AccountRepository.AccountSnapshot(
            account,
            snapshot(listOf(
                NormalizedWindow(WindowKind.FIVE_HOUR, 30),
                NormalizedWindow(WindowKind.WEEKLY, 70) // 周窗更大，但 5h 优先
            ))
        )
        assertEquals(30, item.primaryPercent)
    }

    @Test
    fun primaryPercent_无5h窗_取所有窗最大值() {
        val item = AccountRepository.AccountSnapshot(
            account,
            snapshot(listOf(
                NormalizedWindow(WindowKind.WEEKLY, 19),
                NormalizedWindow(WindowKind.MONTHLY, 45)
            ))
        )
        assertEquals(45, item.primaryPercent)
    }

    @Test
    fun primaryPercent_无快照_返回0() {
        assertEquals(0, AccountRepository.AccountSnapshot(account, null).primaryPercent)
    }

    @Test
    fun primaryPercent_空窗口_返回0() {
        assertEquals(
            0,
            AccountRepository.AccountSnapshot(account, snapshot(emptyList())).primaryPercent
        )
    }
}
