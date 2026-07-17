# ADR-0001：采用 API Key 直连 GLM Coding Plan 用量查询端点

- 状态：Accepted
- 日期：2026-07-18
- 关联：[PRODUCT.md](../../PRODUCT.md) §3.1 P0 / [ARCHITECTURE.md](../../ARCHITECTURE.md) §4.3.1 DirectKeyUsageProvider / [REVIEW.md](../../REVIEW.md)「实测确认」小节

---

## 背景（Context）

PRD 与架构把直连数据源标为「实验性」。调研中一度被社区项目 `JinHanAI/coding-plan-monitor`（2026-03-17 调查）的报告误导——它声称「智谱用量查询接口不接受 API Key、只接受 Cookie、headless 浏览器被反爬拦截」，导致 REVIEW.md 初稿误判「直连 Key 路径不可行、需改桥接」。

后经多源交叉验证 + 实测，确认该结论**错误**：

1. `cc-switch` discussion #1038 的脚本就是用 **API Key** 调该端点，且有用户回复「解决了」。
2. 用户本人在 cc-switch 中用个人 Key 实测能看到余量（`five_hour: 0% / weekly_limit: 19%`）。
3. 用临时 Key 直连 `GET /api/monitor/usage/quota/limit` + 特定 header 组合，实测 HTTP 200，拿到 pro 套餐真实用量。

coding-plan-monitor 失败的原因是它**自己的请求方式**（curl / header 不全）问题，非接口不接受 Key。

本 ADR 锁定：直连可行、数据契约（端点 / header / 响应 / unit 映射）、以及依赖此非公开端点的风险与边界，作为 `DirectKeyUsageProvider` 实现的唯一权威依据。

---

## 决策（Decision）

采用 **API Key 直连** 作为 `DirectKeyUsageProvider` 的数据通路。

### 请求契约

| 项 | 值 |
|---|---|
| 方法 | `GET` |
| 国内站端点 | `https://open.bigmodel.cn/api/monitor/usage/quota/limit` |
| 国际站端点 | `https://api.z.ai/api/monitor/usage/quota/limit`（同路径，换 host） |
| 认证 | `Authorization: <Coding Plan API Key>`（**直接 Key，不加 Bearer 前缀**） |
| 必要 header | `Content-Type: application/json`、`Accept-Language: en-US,en`（这两项是通过反爬的关键，**不是 Cookie**） |
| 建议 header | 常规浏览器 `User-Agent` |
| 超时 | 15 秒（架构 4.3.1） |

### 响应 → 领域模型映射（经 cc-switch 现场标定）

响应结构：`{ code, msg, success, data: { level, limits: [...] } }`

`data.limits` 是数组，按 **`type` + `unit`** 定位窗口语义：

| API 字段 | 窗口语义 | 映射到 `CodingPlanUsage` |
|---|---|---|
| `{ type:'TOKENS_LIMIT', unit:3 }` | five_hour（5 小时窗口） | `session`（5h 窗） |
| `{ type:'TOKENS_LIMIT', unit:6 }` | weekly（周窗口） | `weekly`（周窗） |
| `{ type:'TIME_LIMIT',  unit:5 }` | 模型级用量（含 `usageDetails`） | 可选：`modelUsage`（中卡附加展示） |

每条 limit 的可用字段：`percentage`（已用%）、`nextResetTime`（Unix 毫秒）；剩余 = `100 - percentage`。
`planName` = `data.level`（如 `pro`）。
`unit:5` 的 `usageDetails` 按 `modelCode` 细分（实测见 search-prime / web-reader / zread）。

伪代码：

```ts
const fiveHour = limits.find(l => l.type === 'TOKENS_LIMIT' && l.unit === 3);
const weekly   = limits.find(l => l.type === 'TOKENS_LIMIT' && l.unit === 6);
const modelUsage = limits.find(l => l.type === 'TIME_LIMIT' && l.unit === 5)?.usageDetails;
```

---

## 已验证的边界

1. **5h 窗未消耗时（`percentage:0`）不返回 `nextResetTime`**——PRD 6.2「无值时显示『重置时间暂不可用』」的降级设计正好兜住，**非 bug，上游如此**。
2. `unit` 是智谱内部窗口枚举码（非时长数值）。其语义（3=5h、6=周、5=模型用量）由本次实测 + cc-switch 标定确认；若智谱调整枚举，需重新标定。
3. 该端点**非公开**（不在智谱官方 API 文档），可能随上游规则变化字段或认证方式。

---

## 备选方案（Alternatives，均已否决）

- **Cookie / CDP 连真实浏览器**：能取更详细数据，但违反 PRD「不做浏览器自动化 / Cookie」边界，且用户体验差（需常驻登录浏览器）。
- **官方 `glm-plan-usage` 插件桥接**：稳定，但仅 Claude Code 内、仅个人版，无法满足手机端独立查询。留作 Phase 4 `BridgeUsageProvider` 备选。
- **等智谱开放官方用量 API**：无时间表，搁置。

---

## 后果（Consequences）

**正面**
- `DirectKeyUsageProvider` 可直接落地，PRD 的 P0（读取用量）可达验收。
- 不需要改 PRD 边界、不需要桥接，架构维持原设计。
- Provider 隔离设计（架构 §4.3）让未来换数据源时 UI / 卡片 / 缓存不用动。

**负面 / 风险**
- 依赖非公开端点：上游变更可能导致解析失败 → Provider 内须做结构校验，不符时返回 `UPSTREAM_CHANGED`（架构 4.3.1 已预留）。
- 合规未确认：第三方 App 用 Key 查询是否违反智谱 ToS，开发前需问询智谱（见 REVIEW.md D3）。
- `unit` 枚举若变，映射需更新 → Provider 内对未知 `unit` 优雅降级（忽略而非崩溃），并在 `testConnection` 时记录所见 unit 值便于排查。

---

## 参考依据

- 实测响应原文：[REVIEW.md](../../REVIEW.md)「实测确认」小节（2026-07-18，临时 Key 直连）。
- cc-switch 现场标定：`five_hour:0% / weekly_limit:19%` ↔ API `unit:3` / `unit:6`。
- 端点与脚本来源：[cc-switch discussion #1038](https://github.com/farion1231/cc-switch/discussions/1038)、issue #1588。
- 历史误判记录：[JinHanAI/coding-plan-monitor](https://github.com/JinHanAI/coding-plan-monitor)（2026-03-17）曾误报「不接受 Key」，已证伪。
