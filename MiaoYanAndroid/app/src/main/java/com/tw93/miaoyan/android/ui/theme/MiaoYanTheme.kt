package com.tw93.miaoyan.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colors copied from the macOS editor assets and Markdown preview CSS. */
object MiaoYanColors {
    val EditorBackgroundLight = Color(0xFFFFFFFF)
    val EditorBackgroundDark = Color(0xFF23282D)
    // Theme.textColor resolves to NSColor.labelColor in the default system appearance.
    val EditorTextLight = Color(0xD8000000)
    val EditorTextDark = Color(0xD8FFFFFF)

    const val PreviewBackgroundLightCss = "#FFFFFF"
    const val PreviewBackgroundDarkCss = "#23282D"
    const val PreviewTextLightCss = "#262626"
    const val PreviewTextDarkCss = "#E7E9EA"
    const val PreviewSecondaryLightCss = "#777777"
    const val PreviewSecondaryDarkCss = "#ABB2BF"
    const val PreviewLinkLightCss = "#0C6ADA"
    const val PreviewLinkDarkCss = "#1D9BF0"
    const val PreviewBorderLightCss = "#E6E6E6"
    const val PreviewBorderDarkCss = "#454545"
    const val PreviewCodeLightCss = "#F7F7F7"
    const val PreviewCodeDarkCss = "#282E33"
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1C5D33),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4F1E7),
    onPrimaryContainer = Color(0xFF123D22),
    background = MiaoYanColors.EditorBackgroundLight,
    onBackground = Color(0xFF262626),
    surface = MiaoYanColors.EditorBackgroundLight,
    onSurface = Color(0xFF262626),
    surfaceDim = Color(0xFFF2F2F2),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = Color(0xFFF2F2F2),
    surfaceContainerHighest = Color(0xFFECECEC),
    surfaceVariant = Color(0xFFF7F7F7),
    onSurfaceVariant = Color(0xFF777777),
    outline = Color(0xFFE6E6E6),
    outlineVariant = Color(0xFFF0F0F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF54C59F),
    onPrimary = Color(0xFF102A21),
    primaryContainer = Color(0xFF25483C),
    onPrimaryContainer = Color(0xFFC0F1DF),
    background = MiaoYanColors.EditorBackgroundDark,
    onBackground = Color(0xFFE7E9EA),
    surface = MiaoYanColors.EditorBackgroundDark,
    onSurface = Color(0xFFE7E9EA),
    surfaceDim = Color(0xFF1E2327),
    surfaceBright = Color(0xFF343A40),
    surfaceContainerLowest = Color(0xFF1E2327),
    surfaceContainerLow = Color(0xFF252B30),
    surfaceContainer = Color(0xFF282E33),
    surfaceContainerHigh = Color(0xFF30363D),
    surfaceContainerHighest = Color(0xFF373E45),
    surfaceVariant = Color(0xFF282E33),
    onSurfaceVariant = Color(0xFFABB2BF),
    outline = Color(0xFF454545),
    outlineVariant = Color(0xFF343A40),
)

@Composable
fun MiaoYanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
