package com.metaquest.cast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = HorizonMetaBlue,
    onPrimary = HorizonTextPrimary,
    primaryContainer = HorizonSurfaceElevated,
    secondary = HorizonLanGreen,
    tertiary = HorizonCloudCyan,
    background = HorizonSpace,
    surface = HorizonSurface,
    onSurface = HorizonTextPrimary,
    outline = HorizonBorderSubtle
)

@Composable
fun QuestCastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
