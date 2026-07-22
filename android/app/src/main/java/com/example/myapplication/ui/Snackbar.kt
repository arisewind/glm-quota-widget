package com.example.myapplication.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.BrandPrimary
import com.example.myapplication.ui.theme.UsageDanger
import com.example.myapplication.ui.theme.UsageSafe

/**
 * 分级 Snackbar（v3.8 任务1 变体1·底部）：成功绿 / 失败红 / 信息蓝，左 icon + 文案 + 可选 action。
 * 成功=UsageSafe，失败=UsageDanger（带「重试」），信息=BrandPrimary。
 */
@Composable
internal fun GlintSnackbar(msg: SnackbarMsg, onAction: () -> Unit) {
    val container = when (msg.level) {
        SnackbarLevel.SUCCESS -> UsageSafe
        SnackbarLevel.ERROR -> UsageDanger
        SnackbarLevel.INFO -> BrandPrimary
    }
    val icon = when (msg.level) {
        SnackbarLevel.SUCCESS -> Icons.Filled.Check
        SnackbarLevel.ERROR -> Icons.Filled.Warning
        SnackbarLevel.INFO -> Icons.Filled.Info
    }
    Snackbar(
        modifier = Modifier.padding(horizontal = 16.dp),
        containerColor = container,
        contentColor = Color.White,
        action = msg.actionLabel?.let { label ->
            @Composable { TextButton(onClick = onAction) { Text(label, color = Color.White) } }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(msg.message, fontWeight = FontWeight.Medium)
        }
    }
}
