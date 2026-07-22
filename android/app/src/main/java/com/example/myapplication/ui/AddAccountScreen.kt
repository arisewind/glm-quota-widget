package com.example.myapplication.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.services.Region

/** 添加账户：选服务商/区域 + 填 Key + 测试连接保存。isFirst=true 时无返回按钮（首次配置）。 */
@Composable
internal fun AddAccountScreen(vm: UsageViewModel, isFirst: Boolean, onDone: () -> Unit) {
    val options = vm.providerOptions
    var providerId by remember { mutableStateOf(options.first().providerId) }
    val selected = options.first { it.providerId == providerId }
    var region by remember { mutableStateOf(Region.CN) }
    var key by remember { mutableStateOf("") }
    var orgId by remember { mutableStateOf("") }      // GLM 团队版：组织 ID
    var projectId by remember { mutableStateOf("") }  // GLM 团队版：项目 ID
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
            // 竖向排列：横排时「智谱 GLM Coding Plan」文字过长挤压后面的 chip，第 3 个宽度不足导致高度异常撑开（曾现 1336px 巨高空白 bug）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { opt ->
                    FilterChip(
                        selected = providerId == opt.providerId,
                        onClick = { providerId = opt.providerId },
                        label = { Text(opt.label, maxLines = 1) },
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
                label = { Text(if (selected.requiresTeamCreds) "团队套餐 API Key" else "API Key") },
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

            // GLM 团队版三件套：组织 ID / 项目 ID（官方未公开格式，需抓包获得）
            if (selected.requiresTeamCreds) {
                OutlinedTextField(
                    value = orgId,
                    onValueChange = { orgId = it },
                    label = { Text("组织 ID（bigmodel-organization）") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = projectId,
                    onValueChange = { projectId = it },
                    label = { Text("项目 ID（bigmodel-project）") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "组织 / 项目 ID 获取：登录 bigmodel.cn/coding-plan/team/usage-stats，" +
                        "浏览器开发者工具抓包 quota/limit 请求，复制请求头中 " +
                        "bigmodel-organization / bigmodel-project 的值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                    val callback: (Boolean, String?) -> Unit = { ok, msg ->
                        testing = false
                        if (ok) onDone() else error = msg ?: "连接失败"
                    }
                    if (selected.requiresTeamCreds) {
                        vm.addTeamAccount(
                            providerId, key.trim(), orgId.trim(), projectId.trim(), label.trim(), callback
                        )
                    } else {
                        vm.addAccount(providerId, key.trim(), regionArg, label.trim(), callback)
                    }
                },
                enabled = (if (selected.requiresTeamCreds)
                    key.isNotBlank() && orgId.isNotBlank() && projectId.isNotBlank() else key.isNotBlank()) && !testing,
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
