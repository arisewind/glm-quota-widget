// 用量缓存（架构 §4.5）。
// 带 schemaVersion 的可版本化结构 —— 后续字段变更时按版本迁移或清除，避免旧缓存导致 UI/卡片崩溃。
// 序列化逻辑平台无关；具体存储后端（Node fs / ArkTS Preferences）由 CacheStorage 注入。

import type { CodingPlanUsage } from '../domain/types.ts';

const SCHEMA_VERSION = 1;

export interface CachedUsageV1 {
  schemaVersion: 1;
  usage: CodingPlanUsage;
}

export interface CacheStorage {
  read(): Promise<string | null>;
  write(text: string): Promise<void>;
  clear(): Promise<void>;
}

export class UsageCache {
  private readonly storage: CacheStorage;
  constructor(storage: CacheStorage) {
    this.storage = storage;
  }

  async load(): Promise<CachedUsageV1 | null> {
    const text = await this.storage.read();
    if (!text) return null;
    try {
      const obj = JSON.parse(text) as Partial<CachedUsageV1>;
      if (obj.schemaVersion !== SCHEMA_VERSION) {
        // 版本不匹配：当前仅 v1，直接清除（架构 §4.5 的「迁移或清除」决策规则）
        await this.storage.clear();
        return null;
      }
      if (!obj.usage) {
        await this.storage.clear();
        return null;
      }
      return obj as CachedUsageV1;
    } catch {
      await this.storage.clear();
      return null;
    }
  }

  async save(usage: CodingPlanUsage): Promise<void> {
    const payload: CachedUsageV1 = { schemaVersion: SCHEMA_VERSION, usage };
    await this.storage.write(JSON.stringify(payload));
  }

  async clear(): Promise<void> {
    await this.storage.clear();
  }
}
