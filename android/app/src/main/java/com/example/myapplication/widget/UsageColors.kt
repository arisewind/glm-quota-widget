package com.example.myapplication.widget

import com.example.myapplication.domain.UsageThresholds
import com.example.myapplication.domain.UsageTier

/**
 * Widget 数字用量色（详细版 + 列表版共用单一真源）。
 *
 * 阈值与 App 内一致，统一引用 [UsageThresholds]（WARN=60 / DANGER=85）：
 * - DANGER(>85%) 珊瑚红 / WARN(60–85%) 琥珀 / SAFE(<60%) 安全青。
 *
 * App 内 Compose 变色见 [com.example.myapplication.MainActivity.usageColorFor]（同源，v3.4 收敛）。
 */
internal fun usageColorInt(usedPercent: Int): Int = when (UsageThresholds.tierOf(usedPercent)) {
    UsageTier.DANGER -> 0xFFFF6B6B.toInt()  // 红（UsageDanger）
    UsageTier.WARN -> 0xFFF5A623.toInt()    // 橙（UsageWarn）
    UsageTier.SAFE -> 0xFF00B894.toInt()    // 绿（UsageSafe，v3.3 与品牌蓝分离）
}
