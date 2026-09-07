package com.tw93.miaoyan.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFC8661D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC2),
    onPrimaryContainer = Color(0xFF351000),
    background = Color(0xFFF7F3EE),
    onBackground = Color(0xFF242220),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF242220),
    surfaceVariant = Color(0xFFECE6DF),
    outline = Color(0xFF85746A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB77E),
    onPrimary = Color(0xFF562000),
    primaryContainer = Color(0xFF773100),
    onPrimaryContainer = Color(0xFFFFDBC2),
    background = Color(0xFF171513),
    onBackground = Color(0xFFEAE1DA),
    surface = Color(0xFF1F1C1A),
    onSurface = Color(0xFFEAE1DA),
    surfaceVariant = Color(0xFF322E2B),
    outline = Color(0xFFA39186),
)

@Composable
fun MiaoYanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
