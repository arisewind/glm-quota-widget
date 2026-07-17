import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  DirectKeyUsageProvider,
  UsageProviderError,
  type HttpExecutor,
  type HttpResponse,
} from '../src/services/DirectKeyUsageProvider.ts';

const __dirname = dirname(fileURLToPath(import.meta.url));
const proBody = readFileSync(join(__dirname, 'fixtures', 'quota-limit-pro.json'), 'utf-8');

function executor(resp: HttpResponse | Error): HttpExecutor {
  return {
    get: async () => {
      if (resp instanceof Error) throw resp;
      return resp;
    },
  };
}

function expectCode(code: string) {
  return (e: unknown) => {
    assert.ok(e instanceof UsageProviderError, 'should be UsageProviderError');
    assert.equal((e as UsageProviderError).mapped.code, code);
    return true;
  };
}

test('成功：返回按 ADR 映射的用量', async () => {
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'fake-key',
    http: executor({ status: 200, bodyText: proBody }),
    now: () => 12345,
  });
  const u = await p.fetchUsage();
  assert.equal(u.planName, 'pro');
  assert.equal(u.session.usedPercent, 0);
  assert.equal(u.weekly.usedPercent, 19);
  assert.equal(u.updatedAt, 12345);
  assert.equal(u.source, 'direct');
});

test('无 Key → NO_PLAN', async () => {
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => undefined,
    http: executor({ status: 200, bodyText: proBody }),
  });
  await assert.rejects(() => p.fetchUsage(), expectCode('NO_PLAN'));
});

test('网络异常 → NETWORK', async () => {
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'k',
    http: executor(new Error('ETIMEDOUT')),
  });
  await assert.rejects(() => p.fetchUsage(), expectCode('NETWORK'));
});

test('HTTP 401 → AUTH', async () => {
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'k',
    http: executor({ status: 401, bodyText: '{}' }),
  });
  await assert.rejects(() => p.fetchUsage(), expectCode('AUTH'));
});

test('HTTP 429 → RATE_LIMITED', async () => {
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'k',
    http: executor({ status: 429, bodyText: '{}' }),
  });
  await assert.rejects(() => p.fetchUsage(), expectCode('RATE_LIMITED'));
});

test('HTTP 200 但 body success=false（实测认证失败场景）→ UPSTREAM_CHANGED', async () => {
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'k',
    http: executor({
      status: 200,
      bodyText: JSON.stringify({ code: 1000, msg: 'Authentication Failed', success: false }),
    }),
  });
  await assert.rejects(() => p.fetchUsage(), expectCode('UPSTREAM_CHANGED'));
});

test('响应非 JSON → UPSTREAM_CHANGED', async () => {
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'k',
    http: executor({ status: 200, bodyText: '<html>not json</html>' }),
  });
  await assert.rejects(() => p.fetchUsage(), expectCode('UPSTREAM_CHANGED'));
});

test('headers 严格符合 ADR-0001：直接 Key + Content-Type + Accept-Language', async () => {
  let captured: Record<string, string> = {};
  const exec: HttpExecutor = {
    get: async (_url, h) => {
      captured = h;
      return { status: 200, bodyText: proBody };
    },
  };
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'my-secret-key',
    http: exec,
  });
  await p.fetchUsage();
  assert.equal(captured['Authorization'], 'my-secret-key', '直接 Key，不加 Bearer');
  assert.equal(captured['Content-Type'], 'application/json');
  assert.equal(captured['Accept-Language'], 'en-US,en');
});

test('国际站端点切换', async () => {
  let capturedUrl = '';
  const exec: HttpExecutor = {
    get: async (url) => {
      capturedUrl = url;
      return { status: 200, bodyText: proBody };
    },
  };
  const p = new DirectKeyUsageProvider({
    region: 'intl',
    getKey: async () => 'k',
    http: exec,
  });
  await p.fetchUsage();
  assert.ok(capturedUrl.startsWith('https://api.z.ai/'), capturedUrl);
});

test('testConnection 复用 fetchUsage（不重复请求）', async () => {
  let calls = 0;
  const exec: HttpExecutor = {
    get: async () => {
      calls++;
      return { status: 200, bodyText: proBody };
    },
  };
  const p = new DirectKeyUsageProvider({
    region: 'cn',
    getKey: async () => 'k',
    http: exec,
  });
  const u = await p.testConnection();
  assert.equal(u.planName, 'pro');
  assert.equal(calls, 1, '只发了一次请求');
});
