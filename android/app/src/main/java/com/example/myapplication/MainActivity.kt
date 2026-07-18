package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.domain.CodingPlanUsage
import com.example.myapplication.domain.UsageStatus
import com.example.myapplication.services.Region
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.theme.UsageDanger
import com.example.myapplication.ui.theme.UsageSafe
import com.example.myapplication.ui.theme.UsageWarn
import com.example.myapplication.ui.UsageUiState
import com.example.myapplication.ui.UsageViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vm = ViewModelProvider(this)[UsageViewModel::class.java]
        // App 回前台时静默刷新（FOREGROUND reason，自带 15min 节流）。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                vm.refreshOnForeground()
            }
        })
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    val state by vm.state.collectAsState()
                    if (showSettings) {
                        SettingsScreen(vm) { showSettings = false }
                    } else {
                        when (val s = state) {
                            UsageUiState.Loading -> LoadingView()
                            UsageUiState.Unconfigured -> SetupScreen(vm)
                            is UsageUiState.Data -> UsageScreen(s.usage, vm) { showSettings = true }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/** 已用百分比 → 配色：<60% 安全（青）、60–85% 警告（琥珀）、>85% 危险（珊瑚红）。 */
private fun usageColorFor(usedPercent: Int): Color = when {
    usedPercent > 85 -> UsageDanger
    usedPercent >= 60 -> UsageWarn
    else -> UsageSafe
}

@Composable
private fun SetupScreen(vm: UsageViewModel) {
    var key by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(Region.CN) }
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
            Text(
                "智谱 GLM Coding Plan",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "桌面卡片 · 用量实时查",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("套餐区域", style = MaterialTheme.typography.labelLarge)
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

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Coding Plan API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示")
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "Key 仅保存在本机并加密存储，不会上传至开发者服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    testing = true
                    error = null
                    vm.setup(key.trim(), region) { ok, msg ->
                        testing = false
                        if (!ok) error = msg ?: "连接失败"
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
private fun UsageScreen(
    usage: CodingPlanUsage,
    vm: UsageViewModel,
    onOpenSettings: () -> Unit
) {
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
                Column(Modifier.weight(1f)) {
                    Text(
                        usage.providerLabel ?: "智谱 GLM Coding Plan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    usage.planName?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onOpenSettings) {
                    Text("设置", fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { vm.refresh() }) {
                    Text("刷新", fontWeight = FontWeight.SemiBold)
                }
            }

            if (usage.status == UsageStatus.STALE || usage.status == UsageStatus.ERROR) {
                StatusBanner(
                    message = usage.errorMessage ?: "数据异常",
                    isStale = usage.status == UsageStatus.STALE
                )
            }

            WindowCard("5 小时额度", usage.session.usedPercent, usage.session.resetAt)
            WindowCard("本周额度", usage.weekly.usedPercent, usage.weekly.resetAt)

            usage.modelUsage?.takeIf { it.isNotEmpty() }?.let { items ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "模型用量",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        items.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    m.modelCode,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
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
                "最近更新：${formatTime(usage.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsScreen(vm: UsageViewModel, onClose: () -> Unit) {
    val maskedKey by vm.maskedKey.collectAsState()
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
                TextButton(onClick = onClose) { Text("返回") }
                Spacer(Modifier.size(4.dp))
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            SettingsCard(title = "当前服务商") {
                Text(
                    "智谱 GLM Coding Plan",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "直连 · 实验性",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard(title = "API Key") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "状态",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (maskedKey.isNotEmpty()) "已配置 $maskedKey" else "未配置",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "本机加密存储（EncryptedSharedPreferences），不上传任何服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard(title = "数据刷新") {
                InfoLine("后台自动刷新", "每 30 分钟")
                InfoLine("回到 App", "静默刷新（≥15 分钟）")
                InfoLine("连续失败", "暂停 6 小时")
            }

            SettingsCard(title = "关于") {
                InfoLine("版本", APP_VERSION)
                InfoLine("用途", "桌面卡片 · 用量实时查")
                Text(
                    "多账户管理（Kimi / MiniMax / 火山方舟 等）规划中，见 ROADMAP v2.0。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = { vm.clearConfig() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("清除配置并退出登录", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WindowCard(title: String, usedPercent: Int, resetAt: Long?) {
    val remaining = 100 - usedPercent
    val color by animateColorAsState(
        targetValue = usageColorFor(usedPercent),
        label = "usageColor"
    )
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
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
            Text(
                if (resetAt != null) "预计 ${formatTime(resetAt)} 恢复" else "重置时间暂不可用",
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

private const val APP_VERSION = "1.1.0"
