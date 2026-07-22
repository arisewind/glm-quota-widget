@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.myapplication.ui

import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.NormalizedWindow
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.domain.formatTime
import com.example.myapplication.domain.UsageThresholds
import com.example.myapplication.domain.UsageTier
import com.example.myapplication.services.SettingsStore
import com.example.myapplication.services.UsageHistoryStore
import com.example.myapplication.ui.theme.UsageDanger
import com.example.myapplication.ui.theme.BrandAccent
import com.example.myapplication.ui.theme.BrandPrimary
import com.example.myapplication.ui.theme.UsageSafe
import com.example.myapplication.ui.theme.UsageWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 用量档位颜色（UI Compose Color 版；widget 用 widget.UsageColors.usageColorInt 的 Int 版）。 */
private fun usageColorFor(usedPercent: Int): Color = when (UsageThresholds.tierOf(usedPercent)) {
    UsageTier.DANGER -> UsageDanger
    UsageTier.WARN -> UsageWarn
    UsageTier.SAFE -> UsageSafe
}

/**
 * v3.8 方案 B：浅色续航页 safe 档进度条品牌化（蓝青渐变）。
 *
 * 浅色 + safe 档 → Canvas 自绘 [BrandPrimary]→[BrandAccent] 对角渐变填充（LinearProgressIndicator
 * 的 color 参数只接 [Color] 不接 [Brush]，故自绘）；warn/danger 或深色 → 回退纯色
 * [LinearProgressIndicator]（告警橙红不被品牌色稀释，深色续航页保持纯色不变）。
 *
 * 渐变是本地 brush，不污染 [usageColorFor] 单一真源（数字 / TierPill / mini card 仍走单色）。
 *
 * TODO(光晕): safe 档大数字蓝青光晕（drop-shadow α.20）—— Compose Text 渐变需 TextStyle brush
 *   或 drawWithContent 叠加，本次先保进度条品牌化，数字光晕留后续。
 */
@Composable
private fun BrandProgressIndicator(
    progress: Float,
    usedPercent: Int,
    isLight: Boolean,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val tier = UsageThresholds.tierOf(usedPercent)
    val trackColor = MaterialTheme.colorScheme.surface
    if (isLight && tier == UsageTier.SAFE) {
        // safe 档：蓝青渐变（Canvas 自绘，track + 渐变填充）
        Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))) {
            val w = size.width
            val h = size.height
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(color = trackColor, size = size, cornerRadius = r)
            val fillW = w * progress.coerceIn(0f, 1f)
            if (fillW > 0f) {
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(BrandPrimary, BrandAccent)),
                    size = Size(fillW, h),
                    cornerRadius = r
                )
            }
        }
    } else {
        // warn/danger 或深色：纯色（告警语义独立，深色不变）
        LinearProgressIndicator(
            progress = { progress },
            color = usageColorFor(usedPercent),
            trackColor = trackColor,
            modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))
        )
    }
}

/** v3.8 方案 B：浅色卡片蓝青微光描边 brush（α.16，白卡边缘隐约冷光）；深色返回 null（描边不变）。 */
@Composable
private fun brandCardBorderBrush(isLight: Boolean): Brush? = if (isLight) {
    // 默认 topLeft→bottomRight 对角渐变（brush 作用域为 border 绘制区域）
    Brush.linearGradient(listOf(BrandPrimary.copy(alpha = 0.16f), BrandAccent.copy(alpha = 0.16f)))
} else {
    null
}

/** v3.5 owlmeter 风格：浅灰底圆角方块 icon 按钮（surfaceContainerHigh 底 + 12dp 圆角，替代裸 IconButton）。 */
@Composable
private fun OwlIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        content()
    }
}

/** v3.5 用量档位状态胶囊（绿/橙/红 + 文案）。 */
@Composable
private fun TierPill(usedPercent: Int) {
    val color = usageColorFor(usedPercent)
    val text = when (UsageThresholds.tierOf(usedPercent)) {
        UsageTier.SAFE -> "正常使用"
        UsageTier.WARN -> "额度偏紧"
        UsageTier.DANGER -> "额度告急"
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.12f)) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun StatusBanner(message: String, isStale: Boolean) {
    val accent = if (isStale) UsageWarn else UsageDanger
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.15f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 7 天用量趋势（v3.1，Compose Canvas 自绘：时间驱动 X 轴固定 7 天日期 + 参考线 + 85% 告警线 + 渐变面积 + 末点标注；重置日置卡片右下角）。 */
@Composable
private fun WeeklyTrendCard(points: List<UsageHistoryStore.Point>, resetAt: Long?, isLight: Boolean) {
    val dateFmt = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    val borderBrush = brandCardBorderBrush(isLight)
    Card(
        Modifier
            .fillMaxWidth()
            .then(if (borderBrush != null) Modifier.border(1.dp, borderBrush, RoundedCornerShape(20.dp)) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // v3.8 方案 B：趋势标题旁 sparkle 点缀（浅色才显）
                if (isLight) SparkleIcon(Modifier.padding(end = 5.dp), size = 12.dp)
                Text("7天用量趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(108.dp)) {
                val w = size.width
                val h = size.height
                val n = points.size
                if (n < 2) return@Canvas
                val padL = 30.dp.toPx()
                val padB = 18.dp.toPx()
                val padT = 14.dp.toPx()
                val plotW = w - padL
                val plotTop = padT
                val plotBottom = h - padB
                val plotH = plotBottom - plotTop
                fun py(p: Int) = plotBottom - (p / 100f) * plotH

                // X 轴跨度：最早数据那天 → 今天，最多 7 天（新账户数据少时从当天起，不固定画满 7 天）
                val dayMs = 86_400_000L
                fun dayStart(ts: Long): Long = java.util.Calendar.getInstance().run {
                    timeInMillis = ts
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    timeInMillis
                }
                val todayMs = dayStart(System.currentTimeMillis())
                val xAxisStart = maxOf(dayStart(points.first().ts), todayMs - 6 * dayMs)
                val spanDays = ((todayMs - xAxisStart) / dayMs).toInt().coerceIn(1, 6)
                fun px(i: Int) = padL + i * plotW / (n - 1)   // 数据点按索引等距（视觉连续）

                val native = drawContext.canvas.nativeCanvas
                val gridPaint = Paint().apply { color = 0xFF64748B.toInt(); textSize = 9.dp.toPx(); isAntiAlias = true }
                val warnPaint = Paint().apply { color = UsageDanger.toArgb(); textSize = 9.dp.toPx(); isAntiAlias = true }
                val ptPaint = Paint().apply { color = BrandPrimary.toArgb(); textSize = 10.dp.toPx(); isAntiAlias = true; isFakeBoldText = true }
                val xPaint = Paint().apply { color = 0xFF64748B.toInt(); textSize = 9.dp.toPx(); isAntiAlias = true }

                // Y 轴参考线 0/100
                listOf(0, 100).forEach { p ->
                    val y = py(p)
                    drawLine(Color(0xFF334155), Offset(padL, y), Offset(w, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    native.drawText("$p%", 0f, y + 3.dp.toPx(), gridPaint)
                }
                // DANGER(85%) 告警线，标签入 Y 轴左侧栏
                val wy = py(UsageThresholds.DANGER)
                drawLine(Color(0xFFFF6B6B), Offset(padL, wy), Offset(w, wy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)))
                native.drawText("${UsageThresholds.DANGER}%", 0f, wy + 3.dp.toPx(), warnPaint)
                // 面积 + 折线（点按索引等距）
                val line = Path().apply {
                    moveTo(px(0), py(points[0].percent))
                    for (i in 1 until n) lineTo(px(i), py(points[i].percent))
                }
                val area = Path().apply { addPath(line); lineTo(px(n - 1), plotBottom); lineTo(px(0), plotBottom); close() }
                drawPath(area, Brush.verticalGradient(listOf(BrandPrimary.copy(alpha = 0.4f), BrandPrimary.copy(alpha = 0.02f)), startY = plotTop, endY = plotBottom))
                drawPath(line, BrandPrimary, style = Stroke(width = 2.dp.toPx()))
                // 末点 + 数值标注
                val lx = px(n - 1)
                val ly = py(points[n - 1].percent)
                drawCircle(BrandPrimary, 4.dp.toPx(), Offset(lx, ly))
                ptPaint.textAlign = Paint.Align.RIGHT
                native.drawText("${points[n - 1].percent}%", lx, ly - 8.dp.toPx(), ptPaint)
                // X 轴：按数据范围（xAxisStart→today）画日期刻度，首尾对齐边缘防溢出
                for (i in 0..spanDays) {
                    val ts = xAxisStart + i * dayMs
                    if (ts > todayMs) continue   // 不画未来刻度（同天数据只标今天）
                    val x = padL + (i.toFloat() / spanDays) * plotW
                    xPaint.textAlign = when {
                        i == 0 -> Paint.Align.LEFT
                        i == spanDays -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                    native.drawText(dateFmt.format(Date(ts)), x, h - 4.dp.toPx(), xPaint)
                }
            }
            // 重置日置卡片右下角（删「最近 7 天采样」字样）
            resetAt?.takeIf { it > 0 }?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("↻重置 ${dateFmt.format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = BrandPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** v3.5 样式 A 主卡：横向大卡（左 标签+状态胶囊 / 右 大剩余% / 横向进度 / 已用·重置）。 */
@Composable
private fun RangePrimaryCard(window: NormalizedWindow, isLight: Boolean) {
    val usedPercent = window.usedPercent
    val isTools = window.kind == WindowKind.TOOLS
    val color by animateColorAsState(targetValue = usageColorFor(usedPercent), label = "primaryUsageColor")
    // 任务3 微动效：数字滚动 + 进度条增长；reduced-motion 瞬切（snap）
    val reduceMotion = rememberReduceMotion()
    val animatedUsed by animateIntAsState(
        targetValue = usedPercent,
        animationSpec = if (reduceMotion) snap() else tween(Motion.Durations.LONG),
        label = "usedNum"
    )
    val animatedRatio by animateFloatAsState(
        targetValue = (usedPercent / 100f).coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.8f),
        label = "primaryBar"
    )
    val remainingText: String
    val remainingUnit: String
    if (isTools) {
        remainingText = "${((window.totalValue ?: 0.0) - (window.usedValue ?: 0.0)).toInt()}"
        remainingUnit = "次剩余"
    } else {
        remainingText = "${100 - animatedUsed}"
        remainingUnit = "% 剩余"
    }
    val borderBrush = brandCardBorderBrush(isLight)
    Card(
        Modifier
            .fillMaxWidth()
            .then(if (borderBrush != null) Modifier.border(1.dp, borderBrush, RoundedCornerShape(20.dp)) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // v3.8 方案 B：主卡标签旁 sparkle 点缀（品牌印记，浅色才显，深色续航页不动）
                        if (isLight) {
                            SparkleIcon(Modifier.padding(end = 5.dp), size = 14.dp)
                        }
                        Text(window.kind.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    TierPill(usedPercent)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(remainingText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
                    Text(remainingUnit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                }
            }
            // v3.8 方案 B：safe 档（浅色）蓝青渐变进度条；warn/danger/深色 纯色不变
            BrandProgressIndicator(
                progress = animatedRatio,
                usedPercent = usedPercent,
                isLight = isLight,
                height = 10.dp
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val used = "已用 $usedPercent%"   // v3.6：底行统一纯百分比，去掉 "X/Y 单位 已用" 绝对值文案
                Text(used, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (window.resetAt != null) "${formatTime(window.resetAt)} 恢复" else "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** v3.5 样式 A mini 卡：可点击升主（onClick 传该窗口 kind）。 */
@Composable
private fun RangeMiniCard(window: NormalizedWindow, onClick: (WindowKind) -> Unit, isLight: Boolean) {
    val usedPercent = window.usedPercent
    val isTools = window.kind == WindowKind.TOOLS
    val color = usageColorFor(usedPercent)
    val remainingText: String
    val remainingUnit: String
    if (isTools) {
        remainingText = "${((window.totalValue ?: 0.0) - (window.usedValue ?: 0.0)).toInt()}"
        remainingUnit = "次剩余"
    } else {
        remainingText = "${100 - usedPercent}"
        remainingUnit = "% 剩余"
    }
    val borderBrush = brandCardBorderBrush(isLight)
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(window.kind) }
            .then(if (borderBrush != null) Modifier.border(1.dp, borderBrush, RoundedCornerShape(16.dp)) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(window.kind.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(remainingText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                Text(remainingUnit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp, bottom = 2.dp))
            }
            // v3.8 方案 B：safe 档（浅色）蓝青渐变 mini 进度条；warn/danger/深色 纯色不变
            BrandProgressIndicator(
                progress = (usedPercent / 100f).coerceIn(0f, 1f),
                usedPercent = usedPercent,
                isLight = isLight,
                height = 6.dp
            )
            val aux = window.resetAt?.let { formatTime(it) }   // v3.6：辅助行统一重置时间，去掉 TOOLS "X/Y" 绝对值
            Text(aux ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** v3.5 样式 A mini 行：横向并排，每张 weight(1) 占满；点 mini 升主。 */
@Composable
private fun RangeMiniRow(windows: List<NormalizedWindow>, onClick: (WindowKind) -> Unit, isLight: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        windows.forEach { w ->
            Box(Modifier.weight(1f)) {
                RangeMiniCard(w, onClick, isLight)
            }
        }
    }
}

/** 续航主页：顶栏（账户名+刷新+铃铛 badge）+ 主卡+mini（v3.5 横向）+ 趋势卡 + 工具明细 + 最近更新。 */
@Composable
internal fun UsageScreen(
    content: UsageUiState.Content,
    vm: UsageViewModel,
    onOpenAccounts: () -> Unit,
    onOpenNotifications: () -> Unit
) {
    val snap = content.snapshot
    val activeAccount = content.accounts.firstOrNull { it.accountId == content.activeAccountId }
    // v3.8 任务3①：刷新中 → 顶栏刷新 icon 匀速 360° 旋转（VM.isRefreshing 驱动）
    // 任务8：仅刷新中且非 reduced-motion 才创建无限旋转（否则 transition 不进 composition，零帧开销 + 挡 WCAG 2.3.3）
    val isRefreshing by vm.isRefreshing.collectAsState()
    val reduceMotion = rememberReduceMotion()
    // v3.8 方案 B：浅色判断 —— mesh 背景 / safe 档渐变进度 / sparkle 点缀 / 微光描边 仅浅色生效（深色续航页不变）
    val themeMode by vm.themeMode.collectAsState()
    val isLight = when (themeMode) {
        SettingsStore.THEME_LIGHT -> true
        SettingsStore.THEME_DARK -> false
        else -> !isSystemInDarkTheme()
    }
    val refreshDeg = if (isRefreshing && !reduceMotion) {
        val spin = rememberInfiniteTransition(label = "refresh")
        spin.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(Motion.Durations.REFRESH_SPIN, easing = LinearEasing)),
            label = "refreshDeg"
        ).value
    } else {
        0f
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { onOpenAccounts() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // v3.8 方案 B：顶栏账户名前 sparkle 品牌印记（浅色慢呼吸；深色续航页不加点缀，保持纯色）
                            if (isLight) {
                                SparkleIcon(Modifier.padding(end = 7.dp), size = 18.dp)
                            }
                            Text(
                                activeAccount?.label ?: snap.providerLabel,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "切换账户",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val sub = listOfNotNull(
                            snap.providerLabel.takeIf { it.isNotEmpty() && it != (activeAccount?.label ?: snap.providerLabel) },
                            snap.planName?.takeIf { it.isNotEmpty() }
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    OwlIconButton(onClick = { vm.refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier.graphicsLayer { rotationZ = if (isRefreshing) refreshDeg else 0f }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    val unreadCount by vm.unreadCount.collectAsState()
                    BadgedBox(badge = {
                        // 任务3 微动效：未读数 badge 过冲弹出（unreadCount 变化重弹）；reduced-motion 直接显示
                        if (unreadCount > 0) {
                            if (reduceMotion) {
                                Badge { Text(unreadCount.toString()) }
                            } else {
                                val badgeScale = remember { Animatable(0f) }
                                LaunchedEffect(unreadCount) {
                                    badgeScale.snapTo(0f)
                                    badgeScale.animateTo(1f, spring(dampingRatio = 0.4f))
                                }
                                Badge(modifier = Modifier.scale(badgeScale.value)) { Text(unreadCount.toString()) }
                            }
                        }
                    }) {
                        OwlIconButton(onClick = onOpenNotifications) {
                            Icon(Icons.Filled.Notifications, contentDescription = "通知记录")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .drawBehind {
                    // v3.8 方案 B：浅色续航页极浅蓝青 mesh（3 层 radialGradient，α.06–.10）；
                    // 深色续航页保持纯色不绘制（切肤不切品牌——mesh 公式与深色 splash 同源，仅 alpha 等比降）
                    if (isLight) {
                        val w = size.width
                        val h = size.height
                        drawRect(Brush.radialGradient(
                            listOf(BrandPrimary.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(w * 0.22f, h * 0.14f), radius = w * 0.6f
                        ))
                        drawRect(Brush.radialGradient(
                            listOf(BrandAccent.copy(alpha = 0.09f), Color.Transparent),
                            center = Offset(w * 0.84f, h * 0.28f), radius = w * 0.55f
                        ))
                        drawRect(Brush.radialGradient(
                            listOf(BrandPrimary.copy(alpha = 0.055f), Color.Transparent),
                            center = Offset(w * 0.50f, h * 0.92f), radius = w * 0.7f
                        ))
                    }
                }
                .padding(horizontal = 22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (snap.status == UsageStatus.STALE || snap.status == UsageStatus.ERROR) {
                StatusBanner(snap.errorMessage ?: "数据异常", snap.status == UsageStatus.STALE)
            }

            // v3.5 样式 A：主卡（用户偏好窗口，VM 已给实际主 kind）+ 其余窗口 mini 并排（点 mini 升主）
            val primaryKind = content.primaryWindowKind
            val primaryWin = primaryKind?.let { snap.window(it) }
            val minis = snap.windows.filter { it.kind != primaryKind }
            primaryWin?.let { RangePrimaryCard(it, isLight) }
            if (minis.isNotEmpty()) RangeMiniRow(minis, { kind -> vm.setPrimaryWindow(kind) }, isLight)

            val weeklyHist by vm.weeklyHistory.collectAsState()
            if (weeklyHist.size >= 2) WeeklyTrendCard(weeklyHist, snap.window(WindowKind.WEEKLY)?.resetAt, isLight)

            val cardBorder = brandCardBorderBrush(isLight)
            snap.modelUsage?.takeIf { it.isNotEmpty() }?.let { items ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .then(if (cardBorder != null) Modifier.border(1.dp, cardBorder, RoundedCornerShape(20.dp)) else Modifier),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "工具调用明细",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        items.forEach { m ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(m.modelCode, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${m.usage}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Text(
                "最近更新：${formatTime(snap.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
