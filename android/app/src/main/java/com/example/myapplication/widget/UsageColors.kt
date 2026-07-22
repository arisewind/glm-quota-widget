package com.example.myapplication.widget

import androidx.compose.ui.graphics.toArgb
import com.example.myapplication.domain.UsageThresholds
import com.example.myapplication.domain.UsageTier
import com.example.myapplication.ui.theme.UsageDanger
import com.example.myapplication.ui.theme.UsageSafe
import com.example.myapplication.ui.theme.UsageWarn

/**
 * Widget 数字用量色（详细版 + 列表版共用单一真源）。
 *
 * 阈值统一引用 [UsageThresholds]（WARN=60 / DANGER=85）；颜色直接派生自 theme 的
 * [UsageDanger]/[UsageWarn]/[UsageSafe]（经 [toArgb]），消除与 ui/theme/Color.kt 的双抄——
 * 改用量色只改 theme 一处，widget 自动跟随。App 内 Compose 变色见 ui/UsageScreen.kt 的 usageColorFor（同源）。
 */
internal fun usageColorInt(usedPercent: Int): Int = when (UsageThresholds.tierOf(usedPercent)) {
    UsageTier.DANGER -> UsageDanger.toArgb()
    UsageTier.WARN -> UsageWarn.toArgb()
    UsageTier.SAFE -> UsageSafe.toArgb()
}
