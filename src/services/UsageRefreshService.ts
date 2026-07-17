// UsageRefreshService —— 架构 §4.4 / §5。
// 唯一允许执行刷新流程的服务。负责：节流、并发锁、连续失败退避、认证/解析失败停止自动刷新。
// 平台无关纯逻辑：网络/存储/UI 更新通过依赖注入解耦，移植 ArkTS 时只换实现、编排逻辑不动。

import type { CodingPlanUsage, UsageErrorCode, UsageStatus } from '../domain/types.ts';
import type { UsageCache } from './UsageCache.ts';
import type { MappedError } from './ErrorMapper.ts';

export type RefreshReason = 'manual' | 'foreground' | 'background';

export interface UsageProviderLike {
  fetchUsage(): Promise<CodingPlanUsage>;
}

export interface RefreshSettings {
  /** 后台自动刷新开关（PRD §6.4，默认关） */
  autoRefreshEnabled: boolean;
}

export interface RefreshServiceOptions {
  provider: UsageProviderLike;
  cache: UsageCache;
  /** 注入时钟，便于测试 */
  now: () => number;
  /** 每次刷新结果（成功或失败快照）都触发，供 UI / Form 更新 */
  onRefresh?: (usage: CodingPlanUsage) => void;
  /** 读取用户设置（后台刷新开关等） */
  getSettings?: () => RefreshSettings;
}

// 时长常量（架构 §5.1 / §5.2 / PRD §8.1）
const THROTTLE_MS = 10_000;                    // 手动节流 10s
const FOREGROUND_INTERVAL_MS = 15 * 60_000;    // 前台 15min
const BACKGROUND_INTERVAL_MS = 60 * 60_000;    // 后台 60min
const FAILURE_THRESHOLD = 3;                   // 连续失败 3 次
const AUTO_PAUSE_MS = 6 * 60 * 60_000;         // 暂停自动刷新 6h

/** 这些错误码意味着自动刷新必须停止（仅允许手动），直到用户重新配置/升级 */
const STOP_CODES: ReadonlySet<UsageErrorCode> = new Set(['AUTH', 'NO_PLAN', 'UPSTREAM_CHANGED']);

export class UsageRefreshService {
  private lastFetchStartedAt = 0;
  private lastSuccessAt = 0;
  private consecutiveFailures = 0;
  private autoRefreshPausedUntil = 0;
  private stoppedFor: UsageErrorCode | null = null;
  private lastUsage: CodingPlanUsage | null = null;
  private inFlight: Promise<CodingPlanUsage> | null = null;

  private readonly opts: RefreshServiceOptions;

  constructor(opts: RefreshServiceOptions) {
    this.opts = opts;
  }

  /** 是否允许发起刷新（不实际发起）。供调用方决定是否触发后台任务。 */
  canRefresh(reason: RefreshReason): boolean {
    const now = this.opts.now();
    if (reason === 'manual') {
      // 手动：仅受 10s 节流约束；从未请求过时直接允许（lastFetchStartedAt===0 为哨兵）
      return this.lastFetchStartedAt === 0 || now - this.lastFetchStartedAt >= THROTTLE_MS;
    }
    if (this.stoppedFor !== null) return false;
    if (now < this.autoRefreshPausedUntil) return false;
    if (reason === 'background' && !(this.opts.getSettings?.().autoRefreshEnabled ?? false)) return false;
    const interval = reason === 'foreground' ? FOREGROUND_INTERVAL_MS : BACKGROUND_INTERVAL_MS;
    // 从未成功过时直接允许（建立首次数据）
    return this.lastSuccessAt === 0 || now - this.lastSuccessAt >= interval;
  }

  /**
   * 发起刷新。并发触发复用进行中的 Promise（架构 §4.4）。
   * 总是 resolve 一个 CodingPlanUsage：成功 status=ok；失败保留上次缓存标 stale，或无缓存返回 error 态。
   */
  async refresh(reason: RefreshReason): Promise<CodingPlanUsage> {
    if (this.inFlight) return this.inFlight;
    if (!this.canRefresh(reason)) return this.currentSnapshot();
    const p = this.doRefresh();
    this.inFlight = p;
    try {
      return await p;
    } finally {
      this.inFlight = null;
    }
  }

  /** 当前快照（内存中的最后结果），供不刷新时展示 */
  currentSnapshot(): CodingPlanUsage {
    if (this.lastUsage) return this.lastUsage;
    return {
      session: { usedPercent: 0, remainingPercent: 0 },
      weekly: { usedPercent: 0, remainingPercent: 0 },
      updatedAt: 0,
      source: 'direct',
      status: 'unconfigured',
    };
  }

  /** 冷启动：从持久化缓存加载到内存（架构 §8.3） */
  async hydrateFromCache(): Promise<CodingPlanUsage | null> {
    const cached = await this.opts.cache.load();
    if (cached) {
      this.lastUsage = cached.usage;
      this.lastSuccessAt = cached.usage.updatedAt;
    }
    return cached ? cached.usage : null;
  }

  private async doRefresh(): Promise<CodingPlanUsage> {
    this.lastFetchStartedAt = this.opts.now();
    try {
      const usage = await this.opts.provider.fetchUsage();
      // 成功：覆盖缓存、重置失败计数与暂停/停止状态
      this.lastUsage = usage;
      this.lastSuccessAt = this.opts.now();
      this.consecutiveFailures = 0;
      this.autoRefreshPausedUntil = 0;
      this.stoppedFor = null;
      await this.opts.cache.save(usage);
      this.opts.onRefresh?.(usage);
      return usage;
    } catch (e) {
      const mapped: MappedError = (e as { mapped?: MappedError })?.mapped ?? {
        code: 'UNKNOWN',
        message: '暂时无法获取用量',
      };
      const snapshot = this.applyFailure(mapped);
      this.opts.onRefresh?.(snapshot);
      return snapshot;
    }
  }

  private applyFailure(mapped: MappedError): CodingPlanUsage {
    const { code, message } = mapped;
    if (STOP_CODES.has(code)) {
      // 认证/无套餐/解析失败：停止自动刷新（架构 §5.2）
      this.stoppedFor = code;
    } else {
      // 网络/限流/未知：计入连续失败，达阈值则暂停 6h
      this.consecutiveFailures += 1;
      if (this.consecutiveFailures >= FAILURE_THRESHOLD) {
        this.autoRefreshPausedUntil = this.opts.now() + AUTO_PAUSE_MS;
      }
    }
    const status: UsageStatus = this.lastUsage ? 'stale' : 'error';
    if (this.lastUsage) {
      // 保留最后有效数据，仅改状态与错误信息
      return { ...this.lastUsage, status, errorCode: code, errorMessage: message };
    }
    return {
      session: { usedPercent: 0, remainingPercent: 0 },
      weekly: { usedPercent: 0, remainingPercent: 0 },
      updatedAt: this.opts.now(),
      source: 'direct',
      status: 'error',
      errorCode: code,
      errorMessage: message,
    };
  }
}
