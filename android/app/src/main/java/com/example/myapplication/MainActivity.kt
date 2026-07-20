@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import com.example.myapplication.ui.PushedScreen
import com.example.myapplication.ui.Tab
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import com.example.myapplication.domain.UsageThresholds
import com.example.myapplication.domain.UsageTier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.domain.Account
import com.example.myapplication.domain.NormalizedWindow
import com.example.myapplication.services.ServiceProviders
import com.example.myapplication.services.UsageHistoryStore
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.services.Region
import com.example.myapplication.ui.UsageUiState
import com.example.myapplication.ui.UsageViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.theme.UsageDanger
import com.example.myapplication.ui.theme.UsageSafe
import com.example.myapplication.ui.theme.UsageWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+ 需运行时申请通知权限（额度告警 v3.0 用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }
        val vm = ViewModelProvider(this)[UsageViewModel::class.java]
        // App 回前台时静默刷新（FOREGROUND reason，自带 15min 节流）。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                vm.refreshOnForeground()
            }
        })
        handleAccountIntent(intent, vm)
        setContent {
            val themeMode by vm.themeMode.collectAsState()
            MyApplicationTheme(themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by vm.state.collectAsState()
                    when (val s = state) {
                        UsageUiState.Loading -> LoadingView()
                        UsageUiState.Unconfigured -> AddAccountScreen(vm, isFirst = true, onDone = {})
                        is UsageUiState.Content -> AppScaffold(s, vm)
                    }
                }
            }
        }
    }

    /** 列表 widget 点击行 → 切到对应账户。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAccountIntent(intent, ViewModelProvider(this)[UsageViewModel::class.java])
    }

    private fun handleAccountIntent(intent: Intent, vm: UsageViewModel) {
        intent.getStringExtra("account_id")?.let { vm.switchAccount(it) }
    }
}

/**
 * 主导航骨架（v3.4）：底栏 3-tab + pushed 子页。
 * - Loading/Unconfigured 由顶层渲染（无底栏）；Content 进本骨架。
 * - pushed（添加账户/通知记录）覆盖 tab，系统返回回 tab 而非退出 app。
 */
@Composable
private fun AppScaffold(content: UsageUiState.Content, vm: UsageViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.RANGE) }
    var pushed by rememberSaveable { mutableStateOf<PushedScreen?>(null) }
    BackHandler(enabled = pushed != null) { pushed = null }

    val p = pushed
    if (p != null) {
        when (p) {
            PushedScreen.ADD_ACCOUNT -> AddAccountScreen(vm, isFirst = false, onDone = { pushed = null })
            PushedScreen.NOTIFICATIONS -> NotificationLogScreen(vm, onBack = { pushed = null })
        }
    } else {
        Scaffold(bottomBar = { AppBottomBar(selected = tab, onChange = { tab = it }) }) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    Tab.RANGE -> UsageScreen(
                        content, vm,
                        onOpenAccounts = { tab = Tab.ACCOUNTS },
                        onOpenNotifications = { pushed = PushedScreen.NOTIFICATIONS }
                    )
                    Tab.ACCOUNTS -> AccountsScreen(
                        vm, onAdd = { pushed = PushedScreen.ADD_ACCOUNT }
                    )
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun AppBottomBar(selected: Tab, onChange: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = selected == t,
                onClick = { onChange(t) },
                icon = { Icon(imageVector = t.navIcon(), contentDescription = null) },
                label = { Text(t.label) }
            )
        }
    }
}

private fun Tab.navIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    Tab.RANGE -> Icons.Filled.Home
    Tab.ACCOUNTS -> Icons.Filled.AccountCircle
    Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

private fun usageColorFor(usedPercent: Int): Color = when (UsageThresholds.tierOf(usedPercent)) {
    UsageTier.DANGER -> UsageDanger
    UsageTier.WARN -> UsageWarn
    UsageTier.SAFE -> UsageSafe
}

private fun windowTitle(kind: WindowKind) = kind.displayName

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

private fun providerLabelOf(providerId: String) = ServiceProviders.labelOf(providerId)

@Composable
private fun UsageScreen(
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
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                    IconButton(onClick = onOpenNotifications) { Icon(Icons.Filled.Notifications, contentDescription = "通知记录") }
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

            snap.windows.forEach { w -> WindowCard(windowTitle(w.kind), w) }

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

@Composable
private fun AccountsScreen(vm: UsageViewModel, onAdd: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val activeId by vm.activeAccountId.collectAsState()
    var renaming by remember { mutableStateOf<Account?>(null) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("账户管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            accounts.forEach { acc ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(acc.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    providerLabelOf(acc.providerId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (acc.accountId == activeId) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Text(
                            "Key ${vm.maskKeyFor(acc)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (acc.accountId != activeId) {
                                TextButton(onClick = { vm.switchAccount(acc.accountId) }) { Text("切换") }
                            }
                            TextButton(onClick = { renaming = acc }) { Text("重命名") }
                            TextButton(
                                onClick = { vm.removeAccount(acc.accountId) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("删除") }
                        }
                    }
                }
            }

            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("添加账户", fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(16.dp))
        }
    }

    renaming?.let { acc ->
        RenameAccountDialog(
            initial = acc.label,
            onConfirm = { vm.renameAccount(acc.accountId, it); renaming = null },
            onDismiss = { renaming = null }
        )
    }
}

@Composable
private fun RenameAccountDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名账户") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddAccountScreen(vm: UsageViewModel, isFirst: Boolean, onDone: () -> Unit) {
    val options = vm.providerOptions
    var providerId by remember { mutableStateOf(options.first().providerId) }
    val selected = options.first { it.providerId == providerId }
    var region by remember { mutableStateOf(Region.CN) }
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            if (!isFirst) TextButton(onDone) { Text("返回") }
            Text(
                if (isFirst) "添加第一个账户" else "添加账户",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "桌面卡片 · 用量实时查",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("服务商", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { opt ->
                    FilterChip(
                        selected = providerId == opt.providerId,
                        onClick = { providerId = opt.providerId },
                        label = { Text(opt.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            if (selected.supportsRegion) {
                Text("区域", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Region.entries.forEach { r ->
                        FilterChip(
                            selected = region == r,
                            onClick = { region = r },
                            label = { Text(if (r == Region.CN) "中国站" else "国际站") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("备注名（可选）") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "Key 仅本机加密存储，不上传服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    testing = true
                    error = null
                    val regionArg = if (selected.supportsRegion) region.name else null
                    vm.addAccount(providerId, key.trim(), regionArg, label.trim()) { ok, msg ->
                        testing = false
                        if (ok) onDone() else error = msg ?: "连接失败"
                    }
                },
                enabled = key.isNotBlank() && !testing,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("测试并保存", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WindowCard(title: String, window: NormalizedWindow) {
    val usedPercent = window.usedPercent
    val remaining = 100 - usedPercent
    val color by animateColorAsState(targetValue = usageColorFor(usedPercent), label = "usageColor")
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$remaining",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    Text(
                        "% 剩余",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                }
            }
            LinearProgressIndicator(
                progress = { (usedPercent / 100f).coerceIn(0f, 1f) },
                color = color,
                trackColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            window.usedValue?.let { used -> window.totalValue?.let { total ->
                Text(
                    "${used.toInt()} / ${total.toInt()}${window.unit?.let { " $it" } ?: ""} 已用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }}
            Text(
                if (window.resetAt != null) "预计 ${formatTime(window.resetAt)} 恢复" else "重置时间暂不可用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

private fun formatTime(ts: Long): String {
    if (ts <= 0) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
