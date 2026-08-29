package com.videocompress.local.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题。
 *
 * 刻意让 background / surface / topBar / bottomBar 使用同一个颜色，
 * 保证整页背景扁平，不出现一条条灰白色带。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F4F0),
    onPrimaryContainer = Color(0xFF00433C),
    secondary = Color(0xFF4A5A6A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E8F1),
    onSecondaryContainer = Color(0xFF16222E),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF14181C),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFF6F7F9),
    onSurfaceVariant = Color(0xFF5A6470),
    surfaceContainerLow = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFF6F7F9),
    surfaceContainerHigh = Color(0xFFF6F7F9),
    surfaceContainerHighest = Color(0xFFF6F7F9),
    outline = Color(0xFFD6DBE2),
    outlineVariant = Color(0xFFE6EAF0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE4E2),
    onErrorContainer = Color(0xFF7A1B16)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0BE),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF004F47),
    onPrimaryContainer = Color(0xFFCFF3EC),
    secondary = Color(0xFFB8C6D9),
    onSecondary = Color(0xFF22303E),
    secondaryContainer = Color(0xFF1E2A36),
    onSecondaryContainer = Color(0xFFD6E2F0),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF101418),
    onSurfaceVariant = Color(0xFFA3AEBB),
    surfaceContainerLow = Color(0xFF14181D),
    surfaceContainer = Color(0xFF171C22),
    surfaceContainerHigh = Color(0xFF1A1F25),
    surfaceContainerHighest = Color(0xFF1E242B),
    outline = Color(0xFF2B3238),
    outlineVariant = Color(0xFF222831),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1410),
    onErrorContainer = Color(0xFFFFDAD5)
)

@Composable
fun VideoCompressorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
