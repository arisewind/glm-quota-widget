package com.example.myapplication

import android.content.Intent
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
import com.example.myapplication.domain.NormalizedWindow
import com.example.myapplication.domain.ServiceProviderInfo
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
        val vm = ViewModelProvider(this)[UsageViewModel::class.java]
        // App 回前台时静默刷新（FOREGROUND reason，自带 15min 节流）。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                vm.refreshOnForeground()
            }
        })
        handleAccountIntent(intent, vm)
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var screen by rememberSaveable { mutableStateOf("main") }
                    when (screen) {
                        "accounts" -> AccountsScreen(
                            vm,
                            onBack = { screen = "main" },
                            onAdd = { screen = "add" }
                        )
                        "add" -> AddAccountScreen(vm, isFirst = false, onDone = { screen = "accounts" })
                        else -> {
                            val state by vm.state.collectAsState()
                            when (val s = state) {
                                UsageUiState.Loading -> LoadingView()
                                UsageUiState.Unconfigured -> AddAccountScreen(vm, isFirst = true, onDone = {})
                                is UsageUiState.Content -> UsageScreen(s, vm, onOpenAccounts = { screen = "accounts" })
                            }
                        }
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

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

private fun usageColorFor(usedPercent: Int): Color = when {
    usedPercent > 85 -> UsageDanger
    usedPercent >= 60 -> UsageWarn
    else -> UsageSafe
}

private fun windowTitle(kind: WindowKind) = when (kind) {
    WindowKind.FIVE_HOUR -> "5 小时额度"
    WindowKind.WEEKLY -> "本周额度"
    WindowKind.MONTHLY -> "本月额度"
}

private fun providerLabelOf(providerId: String) = when (providerId) {
    ServiceProviderInfo.GLM_ID -> ServiceProviderInfo.GLM_LABEL
    ServiceProviderInfo.KIMI_ID -> ServiceProviderInfo.KIMI_LABEL
    ServiceProviderInfo.MINIMAX_ID -> ServiceProviderInfo.MINIMAX_LABEL
    else -> providerId
}

@Composable
private fun UsageScreen(
    content: UsageUiState.Content,
    vm: UsageViewModel,
    onOpenAccounts: () -> Unit
) {
    val snap = content.snapshot
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
                        snap.providerLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    snap.planName?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onOpenAccounts) { Text("账户", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = { vm.refresh() }) { Text("刷新", fontWeight = FontWeight.SemiBold) }
            }

            if (content.accounts.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    content.accounts.forEach { acc ->
                        FilterChip(
                            selected = acc.accountId == content.activeAccountId,
                            onClick = { vm.switchAccount(acc.accountId) },
                            label = { Text(acc.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            if (snap.status == UsageStatus.STALE || snap.status == UsageStatus.ERROR) {
                StatusBanner(snap.errorMessage ?: "数据异常", snap.status == UsageStatus.STALE)
            }

            snap.windows.forEach { w -> WindowCard(windowTitle(w.kind), w) }

            snap.modelUsage?.takeIf { it.isNotEmpty() }?.let { items ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "模型用量",
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
private fun AccountsScreen(vm: UsageViewModel, onBack: () -> Unit, onAdd: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val activeId by vm.activeAccountId.collectAsState()
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
                TextButton(onBack) { Text("返回") }
                Spacer(Modifier.size(4.dp))
                Text("账户管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            accounts.forEach { acc ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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

            OutlinedButton(
                onClick = { vm.clearConfig() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("清除全部账户", fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(16.dp))
        }
    }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
