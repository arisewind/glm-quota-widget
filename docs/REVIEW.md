# GLM Quota Widget — 项目审阅与评估

- 审阅日期：2026-07-19
- 审阅范围：v2.0 多服务商架构（commit 877e67c）
- 方法：产品 + 架构 + 风险三视角
- 关联：[PRODUCT.md](PRODUCT.md) / [ARCHITECTURE.md](ARCHITECTURE.md) / [ROADMAP.md](ROADMAP.md) / [ADR-0002](adr/0002-multi-provider-normalization.md)

> 本文档替代 v1.0 版。v1.0 议题「直连 Key 可行」已由 [ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md) + 真机端到端验证坐实，不再讨论。

---

## 1. 现状速览

| 维度 | 状态 |
|---|---|
| 功能 | GLM + Kimi + MiniMax 三家 Provider，多账户架构 + 账户管理 UI |
| 验证 | GLM：编译 + 单测 + **真机回归全过**；Kimi/MiniMax：编译 + 单测过，**零真机** |
| 文档 | ADR + ROADMAP + RESEARCH + README + ARCHITECTURE 均同步 v2.0 |

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

6. **多账户价值未发挥** —— widget 还是单账户，阶段 D（多账户列表 widget）没做。多账户是 v2.0 核心，但桌面卡片没体现。
7. 账户切换是 chip（账户多了挤）、无重命名 / 改 Key（要删重建）、Worker 遍历刷新所有账户（多账户时耗电 + 风控压力）。

---

## 4. src/ TS 核心层去留

被 Kotlin 取代，README 谓「平台无关蓝本」。现状是**双份代码**：TS 的 32 单测仍跑但已不反映产品（缺 Kimi/MiniMax），Kotlin 才是真实实现。**当前是中间态** —— 要么冻结 TS 为只读参考，要么删除，避免两头维护。

---

## 5. 下一步优先级

| 优先级 | 事项 |
|---|---|
| 🔴 P0 | Kimi / MiniMax 真机验证（找 Key 或有 plan 用户代测）—— 填发布前最大空缺 |
| 🔴 P0 | 合规确认（至少 GLM ToS）—— 上架前必须 |
| 🟡 P1 | 阶段 D 多账户 widget —— 兑现多账户价值 |
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
