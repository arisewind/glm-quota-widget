// 真实端点端到端连通验证（ADR-0001）。
// 用法：
//   npm run check-live -- <apiKey>          # 默认国内站
//   npm run check-live -- <apiKey> intl     # 国际站
// 注意：Key 只在本次进程内存活，用完即弃；请勿提交到仓库。
import { DirectKeyUsageProvider, UsageProviderError } from '../src/services/DirectKeyUsageProvider.ts';
import { NodeHttpExecutor } from '../src/platform/node/NodeHttpExecutor.ts';
import type { Region } from '../src/services/DirectKeyUsageProvider.ts';

const key = process.argv[2];
const region: Region = process.argv[3] === 'intl' ? 'intl' : 'cn';

if (!key) {
  console.error('用法: npm run check-live -- <apiKey> [cn|intl]');
  process.exit(2);
}

const fmtReset = (ts?: number) => (ts ? new Date(ts).toLocaleString() : '重置时间暂不可用');

const p = new DirectKeyUsageProvider({
  region,
  getKey: async () => key,
  http: new NodeHttpExecutor(),
});

try {
  const u = await p.fetchUsage();
  console.log('✓ 直连成功');
  console.log('  套餐等级 :', u.planName ?? '(未知)');
  console.log(`  5h 窗口  : 已用 ${u.session.usedPercent}%  剩余 ${u.session.remainingPercent}%  重置 ${fmtReset(u.session.resetAt)}`);
  console.log(`  周窗口   : 已用 ${u.weekly.usedPercent}%  剩余 ${u.weekly.remainingPercent}%  重置 ${fmtReset(u.weekly.resetAt)}`);
  if (u.modelUsage && u.modelUsage.length) {
    console.log('  模型用量 :', u.modelUsage.map((m) => `${m.modelCode}=${m.usage}`).join('  '));
  }
} catch (e) {
  if (e instanceof UsageProviderError) {
    console.error(`✗ 失败 [${e.mapped.code}] ${e.mapped.message}`);
  } else {
    console.error('✗ 失败:', e);
  }
  process.exit(1);
}
