# ADR-0007：续航表线性卡 + 3-tab 底栏 + 阈值单一真源（v3.4）

- 状态：Accepted（v3.4 已实施；阈值 `DANGER` 复用进 [ADR-0004](0004-usage-alerting.md) 告警档位；后续 v3.5 在此骨架上精化主卡 / mini 卡样式）
- 日期：2026-07-21
- 关联：[ADR-0002](0002-multi-provider-normalization.md) / [ADR-0004](0004-usage-alerting.md) / [ADR-0006](0006-nordvpn-color-system.md) / [REVIEW.md](../REVIEW.md)

---

## 背景（Context）

v2.x 的用量页是单屏堆叠：5h 窗、周窗、模型明细、趋势卡全部纵向铺在一个可滚动页里，设置 / 账户管理也混在其中（账户切换是顶栏 chip）。随功能增多暴露三个问题：

1. **主次不分**：用户最关心的「现在还能用多久（续航）」和次要的「模型明细 / 趋势」平铺，信息密度低、找不到重点。
2. **导航混乱**：账户、设置、用量挤一屏，chip 切账户在账户多时拥挤；没有清晰的「这是主功能、那是配置」分层。
3. **阈值发散**：`60 / 85` 这对阈值在 `usageColorFor` / `usageColorInt`（widget）/ `UsageAlerter` / `WeeklyTrendCard` **4 处各自手抄**，改一处漏三处，颜色与告警档位会悄悄对不齐。

用户诉求：要一个「续航表」式的总览（一眼看还剩多少），以及底栏导航分层。

---

## 决策（Decision）

### 1. 续航表用线性卡（非环形 gauge）

主用量展示用 `LinearProgressIndicator`（横向进度条 + 大号剩余百分比 + 档位胶囊 + 已用/重置时间），**不用环形 / 仪表盘 gauge**。理由：

- **信息密度高**：线性条能并排挤进「已用 %」「剩余 %」「重置时间」「档位胶囊」多条信息，环形 gauge 中心只能塞一个数字。
- **更直观**：横向条「还剩多长」与「续航」心智一致，环形需要二次解读角度。
- **多窗口友好**：多个窗口（5h / 周 / 月 / 工具）用线性 mini 卡可横向并排，环形并排则拥挤。

布局：一张主卡（用户偏好窗口，v3.5 可点 mini 升主）+ 其余窗口 mini 行并排，全部走 [ADR-0006](0006-nordvpn-color-system.md) 的 `surfaceContainerLow` 卡底 + 用量色进度。

### 2. 3-tab 底栏 + pushed 子页

主导航抽成 3 个平级 tab（`ui/Navigation.kt`）：

| Tab | 标签 | 内容 |
|---|---|---|
| `RANGE` | 续航 | 用量主屏（续航表 + 趋势 + 模型明细） |
| `ACCOUNTS` | 账户 | 账户管理（切换 / 重命名 / 删除 / 添加） |
| `SETTINGS` | 设置 | 设置页（[ADR-0005](0005-settings-system-guide.md)） |

覆盖在 tab 之上的「子页」（有系统返回、非平级）抽成 `PushedScreen` 枚举：`ADD_ACCOUNT`（添加账户）、`NOTIFICATIONS`（通知记录）。

**导航实现**（`MainActivity.AppScaffold`）：`tab` 与 `pushed` 用 `rememberSaveable` 持久化；`pushed != null` 时渲染子页、覆盖底栏，`BackHandler(enabled = pushed != null) { pushed = null }` 拦截系统返回键——**在子页按返回回 tab，而不是退出 App**。`Loading / Unconfigured` 状态在顶层渲染（无底栏），只有 `Content` 进 `AppScaffold` 骨架。

底栏 `NavigationBar` + `NavigationBarItem`（Home / AccountCircle / Settings 图标）。顶栏的账户切换从「FilterChip」改为「标题可点 + 箭头」跳账户 tab，刷新 / 通知用 `IconButton`（v3.5 精化为 owlmeter 风格 `FilledTonalIconButton`）。

### 3. UsageThresholds：阈值单一真源

把 4 处发散的 `60/85` 收敛进 `domain/UsageThresholds.kt`：

```kotlin
object UsageThresholds {
    const val WARN = 60      // >= 60 橙
    const val DANGER = 85    // > 85 红
    fun tierOf(usedPercent: Int): UsageTier = …   // → SAFE / WARN / DANGER
}
enum class UsageTier { SAFE, WARN, DANGER }
```

UI（`usageColorFor`）、widget（`usageColorInt`）、告警（`UsageAlerter.LOW_THRESHOLD = DANGER`）、趋势卡（`WeeklyTrendCard` 85% 告警线）全部引用这一处常量，改阈值改一行、四处同步。告警的 `RECOVERY_THRESHOLD = DANGER - 5` 也派生自此处（[ADR-0004](0004-usage-alerting.md)）。

### 4. UsageMath：纯函数 + 工具调用额度独立维度

抽 `domain/UsageMath.kt` 两个纯函数，UI 与 widget 共用，消除多处重抄 fallback 逻辑：

```kotlin
fun UsageSnapshot.primaryPercent(): Int   // 5h 优先，否则「非工具窗」最大值；无快照 0
fun UsageSnapshot.primaryWindow(): NormalizedWindow?  // 同上规则取窗口
```

**关键：排除 `TOOLS` 窗口**。`primaryPercent / primaryWindow` 用 `windows.filter { it.kind != WindowKind.TOOLS }`——因为 token 额度（5h / 周 / 月）与工具调用额度是**两个维度**：一个是 token 消耗百分比，一个是次数配额，混在一起取 max 会拿「工具用了几次」顶掉「token 用了 90%」，主用量语义错乱。

### 5. WindowKind 加 TOOLS（工具调用额度）

`WindowKind` 枚举增第四项 `TOOLS("工具调用额度")`，承载智谱 `TIME_LIMIT unit:5` 的工具调用次数配额（实测 `usageDetails` 按 modelCode 细分，UI 表「X 次剩余」而非百分比）。TOOLS 作为独立维度展示在续航表 mini 卡，不参与 `primaryPercent`。

---

## 备选方案（Alternatives，均已否决）

- **环形 / 仪表盘 gauge 做主卡**：信息密度低、多窗口难并排，否决（见上）。
- **保留单屏堆叠 + chip 切账户**：导航不分层、阈值继续发散，不解决根因。
- **把 TOOLS 纳入 primaryPercent**：维度混淆，token 与次数不可比，会让主用量错乱。
- **阈值继续各处手抄**：维护负债，已证实会悄悄对不齐。

---

## 后果（Consequences）

**正面**
- 导航清晰：续航（主功能）/ 账户 / 设置（配置）三层分明，pushed 子页返回行为符合直觉。
- 续航表线性卡信息密度高，「还剩多少」一眼可读。
- **阈值单一真源**防发散：颜色、告警、趋势线四处永远对齐，改一行全同步。
- 工具调用额度作为独立维度正确呈现，不污染 token 主用量。
- `UsageMath` 纯函数化，UI 与 widget 共享 fallback 规则，单测好写。

**负面 / 风险**
- 线性卡 + mini 行在小屏 / 窗口多时（5h + 周 + 月 + 工具 4 个）mini 会较窄，需权衡展示顺序（v3.5 主/升主交互缓解）。
- `TOOLS` 作为新窗口维度，Provider 解析须正确归类 `TIME_LIMIT unit:5`，误归会进 `primaryPercent`（已用 `filter != TOOLS` 兜底）。

---

## 关联代码

- `domain/UsageThresholds.kt` —— 阈值单一真源 + `tierOf` / `UsageTier`。
- `domain/UsageMath.kt` —— `primaryPercent` / `primaryWindow`（排除 TOOLS）。
- `domain/Models.kt` —— `WindowKind`（含 `TOOLS`）。
- `ui/Navigation.kt` —— `Tab` / `PushedScreen` 枚举。
- `MainActivity.kt` —— `AppScaffold`（3-tab + pushed + `BackHandler`）/ `AppBottomBar`（`NavigationBar`）/ `RangePrimaryCard` + `RangeMiniRow`（线性卡）。
- 复用：`widget/UsageColors.kt`（`usageColorInt` 走 `UsageThresholds`）、`services/UsageAlerter.kt`（`LOW_THRESHOLD = DANGER`）。

## 参考依据

- Material 3 `NavigationBar`：https://m3.material.io/components/navigation-bar/overview
- 滞回 / 阈值单一真源思想：参见 [ADR-0004](0004-usage-alerting.md) 告警档位复用 `DANGER`。
