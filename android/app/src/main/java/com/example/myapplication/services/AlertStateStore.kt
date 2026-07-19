package com.example.myapplication.services

import android.content.Context
import com.example.myapplication.domain.WindowKind

/**
 * 告警去重状态（v3.0）：按 `accountId + 窗口` 记录"已通知到的最高档位"，
 * 防止每次刷新重复通知；降到阈值以下时清除、重新 arm（下次再超阈值会再通知）。
 *
 * 档位：[TIER_NONE]=未通知 / [TIER_LOW]=低额度(85%) / [TIER_EXHAUSTED]=耗尽(100%)。
 * 非敏感状态，存普通 SharedPreferences。
 */
class AlertStateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("glm_quota_alerts", Context.MODE_PRIVATE)

    fun armedTier(accountId: String, kind: WindowKind): Int =
        prefs.getInt(key(accountId, kind), TIER_NONE)

    fun setArmed(accountId: String, kind: WindowKind, tier: Int) {
        prefs.edit().putInt(key(accountId, kind), tier).apply()
    }

    /** 降到阈值以下：清除该窗口 armed 状态，重新 arm（下次超阈值会再通知）。 */
    fun clear(accountId: String, kind: WindowKind) {
        prefs.edit().remove(key(accountId, kind)).apply()
    }

    /** 账户删除时清其所有 armed 状态，防残留。 */
    fun clearAccount(accountId: String) {
        val prefix = "$accountId:"
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { edit.remove(it) }
        edit.apply()
    }

    private fun key(accountId: String, kind: WindowKind) = "$accountId:${kind.name}"

    companion object {
        const val TIER_NONE = 0
        const val TIER_LOW = 1
        const val TIER_EXHAUSTED = 2
    }
}
