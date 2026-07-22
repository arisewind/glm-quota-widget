package com.example.myapplication.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 动效 design token + reduced-motion 单一真源（v3.8 任务8 大审查收敛）。
 *
 * 集中动画时长/弹簧系数，避免魔法数散落；[rememberReduceMotion] 收敛无障碍降级判断，
 * 让所有动效（刷新旋转 / badge 弹 / 数字滚动 / 进度条 / tab slide / pushed scale）一处降级，
 * 与 splash 光标 blink 保持一致（WCAG 2.3.3 前庭安全）。
 */
object Motion {
    object Durations {
        const val SHORT = 180          // pushed 退出 / 单段 fade
        const val MEDIUM = 220         // pushed 进入 / scale
        const val LONG = 700           // 数字滚动 tween
        const val REFRESH_SPIN = 1000  // 刷新 icon 匀速旋转周期（ms）
    }
}

/**
 * 系统是否开启「移除动画」（[Settings.Global.ANIMATOR_DURATION_SCALE] == 0）。
 *
 * 前庭安全：返回 true 时调用方应跳过无限动画 / 过冲 spring / 水平位移，退化为瞬切（snap）
 * 或单段 fade。收敛自 GlintSplash 的内联实现，全局唯一真源。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}
