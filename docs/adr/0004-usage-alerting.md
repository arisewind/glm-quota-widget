# ADR-0004：额度告警系统（v3.0 两档骨架 + v3.2 恢复通知）

- 状态：Accepted（v3.0 两档告警已实施；v3.2 增第三档恢复通知 + 滞回死区；v3.4 阈值随 [ADR-0007](0007-range-linear-3tab.md) 收敛进 `UsageThresholds`）
- 日期：2026-07-21
- 关联：[ADR-0001](0001-glm-coding-plan-usage-direct-key.md) / [ADR-0002](0002-multi-provider-normalization.md) / [ADR-0007](0007-range-linear-3tab.md) / [REVIEW.md](../REVIEW.md)

---

## 背景（Context）

v2.x 只有「拉到用量 → 展示」，用户必须主动打开 App / 看 widget 才知道额度状态，额度耗尽时无任何主动提示。v3.0 要补上「额度耗尽 / 低额度」的主动触达。

方向上先排除了两种方案：

1. **App 内弹窗 / Toast** —— 用户不在这台屏上、或 App 在后台时完全看不到，覆盖太低。
2. **Widget 变色 + 单一阈值** —— 被动展示，仍需用户看向屏幕。

最终方向定为 **系统通知**（横幅、响铃、震动、锁屏可见、通知中心留存），这是 Android 上唯一能「App 在后台也能主动打断用户」的合法通路，与低额度预警一并打包实现。

刷新链路已有两个稳定触发点：前台手动刷新（`UsageViewModel.refresh`）、App 回前台静默刷新（`refreshOnForeground`）、后台 `QuotaRefreshWorker.doWork` 每 30min 遍历。告警检查就挂在这三处，无需新建定时器。

---

## 决策（Decision）

### 三档通知 + 三通道（v3.0 两档，v3.2 增第三档）

| 档位 | 触发 | 通道 | 重要性 | 行为 |
|---|---|---|---|---|
| **LOW** 低额度 | `usedPercent ≥ 85` | `quota_low` | `IMPORTANCE_DEFAULT` | 默认优先级通知 |
| **EXHAUSTED** 耗尽 | `usedPercent ≥ 100` | `quota_exhausted` | `IMPORTANCE_HIGH` | 横幅 + 震动 + 响铃 |
| **RECOVERY** 已恢复（v3.2） | 从告警态真跌回 `< 80`，且非 5h 窗 | `quota_recovery` | `IMPORTANCE_LOW` | 静默通知（不响铃、不横幅） |

- 5h 窗口不发恢复通知（每 5h 重置一次太频繁，会刷屏）；只有周 / 月窗发。
- 阈值常量 `LOW_THRESHOLD = UsageThresholds.DANGER = 85`，`RECOVERY_THRESHOLD = LOW_THRESHOLD - 5 = 80`（见 Consequences 的滞回死区）。

### 去重（防刷屏）：AlertStateStore armed 档位

每「账户 × 窗口」在 `AlertStateStore`（普通 SharedPreferences `glm_quota_alerts`）记一个 **已通知到的最高档位**（`TIER_NONE=0 / TIER_LOW=1 / TIER_EXHAUSTED=2`）。每次刷新只在 **升到更严重档位**（`tier > armed`）时才发新通知并更新 armed；同档位或降级（未到 NONE）不重发。每账户每窗口一个稳定通知 id：

```
notifyId = (accountId.hashCode() and 0x3FFF) * 10 + kind.ordinal
```

升档时复用同一 id，系统通知以「替换」语义刷新，不会堆出多条。

### 滞回死区（消除 84↔86 抖动）—— v3.2

Worker 每 30min 刷新一次，用量在 85% 边界附近徘徊时（如 84→86→84→86），若「过 85 告警、跌回 84 就 cancel」会让通知反复出现/消失。引入滞回死区：

- **`≥ 85` ↑ 才告警**（`LOW_THRESHOLD`）
- **`< 80` ↓ 才算「真恢复」**（`RECOVERY_THRESHOLD = 80`）
- **中间 80–84 死区完全不动**：不 cancel 旧告警、不清 armed、不发新通知。这样 84↔86 徘徊时 armed 保持 LOW、通知稳定显示。

真跌到 `< 80` 才一次性：cancel 旧告警 → `clear` armed（重新 arm）→（周/月窗）发 RECOVERY 通知。

### 恢复发送判定（纯函数，可单测）

`shouldNotifyRecovery(armed, kind, usedPercent, lowEnabled, exhaustedEnabled)` 全部满足才发：之前真告警过（`armed > NONE`）&& 非 5h 窗 && 已真恢复（`< 80`）&& 该 armed 档对应的开关仍开（避免「关了告警还收到恢复通知」）。

### 触发点

`UsageAlerter.check(snapshot, account)` 在三处调用，且仅对 `status == OK` 的快照生效（stale / error 不告警，防误报）：

- `UsageViewModel.refresh`（手动刷新，`Reason.MANUAL`）
- `UsageViewModel.refreshOnForeground`（App 回前台，`Reason.FOREGROUND`）
- `QuotaRefreshWorker.doWork`（后台 30min 遍历，`Reason.BACKGROUND`）

### 通知权限与生命周期兜底

- **Android 13+ `POST_NOTIFICATIONS` 运行时权限**：`MainActivity.onCreate` 检测未授权时 `requestPermissions`（requestCode `1001`）。权限拒绝时通道写不进通知，但 armed 状态与日志照常记录，权限补开后再触发。
- **通知日志**：`NotificationLogStore`（`glm_quota_notif_log`，JSON 序列化，最近 200 条、按时间倒序）。系统通知是瞬时的（划掉 / 被清理 / IMPORTANCE_LOW 没注意到就没了），App 内留存事件流供通知记录页回看，与趋势图互补（趋势是数值曲线，这里是事件流）。`append` 用 `synchronized` 锁防 VM 主线程与 Worker 后台线程并发 read-modify-write 丢更新。
- **孤儿通知清理**：`onAccountRemoved` 清该账户 armed + cancel 残留通知；`onAllArmedClear`（关告警时）清全部 armed + cancel 所有告警通知，防重开后幽灵「已恢复」通知。

### 开关

v3.2 把 v3.0/v3.1 的单告警开关拆成两档独立开关（`alertLowEnabled` / `alertExhaustedEnabled`，均默认开），并一次性迁移老 key（关过告警的老用户升级后保持关闭）。总开关 `alertEnabled()` 派生（两档都关才 false），供 alerter 顶部早退。

---

## 已知 UX 边界（暂不根治）

**armed 与通知栏解耦**：armed 持久化在 SharedPreferences，但用户手动划掉通知或进程被杀后通知栏被清空时 App 无回调，armed 仍保留；下次刷新若已恢复可能发一条「无源头」的恢复通知。这是去重机制的固有代价，根治需 `setDeleteIntent` 同步清 armed，工程成本高、收益低，暂不做。

---

## 备选方案（Alternatives，均已否决）

- **App 内弹窗 / Toast**：后台不可见，覆盖低。
- **单一阈值 + 无去重**：30min 刷新会重复发同一档通知，刷屏。
- **5h 窗也发恢复通知**：5h 周期太短，频繁重置导致恢复通知刷屏，故只对周 / 月窗发。

---

## 后果（Consequences）

**正面**
- 用户无需主动查看即可感知额度耗尽 / 低额度，达成 v3.0 主动触达目标。
- 滞回死区消除边界抖动，通知稳定不闪烁。
- 通知日志补齐「瞬时通知丢失就无处回看」的短板，与趋势图形成数值 + 事件双视角。
- armed 去重 + 孤儿清理让多账户 / 多窗口 / 频繁刷新下通知数量可控。

**负面 / 风险**
- **依赖通知权限**：Android 13+ 用户拒绝授权则告警静默失效（权限引导见 [ADR-0005](0005-settings-system-guide.md) 系统引导组）。
- armed 与通知栏解耦的「无源头恢复通知」边界（见上）。
- IMPORTANCE_HIGH 耗尽通知会打断用户，若 100% 边界抖动需依赖升档去重 + 死区抑制（已覆盖）。

---

## 关联代码

- `services/UsageAlerter.kt` —— 三档通知 + 滞回死区 + 去重主逻辑，`tierOf` / `shouldNotifyRecovery` 为纯函数供单测。
- `services/AlertStateStore.kt` —— armed 档位去重状态。
- `services/NotificationLogStore.kt` —— 通知事件流（200 条、带锁）。
- `services/SettingsStore.kt` —— 两档开关 + 老开关迁移。
- 触发点：`ui/UsageViewModel.kt`（`refresh` / `refreshOnForeground`）、`widget/QuotaRefreshWorker.kt`（`doWork`）。
- 权限申请：`MainActivity.kt` `onCreate`。
- 阈值真源：`domain/UsageThresholds.kt`（[ADR-0007](0007-range-linear-3tab.md) 收敛）。

## 参考依据

- Android 通知通道重要性：[NotificationManager.IMPORTANCE_*](https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_HIGH)
- Android 13 运行时通知权限：https://developer.android.com/about/versions/13/changes/notification-permission
