package com.example.myapplication.ui

/** 底部主导航 tab（v3.4 续航表重构）。续航=用量主屏，账户/设置为低频配置 tab。 */
enum class Tab(val label: String) {
    RANGE("续航"), ACCOUNTS("账户"), SETTINGS("设置")
}

/** 覆盖在 tab 之上的子页（有系统返回，不属于平级 tab）。 */
enum class PushedScreen { ADD_ACCOUNT, NOTIFICATIONS }
