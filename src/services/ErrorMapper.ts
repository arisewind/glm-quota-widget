// 错误映射（架构 §4.3.1 / §5.2）。
// 把上游 HTTP 状态 / 网络异常 / 解析失败映射成领域错误码 + 本地可读文案。
// 关键约束：errorMessage 不得透传上游错误正文、Authorization 或任何敏感内容（架构 §4.2）。

import type { UsageErrorCode } from '../domain/types.ts';

export interface MappedError {
  code: UsageErrorCode;
  message: string;
}

export function mapHttpStatus(status: number): MappedError {
  if (status === 401 || status === 403) {
    return { code: 'AUTH', message: 'Key 无效、已失效，或不属于可用的 Coding Plan' };
  }
  if (status === 429) {
    return { code: 'RATE_LIMITED', message: '查询过于频繁，请稍后再试' };
  }
  return { code: 'UNKNOWN', message: '暂时无法解析用量，请稍后再试' };
}

export function mapNetworkError(): MappedError {
  return { code: 'NETWORK', message: '网络连接失败，请检查网络后重试' };
}

// detail 仅用于诊断/日志，不进入用户可见文案
export function mapUpstreamChanged(_detail: string): MappedError {
  return { code: 'UPSTREAM_CHANGED', message: '暂时无法解析用量，请更新 App 或稍后再试' };
}

export function mapNoPlan(): MappedError {
  return { code: 'NO_PLAN', message: '尚未配置 Coding Plan Key' };
}
