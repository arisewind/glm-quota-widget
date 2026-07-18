# ADR-0002：多服务商用量数据归一化模型（v2.0 架构）

- 状态：Proposed（待 v2.0 阶段 A 实施）
- 日期：2026-07-18
- 关联：[ADR-0001](0001-glm-coding-plan-usage-direct-key.md) / [ROADMAP.md](../../ROADMAP.md) §3.2 / [v2-provider-research.md](../v2-provider-research.md)

---

## 背景（Context）

v1.x 的 `CodingPlanUsage` 是智谱 GLM 单服务商特化模型（`session`=5h 窗、`weekly`=周窗 + 可选 `modelUsage`）。v2.0 要接入 Kimi / MiniMax / 火山方舟 / ZenMux。经 cc-switch（farion1231/cc-switch）源码调研（详见 [v2-provider-research.md](../v2-provider-research.md)），各家契约差异显著：

| 维度 | GLM | Kimi | MiniMax | ZenMux | 火山方舟 |
|---|---|---|---|---|---|
| 认证 | 直接 Key | Bearer | Bearer | Bearer | AK/SK 签名 V4 |
| 用量表达 | 已用% | 绝对值 limit/remaining | 剩余%（需 100 减） | 百分比 + Flow/USD | AFP 积分绝对值 / 百分比 |
| 窗口数 | 2（5h/周） | 2（5h/周） | 2（5h/可选周） | 2（5h/7天） | 3（5h/周/月） |
| 重置时间 | 毫秒 | 字符串/秒/毫秒 | 毫秒 | 字符串 | 秒/毫秒/字符串 |

cc-switch 已用统一的 `SubscriptionQuota { tiers: Vec<QuotaTier> }` 把前四家归一化（`subscription.rs:15-66`），证明「双窗口 + 百分比」归一化可行。火山因签名 + 三窗口 + 积分制最特殊。

需要一套能容纳上述差异、又对 UI / widget / 缓存稳定的归一化模型。

---

## 决策（Decision）

引入通用 `UsageSnapshot` + `NormalizedWindow` + `Credential` + `ServiceProvider`，取代 v1.x 的 GLM 特化 `CodingPlanUsage`。

### 领域模型（Kotlin）

```kotlin
enum class WindowKind { FIVE_HOUR, WEEKLY, MONTHLY }

data class NormalizedWindow(
    val kind: WindowKind,
    val usedPercent: Int,         // 归一化到 0..100（各家在此统一）
    val resetAt: Long? = null,    // Unix 毫秒
    val usedValue: Double? = null,
    val totalValue: Double? = null,
    val unit: String? = null      // "tokens" / "flows" / "usd" / "points"
)

sealed class Credential {
    data class Raw(val key: String) : Credential()                                  // GLM 直接 Key
    data class Bearer(val key: String) : Credential()                               // Kimi/MiniMax/ZenMux
    data class VolcAksk(val accessKeyId: String, val secretKey: String) : Credential()  // 火山
}

data class UsageSnapshot(
    val providerLabel: String,
    val windows: List<NormalizedWindow>,
    val balance: BalanceAmount? = null,             // 余额制 provider（未来 DeepSeek 等）
    val modelUsage: List<ModelUsageItem>? = null,   // GLM TIME_LIMIT，保留为可选附加
    val updatedAt: Long,
    val source: UsageSource,
    val status: UsageStatus,
    val errorCode: UsageErrorCode? = null,
    val errorMessage: String? = null
)

interface ServiceProvider {
    val id: String
    val label: String
    suspend fun fetchUsage(credential: Credential): UsageSnapshot
}
```

### 归一化规则

1. **百分比统一**：各 Provider 在解析层把用量归一化到 `usedPercent: 0..100`。
   - GLM / ZenMux：直接取百分比
   - Kimi：`(limit - remaining) / limit * 100`
   - MiniMax：`100 - current_interval_remaining_percent`
   - 火山 AFP：`Used / Quota * 100`
2. **重置时间统一**：所有 Provider 输出 Unix 毫秒；各家原始格式（秒/毫秒/ISO 字符串）由 Provider 内的 `extractResetTime` 兼容函数转换（借鉴 cc-switch `coding_plan.rs:64-78`）。
3. **窗口缺失**：周窗口可能缺失（GLM 老套餐、MiniMax `current_weekly_status==3` 无周限额）。`windows` 只含实际存在的窗口；UI 按 `kind` 查找，缺失则不展示。
4. **窗口分类按语义键，不按 resetAt 排序**：cc-switch issue #3036 教训——周期末尾周窗口可能比 5h 窗口更早重置，按时间排序会把两桶标反。**必须按 `kind` 定位**。

### 与 v1.x 的关系（迁移）

- v1.x `CodingPlanUsage.session / weekly` → v2.0 `windows` 中 `kind = FIVE_HOUR / WEEKLY` 的元素。
- `CodingPlanUsage.modelUsage`（GLM `TIME_LIMIT unit:5`）→ `UsageSnapshot.modelUsage`，作为 GLM 特有附加信息保留。**注**：cc-switch 归一化时跳过 TIME_LIMIT，但我们 v1.x 已有模型用量 UI，保留以不破坏体验。
- `DirectKeyUsageProvider` → 改名 `GlmUsageProvider`，实现 `ServiceProvider`，输出 `UsageSnapshot`。
- `UsageCache` schemaVersion 升 **2**，序列化 `UsageSnapshot`；旧 v1 缓存自愈清除。

### Credential 抽象

不同 Provider 需不同凭据形态。`Credential` sealed class 让 `Account` 模型存储任意 provider 凭据，`ServiceProvider.fetchUsage(credential)` 内部按需 cast。

---

## 备选方案（Alternatives，均已否决）

- **每家独立模型**（KimiUsage / MiniMaxUsage / …）：UI/widget/缓存要为每家写一套，违背 Provider 隔离初衷。
- **强制所有家只给百分比、丢弃绝对值**：火山 AFP 积分、ZenMux Flow 的绝对值对用户有意义（"还剩多少积分"），丢弃损失信息。故保留 `usedValue/totalValue` 为可选字段。

---

## 后果（Consequences）

**正面**
- 加新服务商只实现 `ServiceProvider`，不动 UI / widget / 缓存（Provider 隔离红利）。
- 窗口制（5 家）+ 余额制（未来）统一在一个模型。

**负面 / 风险**
- v1.x → v2.0 是破坏性重构（`CodingPlanUsage` → `UsageSnapshot`，缓存 schema 升级）。阶段 A 须先把 GLM 接入新架构，验证不破坏 v1.1。
- 部分字段「待实测」（ZenMux `used_value_usd` vs `used_flows`；MiniMax cookie 鉴权历史）：模型用可选字段容纳未知，具体映射待接入实测。

---

## 参考依据

- cc-switch 统一模型：`src-tauri/src/services/subscription.rs:15-66`（`SubscriptionQuota` / `QuotaTier`）
- 各家契约细节：[v2-provider-research.md](../v2-provider-research.md)
