// 领域模型 —— 跨 UI / 缓存 / Provider 的稳定契约（架构 §4.2）
// 平台无关。移植 ArkTS 时本文件可直接改名 types.ets。
//
// 上游字段映射依据 ADR-0001（docs/adr/0001-glm-coding-plan-usage-direct-key.md）：
//   TOKENS_LIMIT unit:3 → session（5 小时窗口）
//   TOKENS_LIMIT unit:6 → weekly（周窗口）
//   TIME_LIMIT  unit:5 → modelUsage（模型级用量，可选）

export type UsageStatus = 'unconfigured' | 'loading' | 'ok' | 'stale' | 'error';

export interface UsageWindow {
  /** 已用百分比，0..100（已 clamp，架构 §4.2 字段要求） */
  usedPercent: number;
  /** 剩余百分比，0..100 */
  remainingPercent: number;
  /** 预计重置时间（Unix 毫秒）。ADR 边界：5h 窗未消耗时上游不返回该字段 → undefined */
  resetAt?: number;
}

export type UsageErrorCode =
  | 'NETWORK'
  | 'AUTH'
  | 'NO_PLAN'
  | 'RATE_LIMITED'
  | 'UPSTREAM_CHANGED'
  | 'UNKNOWN';

export interface ModelUsageItem {
  modelCode: string;
  usage: number;
}

export interface CodingPlanUsage {
  /** 套餐等级，上游可获取时显示（如 "pro"）；否则调用方回退到默认文案 */
  planName?: string;
  /** 5 小时窗口（unit:3） */
  session: UsageWindow;
  /** 周窗口（unit:6） */
  weekly: UsageWindow;
  /** 模型级用量（unit:5，可选）。中卡附加展示 */
  modelUsage?: ModelUsageItem[];
  /** 最近一次成功解析的时间（Unix 毫秒）。仅在成功后更新 */
  updatedAt: number;
  /** 数据来源 */
  source: 'direct' | 'bridge' | 'mock';
  /** 展示状态 */
  status: UsageStatus;
  /** 错误码（status 为 error/stale 时） */
  errorCode?: UsageErrorCode;
  /** 本地映射的用户可读文案，禁止透传上游错误正文 / 敏感内容 */
  errorMessage?: string;
}
