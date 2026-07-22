package com.example.myapplication

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.theme.Mono

private val BgDeep = Color(0xFF0E1530)
private val GlowBlue = Color(0xFF3B82F6)
private val GlowCyan = Color(0xFF06B6D4)
private val MutedText = Color(0xFF8A94A6)

/**
 * glintapi 品牌 splash（Activity 内，core-splashscreen 之后）。
 *
 * 三层 mesh 径向渐变（蓝/青/深蓝光斑，复刻 v2 原型）+ Canvas 绘制的 sparkle logo
 * + glintapi 字标 + `$ monitoring quota` 终端文案带硬切闪烁光标。
 *
 * reduced-motion（系统「移除动画」/ animator duration scale = 0）时光标常亮不闪，
 * 对应 v2 的 `prefers-reduced-motion`，避免前庭不适。
 */
@Composable
fun GlintSplash() {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }

    // 光标硬切 blink：1100ms 周期，前 550ms 显示、后 550ms 隐藏（CSS steps(2,start) 等效）
    val transition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                1f at 0
                1f at 549
                0f at 550
                0f at 1099
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorAlpha"
    )

    Box(Modifier.fillMaxSize().background(BgDeep)) {
        // mesh 径向渐变（三光斑叠加）
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.radialGradient(
                    colors = listOf(GlowBlue.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(w * 0.28f, h * 0.22f),
                    radius = w * 0.6f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(GlowCyan.copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(w * 0.78f, h * 0.32f),
                    radius = w * 0.55f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1E3A8A).copy(alpha = 0.4f), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.8f),
                    radius = w * 0.7f
                )
            )
        }

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SparkleLogo(Modifier.size(110.dp))
            Spacer(Modifier.height(22.dp))
            Text(
                text = buildAnnotatedString {
                    append("Glint")
                    withStyle(SpanStyle(color = GlowCyan)) { append("API") }
                },
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = MutedText, fontFamily = Mono)) { append("$ ") }
                        withStyle(SpanStyle(color = GlowBlue, fontFamily = Mono)) { append("monitoring quota") }
                    },
                    fontSize = 14.sp
                )
                // 闪烁光标（终端块状，冷光蓝）
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(width = 8.dp, height = 16.dp)
                        .alpha(if (reduceMotion) 1f else cursorAlpha)
                        .clip(RoundedCornerShape(1.dp))
                        .background(GlowBlue)
                )
            }
        }
    }
}

/** sparkle 四角星（蓝→青对角渐变）+ 中心白点，path 同 ic_launcher_foreground（24~84 安全区）。 */
@Composable
private fun SparkleLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = size.width / 108f
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
                colors = listOf(GlowBlue, GlowCyan),
                start = Offset(24f * k, 24f * k),
                end = Offset(84f * k, 84f * k)
            )
        )
        drawCircle(Color.White, radius = 5f * k, center = Offset(54f * k, 54f * k))
    }
}
