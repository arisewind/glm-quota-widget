package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.myapplication.services.SettingsStore

/** NordVPN 浅色 colorScheme（v3.3 落地）：主蓝 #4687FF + 浅冷灰白基底 + 完整 surfaceContainer 角色。 */
private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEFFC),
    onPrimaryContainer = Color(0xFF132B7A),
    secondary = BrandAccent,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = CardLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = DividerLight,
    surfaceContainerLowest = CardLight,
    surfaceContainerLow = Color(0xFFF3F5F8),
    surfaceContainer = Color(0xFFEEF0F3),
    surfaceContainerHigh = Color(0xFFE8EAEE),
    onSurfaceVariant = OnSurfaceMutedLight,
    outline = BorderLight,
    outlineVariant = DividerLight,
    error = UsageDanger,
    onError = Color.White
)

/** NordVPN 深色 colorScheme：深蓝黑基底 + 亮蓝主色 + 完整 surfaceContainer 角色。 */
private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B2A4A),
    onPrimaryContainer = Color(0xFFB0C4FF),
    secondary = BrandAccent,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = CardDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = CardDarkElevated,
    surfaceContainerLowest = SurfaceDark,
    surfaceContainerLow = Color(0xFF161B2A),
    surfaceContainer = CardDark,
    surfaceContainerHigh = CardDarkElevated,
    onSurfaceVariant = OnSurfaceMuted,
    outline = Color(0xFF2A3142),
    outlineVariant = CardDarkElevated,
    error = UsageDanger,
    onError = Color.White
)

/** 圆角 token（v3.3，8dp 网格）：消除散乱的 14/16/18/20 各处硬编码。 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/**
 * 品牌色主题（关闭 dynamicColor 保证视觉一致）。
 * 主题模式由 [themeMode] 决定：浅色 / 深色 / 跟随系统。
 */
@Composable
fun MyApplicationTheme(
    themeMode: String = SettingsStore.THEME_SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        SettingsStore.THEME_DARK -> true
        SettingsStore.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()  // THEME_SYSTEM
    }
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
