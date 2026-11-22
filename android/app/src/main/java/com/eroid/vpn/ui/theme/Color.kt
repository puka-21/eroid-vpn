package com.eroid.vpn.ui.theme

import androidx.compose.ui.graphics.Color
import com.eroid.vpn.model.AppTheme

data class ThemeColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val primary: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val textMuted: Color,
    val border: Color,
    val pingGood: Color = Color(0xFF00E676),
    val pingMedium: Color = Color(0xFFFFD600),
    val pingBad: Color = Color(0xFFFF1744)
)

fun getThemeColors(theme: AppTheme): ThemeColors {
    return when (theme) {
        AppTheme.NORDIC -> ThemeColors(
            background = Color(0xFF0D1117),
            surface = Color(0xFF161B22),
            surfaceElevated = Color(0xFF21262D),
            primary = Color(0xFF00E5FF),
            primaryText = Color(0xFFF0F6FC),
            secondaryText = Color(0xFFC9D1D9),
            textMuted = Color(0xFF8B949E),
            border = Color(0xFF30363D)
        )
        AppTheme.OLED -> ThemeColors(
            background = Color(0xFF000000),
            surface = Color(0xFF0A0A0A),
            surfaceElevated = Color(0xFF141414),
            primary = Color(0xFFFFFFFF),
            primaryText = Color(0xFFFFFFFF),
            secondaryText = Color(0xFFB0B0B0),
            textMuted = Color(0xFF707070),
            border = Color(0xFF222222)
        )
        AppTheme.CYBERPUNK -> ThemeColors(
            background = Color(0xFF05050A),
            surface = Color(0xFF120F1F),
            surfaceElevated = Color(0xFF1E1736),
            primary = Color(0xFFFF007F),
            primaryText = Color(0xFFFDE8F3),
            secondaryText = Color(0xFFE0B4CE),
            textMuted = Color(0xFF966E87),
            border = Color(0xFF3D234A)
        )
        AppTheme.EMERALD -> ThemeColors(
            background = Color(0xFF06120E),
            surface = Color(0xFF0C241C),
            surfaceElevated = Color(0xFF15382D),
            primary = Color(0xFF00E676),
            primaryText = Color(0xFFE8FDF3),
            secondaryText = Color(0xFFB4E0CC),
            textMuted = Color(0xFF6E9682),
            border = Color(0xFF1C4D3B)
        )
        AppTheme.TOKYO -> ThemeColors(
            background = Color(0xFF10101A),
            surface = Color(0xFF1A1B2E),
            surfaceElevated = Color(0xFF242742),
            primary = Color(0xFF7AA2F7),
            primaryText = Color(0xFFC0CAF5),
            secondaryText = Color(0xFFA9B1D6),
            textMuted = Color(0xFF565F89),
            border = Color(0xFF323759)
        )
    }
}
