@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.myapplication.ui

import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.NormalizedWindow
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.domain.formatTime
import com.example.myapplication.domain.UsageThresholds
import com.example.myapplication.domain.UsageTier
import com.example.myapplication.services.UsageHistoryStore
import com.example.myapplication.ui.theme.UsageDanger
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

/** v3.5 owlmeter 风格：浅灰底圆角方块 icon 按钮（surfaceContainerHigh 底 + 12dp 圆角，替代裸 IconButton）。 */
@Composable
private fun OwlIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun WeeklyTrendCard(points: List<UsageHistoryStore.Point>, resetAt: Long?) {
    val dateFmt = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("7天用量趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

                // X 轴：最近 7 天（含今天）固定日期刻度；数据点按索引等距（trend-final 样式）
                val dayMs = 86_400_000L
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                val startMs = cal.timeInMillis   // 本地今天 00:00（避免 UTC 天桶的时区偏移）
                fun px(i: Int) = padL + i * plotW / (n - 1)

                val native = drawContext.canvas.nativeCanvas
                val gridPaint = Paint().apply { color = 0xFF64748B.toInt(); textSize = 9.dp.toPx(); isAntiAlias = true }
                val warnPaint = Paint().apply { color = 0xFFFF6B6B.toInt(); textSize = 9.dp.toPx(); isAntiAlias = true }
                val ptPaint = Paint().apply { color = 0xFF00C2B8.toInt(); textSize = 10.dp.toPx(); isAntiAlias = true; isFakeBoldText = true }
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
                drawPath(area, Brush.verticalGradient(listOf(UsageSafe.copy(alpha = 0.4f), UsageSafe.copy(alpha = 0.02f)), startY = plotTop, endY = plotBottom))
                drawPath(line, UsageSafe, style = Stroke(width = 2.dp.toPx()))
                // 末点 + 数值标注
                val lx = px(n - 1)
                val ly = py(points[n - 1].percent)
                drawCircle(UsageSafe, 4.dp.toPx(), Offset(lx, ly))
                ptPaint.textAlign = Paint.Align.RIGHT
                native.drawText("${points[n - 1].percent}%", lx, ly - 8.dp.toPx(), ptPaint)
                // X 轴：固定 7 天日期刻度（首尾对齐边缘防溢出）
                for (i in 0..6) {
                    val ts = startMs - (6 - i) * dayMs
                    val x = padL + (i.toFloat() / 6) * plotW
                    xPaint.textAlign = when {
                        i == 0 -> Paint.Align.LEFT
                        i == 6 -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                    native.drawText(dateFmt.format(Date(ts)), x, h - 4.dp.toPx(), xPaint)
                }
            }
            // 重置日置卡片右下角（删「最近 7 天采样」字样）
            resetAt?.takeIf { it > 0 }?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("↻重置 ${dateFmt.format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = UsageSafe, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** v3.5 样式 A 主卡：横向大卡（左 标签+状态胶囊 / 右 大剩余% / 横向进度 / 已用·重置）。 */
@Composable
private fun RangePrimaryCard(window: NormalizedWindow) {
    val usedPercent = window.usedPercent
    val isTools = window.kind == WindowKind.TOOLS
    val color by animateColorAsState(targetValue = usageColorFor(usedPercent), label = "primaryUsageColor")
    val remainingText: String
    val remainingUnit: String
    if (isTools) {
        remainingText = "${((window.totalValue ?: 0.0) - (window.usedValue ?: 0.0)).toInt()}"
        remainingUnit = "次剩余"
    } else {
        remainingText = "${100 - usedPercent}"
        remainingUnit = "% 剩余"
    }
    Card(
        Modifier.fillMaxWidth(),
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
                    Text(window.kind.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TierPill(usedPercent)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(remainingText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
                    Text(remainingUnit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                }
            }
            LinearProgressIndicator(
                progress = { (usedPercent / 100f).coerceIn(0f, 1f) },
                color = color,
                trackColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
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
private fun RangeMiniCard(window: NormalizedWindow, onClick: (WindowKind) -> Unit) {
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
    Card(
        Modifier.fillMaxWidth().clickable { onClick(window.kind) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(window.kind.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(remainingText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                Text(remainingUnit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp, bottom = 2.dp))
            }
            LinearProgressIndicator(
                progress = { (usedPercent / 100f).coerceIn(0f, 1f) },
                color = color,
                trackColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
            val aux = window.resetAt?.let { formatTime(it) }   // v3.6：辅助行统一重置时间，去掉 TOOLS "X/Y" 绝对值
            Text(aux ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** v3.5 样式 A mini 行：横向并排，每张 weight(1) 占满；点 mini 升主。 */
@Composable
private fun RangeMiniRow(windows: List<NormalizedWindow>, onClick: (WindowKind) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        windows.forEach { w ->
            Box(Modifier.weight(1f)) {
                RangeMiniCard(w, onClick)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { onOpenAccounts() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    val hasUnread by vm.hasUnreadNotifications.collectAsState()
                    BadgedBox(badge = { if (hasUnread) Badge() }) {
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
            primaryWin?.let { RangePrimaryCard(it) }
            if (minis.isNotEmpty()) RangeMiniRow(minis) { kind -> vm.setPrimaryWindow(kind) }

            val weeklyHist by vm.weeklyHistory.collectAsState()
            if (weeklyHist.size >= 2) WeeklyTrendCard(weeklyHist, snap.window(WindowKind.WEEKLY)?.resetAt)

            snap.modelUsage?.takeIf { it.isNotEmpty() }?.let { items ->
                Card(
                    Modifier.fillMaxWidth(),
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
