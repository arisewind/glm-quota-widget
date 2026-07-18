# GLM Coding Plan 用量桌面组件
## 架构设计文档（MVP）

- 文档版本：v1.0
- 状态：可实施
- 目标平台：HarmonyOS 4.2+（ArkTS / Stage 模型 / Form Kit）
- 产品定位：本地优先、单用户、低频刷新的 GLM Coding Plan 用量查看工具

---

## 1. 背景与目标

本项目是一个 HarmonyOS App，并提供桌面服务卡片（Form）。用户配置其 **GLM Coding Plan 专属 API Key** 后，可查看最近一次成功读取的套餐用量，包括：

- 套餐等级（可获取时显示）；
- 5 小时额度已用比例与预计重置时间；
- 周额度已用比例与预计重置时间；
- 最近成功更新时间；
- 用量状态与异常状态。

### 1.1 MVP 目标

1. 不引入自建后端、账号体系或云端数据库。
2. API Key 仅保存在设备本地的安全存储中。
3. 查询结果归一化缓存到本地；网络不可用时仍能展示最后一次结果。
4. App 与服务卡片复用同一份本地用量缓存。
5. 将上游查询逻辑封装为可替换 Provider，避免 UI 依赖特定接口字段。

### 1.2 非目标

以下能力不在 MVP 范围内：

- 多账户切换与团队管理；
- 手机与电脑、手机与手机之间同步；
- 用户登录、云端备份与远程推送；
- 通过普通模型调用推断套餐余额；
- 收集账号密码、验证码、Cookie 或网页登录态；
- 以秒级频率刷新用量。

---

## 2. 设计约束与风险边界

### 2.1 上游接口边界

公开资料可证实智谱提供 Coding Plan 用量查询插件，并且存在由套餐 Key 关联的用量查询能力；但截至本文档编写时，未确认存在一个面向第三方移动 App、公开版本化且长期稳定的用量查询 REST API。

因此：

- 将直连能力定义为 **实验性数据源**；
- 不在 UI 或代码注释中承诺接口永久稳定；
- 具体请求路径、请求头和原始响应字段只允许出现在 `DirectKeyUsageProvider` 内；
- 上游字段或访问规则变动时，只修改 Provider 与其测试，不改 UI、卡片和缓存模型；
- 正式长期方案预留电脑端官方插件桥接数据源。

### 2.2 安全边界

- 只接受用户主动输入的 **Coding Plan 专属 Key**；
- 不收集账号密码、Cookie、浏览器会话或短信验证码；
- Key 不上传至开发者服务器，不进入分析、埋点或崩溃日志；
- Key 不存储在普通 Preferences、数据库或明文文件中；
- 任何界面、通知、异常信息与日志均不得展示完整 Key。

### 2.3 刷新边界

- 服务卡片展示的是本地缓存，不直接在卡片渲染流程内高频请求网络；
- 仅在手动刷新、前台过期刷新或允许的后台任务中读取网络；
- 网络失败保留最后一次有效数据，并标记为过期；
- 认证失败与解析失败停止自动刷新，避免无意义请求。

---

## 3. 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│                         HarmonyOS App                          │
│                                                              │
│  ┌─────────────┐      ┌───────────────────────────────────┐ │
│  │ 页面层       │      │ 服务层                             │ │
│  │ Home/Setup/ │─────▶│ UsageRefreshService                │ │
│  │ Settings    │      │  ├─ UsageProvider                  │ │
│  └─────────────┘      │  │   └─ DirectKeyUsageProvider     │ │
│          ▲             │  ├─ SecureCredentialStore          │ │
│          │             │  ├─ UsageCache                     │ │
│          │             │  └─ WidgetUpdateService            │ │
│          │             └───────────────────────────────────┘ │
│          │                              │                      │
│  ┌───────┴─────────┐                    │                      │
│  │ Form Kit 卡片    │◀───────────────────┘                      │
│  │ FormExtension   │  读取本地缓存并接收更新                   │
│  └─────────────────┘                                           │
└──────────────────────────────────────────────────────────────┘
                                  │
                                  │ HTTPS / 低频
                                  ▼
                   GLM Coding Plan 用量查询通路（实验性）
```

架构分层原则：页面、服务卡片只依赖领域模型；服务层负责编排、缓存与刷新；Provider 负责上游适配；存储层负责 Key 与非敏感缓存的隔离。

---

## 4. 模块设计

### 4.1 页面层（Presentation）

| 页面 | 职责 | 依赖 |
|---|---|---|
| `HomePage` | 展示完整用量、状态、最近更新时间；触发手动刷新 | `UsageRefreshService`、`UsageCache` |
| `SetupPage` | 配置区域与 Key、连接测试、保存配置 | `SecureCredentialStore`、`UsageProvider` |
| `SettingsPage` | 自动刷新、阈值提醒、清除数据、关于与风险说明 | `Preferences`、存储服务 |

页面层不得：

- 拼装上游 HTTP 请求；
- 解析原始上游响应；
- 读取或打印完整 Key；
- 直接更新 Form 数据。

### 4.2 领域层（Domain）

领域层定义跨 UI、缓存和 Provider 的稳定契约。

```ts
export type UsageStatus = 'unconfigured' | 'loading' | 'ok' | 'stale' | 'error'

export interface UsageWindow {
  usedPercent: number           // 0..100
  remainingPercent: number      // 0..100
  resetAt?: number              // Unix 毫秒时间戳
}

export interface CodingPlanUsage {
  planName?: string
  session: UsageWindow          // 5 小时窗口
  weekly: UsageWindow           // 周窗口
  updatedAt: number             // 最近成功更新时间
  source: 'direct' | 'bridge' | 'mock'
  status: UsageStatus
  errorCode?: UsageErrorCode
  errorMessage?: string         // 已映射的用户可读文案，不含原始敏感内容
}

export type UsageErrorCode =
  | 'NETWORK'
  | 'AUTH'
  | 'NO_PLAN'
  | 'RATE_LIMITED'
  | 'UPSTREAM_CHANGED'
  | 'UNKNOWN'
```

字段要求：

- `usedPercent` 与 `remainingPercent` 必须被限制在 0–100；
- `updatedAt` 只在成功解析有效数据后更新；
- 不保存原始响应；
- `errorMessage` 为本地映射文本，禁止透传上游错误正文。

### 4.3 数据源层（Provider）

```ts
export interface UsageProvider {
  testConnection(): Promise<void>
  fetchUsage(): Promise<CodingPlanUsage>
}
```

#### 4.3.1 `DirectKeyUsageProvider`

职责：

1. 按用户选择的套餐区域获取端点配置；
2. 短暂从安全存储读取 Key；
3. 以 HTTPS 请求上游；
4. 将上游响应映射为 `CodingPlanUsage`；
5. 将认证、网络、限流、解析错误映射为领域错误；
6. 不保存原始请求、完整响应或 Authorization 头。

约束：

- 请求超时建议 15 秒；
- 禁止在 URL Query 中传递 Key；
- 日志仅记录请求结果类别、耗时、HTTP 状态类别，且默认关闭；
- 检测到 API 响应结构不符合预期时，返回 `UPSTREAM_CHANGED`；
- 不能通过实际模型推理调用来“探测额度”。

#### 4.3.2 后续扩展：`BridgeUsageProvider`

用于电脑端官方插件或用户自建本地同步服务。其输入仍为 `CodingPlanUsage`，因此页面和卡片无须重构。

```text
电脑端官方用量查询插件 → 用户自有桥接服务 → BridgeUsageProvider → 本地缓存 → 卡片
```

### 4.4 刷新编排层

`UsageRefreshService` 是唯一允许执行刷新流程的服务。

```text
触发刷新
  ↓
检查配置、网络、节流锁与退避状态
  ↓
调用 UsageProvider.fetchUsage()
  ├─ 成功：更新 UsageCache → 更新 Form → 可选评估通知阈值
  └─ 失败：保留最后有效缓存 → 写入安全错误状态 → 更新 Form
```

推荐 API：

```ts
class UsageRefreshService {
  refresh(reason: 'manual' | 'foreground' | 'background'): Promise<CodingPlanUsage>
  canRefresh(reason: 'manual' | 'foreground' | 'background'): boolean
}
```

同一时刻只允许一个请求：并发触发时复用进行中的 Promise，防止页面与卡片重复请求。

### 4.5 存储层

| 存储对象 | 推荐实现 | 内容 | 安全要求 |
|---|---|---|---|
| `SecureCredentialStore` | HarmonyOS 安全凭据/加密存储能力 | Key、可选 Key 标识 | 不明文、不打印、不导出 |
| `UsageCache` | Preferences 或轻量数据库 | `CodingPlanUsage` 的非敏感字段 | 不保存原始响应 |
| `UserSettings` | Preferences | 区域、刷新频率、通知阈值、上次失败信息 | 不存 Key |

`UsageCache` 建议使用可版本化的持久化结构：

```ts
interface CachedUsageV1 {
  schemaVersion: 1
  usage: CodingPlanUsage
}
```

后续结构变更时，使用 `schemaVersion` 迁移或清除缓存，避免旧缓存导致卡片崩溃。

### 4.6 服务卡片层（Form Kit）

| 组件 | 职责 |
|---|---|
| `FormExtensionAbility` | 生命周期、读取缓存、接收点击事件、更新 Form 数据 |
| `UsageWidget` | 根据 Form 数据渲染小卡/中卡 |
| `WidgetUpdateService` | 将缓存的规范化数据转换为 Form 数据并触发更新 |

Form 的数据模型应独立于领域模型：

```ts
interface UsageFormData {
  title: string
  planLabel: string
  sessionPercentText: string
  weeklyPercentText: string
  sessionProgress: number
  weeklyProgress: number
  sessionResetText: string
  weeklyResetText: string
  updatedText: string
  state: 'normal' | 'stale' | 'error' | 'unconfigured'
  actionUri?: string
}
```

服务卡片原则：

- 小卡展示关键百分比和更新时间；
- 中卡展示重置时间与套餐名称；
- 点击卡片打开首页；
- 点击刷新区域发起 App 内手动刷新（如系统 Form 交互能力允许）；
- 不在卡片内显示完整错误、Key 或用户身份信息；
- `FormExtensionAbility` 不承担上游 HTTP 查询职责。

---

## 5. 刷新、缓存与退避策略

### 5.1 触发规则

| 来源 | 条件 | 行为 |
|---|---|---|
| 手动刷新 | 距上次发起请求 ≥ 10 秒 | 立即尝试 |
| App 前台 | 距上次成功刷新 ≥ 15 分钟 | 自动刷新一次 |
| 后台任务 | 用户开启自动刷新，且距上次成功 ≥ 60 分钟 | 尝试刷新 |
| 卡片刷新动作 | 与手动刷新一致 | 交给 App 服务处理 |

MVP 建议默认：自动刷新关闭或设置为 60 分钟；永远提供手动刷新。

### 5.2 错误策略

| 情况 | 缓存处理 | 后续刷新 |
|---|---|---|
| 成功 | 覆盖缓存，更新 `updatedAt` | 重置失败次数 |
| 网络超时 / DNS / 5xx | 保留有效缓存，状态标记 `stale` | 15 分钟后重试 |
| 连续失败 3 次 | 保留有效缓存 | 暂停自动刷新 6 小时，仅允许手动 |
| 401 / 403 | 保留旧数据并标记认证错误 | 停止自动刷新，提示重新配置 |
| 无有效套餐 | 标记 `NO_PLAN` | 停止自动刷新，提示检查套餐 Key |
| 响应无法解析 | 标记 `UPSTREAM_CHANGED` | 停止自动刷新，提示升级 App |

### 5.3 缓存可用性

- 缓存存在且成功刷新时间在 24 小时内：可显示为 `stale`；
- 超过 24 小时：仍可显示历史值，但应突出“数据可能已失效”；
- 未配置或没有任何成功缓存：显示配置引导，不展示虚构用量。

---

## 6. 安全与隐私设计

### 6.1 Key 生命周期

```text
用户输入 Key
  ↓
最小化格式校验（不记录完整值）
  ↓
写入 SecureCredentialStore
  ↓
调用 testConnection
  ├─ 成功：保存非敏感配置和 Key 脱敏指纹
  └─ 失败：允许用户修正或取消；不写入日志
  ↓
查询时临时读取 Key
  ↓
请求结束后释放引用
```

### 6.2 脱敏规则

- 展示格式：`****...ABCD`（仅最后 4 位）；
- 禁止任何日志输出 `Authorization`、请求 Header、请求体中的密钥字段；
- 崩溃报告默认关闭；如未来启用，必须经过统一脱敏处理；
- 用户点击“清除配置”时，删除 Key、缓存、脱敏指纹和刷新状态。

### 6.3 最小权限

仅申请实现功能必须的网络权限；不申请通讯录、相册、定位、短信等无关权限。

### 6.4 隐私声明要点

- App 不建立账号，不上传或共享 API Key；
- Key 仅用于用户主动触发的本地查询；
- 用量数据只保存在本机；
- 用户可随时在设置页清除 Key 和本地数据；
- 直连查询是兼容性功能，可能因上游规则变更而不可用。

---

## 7. 建议项目结构

```text
entry/src/main/ets/
├─ entryability/
│  └─ EntryAbility.ets
├─ pages/
│  ├─ HomePage.ets
│  ├─ SetupPage.ets
│  └─ SettingsPage.ets
├─ domain/
│  ├─ CodingPlanUsage.ets
│  ├─ UsageProvider.ets
│  └─ UsageError.ets
├─ services/
│  ├─ DirectKeyUsageProvider.ets
│  ├─ UsageRefreshService.ets
│  ├─ SecureCredentialStore.ets
│  ├─ UsageCache.ets
│  ├─ UserSettingsStore.ets
│  ├─ WidgetUpdateService.ets
│  └─ NotificationService.ets
├─ widget/
│  ├─ FormExtensionAbility.ets
│  ├─ UsageWidget.ets
│  └─ UsageFormData.ets
├─ utils/
│  ├─ ErrorMapper.ets
│  ├─ TimeFormatter.ets
│  ├─ Percentage.ets
│  └─ Redaction.ets
└─ tests/
   ├─ DirectKeyUsageProvider.test.ets
   ├─ UsageRefreshService.test.ets
   ├─ ErrorMapper.test.ets
   └─ UsageFormMapper.test.ets
```

---

## 8. 关键流程

### 8.1 首次配置

```text
SetupPage
  → 输入区域和 Key
  → 保存到安全存储（临时）
  → DirectKeyUsageProvider.testConnection()
  → 成功：写入区域、Key 指纹、首次 UsageCache；更新卡片；进入首页
  → 失败：映射错误并保留编辑状态；用户选择重试或取消
```

连接测试应尽可能复用一次实际查询的有效结果，避免“测试一次 + 查询一次”产生重复网络请求。

### 8.2 手动刷新

```text
HomePage / Form 点击
  → UsageRefreshService.refresh('manual')
  → 检查 10 秒节流和 in-flight 锁
  → Provider 查询
  → 成功：缓存、页面状态、Form 数据同步更新
  → 失败：保留旧缓存，显示已映射错误，Form 变为 stale/error
```

### 8.3 冷启动与离线展示

```text
EntryAbility 启动
  → 读取 UsageCache
  → 页面立即显示缓存或配置引导
  → 如满足前台刷新条件，后台发起一次刷新
  → 成功后无感更新 UI 和 Form
```

---

## 9. 可测试性与验收

### 9.1 单元测试重点

1. 原始响应映射到 `CodingPlanUsage`；
2. 百分比越界、缺失 `nextResetTime`、空 limits 的容错；
3. 网络、认证、限流、无套餐、结构变化的错误映射；
4. 节流、并发锁、三次失败暂停逻辑；
5. `CodingPlanUsage` 到 `UsageFormData` 的展示映射；
6. Key 脱敏格式；
7. 清除配置后 Key 和缓存均不可读取。

### 9.2 MVP 验收条件

- 用户可在不创建开发者账号的前提下配置自己的 Key；
- Key 不出现在 Preferences、缓存、日志和页面明文中；
- 成功刷新后，App 首页与服务卡片显示同一份数据；
- 断网时仍展示最近成功数据及明确更新时间；
- 认证失败后自动刷新停止；
- 接口响应结构不兼容时，应用不崩溃且能给出“需要更新”的提示；
- 用户可一键删除全部敏感配置和本地数据。

---

## 10. 演进路线

### Phase 1：直连闭环

- Setup、Home、Key 安全存储；
- 手动刷新与用量缓存；
- Provider 适配与错误映射；
- App 页面展示。

### Phase 2：服务卡片

- Form Kit 小卡和中卡；
- 缓存到 Form 数据映射；
- 点击跳转；
- 刷新后主动更新卡片。

### Phase 3：稳定性与提醒

- 前台/后台低频刷新；
- 节流、退避与状态恢复；
- 本地阈值通知；
- 诊断信息与隐私说明页。

### Phase 4：桥接模式（可选）

- 引入 `BridgeUsageProvider`；
- 用户可在“直连实验性 / 电脑桥接推荐”之间选择数据源；
- 复用领域模型、缓存、卡片和通知层。

---

## 11. 实施决策记录

| 决策 | 选择 | 原因 |
|---|---|---|
| 架构形态 | 纯本地、无后端 | 降低成本、隐私和运维复杂度 |
| Key 存储 | 安全存储 | API Key 可用于套餐调用，必须防止明文泄露 |
| 上游接口 | Provider 隔离 | 用量查询通路未确认是稳定第三方公开 API |
| 刷新频率 | 手动优先、低频后台 | 用量无需实时，减少失败与滥用风险 |
| 卡片数据源 | 本地缓存 | 卡片快速、离线可用，减少后台网络复杂度 |
| 异常处理 | 保留最后有效数据 | 增强可用性，避免网络波动导致内容空白 |
| 未来兼容 | Bridge Provider | 便于迁移至官方插件或更正式授权的数据源 |
