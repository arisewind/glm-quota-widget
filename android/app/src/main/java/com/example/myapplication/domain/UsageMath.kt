package com.example.myapplication.domain

/**
 * 用量百分比 / 主窗口的纯函数（ADR-0002 归一化层）。UI 与 widget 共用，避免在多处重抄 fallback 逻辑。
 *
 * 工具调用额度（[WindowKind.TOOLS]）是独立维度（次数配额，非 token 额度），不参与 primaryPercent/primaryWindow。
 */

/** 主用量百分比：5h 窗优先，否则非工具窗最大值；无快照 0。TOOLS（工具调用额度）是独立维度，不参与。 */
fun UsageSnapshot.primaryPercent(): Int =
    window(WindowKind.FIVE_HOUR)?.usedPercent
        ?: windows.filter { it.kind != WindowKind.TOOLS }.maxOfOrNull { it.usedPercent }
        ?: 0

/** 主用量对应的窗口（5h 优先，否则非工具窗中 usedPercent 最大者）。用于标题 / 重置时间展示。 */
fun UsageSnapshot.primaryWindow(): NormalizedWindow? =
    window(WindowKind.FIVE_HOUR)
        ?: windows.filter { it.kind != WindowKind.TOOLS }.maxByOrNull { it.usedPercent }
