package com.example.myapplication.ui

import androidx.annotation.DrawableRes
import com.example.myapplication.R

/** 底部主导航 tab（v3.4 续航表重构）。续航=用量主屏，账户/设置为低频配置 tab。
 *  v3.8：自定义图标 drawable（[outline] 未选描边 / [solid] 选中填充，res/drawable/ic_*），替代 Material 默认三件套。 */
enum class Tab(val label: String, @DrawableRes val outline: Int, @DrawableRes val solid: Int) {
    RANGE("续航", R.drawable.ic_range_outline, R.drawable.ic_range_solid),
    ACCOUNTS("账户", R.drawable.ic_accounts_outline, R.drawable.ic_accounts_solid),
    SETTINGS("设置", R.drawable.ic_settings_outline, R.drawable.ic_settings_solid)
}

/** 覆盖在 tab 之上的子页（有系统返回，不属于平级 tab）。 */
enum class PushedScreen { ADD_ACCOUNT, NOTIFICATIONS }
