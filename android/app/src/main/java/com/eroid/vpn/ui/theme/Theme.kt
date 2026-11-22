package com.eroid.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.eroid.vpn.model.AppTheme

val LocalThemeColors = staticCompositionLocalOf { getThemeColors(AppTheme.NORDIC) }

@Composable
fun EroidVpnTheme(
    appTheme: AppTheme = AppTheme.NORDIC,
    content: @Composable () -> Unit
) {
    val themeColors = getThemeColors(appTheme)
    val colorScheme = darkColorScheme(
        primary = themeColors.primary,
        background = themeColors.background,
        surface = themeColors.surface,
        onPrimary = themeColors.background,
        onBackground = themeColors.primaryText,
        onSurface = themeColors.primaryText
    )

    CompositionLocalProvider(LocalThemeColors provides themeColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
