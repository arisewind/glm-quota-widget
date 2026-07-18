# GLM Quota Widget — 项目审阅与关键决策记录

- 审阅日期:2026-07-18
- 方法:grilling(事实查清 + 决策拷问)+ 架构视角(depth / seam / locality)
- 关联:[PRODUCT.md](PRODUCT.md) / [ARCHITECTURE.md](ARCHITECTURE.md) / [ROADMAP.md](ROADMAP.md)

---

## 1. 核心结论:直连 Key 可行

✅ **PRD 核心假设成立**——用户输 Key → App 用 Key 直连查用量,可行。

`GET https://open.bigmodel.cn/api/monitor/usage/quota/limit` + `Authorization: <Key>`(不加 Bearer)+ header 组合(`Content-Type: application/json` + `Accept-Language: en-US,en`)可拿到用量。**过反爬的是 header 组合,不是 Cookie**。2026-07-18 已由临时 Key 直连实测(HTTP 200)和真机端到端验证双重坐实。

> **历史勘误**:本审阅早期版本曾据单一社区项目(JinHanAI/coding-plan-monitor)误判「Key 调不通、必须用浏览器 Cookie / CDP」,并据此推出过「直连死结、需改桥接」的结论。后经用户实证(cc-switch 用 API Key 成功检测余量)+ 临时 Key 直连一次成功,**该结论被推翻**。教训:不轻信单一来源,必须交叉验证。文档已修正。

---

## 2. 实测确认的数据契约

响应 `data.limits` 按 `type + unit` 映射(经 cc-switch 现场标定):

| type | unit | 语义 |
|---|---|---|
| `TOKENS_LIMIT` | 3 | 5 小时窗口 |
| `TOKENS_LIMIT` | 6 | 周窗口 |
| `TIME_LIMIT` | 5 | 模型级用量(含 `usageDetails`) |

边界:`5h 窗未消耗(percentage=0)时不返回 nextResetTime`——PRD「无值时显示'重置时间暂不可用'」的降级设计正好对上,不是 bug。

完整契约见 [ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md)。

---

## 3. 架构评价(depth / seam / locality)

| 设计 | 评价 |
|---|---|
| `UsageProvider` 抽象(testConnection / fetchUsage) | ✅ 深 module,换实现不动上层——**最大亮点**。v2.0 多服务商扩展正得益于此:加服务商 = 加 Provider |
| `UsageRefreshService` 单点编排 + 并发锁 + 节流 | ✅ locality 强,刷新逻辑集中 |
| widget 数据模型独立于领域模型 | ✅ 好 seam,卡片渲染不被领域变更牵连 |
| `CodingPlanUsage` 领域模型 | ✅ 与上游契约契合度高 |
| 缓存 `schemaVersion` | 🟡 迁移规则需补(字段新增→迁移;语义变更→清除重拉) |

**一句话**:架构无需推倒。Provider 隔离让「换数据源 / 加服务商」成本可控——这印证了当初做隔离的价值。

---

## 4. 仍然有效的提醒

- **合规**:开发前 / 上架前问智谱客服「第三方工具用用户 Key 查询 `quota/limit` 是否允许」——未答复,是上架被驳回 / Key 被封的风险点。答复存档为 ADR。
- **反爬稳定性**:保持低频刷新(后台 ≥30min)+ 失败优雅降级,降低被风控概率。
- **领域模型通用化**:多服务商时,各家额度结构(窗口制 / 余额制)差异大,`CodingPlanUsage` 需抽象成通用 `UsageSnapshot`(见 ROADMAP v2.0 阶段 B,设为 go/no-go 关卡)。

---

## 5. 参考来源

- [智谱 GLM Coding Plan 套餐概览](https://docs.bigmodel.cn/cn/coding-plan/overview)
- [智谱 Coding Plan FAQ(限官方工具使用)](https://docs.bigmodel.cn/cn/coding-plan/faq)
- [官方用量查询插件 glm-plan-usage](https://docs.bigmodel.cn/cn/coding-plan/extension/usage-query-plugin)
- [cc-switch 用量查询脚本讨论 #1038](https://github.com/farion1231/cc-switch/discussions/1038)
