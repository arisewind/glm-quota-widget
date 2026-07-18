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

    companion object {
        private const val KEY_BG_ALL = "bg_refresh_all"
    }
}
