@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.myapplication

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.domain.WindowKind
import com.example.myapplication.services.NotificationLogEntry
import com.example.myapplication.services.NotificationType
import com.example.myapplication.services.ServiceProviders
import com.example.myapplication.ui.UsageViewModel
import com.example.myapplication.ui.theme.UsageDanger
import com.example.myapplication.ui.theme.UsageSafe
import com.example.myapplication.ui.theme.UsageWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 通知记录页（v3.2）：时间线展示 app 发过的告警/恢复通知。
 * 首次进入 + 每次 ON_RESUME（从后台/其他页返回）都 refresh 读最新——后台 Worker 可能已 append 新条目。
 */
@Composable
internal fun NotificationLogScreen(vm: UsageViewModel, onBack: () -> Unit) {
    val log by vm.notificationLog.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        vm.refreshNotificationLog()  // 首次进入读一次
        vm.markNotificationsSeen()   // v3.5：进入通知页 = 已读，铃铛 badge 清零
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) {
            vm.refreshNotificationLog()
            vm.markNotificationsSeen()
        } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知记录") },
                navigationIcon = { TextButton(onBack) { Text("‹ 返回") } },
                actions = {
                    if (log.isNotEmpty()) {
                        TextButton(onClick = { vm.clearNotificationLog() }) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = 22.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (log.isEmpty()) {
                Spacer(Modifier.height(80.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔕", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.size(12.dp))
                    Text("暂无通知记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                log.forEach { e -> TimelineItem(e) }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "仅保留最近 200 条 · 5 小时窗口告警不发恢复通知",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimelineItem(e: NotificationLogEntry) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(typeColor(e.type)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        typeTag(e.type),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = typeColor(e.type)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        logTimeFmt.format(Date(e.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.size(3.dp))
                Text(
                    typeText(e.type, e.accountLabel, e.windowKind, e.percent),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    ServiceProviders.labelOf(e.providerId) + " · " + e.windowKind.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun typeColor(t: NotificationType): Color = when (t) {
    NotificationType.EXHAUSTED -> UsageDanger
    NotificationType.LOW -> UsageWarn
    NotificationType.RECOVERY -> UsageSafe
}

private fun typeTag(t: NotificationType): String = when (t) {
    NotificationType.EXHAUSTED -> "耗尽"
    NotificationType.LOW -> "低额度"
    NotificationType.RECOVERY -> "已恢复"
}

private fun typeText(t: NotificationType, label: String, kind: WindowKind, percent: Int): String = when (t) {
    NotificationType.EXHAUSTED -> "$label · ${kind.displayName}已耗尽"
    NotificationType.LOW -> "$label · ${kind.displayName}已用 $percent%"
    NotificationType.RECOVERY -> "$label · ${kind.displayName}已恢复可用"
}
