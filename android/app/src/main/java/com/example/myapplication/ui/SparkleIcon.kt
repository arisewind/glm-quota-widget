package com.example.myapplication.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.BrandAccent
import com.example.myapplication.ui.theme.BrandPrimary

/**
 * GlintAPI sparkle 四角星图标（v3.8 方案 B 浅色续航页品牌点缀）。
 *
 * 复用 [GlintSplash] 里 SparkleLogo 的 cubicTo path（24~84 安全区，108x108 viewBox），
 * 蓝→青对角 [Brush.linearGradient] + 中心白点。慢呼吸（α 0.55↔1f，3.2s 周期）模拟
 * "灵感闪光"——深色 splash 的"闪烁"在浅色续航页会刺眼，改极慢呼吸含蓄表达。
 *
 * 无障碍：[breathe] 为真且系统未开启「移除动画」时才创建无限动画，否则静态 α=1f，
 * 挡 WCAG 2.3.3（前庭安全）。降级判断收敛到 [rememberReduceMotion]。
 *
 * 用法：标题旁 / 卡角点缀，14–18dp；非 emoji，纯 Canvas 矢量。
 *
 * @param modifier 外部布局修饰（对齐 / 间距等）
 * @param size 星形边长（dp）
 * @param tint1 渐变起色（默认 [BrandPrimary] 冷光蓝）
 * @param tint2 渐变止色（默认 [BrandAccent] 青光）
 * @param breathe 是否启用慢呼吸动画（false = 静态满 α，用于背景散布装饰可传 false 再叠自身 α）
 */
@Composable
fun SparkleIcon(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint1: Color = BrandPrimary,
    tint2: Color = BrandAccent,
    breathe: Boolean = true
) {
    val reduceMotion = rememberReduceMotion()
    val alphaValue = if (breathe && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "sparkle")
        transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sparkleAlpha"
        ).value
    } else {
        1f
    }

    Canvas(modifier.size(size).alpha(alphaValue)) {
        val k = this.size.width / 108f
        // 四角星 path（同 GlintSplash.SparkleLogo / ic_launcher_foreground）
        val p = Path().apply {
            moveTo(54f * k, 24f * k)
            cubicTo(56.1f * k, 39.8f * k, 68.2f * k, 51.9f * k, 84f * k, 54f * k)
            cubicTo(68.2f * k, 56.1f * k, 56.1f * k, 68.2f * k, 54f * k, 84f * k)
            cubicTo(51.9f * k, 68.2f * k, 39.8f * k, 56.1f * k, 24f * k, 54f * k)
            cubicTo(39.8f * k, 51.9f * k, 51.9f * k, 39.8f * k, 54f * k, 24f * k)
            close()
        }
        drawPath(
            path = p,
            brush = Brush.linearGradient(
                colors = listOf(tint1, tint2),
                start = Offset(24f * k, 24f * k),
                end = Offset(84f * k, 84f * k)
            )
        )
        // 中心白点（高光）
        drawCircle(Color.White, radius = 5f * k, center = Offset(54f * k, 54f * k))
    }
}
