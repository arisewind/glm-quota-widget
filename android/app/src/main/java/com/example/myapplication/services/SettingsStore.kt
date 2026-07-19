package com.example.myapplication.services

import android.content.Context

/** 非敏感用户设置（普通 Preferences，不存 Key）。 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("glm_quota_settings", Context.MODE_PRIVATE)

    /** 后台刷新是否遍历全部账户：默认 false = 仅 active（省电 + 降低风控）。 */
    fun backgroundRefreshAll(): Boolean = prefs.getBoolean(KEY_BG_ALL, false)

    fun setBackgroundRefreshAll(all: Boolean) {
        prefs.edit().putBoolean(KEY_BG_ALL, all).apply()
    }

    /** 额度告警开关（v3.0）：默认开。关则不检查不发通知。 */
    fun alertEnabled(): Boolean = prefs.getBoolean(KEY_ALERT, true)

    fun setAlertEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT, enabled).apply()
    }

    companion object {
        private const val KEY_BG_ALL = "bg_refresh_all"
        private const val KEY_ALERT = "alert_enabled"
    }
}
