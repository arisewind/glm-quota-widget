import { test } from 'node:test';
import assert from 'node:assert/strict';

import { UsageCache, type CacheStorage } from '../src/services/UsageCache.ts';
import type { CodingPlanUsage } from '../src/domain/types.ts';

function mkStorage(initial: string | null = null) {
  const data: { value: string | null } = { value: initial };
  const storage: CacheStorage = {
    read: async () => data.value,
    write: async (t) => {
      data.value = t;
    },
    clear: async () => {
      data.value = null;
    },
  };
  return { storage, snapshot: () => data.value };
}

function sampleUsage(): CodingPlanUsage {
  return {
    planName: 'pro',
    session: { usedPercent: 0, remainingPercent: 100 },
    weekly: { usedPercent: 19, remainingPercent: 81, resetAt: 1784686255977 },
    updatedAt: 1,
    source: 'direct',
    status: 'ok',
  };
}

test('save → load 往返一致', async () => {
  const { storage } = mkStorage();
  const cache = new UsageCache(storage);
  await cache.save(sampleUsage());
  const loaded = await cache.load();
  assert.equal(loaded?.schemaVersion, 1);
  assert.equal(loaded?.usage.planName, 'pro');
  assert.equal(loaded?.usage.weekly.usedPercent, 19);
});

test('schemaVersion 不匹配 → 清除并返回 null', async () => {
  const { storage, snapshot } = mkStorage(JSON.stringify({ schemaVersion: 99, usage: {} }));
  const loaded = await new UsageCache(storage).load();
  assert.equal(loaded, null);
  assert.equal(snapshot(), null, '旧版本缓存被清除');
});

test('损坏的 JSON → 清除并返回 null', async () => {
  const { storage, snapshot } = mkStorage('{not valid json');
  const loaded = await new UsageCache(storage).load();
  assert.equal(loaded, null);
  assert.equal(snapshot(), null);
});

test('空存储 → null', async () => {
  const { storage } = mkStorage(null);
  assert.equal(await new UsageCache(storage).load(), null);
});

test('clear 清空存储', async () => {
  const { storage, snapshot } = mkStorage();
  const cache = new UsageCache(storage);
  await cache.save(sampleUsage());
  assert.ok(snapshot() !== null);
  await cache.clear();
  assert.equal(snapshot(), null);
});
