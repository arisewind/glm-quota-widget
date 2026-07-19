# ADR-0003：Kimi For Coding 用量查询直连（v2.0 第二家）

- 状态：Accepted（v2.0 阶段 B 已接入，通用模型 go/no-go 关卡已通过）
- 日期：2026-07-18
- 关联：[ADR-0001](0001-glm-coding-plan-usage-direct-key.md) / [ADR-0002](0002-multi-provider-normalization.md) / [v2-provider-research.md](../v2-provider-research.md)

---

## 背景（Context）

v2.0 多服务商扩展的第二家。经 cc-switch 调研（`coding_plan.rs:102-206`）+ 第三方工具 [Golden0Voyager/kimi-code-usage](https://github.com/Golden0Voyager/kimi-code-usage) 交叉验证，Kimi 用量查询契约与 GLM 同构（双窗口滚动制），是调研成本最低、契约最稳的候选，定为第二家接入（对应 ROADMAP §3.2.3 阶段 B 的 go/no-go 关卡）。

---

## 决策（Decision）

### 请求契约

| 项 | 值 |
|---|---|
| 方法 | `GET` |
| 端点 | `https://api.kimi.com/coding/v1/usages` |
| 认证 | `Authorization: Bearer {api_key}`（**加 Bearer**，与 GLM 直接 Key 不同） |
| 必要 header | `Accept: application/json`（**不需要** Content-Type / Accept-Language） |
| 请求体 | 无 |
| 超时 | 15 秒 |

### 响应 → 归一化映射

响应结构：

```json
{
  "limits": [ { "detail": { "limit": N, "remaining": N, "resetTime": … } }, … ],
  "usage":  { "limit": N, "remaining": N, "resetTime": … }
}
```

| API 字段 | 窗口语义 | 映射到 `UsageSnapshot.windows` |
|---|---|---|
| `limits[].detail` | five_hour（5 小时窗口） | `NormalizedWindow(kind=FIVE_HOUR, …)` |
| `usage` | weekly（周窗口） | `NormalizedWindow(kind=WEEKLY, …)` |

每个 `detail` / `usage` 的归一化：

- `usedPercent = ((limit - remaining).coerceAtLeast(0) / limit * 100).toInt()` —— Kimi 给**绝对值**，需自算百分比（与 GLM 直接给百分比不同）。
- `resetAt = extractResetTime(resetTime)` —— 兼容 ISO 8601 字符串与数字（`< 1e12` 视为秒，否则毫秒）。
- `usedValue = limit - remaining`，`totalValue = limit`，`unit = "tokens"`（可选）。

### 实现要点

- `KimiUsageProvider : ServiceProvider`，`id = "kimi"`，`label = "Kimi For Coding"`。
- 凭据用 `Credential.Bearer`。
- Provider 内实现 `extractResetTime` 兼容函数（Kimi `resetTime` 格式不固定）。
- 该端点为 **Kimi Code Console 内部接口，官方无公开文档**，字段来自 cc-switch 抓包 + 第三方工具对照。

---

## 已验证的边界

1. `resetTime` 格式不固定（字符串 / 秒 / 毫秒），必须兼容解析。
2. 端点非公开，可能随 Kimi 上游变化。
3. `limits[]` 是数组但实测只含 5h 一项；周窗口在独立的 `usage` 对象（**结构不对称**，不能假设 `limits` 含周）。

---

## 备选方案（Alternatives，均已否决）

- **Kimi 官方 balance 接口**（platform.kimi.com/docs/api/balance）：那是**账户余额制**（金额），不是 Coding Plan 窗口制，与 GLM 用量语义不同，不适用。

---

## 后果（Consequences）

**正面**
- **验证 ADR-0002 归一化模型能否容纳「绝对值→百分比」转换**（GLM 是直接百分比，Kimi 需算）——这正是阶段 B go/no-go 关卡的核心检验。若 Kimi 顺利归一，证明模型通用，可放行阶段 C 铺开。

**负面 / 风险**
- 依赖非公开端点。Provider 内做结构校验，不符时返回 `UPSTREAM_CHANGED`。
- 合规：第三方 App 用 Key 查询是否违反 Kimi ToS，接入前需确认（与 GLM 同类欠账）。

---

## 参考依据

- cc-switch 实现：`src-tauri/src/services/coding_plan.rs:102-206`
- 第三方字段对照：[Golden0Voyager/kimi-code-usage](https://github.com/Golden0Voyager/kimi-code-usage)
- Kimi Code 会员说明：https://www.kimi.com/code/docs/en/kimi-code/membership.html
