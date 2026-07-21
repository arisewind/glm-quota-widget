package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌主色（glintapi 冷光蓝青，v3.7 品牌落地）：主蓝 #3B82F6 → 青 #06B6D4
val BrandPrimary = Color(0xFF3B82F6)      // 冷光蓝（primary）
val BrandPrimaryDark = Color(0xFF2563EB)  // 按压态深蓝
val BrandAccent = Color(0xFF06B6D4)       // 青光（accent）

// 深色基底（glintapi 深夜底：光在暗处闪光）
val SurfaceDark = Color(0xFF0E1530)       // 深夜底
val CardDark = Color(0xFF131A33)          // 卡片
val CardDarkElevated = Color(0xFF1B2440)  // 卡片 elevated
val OnSurfaceDark = Color(0xFFE8EDF5)
val OnSurfaceMuted = Color(0xFF8A94A6)

// 浅色基底（冷灰白，保持）
val SurfaceLight = Color(0xFFF7F8FA)
val CardLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1A1F2E)
val OnSurfaceMutedLight = Color(0xFF6B7280)

// 边框/分割
val BorderLight = Color(0xFFE8EAEE)
val DividerLight = Color(0xFFEEF0F3)

// 用量状态色（v3.3：与品牌蓝分离——绿/橙/红，语义独立于交互蓝，避免「正常用量」和「品牌选中」撞色）
val UsageSafe = Color(0xFF00B894)   // < 60% 正常（绿）
val UsageWarn = Color(0xFFF5A623)   // 60–85%（橙）
val UsageDanger = Color(0xFFFF6B6B) // > 85%（红）
