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
import com.example.myapplication.domain.UsageThresholds
import com.example.myapplication.domain.WindowKind

/**
 * 额度告警（v3.0 + 恢复通知 v3.2）：刷新拿到成功快照后检查，超阈值发系统通知。
 *
 * **三档通知**：
 * - 85%–99% → 低额度警告（[CHANNEL_LOW]，默认优先级）
 * - 100% → 额度耗尽（[CHANNEL_EXHAUSTED]，IMPORTANCE_HIGH 横幅+震动）
 * - 从告警态真恢复（< [RECOVERY_THRESHOLD]）→ 额度已恢复（[CHANNEL_RECOVERY]，IMPORTANCE_LOW 静默；仅周/月窗，5h 重置太频繁不发）
 *
 * **去重**（防刷屏）：每账户每窗口记"已通知到的最高档位"（[AlertStateStore]），只在升到更严重档位时再通知。
 *
 * **滞回死区**（消除 30min 刷新下 85 附近抖动）：
 * - [LOW_THRESHOLD](85)↑ 才告警、[RECOVERY_THRESHOLD](80)↓ 才算"真恢复"
 * - 中间 80–84 死区**完全不动**：不 cancel 旧告警、不清 armed、不发新通知。
 *   这样 84↔86 徘徊时 armed 保持 LOW、通知稳定显示，不会反复出现/消失/重发。
 * - 真跌到 <80 才一次性 cancel 旧告警 + clear armed 重新 arm + （周/月窗）发恢复通知。
 *
 * **armed 与通知栏解耦（已知 UX 边界，暂不根治）**：armed 持久化在 SharedPreferences，
 * 但用户手动划掉通知或进程被杀后通知栏被清空时 app 无回调，armed 仍保留；下次刷新若已恢复
 * 可能发一条"无源头"的恢复通知。这是去重机制的固有代价（根治需 setDeleteIntent 同步清 armed，
 * 工程成本高、收益低）。
 */
class UsageAlerter(
    context: Context,
    private val state: AlertStateStore = AlertStateStore(context),
    private val settings: SettingsStore = SettingsStore(context),
    private val notificationLog: NotificationLogStore = NotificationLogStore(context)
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
                    if (window.usedPercent < RECOVERY_THRESHOLD) {
                        // 真恢复（<80）：取消旧告警 + 清 armed 重新 arm + （周/月窗）发恢复通知
                        nm.cancel(notifyId(account.accountId, window.kind))
                        state.clear(account.accountId, window.kind)
                        if (shouldNotifyRecovery(armed, window.kind, window.usedPercent, settings.alertLowEnabled(), settings.alertExhaustedEnabled())) {
                            notifyRecovery(account, window.kind, window.usedPercent, armed)
                        }
                    }
                    // else：80-84 死区，完全不动（保留 armed + 通知，消除 84↔86 抖动）
                }
                tier > armed -> {                                        // 升到更严重档位 → 按对应开关通知
                    val enabled = if (tier == AlertStateStore.TIER_EXHAUSTED)
                        settings.alertExhaustedEnabled() else settings.alertLowEnabled()
                    if (enabled) {
                        notify(account, window.kind, window.usedPercent, tier)
                        state.setArmed(account.accountId, window.kind, tier)
                    }
                    // 对应档开关关：不 notify 不 setArmed，开开关后下次刷新再触发
                }
                // tier <= armed（同档位或降级未到 NONE）→ 不通知
            }
        }
    }

    /** 账户删除时清其 armed 状态 + 取消残留通知，防孤儿通知。 */
    fun onAccountRemoved(accountId: String) {
        state.clearAccount(accountId)
        WindowKind.entries.forEach { kind -> nm.cancel(notifyId(accountId, kind)) }
    }

    /**
     * 关告警时清全部 armed + 取消所有已显示告警通知，防重开后幽灵"已恢复"通知 + 通知栏残留打扰。
     * [accountIds] 由调用方从 AccountStore 传入（alerter 不持有账户列表）。
     */
    fun onAllArmedClear(accountIds: List<String>) {
        accountIds.forEach { accountId ->
            WindowKind.entries.forEach { kind -> nm.cancel(notifyId(accountId, kind)) }
        }
        state.clearAll()
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
        notificationLog.append(NotificationLogEntry(
            timestamp = System.currentTimeMillis(),
            accountLabel = account.label,
            providerId = account.providerId,
            windowKind = kind,
            type = if (exhausted) NotificationType.EXHAUSTED else NotificationType.LOW,
            percent = percent
        ))
    }

    /** 用量从告警态真恢复（仅周/月窗）：低优先级通知，复用告警同一 notifyId（同 id 替换语义）。 */
    private fun notifyRecovery(account: Account, kind: WindowKind, percent: Int, wasTier: Int) {
        val title = if (wasTier == AlertStateStore.TIER_EXHAUSTED) "额度已恢复可用" else "额度已回到正常"
        val text = "${account.label} · ${windowName(kind)}用量回到正常（${percent}%）"
        val notification = NotificationCompat.Builder(appContext, CHANNEL_RECOVERY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        nm.notify(notifyId(account.accountId, kind), notification)
        notificationLog.append(NotificationLogEntry(
            timestamp = System.currentTimeMillis(),
            accountLabel = account.label,
            providerId = account.providerId,
            windowKind = kind,
            type = NotificationType.RECOVERY,
            percent = percent
        ))
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_LOW, "低额度提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "额度用量达 85% 提醒" })
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_EXHAUSTED, "额度耗尽", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "额度 100% 耗尽紧急提醒（横幅+震动）" })
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_RECOVERY, "额度恢复正常", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "用量从告警态降回正常（低优先级、不响铃、不横幅）" })
        }
    }

    private fun windowName(kind: WindowKind) = kind.displayName

    /** 每账户每窗口一个稳定通知 id（accountId 高 14 位 × 10 + 窗口序号）。 */
    private fun notifyId(accountId: String, kind: WindowKind) =
        (accountId.hashCode() and 0x3FFF) * 10 + kind.ordinal

    companion object {
        const val LOW_THRESHOLD = UsageThresholds.DANGER
        const val RECOVERY_THRESHOLD = LOW_THRESHOLD - 5 // 滞回死区下限：>=85↑告警、<80↓真恢复；80-84 死区完全不动

        /** 用量档位：>=100 耗尽 / >=85 低额度 / else 正常。纯逻辑，供单测。 */
        internal fun tierOf(percent: Int): Int = when {
            percent >= 100 -> AlertStateStore.TIER_EXHAUSTED
            percent >= LOW_THRESHOLD -> AlertStateStore.TIER_LOW
            else -> AlertStateStore.TIER_NONE
        }

        /**
         * 是否发恢复通知：之前真告警过(armed>NONE) && 非 5h 窗口 && 已真恢复(<80) && wasTier 对应档开关仍开。
         * 纯逻辑，供单测。wasTier=armed（之前告警到的档位），其对应开关关则不发（避免"关了告警还收到恢复通知"）。
         */
        internal fun shouldNotifyRecovery(
            armed: Int, kind: WindowKind, usedPercent: Int,
            lowEnabled: Boolean, exhaustedEnabled: Boolean
        ): Boolean {
            if (armed <= AlertStateStore.TIER_NONE) return false
            if (kind == WindowKind.FIVE_HOUR) return false
            if (usedPercent >= RECOVERY_THRESHOLD) return false
            return if (armed == AlertStateStore.TIER_EXHAUSTED) exhaustedEnabled else lowEnabled
        }

        private const val CHANNEL_LOW = "quota_low"
        private const val CHANNEL_EXHAUSTED = "quota_exhausted"
        private const val CHANNEL_RECOVERY = "quota_recovery"
    }
}
