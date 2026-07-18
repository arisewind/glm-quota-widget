package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌青蓝（GLM 科技感）
val BrandPrimary = Color(0xFF00C2B8)
val BrandPrimaryDark = Color(0xFF008A86)
val BrandAccent = Color(0xFF3DD6D0)

// 深色基底
val SurfaceDark = Color(0xFF0F1419)
val CardDark = Color(0xFF1A1F2E)
val CardDarkElevated = Color(0xFF222B3E)
val OnSurfaceDark = Color(0xFFE8EDF5)
val OnSurfaceMuted = Color(0xFF8A94A6)

// 浅色基底
val SurfaceLight = Color(0xFFF6F8FB)
val CardLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1A1F2E)
val OnSurfaceMutedLight = Color(0xFF5A6478)

// 用量警示色（已用越高越警示）
val UsageSafe = Color(0xFF3DD6D0)   // < 60%
val UsageWarn = Color(0xFFFFB547)   // 60–85%
val UsageDanger = Color(0xFFFF6B6B) // > 85%

// 兼容旧引用（Theme 模板默认色，保留避免破坏）
val Purple80 = BrandAccent
val PurpleGrey80 = OnSurfaceMuted
val Pink80 = UsageWarn
val Purple40 = BrandPrimaryDark
val PurpleGrey40 = OnSurfaceMutedLight
val Pink40 = UsageWarn
