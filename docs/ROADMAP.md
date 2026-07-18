# GLM Quota Widget · 功能路线图（v1.1 → v2.0）

- 文档版本：v1.0
- 制定日期：2026-07-18
- 状态：已决策、待启动
- 关联：[PRODUCT.md](PRODUCT.md) / [ARCHITECTURE.md](ARCHITECTURE.md) / [REVIEW.md](REVIEW.md)

---

## 1. 背景与当前状态

**v1.0 已交付（2026-07-18，真机验证通过）**：单 GLM 服务商，端到端跑通——核心 TS→Kotlin 移植、ADR-0001 直连、AppWidget 桌面小部件、深色科技风美化、WorkManager 30min 后台刷新。APK 16.9MB，真机（OCE-AN50 / HarmonyOS 4.2）验证：配置→拉取真实用量→桌面 widget 显示数据全链路通。

**本轮目标**：在 v1.0 单服务商基础上，向「多服务商用量聚合」演进，并补齐品牌化与体验。

---

## 2. 本轮决策记录（用户拍板）

| # | 需求 | 决策 | 优先级 |
|---|---|---|---|
| 1 | 开屏页（Splash） | 做，轻量品牌过渡 | 中（随美化） |
| 2 | 多服务商扩展 | **做**：参考 CCSwitch，先支持 Kimi / GLM / MiniMax / Zenmux / 火山方舟 5 家，其它日后 | 高（v2.0 主线） |
| 3 | Widget 多套餐查看 | **竖向列表**（非横向翻页） | 高（随 #2） |
| 4 | 自动刷新频率 | **维持 30min，不动** | — |
| 5 | 卡片标题显示服务商名 | 做，替换当前 "pro" | 高（立刻） |
| 6 | 毛玻璃风格 | **日后再做**，先想兼容性更强的风格 | 低（搁置） |

> 历史评估见对话记录：#3 因 RemoteViews 不支持横向 swipe 改竖向；#4 受 WorkManager 15min 下限 + 上游风控约束，30min 合理；#6 因 widget 不支持真模糊、minSdk 26 老机无硬件模糊，搁置。

---

## 3. 逐项规划

### 3.1 需求 5：服务商名标题（P0，立刻可做）

**问题**：当前 widget/App 标题用 `data.level`（如 "pro"），用户看不出是哪家服务商。

**方案**：
- 引入「服务商显示名」映射：`providerId → 显示名`（智谱 GLM / Kimi / MiniMax / 火山方舟 / Zenmux）。
- 标题改为服务商名，套餐级（pro/max/...）降为副标题或标签。
- 单 GLM 阶段即可先改（不依赖 #2）：标题 `智谱 GLM Coding Plan`，副标题 `pro · 直连实验性`。

**改动**：`Models.kt`（CodingPlanUsage 加 `providerLabel` 字段）、`UsageParser`（写入）、`MainActivity` / `QuotaWidgetProvider`（渲染）。

**工作量**：约 0.5 天。

---

### 3.2 需求 2：多服务商扩展（v2.0 主线）

#### 3.2.1 厂商清单与调研状态

| 厂商 | 所属 | 已知端点 base | 契约状态 | 备注 |
|---|---|---|---|---|
| 智谱 GLM | 智谱 AI | `open.bigmodel.cn`（中）/ `api.z.ai`（国际） | ✅ 已坐实（ADR-0001） | 窗口制（5h + 周） |
| Kimi | 月之暗面 | `api.moonshot.cn`（待核） | ⏳ 待调研 | 参考 CCSwitch |
| MiniMax | MiniMax | `api.minimaxi.com`（待核） | ⏳ 待调研 | 参考 CCSwitch |
| 火山方舟 | 字节跳动 | `ark.cn-beijing.volces.com`（待核） | ⏳ 待调研 | 参考 CCSwitch |
| Zenmux | 待核实 | — | ⏳ 待调研 | **名称待核实**（疑似拼写，需确认 CCSwitch 中确切厂商名） |

> 关键参考源：[farion1231/cc-switch](https://github.com/farion1231/cc-switch)。每家调研产出一份 ADR（端点、认证方式、header 组合、响应字段、额度模型、风控/反爬要点、合规）。

#### 3.2.2 架构改造（复用 Provider 隔离红利）

当前架构已做 Provider 隔离（REVIEW 第四节表扬的亮点），换/加数据源不动 UI/缓存。改造点：

```
v1.0（单服务商）               v2.0（多服务商）
─────────────────              ─────────────────
DirectKeyUsageProvider    →    ServiceProvider 注册表
CredentialStore（单 Key） →    Account 模型 + 多账户存储
UsageCache（单缓存）      →    按 accountId 分键缓存
配置页（单 GLM）          →    账户管理页（增/删/编辑/启停）
CodingPlanUsage           →    通用 UsageSnapshot（+ 各家原始数据保留）
```

**新增/改造**：
1. **`ServiceProvider` 注册表**：`providerId → { 显示名、logo、Provider 工厂、region 配置 }`。每家一个 Provider 实现（`GlmUsageProvider` / `KimiUsageProvider` / ...）。
2. **`Account` 模型**：`{ accountId, providerId, label, key, region, isActive }`。
3. **`CredentialStore` 改多账户**：按 `accountId` 存 Key（顺带把明文 SharedPreferences 换 EncryptedSharedPreferences——分发前必须，见风险）。
4. **`UsageCache` 按 accountId 分键**：`usage_cache_<accountId>`。
5. **领域模型通用化**：抽象 `UsageSnapshot { remaining, used, resetAt, windows[], raw }`，各家把自身额度结构映射进来；GLM 的 5h/周窗口、MiniMax 的余额、火山方舟的 token 包都归一化。
6. **配置页 → 账户管理页**：列表展示已添加账户，点「添加」选服务商 → 输入 Key → 测试连接 → 保存。

#### 3.2.3 分阶段（降低风险）

- **阶段 A（架构）**：抽象 `ServiceProvider` 注册表 + `Account` + 多账户存储/缓存 + 账户管理页。**仅 GLM 接入新架构**，验证重构不破坏 v1.0。
- **阶段 B（第二家）**：接入调研成本最低的一家（建议 Kimi 或 MiniMax），验证通用模型 `UsageSnapshot` 能否容纳非窗口制额度。**这是 go/no-go 关卡**——若模型不通用，回头改抽象。
- **阶段 C（铺开）**：剩余厂商（火山方舟、Zenmux 等），每家一份 ADR + Provider 实现。
- **阶段 D（聚合体验）**：竖向列表 widget（需求 3）、App 内多账户切换。

---

### 3.3 需求 3：竖向列表 Widget（随 #2）

**方案**：单 widget 内用 `ListView`/`GridView`（经 `RemoteViewsService` + `RemoteViewsFactory`）纵向展示多个账户用量。

**依赖**：需求 2 的多账户数据先到位。

**改动**：新增 `QuotaListWidgetProvider` + `RemoteViewsService` + 布局 `widget_quota_list.xml`（每行一个账户：服务商名 + 剩余 + 进度条）。保留 v1.0 的单卡 widget 作为「单账户精简版」。

**注意**：RemoteViews 不支持横向 swipe，竖向列表是 Android widget 下多数据的正解。

---

### 3.4 需求 4：刷新策略（维持现状）

**决策**：后台 30min 不动。理由：WorkManager 最小 15min + 上游风控（实验性直连接口，高频易触发 429/封 Key）。

**配套建议（可选，不强制）**：补「前台刷新」——App 回前台且距上次成功 ≥15min 时静默刷新一次。这是「打开 App 看到最新数据」的正解，不增加后台压力，也不踩风控。可作为 v1.1 体验项。

---

### 3.5 需求 1：开屏页（随美化）

**方案**：`androidx.core:core-splashscreen`，API 12+ SplashScreen + 低版本兼容。品牌 logo + 应用名，过渡 <800ms。

**约束**：不阻塞冷启动缓存展示（PRD 10.2：冷启动先显示缓存/未配置页，不等网络）。

**工作量**：约 0.5 天（加依赖 + splash theme + 一张 logo 资源）。

---

## 4. 版本里程碑

| 版本 | 范围 | 验收 |
|---|---|---|
| **v1.1（体验补齐）** | 需求 5（服务商名）+ 设置页/账户管理页骨架 + Key 加密存储 + 前台刷新 + 需求 1（开屏） | 单 GLM 下体验完整、Key 安全、可分发前就绪 |
| **v2.0（多服务商）** | 需求 2 阶段 A→D + 需求 3（竖向列表 widget） | 至少 GLM + 2 家其它服务商可查询、widget 多账户展示 |
| **v2.1+（扩展）** | 剩余厂商、需求 6（美化新风格）、需求 7（耗尽/低额度通知） | 按需 |

---

## 5. 风险与合规

| 风险 | 等级 | 对策 |
|---|---|---|
| 各家上游接口不稳定/反爬 | 高 | Provider 隔离 + 低频刷新 + 失败优雅降级（沿用 v1.0 策略） |
| 每家 ToS 合规未确认 | 高 | 接入前逐家确认（GLM 至今未问智谱，欠账） |
| 通用额度模型不兼容某家 | 中 | 阶段 B 设为 go/no-go 关卡，不通用则改抽象 |
| Key 明文存储（v1.0 遗留） | 中 | v1.1 强制换 EncryptedSharedPreferences |
| Zenmux 厂商名/契约不明 | 低 | 启动前核实 CCSwitch 确切厂商 |

---

## 6. 下一步行动项

1. **需求 5**（服务商名）——立刻可做，0.5 天。
2. **CCSwitch 调研**——拉取 cc-switch 仓库，确认 Kimi/MiniMax/火山方舟/Zenmux 的用量查询实现（端点、认证、字段），核实「Zenmux」确切名称。
3. **v1.1 打包**——服务商名 + 账户管理页骨架 + Key 加密 + 前台刷新 + 开屏。
4. **v2.0 阶段 A**——架构改造（ServiceProvider 注册表 + 多账户），GLM 接入新架构验证。
5. **v2.0 阶段 B**——第二家接入（go/no-go 关卡）。

---

## 7. 暂不做（记录）

- **需求 6（毛玻璃美化）**：widget 不支持真模糊、老机无硬件模糊，搁置；待选定兼容性更强的视觉风格后再做。
- **需求 7（额度耗尽提示）**：已评估，方向对但「弹窗」应改「系统通知」、且建议与「低额度预警」打包；依赖设置页。待用户确认是否纳入 v1.1。
