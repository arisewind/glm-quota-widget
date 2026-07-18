package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color(0xFF00312E),
    primaryContainer = BrandPrimaryDark,
    secondary = BrandAccent,
    background = SurfaceDark,
    surface = CardDark,
    surfaceVariant = CardDarkElevated,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceMuted,
    error = UsageDanger,
    onError = Color.White
)

private val LightColors = lightColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = Color.White,
    secondary = BrandPrimary,
    background = SurfaceLight,
    surface = CardLight,
    onBackground = OnSurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceMutedLight,
    error = UsageDanger,
    onError = Color.White
)

/**
 * 固定品牌色主题（关闭 dynamicColor，保证 UI 视觉一致）。
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
