package com.example.myapplication.services

import com.example.myapplication.domain.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UsageAlerter 纯逻辑单测（v3.2 恢复通知 + 告警分档开关）。
 *
 * 覆盖 companion 纯函数 [UsageAlerter.tierOf]（用量→档位）与 [UsageAlerter.shouldNotifyRecovery]
 * （恢复通知触发条件：armed>NONE && 非 5h && <80 && wasTier 对应档开关开）的数值边界。
 *
 * 通知副作用（nm.notify/cancel）依赖 Android NotificationManager，完整测试需 Robolectric，
 * 不在 host JVM 单测范围；纯决策逻辑抽到 companion 正是为绕过该限制、给状态机留回归安全网。
 */
class UsageAlerterTest {

    // ---- tierOf：用量→档位 ----

    @Test
    fun tierOf_boundaries() {
        assertEquals(AlertStateStore.TIER_EXHAUSTED, UsageAlerter.tierOf(100))
        assertEquals(AlertStateStore.TIER_LOW, UsageAlerter.tierOf(99))
        assertEquals(AlertStateStore.TIER_LOW, UsageAlerter.tierOf(85))  // >=85 上界
        assertEquals(AlertStateStore.TIER_NONE, UsageAlerter.tierOf(84)) // <85 下界
        assertEquals(AlertStateStore.TIER_NONE, UsageAlerter.tierOf(0))
    }

    // ---- shouldNotifyRecovery：默认两档开 ----

    @Test
    fun recovery_weeklyStandard_true() {
        assertEquals(true, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_LOW, WindowKind.WEEKLY, 79, true, true))
    }

    @Test
    fun recovery_exhaustedCrossTier_true() {
        assertEquals(true, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_EXHAUSTED, WindowKind.WEEKLY, 50, true, true))
    }

    @Test
    fun recovery_monthly_true() {
        assertEquals(true, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_LOW, WindowKind.MONTHLY, 70, true, true))
    }

    @Test
    fun recovery_firstTimeNormal_false() {
        assertEquals(false, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_NONE, WindowKind.WEEKLY, 79, true, true))
    }

    @Test
    fun recovery_deadZone80_false() {
        assertEquals(false, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_LOW, WindowKind.WEEKLY, 80, true, true))
    }

    @Test
    fun recovery_deadZone84_false() {
        assertEquals(false, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_LOW, WindowKind.WEEKLY, 84, true, true))
    }

    @Test
    fun recovery_fiveHourExcluded_false() {
        assertEquals(false, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_LOW, WindowKind.FIVE_HOUR, 10, true, true))
    }

    // ---- shouldNotifyRecovery：wasTier 对应档开关关则不发（防"关了告警还收到恢复"） ----

    @Test
    fun recovery_lowDisabled_false() {
        // armed=LOW 但 alertLow 关 → 不发 LOW 恢复
        assertEquals(false, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_LOW, WindowKind.WEEKLY, 79, lowEnabled = false, exhaustedEnabled = true))
    }

    @Test
    fun recovery_exhaustedDisabled_false() {
        // armed=EXHAUSTED 但 alertExhausted 关 → 不发 EXHAUSTED 恢复
        assertEquals(false, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_EXHAUSTED, WindowKind.WEEKLY, 50, true, false))
    }

    @Test
    fun recovery_wasTierLow_otherDisabled_stillTrue() {
        // armed=LOW，关的是 exhausted（不影响 LOW 的恢复）→ 仍发
        assertEquals(true, UsageAlerter.shouldNotifyRecovery(AlertStateStore.TIER_LOW, WindowKind.WEEKLY, 79, true, false))
    }
}
