package com.example.myapplication.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.domain.Account
import com.example.myapplication.domain.UsageSnapshot
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind

/**
 * 额度告警（v3.0）：刷新拿到成功快照后检查，超阈值发系统通知。
 *
 * **两档**：
 * - 85%–99% → 低额度警告（[CHANNEL_LOW]，默认优先级）
 * - 100% → 额度耗尽（[CHANNEL_EXHAUSTED]，IMPORTANCE_HIGH 横幅+震动）
 *
 * **去重**（防刷屏）：每账户每窗口记"已通知到的最高档位"（[AlertStateStore]），
 * 只在升到更严重档位时再通知；降到 <85% 清除、重新 arm。
 * 故 85% 通知后涨到 100% 会**再通知一次**（状态升级），但 90%→92% 不会刷屏。
 */
class UsageAlerter(
    context: Context,
    private val state: AlertStateStore = AlertStateStore(context),
    private val settings: SettingsStore = SettingsStore(context)
) {
    private val appContext = context.applicationContext
    private val nm = appContext.getSystemService(NotificationManager::class.java)

    init {
        ensureChannels()
    }

    /** 刷新成功后调用：检查所有窗口，按需通知。非 OK 快照（stale/error）忽略。 */
    fun check(snapshot: UsageSnapshot, account: Account) {
        if (!settings.alertEnabled()) return
        if (snapshot.status != UsageStatus.OK) return
        snapshot.windows.forEach { window ->
            val tier = tierOf(window.usedPercent)
            val armed = state.armedTier(account.accountId, window.kind)
            when {
                tier == AlertStateStore.TIER_NONE -> {
                    nm.cancel(notifyId(account.accountId, window.kind)) // 用量恢复 → 取消旧警告通知
                    state.clear(account.accountId, window.kind)        // 降下 → 重新 arm
                }
                tier > armed -> {                                        // 升到更严重档位 → 通知
                    notify(account, window.kind, window.usedPercent, tier)
                    state.setArmed(account.accountId, window.kind, tier)
                }
                // tier <= armed（同档位或降级未到 NONE）→ 不通知
            }
        }
    }

    /** 账户删除时清其 armed 状态。 */
    fun onAccountRemoved(accountId: String) = state.clearAccount(accountId)

    private fun tierOf(percent: Int): Int = when {
        percent >= 100 -> AlertStateStore.TIER_EXHAUSTED
        percent >= LOW_THRESHOLD -> AlertStateStore.TIER_LOW
        else -> AlertStateStore.TIER_NONE
    }

    private fun notify(account: Account, kind: WindowKind, percent: Int, tier: Int) {
        val exhausted = tier == AlertStateStore.TIER_EXHAUSTED
        val channelId = if (exhausted) CHANNEL_EXHAUSTED else CHANNEL_LOW
        val title = if (exhausted) "额度已耗尽" else "额度即将耗尽"
        val text = if (exhausted) "${account.label} · ${windowName(kind)}已用尽"
                   else "${account.label} · ${windowName(kind)}已用 ${percent}%"
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (exhausted) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(notifyId(account.accountId, kind), notification)
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_LOW, "低额度提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "额度用量达 85% 提醒" })
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_EXHAUSTED, "额度耗尽", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "额度 100% 耗尽紧急提醒（横幅+震动）" })
        }
    }

    private fun windowName(kind: WindowKind) = when (kind) {
        WindowKind.FIVE_HOUR -> "5 小时额度"
        WindowKind.WEEKLY -> "本周额度"
        WindowKind.MONTHLY -> "本月额度"
    }

    /** 每账户每窗口一个稳定通知 id（accountId 高 14 位 × 10 + 窗口序号）。 */
    private fun notifyId(accountId: String, kind: WindowKind) =
        (accountId.hashCode() and 0x3FFF) * 10 + kind.ordinal

    companion object {
        const val LOW_THRESHOLD = 85
        private const val CHANNEL_LOW = "quota_low"
        private const val CHANNEL_EXHAUSTED = "quota_exhausted"
    }
}
