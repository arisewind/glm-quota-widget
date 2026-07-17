# GLM Quota Widget — 项目审阅报告（grilling 式）

- 审阅对象：[PRODUCT.md](PRODUCT.md) / [ARCHITECTURE.md](ARCHITECTURE.md)
- 审阅日期：2026-07-18
- 审阅方法：用新装的 `grilling` skill（事实查清、决策逐个拷问、每题给推荐答案）+ `improve-codebase-architecture` 的架构视角（depth/seam/locality）
- 与上次 [QUESTIONS.md](QUESTIONS.md) 的区别：上次把上游 API 列为**疑问**，这次按 grilling 原则**先核查事实再拷问**——结论因此发生了质变。

---

## ⚠️ 重要勘误（2026-07-18，用户实证后修正）

> **第二节的"致命结论"作废——直连 Key 路径实际可行。**

**推翻证据**：
1. **用户一手实证**：在 cc-switch 里输入个人 API Key，成功检测到余量。
2. **cc-switch 社区脚本**（[discussion #1038](https://github.com/farion1231/cc-switch/discussions/1038)，yrom 提供，已被他人回复"解决了🙏")用的就是 **API Key** 调同一个端点：

```
GET https://open.bigmodel.cn/api/monitor/usage/quota/limit
Headers: Authorization: {{apiKey}}, Accept-Language: en-US,en, Content-Type: application/json
```

**为什么之前引用的 coding-plan-monitor 会失败？** 多半是它自己的请求方式问题（curl/header 不全），而非接口不接受 Key。我轻信了单一来源、没交叉验证——这正是 grilling 该避免的错。向用户致歉。

**修正后的核心结论**：
- ✅ PRD 核心假设（用户输 Key → App 用 Key 直连查用量）**成立**。
- ✅ `DirectKeyUsageProvider` 可实现：GET `quota/limit` + `Authorization: <Key>` + 一组特定 header。
- ✅ **不需要**改桥接、**不需要**放宽"不做浏览器自动化"边界。PRD 现状可推进编码。
- ⚠️ **真正的技术要点**：能通过反爬的是 **header 组合**（`Content-Type: application/json` + `Accept-Language` 等），不是 Cookie。实现时要严格复刻 cc-switch 那组 header，并用真实 Key 实测验证。

**据此作废/降级**的部分（基于已被推翻的"死结"前提）：
- 第二节"致命结论/死结" → **作废**。
- 第三节 D1（MVP 怎么实现）、D2（改 PRD 边界）、D5（BridgeUsageProvider 提到 P0）→ **作废**，维持 PRD 原案。
- 第一节中"端点不接受 API Key 认证，只接受浏览器 Cookie"那一行 → **错误**，应为"支持 Key 认证，但对请求 header 有要求"。

**仍然有效**的部分：
- Provider 隔离是架构亮点（第四节 depth/seam 评估）。
- D3（合规：开发前问智谱"第三方用 Key 查询是否允许"）、D6（`type` 字段对齐：实测有 `TIME_LIMIT` / `TOKENS_LIMIT`，需确认哪个对应 5h 窗、哪个对应周/月）、D7（保持低频刷新 + 失败优雅降级，降低被风控概率）。
- 安全/缓存/降级设计评估。

### 实测确认（2026-07-18，临时 Key 直连）

用临时 API Key 直连端点，**方式 A（`Authorization: 直接 Key`，cc-switch 方式）一次成功**，HTTP 200：

```
GET https://open.bigmodel.cn/api/monitor/usage/quota/limit
Headers: Authorization: <Key>, Accept-Language: en-US,en, Content-Type: application/json
```

响应（pro 套餐，关键字段）：

```
level: pro
limits: [
  { type: TOKENS_LIMIT, unit: 3, number: 5, percentage: 0 },
  { type: TOKENS_LIMIT, unit: 6, number: 1, percentage: 19, nextResetTime },
  { type: TIME_LIMIT,  unit: 5, number: 1, percentage: 5, nextResetTime,
    usageDetails: [ {search-prime:47}, {web-reader:4}, {zread:0} ] }
]
success: true
```

**结论**：
1. 直连 Key 完全可行，彻底坐实——`DirectKeyUsageProvider` 可落地。
2. 额度模型比 PRD 假设复杂：`limits` 有 **3 项**（社区文档只见过 2 项）、`TOKENS_LIMIT` / `TIME_LIMIT` 两种 type、带 `unit`(3/6/5) 周期字段、`TIME_LIMIT` 还按模型细分（`usageDetails`）。
3. **`unit` → 窗口语义映射已确认**（2026-07-18，经 cc-switch 现场标定）：
   - `TOKENS_LIMIT, unit:3` = **five_hour（5 小时窗口）** —— cc-switch 显示 `five_hour: 0%`
   - `TOKENS_LIMIT, unit:6` = **weekly（周窗口）** —— cc-switch 显示 `weekly_limit: 19%`
   - `TIME_LIMIT, unit:5` = 模型级用量（带 `usageDetails`：search-prime / web-reader / zread），cc-switch 未展示
4. **PRD 领域模型映射**（`DirectKeyUsageProvider` 解析逻辑，已可落地）：
   ```
   planName   = data.level                                   // "pro"
   fiveHour   = limits.find(l => l.type==='TOKENS_LIMIT' && l.unit===3)   // percentage + nextResetTime
   weekly     = limits.find(l => l.type==='TOKENS_LIMIT' && l.unit===6)   // percentage + nextResetTime
   modelUsage = limits.find(l => l.type==='TIME_LIMIT'  && l.unit===5)?.usageDetails  // 可选，中卡加分
   ```
5. **一个已验证的边界细节**:5h 窗在 `percentage=0`(未消耗)时**不返回 `nextResetTime`**——PRD 6.2「无值时显示'重置时间暂不可用'」的降级设计正好对上,不是 bug 是上游如此。

---

## 一、命门事实核查（已查证，不再是疑问）

上次 QUESTIONS.md 的 Q1「上游用量查询 API 是否存在」、Q14「是否合规」，现均已有公开证据：

| 事实 | 证据 |
|---|---|
| 用量查询端点**确实存在**：`https://open.bigmodel.cn/api/monitor/usage/quota/limit` | 社区项目实测、智谱用量页 `open.bigmodel.cn/usercenter/glm-coding/usage` 调用 |
| 响应结构**印证了 PRD 额度假设**：`{limits:[{type,percentage,nextResetTime}], level}`，含套餐等级、窗口百分比、重置时间 | 实测响应（见下） |
| **端点不接受 API Key 认证，只接受浏览器 Cookie** | curl + Bearer Token 返回空 body（HTTP 200）；Puppeteer / Playwright headless 全被反爬检测拦截 |
| 唯一可行：CDP 连接用户**已登录的真实 Chrome** | 多个社区项目实测结论 |
| Coding Plan **仅限官方工具使用**（Claude Code/Cline/ZCode），其他环境调用 API 不消耗套餐额度、按标准计费 | [智谱 FAQ 官方原文](https://docs.bigmodel.cn/cn/coding-plan/faq) |
| 官方有 `glm-plan-usage` 插件，但仅在 Claude Code 内、且只支持个人版 | [官方插件文档](https://docs.bigmodel.cn/cn/coding-plan/extension/usage-query-plugin) |

实测响应示例（来自社区项目 [JinHanAI/coding-plan-monitor](https://github.com/JinHanAI/coding-plan-monitor)，调查日期 2026-03-17）：

```json
{
  "code": 200, "msg": "操作成功", "success": true,
  "data": {
    "level": "pro",
    "limits": [
      { "type": "TIME_LIMIT",  "percentage": 33, "nextResetTime": 1774663282997 },
      { "type": "TOKENS_LIMIT","percentage": 32, "nextResetTime": 1773734366338 }
    ]
  }
}
```

**这条事实链的价值**：PRD 假设的「5 小时窗口 + 周窗口 + 套餐等级 + 重置时间」**数据结构是真的**，但 `type` 实际是 `TIME_LIMIT` / `TOKENS_LIMIT`（时间窗 vs token 窗），和 PRD 文案的「5h/周」需要对齐确认。

---

## 二、致命结论：MVP 的核心数据通路在当前条件下不可行

PRD 与架构反复出现的措辞是「直连**实验性**」「未确认存在公开 REST API」。grilling 的拷问会把这层措辞撕掉：

> **「实验性」暗示「不稳定但能用」。现实是「用 Key 根本拿不到数据」。这两者天差地别。**

`DirectKeyUsageProvider` 的实现路径——「HTTPS + Authorization: Bearer <Key>」——**被智谱的反爬 + 仅 Cookie 认证直接堵死**。不是接口会变，是**今天就调不通**。而 PRD 同时把以下列为**明确不做**：

- 账号密码、Cookie、扫码登录、浏览器自动化（PRODUCT 3.2 / 架构 2.2）

于是出现一个无法两全的死结：

```
能拿到数据的唯一方式 = 用户浏览器 Cookie / CDP 连真实 Chrome
PRD 明确禁止的     = Cookie / 登录态 / 浏览器自动化
```

**结论**：按 PRD 现状进入编码，MVP 的 P0「读取用量」无法验收（验收标准要求「成功读取后可断网查看」）。这不是风险，是阻塞。

---

## 三、grilling 拷问（决策树 + 推荐答案）

grilling 的规则：**能查的事实不问用户，只把决策摆出来给推荐答案**。以下每个决策点都附我的推荐。

### 🔴 D1. 既然 Key 拿不到用量，MVP 怎么实现「读取用量」？
- (a) 等智谱开放 Key 认证用量接口 —— **无时间表，等于搁置项目**
- (b) CDP 桥接用户已登录的 Chrome —— 可行但重，且**违反 PRD 现有边界**
- (c) 放弃「手机直连」，改做「读取用户电脑端已查到的用量」的前端
- **推荐**：走 (c)，把电脑端桥接从 P2 提到 MVP 核心。手机只负责「展示 + 通知」，不负责「取数」。这同时绕开合规风险（D3）。

### 🔴 D2. PRD 的「不做 Cookie/登录态/浏览器自动化」边界，改不改？
- 不改 → 项目无法落地（见死结）
- 改 → 产品形态从「本地优先、零依赖」变成「依赖用户电脑常驻一个取数服务」
- **推荐**：改。但要把代价写进 PRD：用户需在电脑上跑一个轻量同步服务（用 CDP 连 Chrome 抓 `quota/limit`，写入本地/局域网可达的缓存），手机读这个缓存。这是唯一既拿到数据、又相对可控的路径。

### 🟡 D3. 用第三方 App 查 Coding Plan 用量，合规吗？
- 智谱 FAQ：Coding Plan 仅限官方工具使用，**其他环境调用不消耗套餐额度**。
- 注意：`quota/limit` 是**查询**接口（不消耗额度），理论上查询本身不触发「按标准计费」。但「用非官方手段（Cookie/CDP）抓取」是否违反 ToS，需智谱确认。
- **推荐**：开发前邮件问智谱客服两点——①第三方工具用 Cookie 查询 `quota/limit` 是否允许；②未来是否会开放 Key 认证的查询接口。把答复存档，作为 ADR。

### 🟡 D4. Provider 隔离架构还有没有用？
- **有用，而且是本文档最大的亮点**。`UsageProvider` 接口 + `UsageRefreshService` 单点编排 + Form 数据模型独立于领域模型——这套分层是教科书级的 seam 设计：**换数据源不需要动 UI / 卡片 / 缓存**。
- 唯一要改：`DirectKeyUsageProvider` 这个实现是死的，应替换为 `CdpBridgeUsageProvider`（读电脑端桥接服务）。**接口和上层一行都不用改**——这正是当初做隔离的回报。
- **推荐**：架构保留，只换 Provider 实现。顺便把 `source: 'direct'` 字段的语义从「直连」改为「桥接」。

### 🟡 D5. BridgeUsageProvider 原本在 P2，现在该不该提到 P0？
- **推荐**：提到 P0。它从「可选退路」变成了「唯一通路」。版本规划（PRODUCT 12 / 架构 10）需要重排：Phase 1 不再是「直连闭环」，而是「桥接闭环」。

### 🟢 D6. 额度模型的 type 对齐
- 实测 `type` = `TIME_LIMIT` / `TOKENS_LIMIT`，PRD 写的是「5h / 周」。需确认：`TIME_LIMIT` 是不是就是 5 小时窗？`TOKENS_LIMIT` 是周还是月？（社区项目输出里写的是「Token 5h Window」和「MCP Monthly」——和 PRD 的「周」对不上）
- **推荐**：用真实账号登录网页看一次用量页，把两个 limit 的确切语义和重置周期记下来，写进领域模型。

### 🟢 D7. 反爬检测对桥接方案的稳定性影响
- 即使用 CDP 连真实 Chrome，智谱仍可能加强检测（指纹/频率）。桥接服务要：低频（≥30 分钟）、带真实浏览器指纹、失败优雅降级。
- **推荐**：架构 5.2 的「连续失败 3 次暂停 6 小时」策略保留，但默认刷新间隔从 60 分钟放宽到更长，降低被风控概率。

---

## 四、架构视角（depth / seam / locality）

用 `improve-codebase-architecture` 的词汇表（虽无代码，可审设计）：

| 设计 | 评价 |
|---|---|
| `UsageProvider` 抽象（testConnection / fetchUsage） | ✅ 深 module，好 seam——换实现不动上层。**保留** |
| `UsageRefreshService` 单点编排 + 并发锁 + 节流 | ✅ locality 强，刷新逻辑集中。**保留** |
| Form 数据模型独立于领域模型 | ✅ 好 seam，卡片渲染不被领域变更牵连。**保留** |
| `DirectKeyUsageProvider` | ❌ 死实现（Key 调不通）。**替换为桥接实现** |
| 领域模型 `CodingPlanUsage` | ✅ 字段设计与上游响应契合度高（仅 `type` 语义要对齐）|
| 缓存 `schemaVersion` 迁移 | 🟡 规则未定义（迁移 vs 清除），补一条决策规则即可 |

**一句话**：架构没有需要推倒的部分，**唯一错的根是数据通路的假设**。把根换掉，上面的树都成立。

---

## 五、需要你拍板的决策（grilling：决策是你的）

1. **产品形态**：坚持「手机直连」（需等智谱开 API，时间不定）？还是接受改型为「手机展示 + 电脑桥接取数」？
2. **PRD 边界**：是否同意放宽「不做浏览器自动化」边界，允许电脑端桥接服务用 CDP 连 Chrome？
3. **合规**：要不要我先帮你起草一封给智谱的问询邮件（确认 Cookie 查询合规性 + 是否会开放 Key 查询）？
4. **MVP 重排**：是否同意把 BridgeUsageProvider 从 P2 提到 P0，Phase 1 改为「桥接闭环」？

---

## 六、总评

- **文档质量**：PRD/架构仍是高质量产物——边界清晰、安全设计到位、Provider 隔离是亮点。这份功夫没有白费。
- **核心问题**：MVP 押在了一条**今天就不通**的数据通路上。措辞「实验性」掩盖了「不可行」这个更严重的事实。
- **建议**：不要按现状进入编码。先用本次事实重新定位产品形态（直连→桥接），改 PRD 边界与版本规划，再启动。架构本身基本可沿用。

**好消息**：因为当初做了 Provider 隔离，这次「换根」的成本被控制在了 Provider 实现这一层——这正好印证了那份架构文档的价值。

---

### 参考来源
- [智谱 GLM Coding Plan 套餐概览](https://docs.bigmodel.cn/cn/coding-plan/overview)
- [智谱 Coding Plan FAQ（限官方工具使用）](https://docs.bigmodel.cn/cn/coding-plan/faq)
- [官方用量查询插件 glm-plan-usage](https://docs.bigmodel.cn/cn/coding-plan/extension/usage-query-plugin)
- [JinHanAI/coding-plan-monitor（社区实测：智谱接口不支持 Key、反爬严格）](https://github.com/JinHanAI/coding-plan-monitor)
- [cc-switch 用量查询脚本讨论](https://github.com/farion1231/cc-switch/discussions/1038)
