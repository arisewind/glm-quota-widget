package com.example.myapplication.domain

/** 用量阈值单一真源（v3.4 收敛：原 60/85 在 usageColorFor / usageColorInt / UsageAlerter / WeeklyTrendCard 4 处手抄）。 */
object UsageThresholds {
    const val WARN = 60      // >= 60 橙
    const val DANGER = 85    // > 85 红

    fun tierOf(usedPercent: Int): UsageTier = when {
        usedPercent > DANGER -> UsageTier.DANGER
        usedPercent >= WARN -> UsageTier.WARN
        else -> UsageTier.SAFE
    }
}

enum class UsageTier { SAFE, WARN, DANGER }
