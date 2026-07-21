# ADR-0005：设置独立页 + 系统引导（v3.2）

- 状态：Accepted（v3.2 已实施；v3.3 主题切换接入 NordVPN 配色，见 [ADR-0006](0006-nordvpn-color-system.md)）
- 日期：2026-07-21
- 关联：[ADR-0004](0004-usage-alerting.md) / [ADR-0006](0006-nordvpn-color-system.md) / [ADR-0007](0007-range-linear-3tab.md) / [REVIEW.md](../REVIEW.md)

---

## 背景（Context）

两个痛点促成 v3.2 的设置页重构：

1. **v2.x 设置散落**：后台刷新开关塞在账户页、告警开关无处安放、关于信息零碎。功能越加越多，用户找不到「在哪关后台刷新 / 告警 / 看版本」，也无处统一管理。
2. **华为 / HarmonyOS ROM 的省电限制是 widget 后台刷新「不工作」的头号真因**，但 App 此前没有任何引导。用户加了 widget 发现「半小时不更新」，去 Issue 反馈，根因其实是系统把 `QuotaRefreshWorker` 冻结了——这必须靠用户手动改系统设置（电池白名单 / 启动管理），App 代码层面无法绕过。需要一个产品化的引导，把「去哪点、点哪个开关」讲清楚，而不是让用户自己摸系统设置。

---

## 决策（Decision）

### SettingsScreen 独立页，6 组结构

设置从账户页剥离，独立成页，6 个分组：

| 组 | 内容 |
|---|---|
| **刷新** | 后台刷新全部账户开关（`backgroundRefreshAll`，默认关 = 仅刷 active，省电 + 降低风控） |
| **显示** | 主题切换（浅色 / 深色 / 跟随系统，`ThemePicker`） |
| **告警** | 低额度提醒开关（≥85%）、额度耗尽开关（100% 横幅+震动，恢复时通知） |
| **系统引导** | 通知权限、电池优化白名单、华为启动管理（条件渲染） |
| **数据** | 清除全部账户与数据 |
| **关于** | 版本、GitHub 仓库、免责声明 |

卡片底统一用 M3 `surfaceContainerLow` + 20dp 圆角（[ADR-0006](0006-nordvpn-color-system.md)）。

### 系统引导组：3 项 Intent 跳转

每项是「状态检测 + 跳转按钮」的 `StatusCard`：左侧标题/副标题，右侧状态标签（已开启/未开启，颜色随 `isOkay` 切 primary/error）+ 动作按钮。状态在 `ON_RESUME` 重读（`DisposableEffect` + `LifecycleEventObserver`），用户从系统设置返回后状态即时刷新。

**1. 通知权限**（[ADR-0004](0004-usage-alerting.md) 告警的前提）

```kotlin
Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
```

状态：Android 13+ 检查 `POST_NOTIFICATIONS` 运行时授权（13 以下视为已授权）。

**2. 电池优化白名单**（widget 后台刷新不被冻结的关键）

```kotlin
Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    .data = Uri.parse("package:${ctx.packageName}")
```

状态：`PowerManager.isIgnoringBatteryOptimizations(packageName)`。

**3. 华为启动管理**（条件渲染，仅 `Build.MANUFACTURER == "HUAWEI"` 显示）

华为的「启动管理」三开关（自启动 / 关联启动 / 后台活动）默认全关，是华为设备上 Worker 被杀的根因。它没有标准 SDK Intent，只能尝试已知 Component 三级 fallback，每级 `resolveActivity` 过滤：

```kotlin
val candidates = listOf(
    ComponentName("com.huawei.systemmanager", "...StartupNormalAppListActivity"),
    ComponentName("com.huawei.systemmanager", "...StartupAppControlActivity"),
    ComponentName("com.huawei.systemmanager", "...ProtectActivity")
)
// 第一个 resolveActivity != null 的；全 null → 应用详情 → 兜底系统设置根
```

fallback 链：华为系统管理器已知 Activity → `ACTION_APPLICATION_DETAILS_SETTINGS`（本应用详情页）→ `ACTION_SETTINGS`（系统设置根）。每跳都用 `runCatching` + `recoverCatching` 兜底，任一 ROM 不认都不崩。

### 主题切换 + 持久化

`ThemePicker` 用 M3 `SingleChoiceSegmentedButtonRow`（浅色 / 深色 / 跟随系统），选中写 `SettingsStore.themeMode`（`THEME_LIGHT / THEME_DARK / THEME_SYSTEM`，默认 system）。`MyApplicationTheme` 读 `themeMode` 决定 `dark` 分支（[ADR-0006](0006-nordvpn-color-system.md)）。

---

## 备选方案（Alternatives，均已否决）

- **设置仍混在账户页**：随功能增加会越来越挤、难找，违背「设置集中」。
- **系统引导放 FAQ / 外部网页**：用户大概率不看；放设置页「就在手边」转化率高，且状态检测能告诉用户「到底配没配好」。
- **华为启动管理直接 `startActivity` 不 `resolveActivity`**：非华为 ROM 或老版 EMUI 上 Component 不存在会抛 `ActivityNotFoundException`，必须 fallback。

---

## 后果（Consequences）

**正面**
- 华为 / HarmonyOS 后台限制的**产品化正解**：不靠代码绕（绕不过），靠引导用户改对系统设置，把「widget 不刷新」的头号客诉根因消化在设置页。
- 设置集中、分组清晰，新增设置项有处安放。
- 主题可控（用户偏好 / 跟随系统），为 [ADR-0006](0006-nordvpn-color-system.md) 配色与 [ADR-0008](0008-widget-theme-follow.md) widget 跟随主题奠定开关基础。
- 状态 `ON_RESUME` 重读，用户改完系统设置回来立刻看到「已开启」，闭环清晰。

**负面 / 风险**
- 依赖各家 ROM 的非公开 Activity（尤其华为系统管理器）：ROM 升级可能改 Component 名，fallback 链需维护。
- `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 是直接弹系统确认（用户体验略重），但比让用户自己在设置里翻省电设置好。
- 华为引导文案（「三开关全开」）随 ROM 版本可能措辞变化，需用户对齐。

---

## 关联代码

- `SettingsScreen.kt` —— 6 组设置页 + 系统引导 Intent 跳转 + `ON_RESUME` 状态检测。
- `services/SettingsStore.kt` —— `backgroundRefreshAll` / `alertLowEnabled` / `alertExhaustedEnabled` / `themeMode`（+ v3.5 `primaryWindowKind` / `lastSeenNotificationAt`）。
- 主题消费：`ui/theme/Theme.kt` `MyApplicationTheme`。

## 参考依据

- 电池优化白名单：https://developer.android.com/training/monitoring-device-state/doze-standby#support-for-other-use-cases
- 应用通知设置 Intent：`Settings.ACTION_APP_NOTIFICATION_SETTINGS`
- 华为启动管理 ROM 行为：华为 / HarmonyOS 开发者文档 + 实机验证（`com.huawei.systemmanager` Component 链）。
