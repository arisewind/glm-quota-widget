# GLM Quota Widget

> HarmonyOS 桌面服务卡片,一眼查看 GLM Coding Plan 的 **5 小时 / 周额度用量**。本地优先、Key 不上传、离线可看。

**当前状态:Phase 1(平台无关核心层)已完成 · 等 DevEco 进 Phase 2(ArkTS + UI/卡片)**

---

## 这是什么

[GLM Coding Plan](https://docs.bigmodel.cn/cn/coding-plan/overview) 订阅者经常要确认"5 小时窗口 / 周额度还剩多少",但每次都要开网页、登录、点好几下。本项目做一个 HarmonyOS 桌面服务卡片(小卡 / 中卡),配好 API Key 后,手机桌面一滑就能看到余量、重置时间和状态;数据本地缓存、离线仍可查看最后一次结果。

**不做**:模型对话、代码生成、账号体系、云端存储、高频刷新。只解决一件事——快速看额度。

## 当前状态

本机暂未装 HarmonyOS 工具链,故先把架构里所有**平台无关的纯逻辑**用 TypeScript 实现 + 单测验证,等 DevEco 进 Phase 2 再移植成 ArkTS、接 HarmonyOS API、做 UI。

- ✅ 领域模型 / ADR-0001 数据契约解析 / Provider / 缓存 / 刷新编排
- ✅ **32 个单测全过** + `tsc` strict 类型检查干净
- ⏳ Phase 2:移植 ArkTS、平台绑定(HUKS / Preferences / net.http)、首页 + Form 卡片

## 核心亮点

- **Provider 隔离架构** —— 换数据源不动 UI / 卡片 / 缓存。直连 Key 通路被实测验证可行后,`DirectKeyUsageProvider` 直接落地;未来若官方开放新接口,加一个 Provider 即可。
- **直连 Key 已实测可行** —— 见 [ADR-0001](docs/adr/0001-glm-coding-plan-usage-direct-key.md)。
- **降级策略完善** —— 手动 10s 节流、并发复用、连续失败 3 次暂停 6h、认证 / 解析失败停止自动刷新、离线展示最后有效数据。

## 数据契约(ADR-0001)

```
GET https://open.bigmodel.cn/api/monitor/usage/quota/limit
Headers: Authorization: <你的Key>      （直接，不加 Bearer）
         Content-Type: application/json
         Accept-Language: en-US,en      ← 过反爬的关键，不是 Cookie
```

响应 `data.limits` 按 `type + unit` 映射:

| 字段 | 窗口语义 |
|---|---|
| `TOKENS_LIMIT, unit:3` | 5 小时窗口 |
| `TOKENS_LIMIT, unit:6` | 周窗口 |
| `TIME_LIMIT, unit:5` | 模型级用量(含 `usageDetails`,可作中卡附加展示) |

> 该端点**非公开**(智谱未开放官方用量查询 API),可能随上游规则变化。Provider 内做了结构校验,字段不符时返回 `UPSTREAM_CHANGED`,不会让卡片崩溃。

## 快速开始

```bash
npm install                              # 装 typescript / @types/node
npm test                                 # 跑 32 个单测
npm run check-live -- <你的Key>          # 真实端点连通验证（国内站）
npm run check-live -- <你的Key> intl     # 国际站（api.z.ai）
```

> 临时 Key 用完请在智谱后台吊销,勿提交到仓库。

## 项目结构

```
src/
  domain/types.ts                   领域模型(CodingPlanUsage / Window / 错误码)
  services/parseUsage.ts            ADR 解析(纯函数,unit 映射)
  services/DirectKeyUsageProvider   直连 Provider(HTTP + 解析 + 错误)
  services/ErrorMapper.ts           错误码映射
  services/UsageCache.ts            schemaVersion 缓存
  services/UsageRefreshService.ts   编排(节流 / 并发 / 退避 / 停止)
  platform/node/NodeHttpExecutor    Node HTTP(移植 ArkTS 时的对照参考)
tests/   fixtures + 单测
scripts/check-live.ts              真实端点验证
docs/adr/0001-...                   直连数据契约 ADR
PRODUCT.md / ARCHITECTURE.md        PRD / 架构设计
QUESTIONS.md / REVIEW.md            待澄清需求 / 项目审阅
```

## 文档

- [PRODUCT.md](PRODUCT.md) — 产品需求(PRD)
- [ARCHITECTURE.md](ARCHITECTURE.md) — 架构设计
- [docs/adr/0001-glm-coding-plan-usage-direct-key.md](docs/adr/0001-glm-coding-plan-usage-direct-key.md) — 直连数据契约 ADR
- [REVIEW.md](REVIEW.md) — 项目审阅(事实核查 / grilling 拷问 / 实测确认)
- [QUESTIONS.md](QUESTIONS.md) — 待澄清需求清单

## 路线图

- **Phase 1** ✅ 平台无关核心层(TS + 32 单测)
- **Phase 2** ⏳ DevEco / ArkTS 工程:移植核心 + 平台绑定 + 首页 + Form 卡片
- **Phase 3** ⏳ 前台 / 后台低频刷新、节流退避、本地阈值通知
- **Phase 4**(可选) 桥接数据源 / 多账户

## 参考

- [智谱 GLM Coding Plan](https://docs.bigmodel.cn/cn/coding-plan/overview) — 套餐说明
- [cc-switch discussion #1038](https://github.com/farion1231/cc-switch/discussions/1038) — 用量查询脚本参考
- 用量查询端点与 `unit` 映射,经 cc-switch 实测现场标定确认
