# GLM Quota Widget 架构设计

- 文档版本：v3.6（2026-07-21）
- 状态：已实施（v3.0–v3.6：额度告警 / 趋势图 / 设置独立页 / UI NordVPN 化 / 续航表 3-tab / owlmeter 横向卡 / widget 主题跟随）
- 目标平台：Android（Kotlin / Jetpack Compose / AppWidget）
- 技术栈：AGP 9.3 / Kotlin 2.2.10 / compileSdk 36.1 / minSdk 26 / targetSdk 36
- 工程位置：仓库 [`android/`](../android/)
- 关联：[PRODUCT.md](PRODUCT.md) / [ROADMAP.md](ROADMAP.md) / [ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md) / [ADR-0002](adr/0002-multi-provider-normalization.md) / [ADR-0003](adr/0003-kimi-usage-direct.md)

> **平台演进**：本文档 v1.0 按 HarmonyOS 4.2（ArkTS / Form Kit）设计，后因 DevEco/hvigor 工具链网络死结转为 Android（HarmonyOS 4.2 兼容 APK）。v2.0 在 Android 上引入多服务商多账户架构。本文反映 v2.2 实际实现（含 v2.2 架构深化），v1.0 HarmonyOS 设计见 git 历史。

> **v2.2 架构深化**（2026-07-19）：Provider 层由「三家 Provider 类 + `ServiceProvider` 接口 + `Providers` 工厂」折叠为单一 `ServiceProviderConfig` 数据表 + 唯一 fetch 实现；新增 `AccountRepository` 读 facade 收敛「活跃账户选择 + 缓存读取」（消除 `"active_account_id"` 契约复制 3 份）；Worker 读 cache `errorCode` 跳过 stop-code 账户。UI/Widget/缓存总体分层不变，Provider 与账户读路径细节以代码为准，详见 [REVIEW §0](REVIEW.md)。

> **v3.x 演进**（2026-07-19~21，分层不变、新增组件）：
> - **v3.0 告警**：`UsageAlerter`（两档 85%/100% + armed 去重 + 恢复清通知）+ `AlertStateStore`（滞回死区）+ `NotificationLogStore`（通知记录 200 条）+ Android13 `POST_NOTIFICATIONS`。
> - **v3.1 趋势**：`UsageHistoryStore`（7 天用量序列）→ `WeeklyTrendCard` Canvas 折线。
> - **v3.2 设置/主题**：`SettingsScreen`（6 组含系统引导：通知权限/电池白名单/启动管理）+ `SettingsStore.themeMode`（light/dark/system）。
> - **v3.3 UI**：NordVPN 蓝 `#4687FF`（`ui/theme/Color.kt`）+ M3 语义（SegmentedButton / surfaceContainerLow / Shapes）。
> - **v3.4 续航表**：线性卡 + 3-tab 底栏（`ui/Navigation` Tab + `AppScaffold` + `AppBottomBar`）+ 工具调用额度 `WindowKind.TOOLS` + `domain/UsageThresholds`（WARN60/DANGER85 单一真源）+ `domain/UsageMath`（primaryPercent 排除 TOOLS）。
> - **v3.5 横向卡**：`RangePrimaryCard`/`RangeMiniRow`（主卡+mini）+ 主窗口可切「点 mini 升主」（`SettingsStore.primaryWindowKind`）+ 铃铛 badge（`hasUnreadNotifications`/`markNotificationsSeen`，`SettingsStore.lastSeenNotificationAt`）。
> - **v3.6 widget 主题跟随**：`widget/WidgetPalette`（深/浅 data class + `forContext` 读 themeMode）—— RemoteViews 在 launcher 进程渲染不能用 Compose 主题，颜色代码驱动；浅色 drawable `widget_background_light`/`item_card_background_light`。
>
> ⚠️ **华为 ROM widget 坑**：`android.widget.ProgressBar` 未实现 `setProgressTint(int)` / `setProgressBackgroundTint(int)` 两个 `@RemotableViewMethod`（AOSP 有、华为删），调任一 → RemoteViews inflate 失败、widget 白屏。**解法**：widget 进度条颜色只用 XML 静态值（`progressTint`/`progressBackgroundTint`），数字用量色走 `setTextColor`（不受影响）。
>
> 本文正文组件清单（§5/§6/§8/§9/§10）尚未逐表补全 v3.x 新增类，结构分层与领域模型不变；新增类见上方分版本列表。

---

## 1. 产品定位

本地优先、多服务商、多账户、低频刷新的 **AI Coding Plan 用量查看工具**。用户配置各家服务商 API Key 后，App 与桌面小部件展示套餐用量（5h/周等窗口）、重置时间、状态；数据本地加密缓存、离线可看。

**不做**：模型对话、云端存储、账号体系、高频刷新、收集 Cookie/密码。

当前支持：**智谱 GLM**（ADR-0001，已真机验证）、**Kimi**（ADR-0003）、**MiniMax**（v2-provider-research）。火山方舟 / ZenMux 延后 v2.3+。

---

## 2. 设计约束与边界

### 2.1 上游接口边界
各家用量查询端点均为**实验性数据源**（智谱非公开端点、Kimi 内部接口、MiniMax 历史接口）：
- 不在 UI/注释中承诺接口稳定；
- 上游路径/头/字段只出现在对应 Provider 内；
- 上游变动时只改 Provider + 其测试，不动 UI/widget/缓存；
- 响应结构不符返回 `UPSTREAM_CHANGED`，不崩溃。

### 2.2 安全边界
- 只接受用户主动输入的 API Key；
- Key 经 EncryptedSharedPreferences（AES256）加密存储，不上传、不进日志、不进普通 Preferences；
- 任何界面/通知/日志不展示完整 Key（脱敏 `****...ABCD`）；
- 不收集 Cookie、密码、验证码。

### 2.3 刷新边界
- 小部件展示本地缓存，不直接在渲染流程内请求网络；
- 仅手动刷新、前台过期刷新、后台 WorkManager 任务读网络；
- 网络失败保留最后一次有效数据并标记 stale；
- 认证失败 / 解析失败停止该账户自动刷新。

---

## 3. 总体架构

```text
┌─────────────────────────────────────────────────────────────────┐
│                   Android App（单 Activity Compose）              │
│                                                                   │
│  UI 层 (ui/)                       Widget 层 (widget/)            │
│  ┌───────────────────┐            ┌─────────────────────────┐   │
│  │ MainActivity      │            │ QuotaWidgetProvider      │   │
│  │  ├ UsageScreen    │            │  └ WidgetRenderer        │   │
│  │  ├ AccountsScreen │            │ QuotaListWidgetProvider  │   │
│  │  └ AddAccountScr  │            │  └ QuotaListRemoteViews… │   │
│  └─────────┬─────────┘            │ QuotaRefreshWorker       │   │
│            │ UsageViewModel        │  (默认仅刷 active +       │   │
│            │  (notifyWidgets)      │   skip stop-code 账户)   │   │
│            ▼                      └────────────┬────────────┘   │
│  服务层 (services/) ─────────────────────────────────────────  │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ UsageRefreshService (per-account 节流/退避/停止)          │ │
│  │ ServiceProviderConfig 数据表 + ServiceProviders 注册表   │ │
│  │   ├ glm() / kimi() / minimax()   (config 化，v2.2)       │ │
│  │ AccountRepository (读 facade: active 选择 + 缓存读取)    │ │
│  │ AccountStore (加密多账户) + UsageCache (schemaV2 分键)   │ │
│  └──────────────────────────────────────────────────────────┘ │
│            │                                                      │
│  领域层 (domain/) ── UsageSnapshot/Account/Credential/Window     │
└─────────────────────────────────────────────────────────────────┘
                                  │ HTTPS / 低频
                                  ▼
              各服务商用量查询通路（实验性，见 ADR）
```

分层原则：UI / Widget 只依赖领域模型；服务层编排多账户刷新与缓存；Provider 适配各家上游；存储层隔离 Key（加密）与缓存（非敏感）。

---

## 4. 领域模型（ADR-0002）

```kotlin
enum class WindowKind { FIVE_HOUR, WEEKLY, MONTHLY }

data class NormalizedWindow(
    val kind: WindowKind,
    val usedPercent: Int,        // 归一化 0..100
    val resetAt: Long? = null,   // Unix 毫秒
    val usedValue: Double? = null,
    val totalValue: Double? = null,
    val unit: String? = null
)

sealed class Credential {
    data class Raw(val key: String) : Credential()           // GLM
    data class Bearer(val key: String) : Credential()        // Kimi/MiniMax/ZenMux
    data class VolcAksk(val accessKeyId: String, val secretKey: String) : Credential()  // 火山 v2.1+
}

data class UsageSnapshot(
    val providerId: String,
    val providerLabel: String,
    val windows: List<NormalizedWindow>,
    val planName: String? = null,
    val modelUsage: List<ModelUsageItem>? = null,  // GLM TIME_LIMIT 附加
    val updatedAt: Long,
    val source: UsageSource,
    val status: UsageStatus,
    val errorCode: UsageErrorCode? = null,
    val errorMessage: String? = null
)

data class Account(
    val accountId: String,
    val providerId: String,
    val label: String,
    val credential: Credential,
    val region: String? = null,   // "CN"/"INTL"
    val isActive: Boolean = true
)
```

**归一化规则**（各家在 Provider 解析层统一到 `usedPercent 0..100`）：
- GLM / ZenMux：直接取百分比
- Kimi：`(limit - remaining) / limit * 100`
- MiniMax：`100 - 剩余百分比`
- 火山 AFP：`Used / Quota * 100`

**窗口定位按 `kind`，禁止按 `resetAt` 排序**（cc-switch #3036：周期末周窗可能比 5h 更早重置）。

---

## 5. Provider 架构

```kotlin
class ServiceProviderConfig(
    val providerId: String,
    val label: String,
    val supportsRegion: Boolean,
    val credentialType: CredentialType,   // RAW(直接Key) / BEARER
    val brandColor: Int,
    private val baseUrl: (region: String?) -> String,
    private val path: String,
    private val extraHeaders: Map<String, String>,
    private val parse: (body: String, now: Long) -> UsageSnapshot
) {
    suspend fun fetchUsage(credential, region, http, now): UsageSnapshot  // 唯一 5 步 fetch 实现
}

object ServiceProviders {
    fun all(): List<ServiceProviderConfig> = listOf(glm(), kimi(), minimax())
    fun byId(id: String): ServiceProviderConfig
    fun findById(id: String): ServiceProviderConfig?
    private fun glm() = ServiceProviderConfig(... parse = UsageParser::parseGlm)
    // kimi() / minimax() 同构
}
```

v2.2 把原「三家 Provider 类 + `ServiceProvider` 接口 + `Providers` 工厂」折叠为单一 `ServiceProviderConfig` 数据表（url / headers / credentialType / brandColor / parse）+ 唯一 fetch 实现。凭据 / region / HttpExecutor 由调用方注入（便于 mock 测试）。**加新服务商 = `ServiceProviders.all()` 加一个工厂函数 + `UsageParser` 加一个 parse 函数**，全栈可见、不再发散 6 处，不动 UI 渲染 / widget / 缓存（Provider 隔离红利）。

各家契约见 ADR-0001（GLM）/ ADR-0003（Kimi）/ [v2-provider-research.md](v2-provider-research.md)（MiniMax/火山/ZenMux）。

---

## 6. 多账户架构

| 组件 | 职责 |
|---|---|
| `AccountStore` | EncryptedSharedPreferences 存 Account 列表（JSON），含 v1.x 单 Key → Account 自动迁移 |
| `AccountRepository` | 读 facade（v2.2）：聚合账户列表 + 活跃选择 + 缓存读取，`AccountSnapshot.primaryPercent` 下沉 window fallback |
| `UsageCache` | schemaVersion=2，按 accountId 分键（`usage_cache_<id>`）序列化 UsageSnapshot |
| `UsageRefreshService` | per-account 实例，节流/退避/停止状态独立；`isStopCode` 共享 stop-code 真源 |
| `UsageViewModel` | 多账户 state（accounts / activeAccountId / activeSnapshot），账户增删切换 + `notifyWidgets()` 联动卡片 |

活跃账户 id 持久化于普通 prefs（`glm_quota_ui`），经 `AccountRepository` 统一读写（v2.2 收敛，原 widget/worker/VM 三处复制的 `"active_account_id"` 契约已消除）。

**迁移**：v1.x 单 Key（加密文件 `api_key`）→ 首个 GLM Account（`migrateV1SingleKeyIfNeeded`），已真机验证通过。

---

## 7. 刷新、缓存与退避策略

### 7.1 触发规则（per-account）
| 来源 | 条件 | 行为 |
|---|---|---|
| 手动 | 距上次发起 ≥ 10s | 立即 |
| 前台（ProcessLifecycle ON_RESUME） | 距上次成功 ≥ 15min | 静默刷新 |
| 后台（WorkManager） | 每 30min | 默认仅刷 active；`SettingsStore.backgroundRefreshAll` 开关可刷全部；skip stop-code 账户 |

WorkManager 最小 15min；30min 兼顾上游风控。v2.1 默认仅刷 active（省电+降风控）；v2.2 后台再跳过 stop-code（AUTH/NO_PLAN/UPSTREAM_CHANGED）账户——VM 已停止的账户不被 Worker 无意义重试。

### 7.2 错误策略
| 情况 | 缓存 | 后续 |
|---|---|---|
| 成功 | 覆盖 + updatedAt | 重置失败计数 |
| 网络 / 5xx | 保留，标 stale | 重试 |
| 连续失败 3 次 | 保留 | 暂停该账户 6h |
| 401 / 403 | 保留 | **停止**自动刷新 |
| 解析失败 | / | **停止** + `UPSTREAM_CHANGED` |

### 7.3 缓存 schema
`schemaVersion=2`。版本不匹配或损坏自愈清除。v1→v2 模型差异大，升级时旧缓存清除重拉（不迁移）。

---

## 8. UI 层（Compose）

单 `MainActivity` + `rememberSaveable` screen state 路由：

| screen | Composable | 职责 |
|---|---|---|
| main (Content) | `UsageScreen` | 活跃账户用量（多窗口 + 模型用量 + 多账户时切换 chip） |
| main (Unconfigured) | `AddAccountScreen(isFirst=true)` | 添加首个账户 |
| accounts | `AccountsScreen` | 账户列表/切换/删除/添加/清除 |
| add | `AddAccountScreen` | 选 provider + region + Key + 测试连接 |

UI 只依赖 `UsageSnapshot` / `Account`，不接触 HTTP/解析。开屏页用 core-splashscreen；App 回前台静默刷新（ProcessLifecycle）。

---

## 9. Widget 层（AppWidget）

| 组件 | 职责 |
|---|---|
| `QuotaWidgetProvider` + `WidgetRenderer` | 单账户详情卡片：onUpdate 渲染占位 + 读活跃账户缓存渲染（5h/周 + 进度条） |
| `QuotaListWidgetProvider` + `QuotaListRemoteViewsService` | 多账户列表卡片（v2.1 阶段 D，ListView 系统管滚动，品牌色条区分服务商） |
| `QuotaRefreshWorker` | WorkManager 30min，默认仅刷 active + skip stop-code → 刷两类 widget |

`UsageViewModel` 数据/账户变更（refresh / switch / add / remove / rename / clear）后调 `notifyWidgets()` → 两个 widget 立即重读缓存渲染（v2.2 修复，不再等 30min Worker）。

---

## 10. 项目结构（实际）

```text
android/app/src/main/java/com/example/myapplication/
├─ MainActivity.kt              # 单 Activity + Compose 路由 + 生命周期
├─ domain/
│  └─ Models.kt                 # UsageSnapshot/Account/Credential/Window
├─ services/
│  ├─ ServiceProviders.kt       # ServiceProviderConfig 数据表 + ServiceProviders 注册表（v2.2）
│  ├─ UsageParser.kt            # parseGlm/parseKimi/parseMiniMax
│  ├─ UsageProvider.kt          # HttpExecutor/HttpResponse/Region/Exception
│  ├─ OkHttpExecutor.kt         # OkHttp 实现
│  ├─ ErrorMapper.kt            # 错误映射
│  ├─ AccountStore.kt           # 加密多账户存储 + 迁移
│  ├─ AccountRepository.kt      # 读 facade（active 选择 + 缓存读取，v2.2）
│  ├─ UsageCache.kt             # schemaV2 缓存（CacheStorage 接口）
│  ├─ PrefsCacheStorage.kt      # SharedPreferences 多键实现
│  ├─ SettingsStore.kt          # 非敏感设置（后台刷新范围等）
│  └─ UsageRefreshService.kt    # per-account 编排 + isStopCode
├─ ui/
│  ├─ UsageViewModel.kt         # 多账户 ViewModel + notifyWidgets
│  └─ theme/                    # Compose 主题（科技青蓝）
└─ widget/
   ├─ QuotaWidgetProvider.kt         # 单账户详情 widget + WidgetRenderer
   ├─ QuotaListWidgetProvider.kt     # 多账户列表 widget（v2.1）
   ├─ QuotaListRemoteViewsService.kt # 列表 widget 的 RemoteViewsService + Factory
   └─ QuotaRefreshWorker.kt          # WorkManager 后台刷新
```

---

## 11. 关键流程

### 11.1 添加账户
```
AddAccountScreen → 选 provider/region/key
  → ViewModel.addAccount → ServiceProviders.byId(id).fetchUsage（测试连接）
  → 成功：AccountStore.saveAccount + 切为 active + hydrateAndRefresh + notifyWidgets
  → 失败：映射错误，保留编辑
```

### 11.2 切换账户
`switchAccount(id)` → 设 active + 清 snapshot + hydrate（per-account RefreshService 独立状态）。

### 11.3 冷启动 / 离线
`init` → listAccounts（触发 v1.x 迁移）→ 取 active → hydrateFromCache（先显缓存）→ 前台刷新。

---

## 12. 可测试性

### 已有（约 30 个单测全过）
- `UsageParserTest`（10 个）—— GLM/Kimi/MiniMax 解析，cc-switch 样例响应
- `GlmUsageProviderTest` / `KimiUsageProviderTest` / `MiniMaxUsageProviderTest`（~12 个）—— 经 `ServiceProviders` config + mock HttpExecutor 验全链路：URL、认证头（Raw/Bearer）、错误映射（AUTH/NETWORK/NO_PLAN）、CN/INTL
- `AccountRepositoryTest`（4 个，v2.2）—— `AccountSnapshot.primaryPercent` window fallback 4 分支
- `UsageRefreshServiceTest`（5 个，v2.2）—— `isStopCode` 分类 + AUTH 停止 vs NETWORK 仅退避（停止策略根源）
- host JVM 单测需 `testImplementation("org.json:json")` 替代 android.jar 的 org.json stub（两者同源、host JVM 单测标准做法）

### 待补（需 Robolectric / instrumented test）
- AccountStore 迁移测试（依赖 Android EncryptedSharedPreferences，v1.x→Account 迁移已**真机验证**通过 glm-xxx）
- 缓存 schema 升级测试、VM 逻辑测试

---

## 13. 演进路线

见 [ROADMAP.md](ROADMAP.md)：阶段 D（多账户列表 widget，✅ v2.1 已完成）、火山方舟/ZenMux（v2.3+）、余额制 provider 抽象、通知/告警等。

---

## 14. 决策记录

| 决策 | 选择 | 原因 |
|---|---|---|
| 平台 | Android Kotlin | DevEco 工具链死结；HarmonyOS 4.2 兼容 APK |
| 架构 | Provider 隔离 + UsageSnapshot 归一化 | 多服务商差异大，隔离让加服务商不动 UI |
| 多账户 | per-account RefreshService + 分键缓存 | 各账户独立节流/退避/失败状态 |
| Key 存储 | EncryptedSharedPreferences | 满足"不进普通 Preferences"的安全要求 |
| 刷新 | 手动优先 + 低频后台 | 用量无需实时，降低风控/耗电 |
| 缓存 | schemaVersion + 离线展示 | 网络波动仍可用 |
