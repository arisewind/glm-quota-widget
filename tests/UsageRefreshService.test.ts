import { test } from 'node:test';
import assert from 'node:assert/strict';

import { UsageRefreshService, type UsageProviderLike } from '../src/services/UsageRefreshService.ts';
import { UsageCache, type CacheStorage } from '../src/services/UsageCache.ts';
import { UsageProviderError } from '../src/services/DirectKeyUsageProvider.ts';
import type { CodingPlanUsage, UsageErrorCode } from '../src/domain/types.ts';

function sampleUsage(at = 1000): CodingPlanUsage {
  return {
    planName: 'pro',
    session: { usedPercent: 10, remainingPercent: 90 },
    weekly: { usedPercent: 20, remainingPercent: 80, resetAt: 1784686255977 },
    updatedAt: at,
    source: 'direct',
    status: 'ok',
  };
}

function memoryStorage(initial: string | null = null): CacheStorage {
  let data: string | null = initial;
  return {
    read: async () => data,
    write: async (t) => {
      data = t;
    },
    clear: async () => {
      data = null;
    },
  };
}

function mockProvider(): UsageProviderLike & {
  set: (fn: () => Promise<CodingPlanUsage>) => void;
  calls: number;
} {
  let impl: () => Promise<CodingPlanUsage> = async () => sampleUsage();
  const obj: UsageProviderLike & { set: (fn: () => Promise<CodingPlanUsage>) => void; calls: number } = {
    calls: 0,
    fetchUsage: async () => {
      obj.calls++;
      return impl();
    },
    set: (fn) => {
      impl = fn;
    },
  };
  return obj;
}

const failWith = (code: UsageErrorCode, message = 'x') => async (): Promise<CodingPlanUsage> => {
  throw new UsageProviderError({ code, message });
};

function setup() {
  let t = 1000;
  const clock = { now: () => t, advance: (ms: number) => (t += ms) };
  const provider = mockProvider();
  const cache = new UsageCache(memoryStorage());
  const refreshed: CodingPlanUsage[] = [];
  const svc = new UsageRefreshService({
    provider,
    cache,
    now: clock.now,
    onRefresh: (u) => refreshed.push(u),
    getSettings: () => ({ autoRefreshEnabled: true }),
  });
  return { clock, provider, cache, svc, refreshed };
}

test('成功刷新：返回 ok 用量、写缓存、触发 onRefresh', async () => {
  const { svc, cache, refreshed } = setup();
  const u = await svc.refresh('manual');
  assert.equal(u.status, 'ok');
  assert.equal(u.session.usedPercent, 10);
  const loaded = await cache.load();
  assert.equal(loaded?.usage.planName, 'pro');
  assert.equal(refreshed.length, 1);
});

test('手动节流：10s 内不重复请求，返回上次快照', async () => {
  const { svc, provider, clock } = setup();
  await svc.refresh('manual');
  assert.equal(provider.calls, 1);
  clock.advance(5_000);
  assert.equal(svc.canRefresh('manual'), false);
  const u = await svc.refresh('manual');
  assert.equal(provider.calls, 1, '节流内不发请求');
  assert.equal(u.status, 'ok', '返回上次成功快照');
  clock.advance(6_000); // 累计 11s
  assert.equal(svc.canRefresh('manual'), true);
});

test('并发复用：同时两次 refresh 只发一次请求', async () => {
  const { svc, provider } = setup();
  await Promise.all([svc.refresh('manual'), svc.refresh('manual')]);
  assert.equal(provider.calls, 1);
});

test('网络失败：有缓存→stale，保留旧数据', async () => {
  const { svc, provider, clock } = setup();
  await svc.refresh('manual'); // 建立缓存
  provider.set(failWith('NETWORK'));
  clock.advance(11_000);
  const u = await svc.refresh('manual');
  assert.equal(u.status, 'stale');
  assert.equal(u.session.usedPercent, 10, '保留旧数据');
  assert.equal(u.errorCode, 'NETWORK');
});

test('无缓存首次失败 → error 态空 usage', async () => {
  const { svc, provider, clock } = setup();
  provider.set(failWith('NETWORK'));
  clock.advance(11_000);
  const u = await svc.refresh('manual');
  assert.equal(u.status, 'error');
  assert.equal(u.errorCode, 'NETWORK');
  assert.equal(u.session.usedPercent, 0);
});

test('连续失败 3 次 → 暂停自动刷新 6h，自动入口被拒、manual 仍允许', async () => {
  const { svc, provider, clock } = setup();
  provider.set(failWith('NETWORK'));
  for (let i = 0; i < 3; i++) {
    clock.advance(11_000);
    await svc.refresh('manual');
  }
  clock.advance(20 * 60_000); // 过前台/后台间隔
  assert.equal(svc.canRefresh('foreground'), false, '暂停期内 foreground 被拒');
  assert.equal(svc.canRefresh('background'), false, '暂停期内 background 被拒');
  assert.equal(svc.canRefresh('manual'), true, '手动始终允许');
  clock.advance(6 * 60 * 60_000 + 20 * 60_000); // 6h 后 + 间隔
  assert.equal(svc.canRefresh('foreground'), true, '6h 后解除暂停');
});

test('AUTH 失败 → 停止自动刷新，自动入口被拒、manual 允许', async () => {
  const { svc, provider, clock } = setup();
  provider.set(failWith('AUTH'));
  clock.advance(11_000);
  await svc.refresh('manual');
  clock.advance(30 * 60_000);
  assert.equal(svc.canRefresh('foreground'), false);
  assert.equal(svc.canRefresh('background'), false);
  assert.equal(svc.canRefresh('manual'), true);
});

test('NO_PLAN / UPSTREAM_CHANGED 也停止自动刷新', async () => {
  const { svc, provider, clock } = setup();
  for (const code of ['NO_PLAN', 'UPSTREAM_CHANGED'] as UsageErrorCode[]) {
    provider.set(failWith(code));
    clock.advance(11_000);
    await svc.refresh('manual');
    clock.advance(30 * 60_000);
    assert.equal(svc.canRefresh('foreground'), false, `${code} 应停止自动刷新`);
    // 恢复成功以重置状态，进入下一轮
    provider.set(async () => sampleUsage());
    clock.advance(11_000);
    await svc.refresh('manual');
  }
});

test('前台间隔：15min 内 foreground 被拒，过后允许', async () => {
  const { svc, clock } = setup();
  await svc.refresh('manual');
  clock.advance(14 * 60_000);
  assert.equal(svc.canRefresh('foreground'), false);
  clock.advance(2 * 60_000); // 累计 16min
  assert.equal(svc.canRefresh('foreground'), true);
});

test('autoRefreshEnabled=false → background 被拒（即便过了间隔）', async () => {
  let t = 1000;
  const provider = mockProvider();
  const cache = new UsageCache(memoryStorage());
  const svc = new UsageRefreshService({
    provider,
    cache,
    now: () => t,
    getSettings: () => ({ autoRefreshEnabled: false }),
  });
  await svc.refresh('manual');
  t += 120 * 60_000; // 2h
  assert.equal(svc.canRefresh('background'), false);
});

test('hydrateFromCache：冷启动加载缓存到内存快照', async () => {
  const storage = memoryStorage();
  const cache = new UsageCache(storage);
  await cache.save(sampleUsage(5000));
  let t = 9999;
  const provider = mockProvider();
  const svc = new UsageRefreshService({ provider, cache, now: () => t });
  const hydrated = await svc.hydrateFromCache();
  assert.equal(hydrated?.updatedAt, 5000);
  assert.equal(svc.currentSnapshot().planName, 'pro');
});
