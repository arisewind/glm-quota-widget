// ADR-0001 直连实现：DirectKeyUsageProvider。
// HTTP 通过 HttpExecutor 抽象注入 —— 移植 ArkTS 时只换 executor（@ohos.net.http），本文件逻辑不动。

import type { CodingPlanUsage } from '../domain/types.ts';
import { parseUsageResponse, UpstreamChangedError, type RawUsageResponse } from './parseUsage.ts';
import {
  mapHttpStatus,
  mapNetworkError,
  mapNoPlan,
  mapUpstreamChanged,
  type MappedError,
} from './ErrorMapper.ts';

export type Region = 'cn' | 'intl';

// ADR-0001：国际站仅换 host
const ENDPOINTS: Record<Region, string> = {
  cn: 'https://open.bigmodel.cn/api/monitor/usage/quota/limit',
  intl: 'https://api.z.ai/api/monitor/usage/quota/limit',
};

export interface HttpResponse {
  status: number;
  bodyText: string;
}

/**
 * 可注入的 HTTP 执行器。
 * Node 端用全局 fetch 实现；ArkTS 端用 @ohos.net.http 实现。
 * Provider 核心逻辑只依赖此接口，与具体平台解耦。
 */
export interface HttpExecutor {
  get(url: string, headers: Record<string, string>, timeoutMs: number): Promise<HttpResponse>;
}

/** Provider 抛出的统一错误，携带已映射的错误码与本地文案。 */
export class UsageProviderError extends Error {
  readonly mapped: MappedError;
  constructor(mapped: MappedError) {
    super(mapped.message);
    this.name = 'UsageProviderError';
    this.mapped = mapped;
  }
}

export interface DirectKeyProviderOptions {
  region: Region;
  /** 从安全存储读取 Key 的回调 —— Provider 不持有 Key 明文，用完即由调用方释放 */
  getKey: () => Promise<string | undefined>;
  http: HttpExecutor;
  /** 注入时间函数，便于测试 */
  now?: () => number;
  timeoutMs?: number;
}

export class DirectKeyUsageProvider {
  private readonly opts: DirectKeyProviderOptions;
  constructor(opts: DirectKeyProviderOptions) {
    this.opts = opts;
  }

  async fetchUsage(): Promise<CodingPlanUsage> {
    const key = await this.opts.getKey();
    if (!key) {
      throw new UsageProviderError(mapNoPlan());
    }

    const url = ENDPOINTS[this.opts.region];
    // ADR-0001：Authorization 直接 Key（不加 Bearer）；过反爬的关键是 Content-Type + Accept-Language，不是 Cookie
    const headers: Record<string, string> = {
      Authorization: key,
      'Accept-Language': 'en-US,en',
      'Content-Type': 'application/json',
    };

    let resp: HttpResponse;
    try {
      resp = await this.opts.http.get(url, headers, this.opts.timeoutMs ?? 15_000);
    } catch {
      throw new UsageProviderError(mapNetworkError());
    }

    if (resp.status === 401 || resp.status === 403 || resp.status === 429 || resp.status < 200 || resp.status >= 300) {
      throw new UsageProviderError(mapHttpStatus(resp.status));
    }

    let raw: RawUsageResponse;
    try {
      raw = JSON.parse(resp.bodyText) as RawUsageResponse;
    } catch {
      throw new UsageProviderError(mapUpstreamChanged('json parse failed'));
    }

    // 注意：上游认证失败可能返回 HTTP 200 + body {success:false}（实测 code:1000），
    // 由 parseUsageResponse 的 success 检查兜住 → UpstreamChangedError。
    try {
      return parseUsageResponse(raw, this.opts.now ? this.opts.now() : Date.now());
    } catch (e) {
      if (e instanceof UpstreamChangedError) {
        throw new UsageProviderError(mapUpstreamChanged(e.message));
      }
      throw e;
    }
  }

  /**
   * 连接测试：复用一次真实查询的有效结果，避免「测一次 + 查一次」产生重复请求（架构 §8.1）。
   * 成功即连接可用；失败抛 UsageProviderError。
   */
  async testConnection(): Promise<CodingPlanUsage> {
    return this.fetchUsage();
  }
}
