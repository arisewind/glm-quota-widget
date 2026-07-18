# GLM Quota Widget

> 手机桌面卡片,一眼查看 GLM Coding Plan 的 **5 小时 / 周额度用量**。本地优先、Key 不上传、离线可看。

**当前状态:Android 版 v1.1 已交付，编译 + 静态验证通过(2026-07-18)。** v1.0 基础上新增服务商名标题、Key 加密存储、前台刷新、开屏页、设置页骨架。后续规划见 [ROADMAP.md](docs/ROADMAP.md)。

---

## 这是什么

[GLM Coding Plan](https://docs.bigmodel.cn/cn/coding-plan/overview) 订阅者经常要确认"5h 窗口 / 周额度还剩多少",但每次开网页、登录、点好几下。本项目做一个手机 App + 桌面小部件,配好 API Key 后,桌面一滑就能看到余量、重置时间、状态;数据本地缓存、离线仍可查看最后一次结果。

**不做**:模型对话、代码生成、账号体系、云端存储、高频刷新。只解决一件事——快速看额度。

## 当前实现

- **Android 工程**:仓库内 [`android/`](android/)(AGP 9.3 / Kotlin 2.2.10 / Jetpack Compose / AppWidget / minSdk 26)
- **核心层**:TypeScript 平台无关核心(`src/`,32 单测全过)已移植为 Kotlin(`domain/` + `services/`)
- **产物**:`app-debug.apk`(13.1MB,v1.1.0/versionCode 2)。v1.0 真机(OCE-AN50 / HarmonyOS 4.2)端到端验证通过;v1.1 新增功能已编译 + aapt2 静态验证,待真机回归。

> **平台演进**:原计划 HarmonyOS 4.2,因 DevEco/hvigor 工具链网络死结转为 **Android**(HarmonyOS 4.2 兼容 APK,可直接装)。TS 核心层完整复用为 Kotlin。

## 核心亮点

- **Provider 隔离架构** —— 换/加数据源不动 UI / widget / 缓存。v2.0 多服务商扩展正得益于此。
- **直连 Key 已实测可行** —— [ADR-0001](docs/adr/0001-glm-coding-plan-usage-direct-key.md),真机端到端跑通。
- **降级策略完善** —— 手动 10s 节流、并发复用、连续失败 3 次暂停 6h、认证 / 解析失败停止自动刷新、离线展示最后有效数据。
- **Key 加密存储** —— v1.1 起 API Key 经 EncryptedSharedPreferences(AES256)存于本机,旧明文一次性自动迁移,Keystore 不可用时安全降级。

## v1.1 新增(2026-07-18)

| 功能 | 说明 |
|---|---|
| 服务商名标题 | App/Widget 标题由 `pro` 改为服务商显示名(智谱 GLM Coding Plan),套餐级降为副标题 |
| Key 加密 | EncryptedSharedPreferences,旧明文凭据自动迁移并清除 |
| 前台刷新 | App 回前台且距上次成功 ≥15min 时静默刷新(走 FOREGROUND reason,不增后台压力) |
| 开屏页 | core-splashscreen,品牌深色背景 + 图标过渡 |
| 设置页 | 服务商/Key 状态/刷新策略/加密说明/版本/清除配置 |
| 应用名 | 桌面图标名改为「智谱额度」 |

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
| `TIME_LIMIT, unit:5` | 模型级用量(含 `usageDetails`) |

> 该端点**非公开**(智谱未开放官方用量查询 API),可能随上游规则变化。Provider 内做了结构校验,字段不符时返回 `UPSTREAM_CHANGED`,不会让卡片崩溃。

## 快速开始(TS 核心层)

```bash
npm install                              # 装 typescript / @types/node
npm test                                 # 跑 32 个单测
npm run check-live -- <你的Key>          # 真实端点连通验证（国内站）
npm run check-live -- <你的Key> intl     # 国际站（api.z.ai）
```

Android 工程:在 [`android/`](android/) 用 `gradlew assembleDebug` 编译(需 JDK 21 + 阿里云镜像,详见 [ROADMAP.md](docs/ROADMAP.md) 环境说明)。

> 临时 Key 用完请在智谱后台吊销,勿提交到仓库。

## 文档导航

| 文档 | 内容 |
|---|---|
| [PRODUCT.md](docs/PRODUCT.md) | 产品需求(PRD) |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构设计(TS 核心,已移植 Kotlin) |
| [ROADMAP.md](docs/ROADMAP.md) | 路线图(v1.1 → v2.0 多服务商) |
| [REVIEW.md](docs/REVIEW.md) | 项目审阅与关键决策记录 |
| [docs/adr/0001-...](docs/adr/0001-glm-coding-plan-usage-direct-key.md) | 直连数据契约 ADR |

## 路线图(摘要)

- ✅ **v1.0**:单 GLM,Android 真机跑通
- ✅ **v1.1**:服务商名标题 / Key 加密 / 前台刷新 / 开屏页 / 设置页骨架(编译 + 静态验证过,待真机回归)
- 🔜 **v2.0**:多服务商(Kimi / GLM / MiniMax / 火山方舟 / Zenmux)+ 竖向列表 widget

详见 [ROADMAP.md](docs/ROADMAP.md)。

## 参考

- [智谱 GLM Coding Plan](https://docs.bigmodel.cn/cn/coding-plan/overview) — 套餐说明
- [cc-switch discussion #1038](https://github.com/farion1231/cc-switch/discussions/1038) — 用量查询脚本参考
- 用量查询端点与 `unit` 映射,经 cc-switch 实测现场标定确认
