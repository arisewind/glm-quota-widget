package com.example.myapplication.services

import android.content.Context
import com.example.myapplication.domain.WindowKind

/** 非敏感用户设置（普通 Preferences，不存 Key）。 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("glm_quota_settings", Context.MODE_PRIVATE)

    init {
        migrateLegacyAlert()
    }

    /** v3.2：旧 KEY_ALERT（v3.0/v3.1 单告警开关）一次性迁移到新两档。老用户关过告警的升级后保持关闭。 */
    private fun migrateLegacyAlert() {
        if (!prefs.contains(LEGACY_KEY_ALERT)) return
        val oldEnabled = prefs.getBoolean(LEGACY_KEY_ALERT, true)
        prefs.edit().apply {
            if (!oldEnabled) {
                putBoolean(KEY_ALERT_LOW, false)
                putBoolean(KEY_ALERT_EXHAUSTED, false)
            }
            remove(LEGACY_KEY_ALERT)
        }.apply()
    }

    /** 后台刷新是否遍历全部账户：默认 false = 仅 active（省电 + 降低风控）。 */
    fun backgroundRefreshAll(): Boolean = prefs.getBoolean(KEY_BG_ALL, false)

    fun setBackgroundRefreshAll(all: Boolean) {
        prefs.edit().putBoolean(KEY_BG_ALL, all).apply()
    }

    /** 低额度告警（用量 ≥85%）开关：默认开。 */
    fun alertLowEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_LOW, true)

    fun setAlertLowEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_LOW, enabled).apply()
    }

    /** 额度耗尽（用量 100%）告警开关：默认开（IMPORTANCE_HIGH 横幅+震动）。 */
    fun alertExhaustedEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_EXHAUSTED, true)

    fun setAlertExhaustedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_EXHAUSTED, enabled).apply()
    }

    /** 告警总开关（派生）：两档都关才 false。供 alerter 顶部早退 + onAllArmedClear 判断。 */
    fun alertEnabled(): Boolean = alertLowEnabled() || alertExhaustedEnabled()

    /** 主题模式：默认跟随系统。 */
    fun themeMode(): String = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME, mode).apply()
    }

    /** v3.5：续航页主卡显示哪个窗口（用户偏好），null = 默认 5h 优先回退（见 UsageMath.primaryWindow）。 */
    fun primaryWindowKind(): WindowKind? =
        prefs.getString(KEY_PRIMARY_WINDOW, null)?.let { runCatching { WindowKind.valueOf(it) }.getOrNull() }

    fun setPrimaryWindowKind(kind: WindowKind?) {
        prefs.edit().putString(KEY_PRIMARY_WINDOW, kind?.name).apply()
    }

    /** v3.5：通知已读时间戳（铃铛 badge 用），0L = 全部视为未读。 */
    fun lastSeenNotificationAt(): Long = prefs.getLong(KEY_LAST_SEEN_NOTIF, 0L)

    fun setLastSeenNotificationAt(ts: Long) {
        prefs.edit().putLong(KEY_LAST_SEEN_NOTIF, ts).apply()
    }

    companion object {
        private const val KEY_BG_ALL = "bg_refresh_all"
        private const val KEY_ALERT_LOW = "alert_low"
        private const val KEY_ALERT_EXHAUSTED = "alert_exhausted"
        private const val LEGACY_KEY_ALERT = "alert_enabled"  // v3.0/v3.1 单告警开关，v3.2 迁移后废弃
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PRIMARY_WINDOW = "primary_window_kind"  // v3.5 主窗口偏好（null=默认）
        private const val KEY_LAST_SEEN_NOTIF = "last_seen_notification_at"  // v3.5 通知已读时间戳

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"
    }
}
