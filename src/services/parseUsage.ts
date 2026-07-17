// 上游响应 → CodingPlanUsage 解析（ADR-0001 数据契约）。
// 纯函数、平台无关、无副作用 —— 是 DirectKeyUsageProvider 最核心、最该被单测覆盖的部分。
//
// 设计要点：
//  - 结构不符合契约时抛 UpstreamChangedError，由 Provider 捕获后映射为 UPSTREAM_CHANGED（架构 §4.3.1）。
//  - 不保存原始响应、Authorization 或任何敏感字段（架构 §4.2）。
//  - percentage clamp 到 0..100，防御上游越界。

export class UpstreamChangedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'UpstreamChangedError';
  }
}

interface RawUsageDetail {
  modelCode?: string;
  usage?: number;
}

interface RawLimit {
  type?: string;
  unit?: number;
  percentage?: number;
  nextResetTime?: number;
  usageDetails?: RawUsageDetail[];
}

export interface RawUsageResponse {
  code?: number;
  msg?: string;
  success?: boolean;
  data?: {
    level?: string;
    limits?: RawLimit[];
  };
}

const clampPercent = (n: number): number => Math.max(0, Math.min(100, n));

function toWindow(limit: RawLimit | undefined): UsageWindow {
  const used = clampPercent(limit?.percentage ?? 0);
  const resetAt = typeof limit?.nextResetTime === 'number' ? limit.nextResetTime : undefined;
  return { usedPercent: used, remainingPercent: 100 - used, resetAt };
}

import type { CodingPlanUsage, ModelUsageItem, UsageWindow } from '../domain/types.ts';

/**
 * 解析上游用量响应。
 * @param raw 上游 JSON（已 JSON.parse）
 * @param now 当前时间戳（Unix 毫秒），由调用方注入便于测试
 * @throws UpstreamChangedError 当响应结构不符合契约（success=false、缺核心窗口等）
 */
export function parseUsageResponse(raw: RawUsageResponse, now: number): CodingPlanUsage {
  if (!raw || raw.success === false || !raw.data || !Array.isArray(raw.data.limits)) {
    throw new UpstreamChangedError(
      `unexpected upstream shape: code=${raw?.code}, msg=${raw?.msg}, hasData=${!!raw?.data}`,
    );
  }

  const limits = raw.data.limits;
  const find = (type: string, unit: number): RawLimit | undefined =>
    limits.find((l) => l.type === type && l.unit === unit);

  // ADR-0001 映射
  const sessionLimit = find('TOKENS_LIMIT', 3); // 5h 窗
  const weeklyLimit = find('TOKENS_LIMIT', 6); // 周窗
  const modelLimit = find('TIME_LIMIT', 5); // 模型级用量（可选）

  // session / weekly 是 PRD 的核心展示项，缺失即视为上游结构变更
  if (!sessionLimit || !weeklyLimit) {
    const seen = limits.map((l) => `${l.type}:${l.unit}`).join(', ');
    throw new UpstreamChangedError(
      `missing core window(s): session=${!!sessionLimit}, weekly=${!!weeklyLimit}; seen=[${seen}]`,
    );
  }

  const modelUsage: ModelUsageItem[] | undefined = modelLimit?.usageDetails
    ?.map((d) => ({ modelCode: d.modelCode ?? 'unknown', usage: d.usage ?? 0 }));

  return {
    planName: raw.data.level,
    session: toWindow(sessionLimit),
    weekly: toWindow(weeklyLimit),
    modelUsage,
    updatedAt: now,
    source: 'direct',
    status: 'ok',
  };
}
