package com.example.myapplication.widget

import android.content.Context
import android.content.res.Configuration
import com.example.myapplication.R
import com.example.myapplication.services.SettingsStore

/**
 * Widget 深浅色调色板（v3.6）。Widget 是 RemoteViews（桌面 launcher 进程渲染），
 * 不能用 Compose 主题，颜色需代码动态设；跟随 App [SettingsStore.themeMode]（非系统夜间）。
 *
 * - 深色 = 深夜底系（widget_bg #131A33，对齐 App CardDark）
 * - 浅色 = App 浅色 token（背景 #F7F8FA / 卡白 / 深字 #1A1F2E）
 * - accent 统一 glintapi 冷光蓝 #3B82F6（v3.7 品牌对齐）
 *
 * 用量数字色（绿/橙/红）见 [usageColorInt]，深浅共用（三色在双底上都醒目）。
 */
internal data class WidgetPalette(
    val bgDrawable: Int,       // 根背景 drawable（保留圆角 + stroke）
    val itemDrawable: Int,     // 列表 item 背景 drawable
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int
) {
    companion object {
        val DARK = WidgetPalette(
            bgDrawable = R.drawable.widget_background,
            itemDrawable = R.drawable.item_card_background,
            textPrimary = 0xFFE8EDF5.toInt(),
            textSecondary = 0xFF8A94A6.toInt(),
            accent = 0xFF3B82F6.toInt()
        )

        val LIGHT = WidgetPalette(
            bgDrawable = R.drawable.widget_background_light,
            itemDrawable = R.drawable.item_card_background_light,
            textPrimary = 0xFF1A1F2E.toInt(),
            textSecondary = 0xFF6B7280.toInt(),
            accent = 0xFF3B82F6.toInt()
        )

        /** 跟随 App themeMode：light/dark 强制，system 看系统 uiMode。 */
        fun forContext(context: Context): WidgetPalette {
            val mode = SettingsStore(context.applicationContext).themeMode()
            val dark = when (mode) {
                SettingsStore.THEME_DARK -> true
                SettingsStore.THEME_LIGHT -> false
                else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            }
            return if (dark) DARK else LIGHT
        }
    }
}
