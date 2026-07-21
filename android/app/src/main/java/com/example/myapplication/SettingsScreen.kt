@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.myapplication

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.services.SettingsStore
import com.example.myapplication.ui.UsageViewModel

internal const val GITHUB_URL = "https://github.com/arisewind/glm-quota-widget"

/**
 * 设置页（v3.2）：6 组——刷新 / 显示 / 告警 / 系统引导 / 数据 / 关于。
 * 开关从 AccountsScreen 搬迁；系统引导组是解华为 widget 后台限制的产品化正解。
 */
@Composable
internal fun SettingsScreen(vm: UsageViewModel) {
    val ctx = LocalContext.current
    val refreshAll by vm.backgroundRefreshAll.collectAsState()
    val alertLow by vm.alertLowEnabled.collectAsState()
    val alertExhausted by vm.alertExhaustedEnabled.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val notifGranted = rememberNotificationGranted()
    val batteryIgnoring = rememberBatteryIgnoring()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().padding(padding)
                .padding(horizontal = 22.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SectionHeader("刷新")
            SwitchCard("后台刷新全部账户", "关闭 = 只刷当前账户（省电、降低风控）", refreshAll) { vm.setBackgroundRefreshAll(it) }

            SectionHeader("显示")
            Card(Modifier.fillMaxWidth(), shape = cardShape, colors = sectionCardColors()) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("主题", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    ThemePicker(themeMode, onPick = { vm.setThemeMode(it) })
                }
            }

            SectionHeader("告警")
            SwitchCard("低额度提醒", "用量 ≥ 85% 时通知", alertLow) { vm.setAlertLowEnabled(it) }
            SwitchCard("额度耗尽", "用量 100% 紧急通知（横幅+震动）· 恢复时通知", alertExhausted) { vm.setAlertExhaustedEnabled(it) }

            SectionHeader("系统引导")
            InfoCard("后台刷新不工作？通知不响？下方配置系统权限可解决——这是华为/HarmonyOS 省电限制的产品化正解。")
            StatusCard(
                "通知权限", "允许 app 发送额度告警与恢复通知",
                isOkay = notifGranted,
                statusLabel = if (notifGranted) "已开启" else "未开启",
                actionLabel = if (notifGranted) "管理" else "去开启",
                enabled = true,
                onAction = { openNotificationSettings(ctx) }
            )
            StatusCard(
                "电池优化白名单", "加入后 widget 后台刷新不被系统冻结",
                isOkay = batteryIgnoring,
                statusLabel = if (batteryIgnoring) "已加入" else "未开启",
                actionLabel = if (batteryIgnoring) "管理" else "去开启",
                enabled = true,
                onAction = { openBatteryWhitelist(ctx) }
            )
            if (isHuawei()) {
                StatusCard(
                    "启动管理（华为）", "设置 → 应用 → 智谱额度 → 启动管理 → 手动管理，三开关全开",
                    isOkay = false,
                    statusLabel = "需配置",
                    actionLabel = "去设置",
                    enabled = true,
                    onAction = { openHuaweiStartup(ctx) }
                )
            }

            SectionHeader("数据")
            OutlinedButton(
                onClick = { vm.clearConfig() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = buttonShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("清除全部账户与数据", fontWeight = FontWeight.SemiBold) }

            SectionHeader("关于")
            AboutCard()

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun sectionCardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

private val cardShape = RoundedCornerShape(20.dp)
private val buttonShape = RoundedCornerShape(14.dp)

@Composable
private fun SwitchCard(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = cardShape, colors = sectionCardColors()) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    subtitle: String,
    isOkay: Boolean,
    statusLabel: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), shape = cardShape, colors = sectionCardColors()) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOkay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.size(6.dp))
                TextButton(enabled = enabled, onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Text(
            text,
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThemePicker(current: String, onPick: (String) -> Unit) {
    val opts = listOf(
        "浅色" to SettingsStore.THEME_LIGHT,
        "深色" to SettingsStore.THEME_DARK,
        "系统" to SettingsStore.THEME_SYSTEM
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        opts.forEachIndexed { index, (label, mode) ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onPick(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = opts.size)
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth(), shape = cardShape, colors = sectionCardColors()) {
        Column(Modifier.padding(20.dp)) {
            Text("智谱额度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text(
                "桌面卡片实时查 API Coding Plan 用量，支持多服务商多账户。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "非官方客户端，直连各服务商实验性 API，数据可能与官方面板有偏差。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Spacer(Modifier.size(14.dp))
            HorizontalDivider()
            Spacer(Modifier.size(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.size(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("GitHub 仓库", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── 系统引导：状态检测（ON_RESUME 重读）+ Intent 跳转 ──

private fun checkNotificationGranted(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
    else ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun checkBatteryIgnoring(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private fun isHuawei() = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

private fun openNotificationSettings(ctx: Context) {
    val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(i) }
}

private fun openBatteryWhitelist(ctx: Context) {
    val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${ctx.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(i) }
}

/** 华为启动管理：尝试已知 Intent（resolveActivity 过滤）→ fallback 应用详情 → 兜底系统设置根。 */
private fun openHuaweiStartup(ctx: Context) {
    val candidates = listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
    )
    val pm = ctx.packageManager
    val intent = candidates.firstNotNullOfOrNull { cn ->
        Intent().apply { component = cn }.takeIf { pm.resolveActivity(it, 0) != null }
    } ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${ctx.packageName}")
    }
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
}

@Composable
private fun rememberNotificationGranted(): Boolean {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(checkNotificationGranted(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = checkNotificationGranted(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return granted
}

@Composable
private fun rememberBatteryIgnoring(): Boolean {
    val ctx = LocalContext.current
    var ignoring by remember { mutableStateOf(checkBatteryIgnoring(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ignoring = checkBatteryIgnoring(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return ignoring
}
