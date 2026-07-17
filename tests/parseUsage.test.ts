import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import { parseUsageResponse, UpstreamChangedError } from '../src/services/parseUsage.ts';

const __dirname = dirname(fileURLToPath(import.meta.url));
const proFixture = JSON.parse(
  readFileSync(join(__dirname, 'fixtures', 'quota-limit-pro.json'), 'utf-8'),
);

test('pro 套餐：5h / 周 / 模型用量映射正确（ADR-0001）', () => {
  const u = parseUsageResponse(proFixture, 1_700_000_000_000);

  assert.equal(u.planName, 'pro');

  // 5h 窗 = TOKENS_LIMIT unit:3, percentage 0
  assert.equal(u.session.usedPercent, 0);
  assert.equal(u.session.remainingPercent, 100);
  assert.equal(u.session.resetAt, undefined, 'ADR 边界：5h 窗未消耗时不返回重置时间');

  // 周窗 = TOKENS_LIMIT unit:6, percentage 19
  assert.equal(u.weekly.usedPercent, 19);
  assert.equal(u.weekly.remainingPercent, 81);
  assert.equal(u.weekly.resetAt, 1784686255977);

  // 模型用量 = TIME_LIMIT unit:5 usageDetails
  assert.deepEqual(u.modelUsage, [
    { modelCode: 'search-prime', usage: 47 },
    { modelCode: 'web-reader', usage: 4 },
    { modelCode: 'zread', usage: 0 },
  ]);

  assert.equal(u.source, 'direct');
  assert.equal(u.status, 'ok');
  assert.equal(u.updatedAt, 1_700_000_000_000);
});

test('percentage 越界 clamp 到 0..100（架构 §4.2 字段要求）', () => {
  const raw = {
    success: true,
    data: {
      level: 'pro',
      limits: [
        { type: 'TOKENS_LIMIT', unit: 3, percentage: 150 },
        { type: 'TOKENS_LIMIT', unit: 6, percentage: -5 },
      ],
    },
  };
  const u = parseUsageResponse(raw, 1);
  assert.equal(u.session.usedPercent, 100);
  assert.equal(u.session.remainingPercent, 0);
  assert.equal(u.weekly.usedPercent, 0);
  assert.equal(u.weekly.remainingPercent, 100);
});

test('缺少核心窗口 → UpstreamChangedError', () => {
  const raw = {
    success: true,
    data: {
      level: 'pro',
      limits: [{ type: 'TOKENS_LIMIT', unit: 3, percentage: 0 }], // 缺 weekly
    },
  };
  assert.throws(() => parseUsageResponse(raw, 1), UpstreamChangedError);
});

test('success=false（如认证失败）→ UpstreamChangedError', () => {
  const raw = { code: 1000, msg: 'Authentication Failed', success: false };
  assert.throws(() => parseUsageResponse(raw, 1), UpstreamChangedError);
});

test('无 data.limits → UpstreamChangedError', () => {
  const raw = { success: true, data: { level: 'pro' } };
  assert.throws(() => parseUsageResponse(raw, 1), UpstreamChangedError);
});

test('modelUsage 缺失时为 undefined（可选字段）', () => {
  const raw = {
    success: true,
    data: {
      level: 'pro',
      limits: [
        { type: 'TOKENS_LIMIT', unit: 3, percentage: 10 },
        { type: 'TOKENS_LIMIT', unit: 6, percentage: 20 },
        // 没有 TIME_LIMIT unit:5
      ],
    },
  };
  const u = parseUsageResponse(raw, 1);
  assert.equal(u.modelUsage, undefined);
  assert.equal(u.session.usedPercent, 10);
  assert.equal(u.weekly.usedPercent, 20);
});
