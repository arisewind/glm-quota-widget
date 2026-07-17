// Node 端 HttpExecutor 实现（用 Node 22 内置的 global fetch）。
// 移植 ArkTS 时：本文件不移植，在 ArkTS 侧用 @ohos.net.http 写一个等价的 HttpExecutor 即可，
// DirectKeyUsageProvider 的核心逻辑完全不依赖 Node / fetch。

import type { HttpExecutor, HttpResponse } from '../../services/DirectKeyUsageProvider.ts';

export class NodeHttpExecutor implements HttpExecutor {
  async get(url: string, headers: Record<string, string>, timeoutMs: number): Promise<HttpResponse> {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const resp = await fetch(url, { method: 'GET', headers, signal: ctrl.signal });
      const bodyText = await resp.text();
      return { status: resp.status, bodyText };
    } finally {
      clearTimeout(timer);
    }
  }
}
