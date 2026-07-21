# GLM Quota Widget — 项目审阅与评估

- 审阅日期：2026-07-19（v2.x）/ 2026-07-21（v3.0–v3.6 补记，见 §0.5）
- 审阅范围：v2.0 多服务商架构 + v2.1 列表 widget + v2.2 架构深化（§0）+ v3.0–v3.6 告警/设置/配色/续航表/widget 主题（§0.5；最新 commit 见 git log）
- 方法：产品 + 架构 + 风险三视角
- 关联：[PRODUCT.md](PRODUCT.md) / [ARCHITECTURE.md](ARCHITECTURE.md) / [ROADMAP.md](ROADMAP.md) / [ADR-0002](adr/0002-multi-provider-normalization.md)

> 本文档替代 v1.0 版。v1.0 议题「直连 Key 可行」已由 [ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md) + 真机端到端验证坐实，不再讨论。
>
> **2026-07-21 补记**：v3.x 系列功能（额度告警 / 设置独立页 / NordVPN 配色 / 续航表 3-tab / widget 跟随主题）此前已落地代码，但本文档与 ADR 滞后停在 v2.2。本次一次性补齐 [ADR-0004](adr/0004-usage-alerting.md) ~ [ADR-0008](adr/0008-widget-theme-follow.md)，并把审阅范围扩至 v3.6。承认文档此前是短板，现与代码对齐。

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

## 0.5 v3.0–v3.6 进展（2026-07-21 补记）

v2.2 之后功能层持续演进，但本文档与 ADR 此前停在 v2.2，本次补齐 ADR-0004~0008 并把审阅范围扩到 v3.6。各决策详见对应 ADR：

| 版本 | 主题 | ADR | 要点 |
|---|---|---|---|
| v3.0 / v3.2 | 额度告警 | [ADR-0004](adr/0004-usage-alerting.md) | 系统通知主动触达：LOW 85%（默认优先级）/ EXHAUSTED 100%（IMPORTANCE_HIGH 横幅+震动）；AlertStateStore 按账户×窗口 armed 去重；Android13 `POST_NOTIFICATIONS` 运行时权限；NotificationLogStore 留存最近 200 条事件流。v3.2 增 RECOVERY 第三档 + 滞回死区（80–84 死区不动，消除 84↔86 抖动） |
| v3.1 | 7 天趋势 | — | Compose Canvas 自绘折线（时间驱动 X 轴 + 0/100 参考线 + 85% 告警线 + 渐变面积 + 末点标注） |
| v3.2 | 设置独立页 + 系统引导 | [ADR-0005](adr/0005-settings-system-guide.md) | SettingsScreen 6 组（刷新/显示/告警/系统引导/数据/关于）；华为/HarmonyOS 后台限制产品化引导——通知权限、电池白名单、华为启动管理（`Build.MANUFACTURER` 条件渲染 + `resolveActivity` 三级 fallback）；主题切换 |
| v3.3 | NordVPN 配色 | [ADR-0006](adr/0006-nordvpn-color-system.md) | 主蓝 #4687FF；用量色（SAFE 绿 #00B894 / WARN 橙 #F5A623 / DANGER 红 #FF6B6B）与交互蓝分离，消除「正常用量」与「品牌选中」撞色；M3 完整 surfaceContainer 角色 + 8dp Shapes 网格 + 完整 type scale；关闭 dynamicColor |
| v3.4 | 续航表 + 3-tab | [ADR-0007](adr/0007-range-linear-3tab.md) | 续航表用线性卡（非环形 gauge，信息密度高）；底栏 3-tab（续航/账户/设置 NavigationBar）+ pushed 子页 BackHandler 拦截回 tab；UsageThresholds 阈值单一真源（收敛原 4 处发散的 60/85）；UsageMath 纯函数排除 TOOLS；WindowKind 加 TOOLS（智谱 TIME_LIMIT unit:5 工具调用额度，独立维度） |
| v3.5 | 主窗口偏好 + 铃铛 + owlmeter | — | 续航主卡可点 mini 升主（primaryWindowKind 持久化）+ 通知未读 badge（lastSeenNotificationAt）+ owl 风格 FilledTonalIconButton |
| v3.6 | widget 跟随 App 主题 | [ADR-0008](adr/0008-widget-theme-follow.md) | WidgetPalette 代码驱动调色板读 `SettingsStore.themeMode`（light/dark 强制、system 看 uiMode）+ 浅色 drawable；**关键坑**：华为 ROM ProgressBar 删了 `setProgressTint`/`setProgressBackgroundTint` 两个 @RemotableViewMethod（AOSP 有、华为删），调任一 → RemoteViews inflate 失败、widget 白屏；解法 = 进度条颜色只用 XML 静态值，数字用量色走 `setTextColor`（不受影响） |

**验证状态**：v3.x 编译 + 全量单测绿，GLM 真机回归通过；Kimi / MiniMax 仍待借 Key 真机验证（与 v2.x 同一欠账，见 §3 🔴）。阈值单一真源（ADR-0007）+ 滞回死区（ADR-0004）等纯逻辑已有单测覆盖。

---

## 1. 现状速览

| 维度 | 状态 |
|---|---|
| 功能 | GLM + Kimi + MiniMax 三家 Provider，多账户架构 + 账户管理 UI |
| 验证 | GLM：编译 + 单测 + **真机回归全过**；Kimi/MiniMax：编译 + 单测过，**零真机** |
| 文档 | ADR + ROADMAP + RESEARCH + README + ARCHITECTURE 同步 v2.2；v3.0–v3.6 决策本次补齐 ADR-0004~0008（见 §0.5） |

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
7. ~~账户切换是 chip、无重命名、Worker 遍历所有账户~~ **已解决（v2.1/v2.2/v3.4）** —— 重命名已实现（`renameAccount`，label 唯一性校验）；Worker 默认仅刷 active + skip stop-code 账户（`SettingsStore.backgroundRefreshAll` 开关）；账户切换从顶栏 chip 改为底栏 3-tab 导航（[ADR-0007](adr/0007-range-linear-3tab.md) v3.4，账户多时不再挤）。**仍待办**：改 Key 仍要删重建。

---

## 4. src/ TS 核心层去留

被 Kotlin 取代，README 谓「平台无关蓝本」。现状是**双份代码**：TS 的 32 单测仍跑但已不反映产品（缺 Kimi/MiniMax），Kotlin 才是真实实现。**当前是中间态** —— 要么冻结 TS 为只读参考，要么删除，避免两头维护。

---

## 5. 下一步优先级

| 优先级 | 事项 |
|---|---|
| 🔴 P0 | Kimi / MiniMax 真机验证（找 Key 或有 plan 用户代测）—— 填发布前最大空缺 |
| 🔴 P0 | 合规确认（至少 GLM ToS）—— 上架前必须 |
| ✅ 已完成 | ~~阶段 D 多账户 widget~~ —— v2.1（列表 widget + 账户重命名 + Worker 仅刷 active） |
| ✅ 已完成 | ~~设置散落 / 账户切换 chip~~ —— v3.2 设置独立页 6 组（[ADR-0005](adr/0005-settings-system-guide.md)）+ v3.4 底栏 3-tab（[ADR-0007](adr/0007-range-linear-3tab.md)） |
| ✅ 已完成 | v3.0–v3.6 功能层：额度告警 / NordVPN 配色 / 续航表 / widget 跟随主题（[ADR-0004](adr/0004-usage-alerting.md) / [0006](adr/0006-nordvpn-color-system.md) / [0007](adr/0007-range-linear-3tab.md) / [0008](adr/0008-widget-theme-follow.md)），编译 + 单测 + GLM 真机过 |
| 🟡 P1 | 补集成测试（Provider mock 全链路） |
| 🟢 P2 | TS src 去留决策 |

---

## 6. 一句话

架构和 GLM 核心链路扎实，多服务商骨架立住，v3.x 功能层（告警 / 设置 / 配色 / 续航表 / widget 主题）已就绪；最大短板仍是 **Kimi / MiniMax 零真机验证 + 合规欠账**。距离「可放心分发」还差**验证**和**合规**两关，代码本身已不是主要风险（文档此前滞后，2026-07-21 已补齐 ADR-0004~0008）。

---

## 7. 参考来源

- 各家契约：[ADR-0001](adr/0001-glm-coding-plan-usage-direct-key.md) / [ADR-0002](adr/0002-multi-provider-normalization.md) / [ADR-0003](adr/0003-kimi-usage-direct.md) / [v2-provider-research.md](v2-provider-research.md)
- v3.x 决策：[ADR-0004](adr/0004-usage-alerting.md)（告警）/ [ADR-0005](adr/0005-settings-system-guide.md)（设置）/ [ADR-0006](adr/0006-nordvpn-color-system.md)（配色）/ [ADR-0007](adr/0007-range-linear-3tab.md)（续航表 3-tab）/ [ADR-0008](adr/0008-widget-theme-follow.md)（widget 主题）
- 架构细节：[ARCHITECTURE.md](ARCHITECTURE.md)
- 路线：[ROADMAP.md](ROADMAP.md)
- 历史误判记录（v1.0「直连死结」已证伪）：见 git 历史 REVIEW.md v1.0
