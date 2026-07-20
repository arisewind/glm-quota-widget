package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌主蓝（NordVPN 风，v3.3 落地）
val BrandPrimary = Color(0xFF4687FF)
val BrandPrimaryDark = Color(0xFF3A6FE0)
val BrandAccent = Color(0xFF6BA3FF)

// 深色基底（NordVPN 深色：深蓝黑）
val SurfaceDark = Color(0xFF131826)
val CardDark = Color(0xFF1A1F2E)
val CardDarkElevated = Color(0xFF222B3E)
val OnSurfaceDark = Color(0xFFE8EDF5)
val OnSurfaceMuted = Color(0xFF8A93A6)

// 浅色基底（NordVPN 浅色）
val SurfaceLight = Color(0xFFF7F8FA)
val CardLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1A1F2E)
val OnSurfaceMutedLight = Color(0xFF6B7280)

// 边框/分割（NordVPN）
val BorderLight = Color(0xFFE8EAEE)
val DividerLight = Color(0xFFEEF0F3)

// 用量状态色（v3.3：与品牌蓝分离——绿/橙/红，语义独立于交互蓝，避免「正常用量」和「品牌选中」撞色）
val UsageSafe = Color(0xFF00B894)   // < 60% 正常（绿，原青改绿避免与品牌蓝近）
val UsageWarn = Color(0xFFF5A623)   // 60–85%（橙）
val UsageDanger = Color(0xFFFF6B6B) // > 85%（红）

// 兼容旧引用（Theme 模板默认色，保留避免破坏）
val Purple80 = BrandAccent
val PurpleGrey80 = OnSurfaceMuted
val Pink80 = UsageWarn
val Purple40 = BrandPrimaryDark
val PurpleGrey40 = OnSurfaceMutedLight
val Pink40 = UsageWarn
