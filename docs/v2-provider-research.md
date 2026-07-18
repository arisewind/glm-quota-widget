# v2.0 候选服务商用量契约调研

- 日期：2026-07-18
- 来源：cc-switch（farion1231/cc-switch）源码调研，main 分支 `src-tauri/src/services/coding_plan.rs`（本地克隆核对）
- 关联：[ADR-0002](adr/0002-multi-provider-normalization.md) / [ADR-0003](adr/0003-kimi-usage-direct.md) / [ROADMAP.md](ROADMAP.md) §3.2

本文档记录 v2.0 候选服务商（MiniMax / 火山方舟 / ZenMux）的用量查询契约。**Kimi** 已决为第二家接入（ADR-0003），**GLM** 见 ADR-0001。本文件供未来接入参考，**各家均含「待实测」项，接入前必须 curl 验证真实返回**。

---

## 候选优先级与决策

| 厂商 | 决策 | 理由 |
|---|---|---|
| Kimi | ✅ 第二家接入（[ADR-0003](adr/0003-kimi-usage-direct.md)） | 契约最稳、与 GLM 同构、调研成本最低 |
| MiniMax | 🟡 候选（v2.0 阶段 C） | 契约类似，但有字段命名陷阱 + cookie 鉴权历史风险，需实测 |
| ZenMux | 🟡 候选（v2.0 阶段 C） | cc-switch 字段名与官方文档矛盾，须 curl 实测；需 Management API Key |
| 火山方舟 | 🔴 延后（v2.1+） | AK/SK 签名 V4 实现成本极高 + Action 名未公开，稳定性最差 |

---

## MiniMax（月之暗面对标，MiniMax-AI）

### 契约

| 项 | 值 |
|---|---|
| URL | `GET https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains`（CN）/ `https://api.minimax.io/…`（EN） |
| 认证 | `Authorization: Bearer {api_key}` |
| header | `Content-Type: application/json` |
| 响应 | `{ base_resp:{status_code,status_msg}, model_remains:[{model_name, current_interval_remaining_percent, current_weekly_status, current_weekly_remaining_percent, end_time, weekly_end_time}] }` |

### 解析要点

- `model_remains[]` 中找 `model_name == "general"`（**跳过 `video` 等非编程桶**）。
- 5h 窗：`usedPercent = 100 - current_interval_remaining_percent`（**给的是剩余百分比，需反转**），`resetAt = end_time`（毫秒）。
- 周窗：仅当 `current_weekly_status == 1` 才激活（`== 3` 表示无周限额，**跳过，remaining_percent 恒为 100 不应展示**）；`usedPercent = 100 - current_weekly_remaining_percent`，`resetAt = weekly_end_time`。

### 已知坑（接入必读）

1. **字段命名陷阱**（[MiniMax-M2 issue #99](https://github.com/MiniMax-AI/MiniMax-M2/issues/99)）：旧字段 `current_interval_usage_count` 字面像「已用」实为「剩余」。cc-switch 用后改版的 `_remaining_percent` 字段并反转。
2. **cookie 鉴权历史**（[issue #88](https://github.com/MiniMax-AI/MiniMax-M2/issues/88)）：`/coding_plan/remains` 历史上要求 cookie 鉴权；cc-switch 现用 Bearer API Key，**可能在某些账号/时期失败，待实测**。
3. 周桶靠 `current_weekly_status == 1` 判定，无周限额套餐该字段为 3。

### 参考

- cc-switch：`coding_plan.rs:408-491` + `parse_minimax_tiers:639-695`
- MiniMax Token Plan：https://platform.minimax.io/docs/token-plan/intro

---

## 火山方舟（字节跳动）— 延后到 v2.1+

### 契约（五家里最复杂）

| 项 | 值 |
|---|---|
| URL | `POST https://open.volcengineapi.com/?Action=GetAFPUsage&Region=cn-beijing&Version=2024-01-01`（Coding Plan 探测用 `Action=GetCodingPlanUsage`） |
| 方法 | `POST`（**空 body**） |
| 认证 | **火山引擎签名 V4（AK/SK），非 Bearer**。用户须另填火山账号 AccessKey ID + Secret（与推理 API Key 是两套凭据） |
| header | `Authorization: HMAC-SHA256 Credential=…` + `X-Date: {YYYYMMDDTHHMMSSZ}` + `X-Content-Sha256: {sha256(空body)}` + `Content-Type: application/json; charset=utf-8` |

### 响应

- **Agent Plan（GetAFPUsage）**：`{ Result: { AFPFiveHour:{Quota,Used,ResetTime}, AFPWeekly:{…}, AFPMonthly:{…}, AFPDaily:{…}, PlanType } }`
- **Coding Plan（GetCodingPlanUsage）**：`{ Result: { QuotaUsage:[{Level:"session"/"weekly"/"monthly", Percent, ResetTime}] } }`（字段名有 fallback：`Usages`/`Details`，百分比字段也可能叫 `UsedPercent`/`UsagePercent`，cc-switch 全做了 fallback）

### 延后理由

1. **签名 V4 是 AWS SigV4 的火山变体，两处致命差异不能照搬标准 SigV4**（`coding_plan.rs:788-796`）：
   - canonical headers 顺序固定为 `host;x-date;x-content-sha256;content-type`（**不按字母序**）
   - algorithm 串是 `HMAC-SHA256`（无 `AWS4-` 前缀）、credential scope 结尾是 `request`（非 `aws4_request`）、签名密钥 `kDate = HMAC(SK, date)`（SK 不加前缀）
   - 照搬标准 SigV4 会被网关以 `400 InvalidAuthorization` 拒绝。实现成本高、易错。
2. **Action 名未公开**：`GetAFPUsage` / `GetCodingPlanUsage` 官方文档查不到（官方只公开 `GetUsageDetails`、`ListSeatAFPUsage`），cc-switch 从官方 `ark-cli` 抓包得到，稳定性风险。
3. 三窗口（5h / 周 / 月）+ AFP 积分制，比 GLM 多一个维度。
4. 用户门槛高：要填 AK/SK（非推理 Key），且要在火山控制台开通。

### 参考

- cc-switch：`coding_plan.rs:697-1170`（签名 V4 `:788-891`）
- 火山用量 OpenAPI（公开部分，不含 cc-switch 用的 Action）：https://www.volcengine.com/docs/82379/1390291

---

## ZenMux（`ZenMux`，zenmux.ai）

### 核实结果

- **确切名称**：`ZenMux`（驼峰；**非** Zenmux / Zen Mux）
- **域名**：`zenmux.ai`（**非 zenmux.com** —— cc-switch 前端占位符 `api.zenmux.com` 写错了）
- **归属**：独立第三方 AI 模型聚合中转商（聚合 Claude/GPT/Gemini/Grok/GLM/Kimi/MiniMax/Doubao 等 100+ 模型，订阅制，定位类 OpenRouter）。公司主体未公开披露。
- **计费单位**：Flow（ZenMux 自创复合单位 = token 消耗 + 单次请求开销，1 Flow ≈ $0.03283，动态）

### 契约

| 项 | 值 |
|---|---|
| URL | `GET {base_url}/api/v1/management/subscription/detail`（官方文档；cc-switch 让用户手填 base_url） |
| 认证 | `Authorization: Bearer {management_api_key}`（**需 Management API Key**，普通订阅 Key `sk-ss-v1-` 前缀的可能调不动，须 Console > Management 单独创建） |
| header | `Accept: application/json` |
| 响应（官方文档） | `{ success, data:{ plan:{tier,amount_usd,interval}, account_status, quota_5_hour:{max_flows,used_flows,remaining_flows,usage_percentage}, quota_7_day:{…} } }` |

### ⚠️ 待实测（接入前必须 curl）

**cc-switch 代码读的是 `quota_5_hour.used_value_usd` / `max_value_usd`（USD 字段），但官方文档是 `used_flows` / `max_flows`（Flow 单位）**。两者矛盾，可能：

- cc-switch 基于更早 / 更新版本文档，接口确实返回 USD 字段；或
- cc-switch 字段名猜错有 bug。

接入前必须 curl 实测真实字段名。若以官方文档为准，单位是 Flow，换算 USD 需额外调 `/management/flow_rate`。

另外 `usage_percentage` 是**小数**（0.0715 = 7.15%），cc-switch 乘 100 转百分比（`coding_plan.rs:571`）。

### 参考

- cc-switch：`coding_plan.rs:493-629`
- ZenMux 官方：https://zenmux.ai/docs/guide/quickstart.html 、 https://zenmux.ai/docs/guide/subscription.html
- cc-switch 接入：PR [#2709](https://github.com/farion1231/cc-switch/pull/2709)，v3.16.2 发布说明

---

## 附：GLM 契约（对照基准，ADR-0001 补充）

cc-switch 实现里对 GLM 有两点值得记入：

1. **cc-switch 完全跳过 `TIME_LIMIT`**（`coding_plan.rs:257` 只匹配 `TOKENS_LIMIT`），只用 `unit:3`(5h) + `unit:6`(周) 两窗。我们 v1.x 用 `TIME_LIMIT unit:5` 做了模型用量（modelUsage）展示 —— v2.0 归一化时把它保留为 `UsageSnapshot.modelUsage` 可选附加（见 ADR-0002）。
2. **必须按 `unit` 字段分类窗口，不能按 `nextResetTime` 排序**（cc-switch issue #3036）：周期末尾周窗口可能比 5h 窗口更早重置，按时间排序会把两桶标反。
3. GLM **Team（团队版）**（`coding_plan.rs:1188-1263`）：同 host 同 path，差异是 URL 加 `?type=2` + 额外头 `bigmodel-organization` / `bigmodel-project`（与 api_key 三者缺一不可），仅国内站。未来若支持团队账号需扩展 Credential。
