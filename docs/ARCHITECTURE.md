# GLM Quota Widget 架构设计

- 文档版本：v2.2（2026-07-19）
- 状态：已实施
- 目标平台：Android（Kotlin / Jetpack Compose / AppWidget）
- 技术栈：AGP 9.3 / Kotlin 2.2.10 / compileSdk 36.1 / minSdk 26 / targetSdk 36
- 工程位置：仓库 [`android/`](../android/)
- 关联：[PRODUCT.md](PRODUCT.md) / [ROADMAP.md](ROADMAP.md) / [ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md) / [ADR-0002](adr/0002-multi-provider-normalization.md) / [ADR-0003](adr/0003-kimi-usage-direct.md)

> **平台演进**：本文档 v1.0 按 HarmonyOS 4.2（ArkTS / Form Kit）设计，后因 DevEco/hvigor 工具链网络死结转为 Android（HarmonyOS 4.2 兼容 APK）。v2.0 在 Android 上引入多服务商多账户架构。本文反映 v2.0 实际实现，v1.0 HarmonyOS 设计见 git 历史。

> **v2.2 架构深化**（2026-07-19）：Provider 层由「三家 Provider 类 + `ServiceProvider` 接口 + `Providers` 工厂」折叠为单一 `ServiceProviderConfig` 数据表 + 唯一 fetch 实现；新增 `AccountRepository` 读 facade 收敛「活跃账户选择 + 缓存读取」（消除 `"active_account_id"` 契约复制 3 份）；Worker 读 cache `errorCode` 跳过 stop-code 账户。UI/Widget/缓存总体分层不变，Provider 与账户读路径细节以代码为准，详见 [REVIEW §0](REVIEW.md)。

---

## 1. 产品定位

本地优先、多服务商、多账户、低频刷新的 **AI Coding Plan 用量查看工具**。用户配置各家服务商 API Key 后，App 与桌面小部件展示套餐用量（5h/周等窗口）、重置时间、状态；数据本地加密缓存、离线可看。

**不做**：模型对话、云端存储、账号体系、高频刷新、收集 Cookie/密码。

当前支持：**智谱 GLM**（ADR-0001，已真机验证）、**Kimi**（ADR-0003）、**MiniMax**（v2-provider-research）。火山方舟 / ZenMux 延后 v2.1+。

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
│  │ MainActivity      │            │ QuotaWidgetProvider     │   │
│  │  ├ UsageScreen    │            │  └ WidgetRenderer       │   │
│  │  ├ AccountsScreen │            │ QuotaRefreshWorker      │   │
│  │  └ AddAccountScr  │            │  (遍历所有账户刷新)      │   │
│  └─────────┬─────────┘            └────────────┬────────────┘   │
│            │  UsageViewModel (多账户 state)      │                │
│            ▼                                     ▼                │
│  服务层 (services/) ─────────────────────────────────────────  │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ UsageRefreshService (per-account 节流/退避/停止)          │ │
│  │ ServiceProvider 抽象 + Providers 注册表                  │ │
│  │   ├ GlmUsageProvider    (ADR-0001)                        │ │
│  │   ├ KimiUsageProvider   (ADR-0003)                        │ │
│  │   └ MiniMaxUsageProvider                                 │ │
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
interface ServiceProvider {
    val providerId: String
    val label: String
    val supportsRegion: Boolean
    suspend fun fetchUsage(credential: Credential, region: String?, http: HttpExecutor): UsageSnapshot
}

object Providers {
    fun create(providerId: String): ServiceProvider = when (providerId) {
        ServiceProviderInfo.GLM_ID -> GlmUsageProvider()
        ServiceProviderInfo.KIMI_ID -> KimiUsageProvider()
        ServiceProviderInfo.MINIMAX_ID -> MiniMaxUsageProvider()
        else -> throw IllegalArgumentException("未知 provider")
    }
}
```

Provider **无状态**，凭据/region/HttpExecutor 由调用方注入（便于 mock 测试）。加新服务商 = 实现 `ServiceProvider` + 注册 `Providers` + UI providerOptions，**不动 UI 渲染 / widget / 缓存**（Provider 隔离红利）。

各家契约见 ADR-0001（GLM）/ ADR-0003（Kimi）/ [v2-provider-research.md](v2-provider-research.md)（MiniMax/火山/ZenMux）。

---

## 6. 多账户架构

| 组件 | 职责 |
|---|---|
| `AccountStore` | EncryptedSharedPreferences 存 Account 列表（JSON），含 v1.x 单 Key → Account 自动迁移 |
| `UsageCache` | schemaVersion=2，按 accountId 分键（`usage_cache_<id>`）序列化 UsageSnapshot |
| `UsageRefreshService` | per-account 实例，节流/退避/停止状态独立 |
| `UsageViewModel` | 多账户 state（accounts / activeAccountId / activeSnapshot），账户增删切换 |

活跃账户 id 持久化于普通 prefs（`glm_quota_ui`），Widget 据此显示当前账户。

**迁移**：v1.x 单 Key（加密文件 `api_key`）→ 首个 GLM Account（`migrateV1SingleKeyIfNeeded`），已真机验证通过。

---

## 7. 刷新、缓存与退避策略

### 7.1 触发规则（per-account）
| 来源 | 条件 | 行为 |
|---|---|---|
| 手动 | 距上次发起 ≥ 10s | 立即 |
| 前台（ProcessLifecycle ON_RESUME） | 距上次成功 ≥ 15min | 静默刷新 |
| 后台（WorkManager） | 每 30min | 遍历所有账户刷新 |

WorkManager 最小 15min；30min 兼顾上游风控。后台遍历所有账户（账户多时注意耗电/风控，未来可优化为仅 active）。

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
| `QuotaWidgetProvider` | AppWidgetProvider，onUpdate 渲染占位 + 触发刷新 |
| `WidgetRenderer` | 读活跃账户缓存 → RemoteViews 渲染（5h/周两行 + 进度条） |
| `QuotaRefreshWorker` | WorkManager 30min，遍历所有账户刷新缓存 → 刷 widget |

Widget 显示**当前活跃账户**（单账户精简版）。多账户同屏（阶段 D 竖向列表 widget）见 [ROADMAP.md](ROADMAP.md)。

---

## 10. 项目结构（实际）

```text
android/app/src/main/java/com/example/myapplication/
├─ MainActivity.kt              # 单 Activity + Compose 路由 + 生命周期
├─ domain/
│  └─ Models.kt                 # UsageSnapshot/Account/Credential/Window
├─ services/
│  ├─ ServiceProvider.kt        # 接口 + Providers 注册表
│  ├─ GlmUsageProvider.kt       # ADR-0001
│  ├─ KimiUsageProvider.kt      # ADR-0003
│  ├─ MiniMaxUsageProvider.kt
│  ├─ UsageParser.kt            # parseGlm/parseKimi/parseMiniMax
│  ├─ UsageProvider.kt          # HttpExecutor/HttpResponse/Region/Exception
│  ├─ OkHttpExecutor.kt         # OkHttp 实现
│  ├─ ErrorMapper.kt            # 错误映射
│  ├─ AccountStore.kt           # 加密多账户存储 + 迁移
│  ├─ UsageCache.kt             # schemaV2 缓存（CacheStorage 接口）
│  ├─ PrefsCacheStorage.kt      # SharedPreferences 多键实现
│  └─ UsageRefreshService.kt    # per-account 编排
├─ ui/
│  ├─ UsageViewModel.kt         # 多账户 ViewModel
│  └─ theme/                    # Compose 主题（科技青蓝）
└─ widget/
   ├─ QuotaWidgetProvider.kt    # AppWidgetProvider + WidgetRenderer
   └─ QuotaRefreshWorker.kt     # WorkManager 后台刷新
```

---

## 11. 关键流程

### 11.1 添加账户
```
AddAccountScreen → 选 provider/region/key
  → ViewModel.addAccount → provider.fetchUsage（测试连接）
  → 成功：AccountStore.saveAccount + 切为 active + hydrateAndRefresh
  → 失败：映射错误，保留编辑
```

### 11.2 切换账户
`switchAccount(id)` → 设 active + 清 snapshot + hydrate（per-account RefreshService 独立状态）。

### 11.3 冷启动 / 离线
`init` → listAccounts（触发 v1.x 迁移）→ 取 active → hydrateFromCache（先显缓存）→ 前台刷新。

---

## 12. 可测试性

### 已有（20 个单测全过）
- `UsageParserTest`（10 个）—— GLM/Kimi/MiniMax 解析，cc-switch 样例响应
- `GlmUsageProviderTest` / `KimiUsageProviderTest` / `MiniMaxUsageProviderTest`（10 个）—— mock HttpExecutor 验全链路：URL 拼装、认证头（Raw/Bearer）、错误映射（AUTH/NETWORK/NO_PLAN）、CN/INTL 切换
- host JVM 单测需 `testImplementation("org.json:json")` 替代 android.jar 的 org.json stub（生产用 android org.json，测试用 org.json:json —— 两者同源、host JVM 单测标准做法）

### 待补（需 Robolectric / instrumented test）
- AccountStore 迁移测试（依赖 Android EncryptedSharedPreferences，v1.x→Account 迁移已**真机验证**通过 glm-xxx）
- 缓存 schema 升级测试、VM 逻辑测试

---

## 13. 演进路线

见 [ROADMAP.md](ROADMAP.md)：阶段 D（多账户列表 widget）、火山方舟/ZenMux（v2.1+）、余额制 provider 抽象等。

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
