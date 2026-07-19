# GLM Quota Widget — 项目审阅与评估

- 审阅日期：2026-07-19
- 审阅范围：v2.0 多服务商架构 + v2.1 列表 widget + v2.2 架构深化（详见 §0；最新 commit 见 git log）
- 方法：产品 + 架构 + 风险三视角
- 关联：[PRODUCT.md](PRODUCT.md) / [ARCHITECTURE.md](ARCHITECTURE.md) / [ROADMAP.md](ROADMAP.md) / [ADR-0002](adr/0002-multi-provider-normalization.md)

> 本文档替代 v1.0 版。v1.0 议题「直连 Key 可行」已由 [ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md) + 真机端到端验证坐实，不再讨论。

---

## 0. v2.2 架构深化（2026-07-19，已实施）

继 v2.1 多账户列表 widget 之后，按 [架构审查报告](architecture-review.html)（deep module / seam / leverage / locality 视角）实施了候选 1/2/3：

| 候选 | 改动 | 收益 |
|---|---|---|
| **1. Provider config 化**（Strong） | 三家 Provider 的重复 5 步 fetch 模板折叠为 `ServiceProviderConfig` 数据表 + 唯一 fetch 实现；删 3 个 Provider 类 + `ServiceProvider` 接口 + `Providers` 工厂 + `testConnection` 死方法 | 加服务商从改 6 处（Models/Providers/VM/Factory/credentialFor/parse）变成 config 表加一行；`UsageParser` 的真 depth 显式化 |
| **2. AccountRepository**（Strong） | 新增读 facade 聚合「账户列表 + 活跃选择 + 缓存读取」；WidgetRenderer/Factory/Worker/VM 四入口收敛 | 消除 `"active_account_id"` 字符串契约复制 3 份；window fallback 业务规则从 Factory 下沉到 `AccountSnapshot.primaryPercent` |
| **3. Worker skip 停止账户**（修 bug） | Worker 读 cache `errorCode`，跳过 `AUTH/NO_PLAN/UPSTREAM_CHANGED` 账户；stop-code 真源 `UsageRefreshService.isStopCode` 共享 | 修「VM 停止的 AUTH 账户被 Worker 每 30min 无限重试」的潜在 bug（风控/浪费） |
| ~~4. 内联 CacheStorage~~（Speculative，**跳过**） | — | 只一个 adapter、test leverage 弱；按 YAGNI 第二个 cache adapter 出现再抽 |

**验证**：编译 + 全量单测（GLM/Kimi/MiniMax Provider 全链路 + Parser）绿。候选 4 待第二 adapter 出现再议。

> 注：本次为架构重构（无新功能、无用户可见变化），未做真机回归——真机验证留到 Kimi/MiniMax 借到 Key 时一并做。

---

## 1. 现状速览

| 维度 | 状态 |
|---|---|
| 功能 | GLM + Kimi + MiniMax 三家 Provider，多账户架构 + 账户管理 UI |
| 验证 | GLM：编译 + 单测 + **真机回归全过**；Kimi/MiniMax：编译 + 单测过，**零真机** |
| 文档 | ADR + ROADMAP + RESEARCH + README + ARCHITECTURE 均同步 v2.2（v2.2 架构深化见 §0） |

---

## 2. 做得好的（继续保持）

1. **Provider 隔离 + UsageSnapshot 归一化** —— 三家同架构，加服务商成本可控（当初做隔离的红利兑现）。
2. **降级策略完善**（节流 10s / 连续失败退避 / 认证失败停止 / 离线缓存）。
3. **单测网** —— 无真实 API 时的解析正确性兜底（10 个，GLM/Kimi/MiniMax）。
4. **v1.x → v2.0 迁移真机验证通过**，不破坏存量用户。
5. **ADR / RESEARCH 文档体系**，决策可追溯。

---

## 3. 真实问题（按严重度，不回避）

### 🔴 发布前必须解决

1. **Kimi / MiniMax 端到端零验证** —— 单测只盖解析。HTTP 调用、认证头、错误路径、UI 添加流程都没跑过。真用户填 Key 踩坑概率高（尤其 MiniMax cookie 鉴权历史、Kimi `resetTime` 格式、各家字段"待实测"项）。
2. **合规欠账** —— GLM / Kimi / MiniMax 的 ToS 都没确认「第三方 App 用用户 Key 查用量」是否允许。Key 被封 / 应用下架风险，一直挂着。

### 🟡 架构 / 技术债

3. **测试覆盖改善中** —— 解析单测(10) + Provider 集成测试(10) 已覆盖核心数据通路（URL/认证/解析/错误映射）；仍缺 AccountStore 迁移测试、缓存 schema 升级测试、VM 逻辑测试（依赖 Android EncryptedSharedPreferences，需 Robolectric/instrumented；迁移已真机验证通过）。
4. **单测 org.json hack** —— 生产用 android org.json、测试用 `org.json:json`，两者边缘行为（数字 / null 解析）可能微妙不一致。
5. **AccountStore 三层迁移**（v1.0 明文 → v1.1 secure → v2.0 Account）逻辑叠太久，未来清理负担。

### 🟢 体验 / 功能

6. ~~多账户价值未发挥~~ **✅ 已解决（v2.1）** —— 阶段 D 多账户列表 widget 已完成（`QuotaListWidgetProvider` + 卡片式 + 品牌色条 + ListView 系统管滚动，账户数任意不崩）。
7. ~~账户切换是 chip、无重命名、Worker 遍历所有账户~~ **部分已解决（v2.1/v2.2）** —— 重命名已实现（`renameAccount`，label 唯一性校验）；Worker 默认仅刷 active + skip stop-code 账户（`SettingsStore.backgroundRefreshAll` 开关）。**仍待办**：账户切换仍是 chip（账户多时挤）、改 Key 仍要删重建。

---

## 4. src/ TS 核心层去留

被 Kotlin 取代，README 谓「平台无关蓝本」。现状是**双份代码**：TS 的 32 单测仍跑但已不反映产品（缺 Kimi/MiniMax），Kotlin 才是真实实现。**当前是中间态** —— 要么冻结 TS 为只读参考，要么删除，避免两头维护。

---

## 5. 下一步优先级

| 优先级 | 事项 |
|---|---|
| 🔴 P0 | Kimi / MiniMax 真机验证（找 Key 或有 plan 用户代测）—— 填发布前最大空缺 |
| 🔴 P0 | 合规确认（至少 GLM ToS）—— 上架前必须 |
| ✅ 已完成 | ~~阶段 D 多账户 widget~~ —— v2.1 已完成（列表 widget + 账户重命名 + Worker 仅刷 active） |
| 🟡 P1 | 补集成测试（Provider mock 全链路） |
| 🟢 P2 | TS src 去留决策 |

---

## 6. 一句话

架构和 GLM 核心链路扎实，多服务商骨架立住了；最大短板是 **Kimi / MiniMax 零真机验证 + 合规欠账**。距离「可放心分发」还差**验证**和**合规**两关，代码本身已不是主要风险。

---

## 7. 参考来源

- 各家契约：[ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md) / [ADR-0002](adr/0002-multi-provider-normalization.md) / [ADR-0003](adr/0003-kimi-usage-direct.md) / [v2-provider-research.md](v2-provider-research.md)
- 架构细节：[ARCHITECTURE.md](ARCHITECTURE.md)
- 路线：[ROADMAP.md](ROADMAP.md)
- 历史误判记录（v1.0「直连死结」已证伪）：见 git 历史 REVIEW.md v1.0
