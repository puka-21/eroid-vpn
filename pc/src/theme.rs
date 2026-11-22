use eframe::egui::Color32;
use crate::types::AppTheme;

pub struct ThemeColors {
    pub bg: Color32,
    pub surface: Color32,
    pub surface_elevated: Color32,
    pub surface_hover: Color32,
    pub border: Color32,
    pub border_subtle: Color32,
    pub accent: Color32,
    pub accent_glow: Color32,
    pub accent_dark: Color32,
    pub text_primary: Color32,
    pub text_secondary: Color32,
    pub text_muted: Color32,
    pub success: Color32,
    pub warning: Color32,
    pub danger: Color32,
}

pub fn get_theme_colors(theme: AppTheme) -> ThemeColors {
    match theme {
        AppTheme::Nordic => ThemeColors {
            bg: Color32::from_rgb(10, 14, 23),             // Deep Obsidian Slate
            surface: Color32::from_rgb(16, 22, 36),        // Unibody Card
            surface_elevated: Color32::from_rgb(22, 30, 48), // Floating Nav Capsule
            surface_hover: Color32::from_rgb(28, 38, 60),
            border: Color32::from_rgb(33, 44, 70),
            border_subtle: Color32::from_rgb(20, 27, 44),
            accent: Color32::from_rgb(56, 189, 248),        // Ice Sky Blue
            accent_glow: Color32::from_rgb(14, 165, 233),
            accent_dark: Color32::from_rgb(12, 74, 110),
            text_primary: Color32::from_rgb(255, 255, 255),
            text_secondary: Color32::from_rgb(203, 213, 225),
            text_muted: Color32::from_rgb(100, 116, 139),
            success: Color32::from_rgb(34, 197, 94),
            warning: Color32::from_rgb(245, 158, 11),
            danger: Color32::from_rgb(239, 68, 68),
        },
        AppTheme::Void => ThemeColors {
            bg: Color32::from_rgb(4, 4, 6),                // Absolute OLED
            surface: Color32::from_rgb(14, 14, 18),
            surface_elevated: Color32::from_rgb(20, 20, 26),
            surface_hover: Color32::from_rgb(28, 28, 36),
            border: Color32::from_rgb(36, 36, 46),
            border_subtle: Color32::from_rgb(18, 18, 24),
            accent: Color32::from_rgb(0, 240, 255),        // Pure Electric Cyan
            accent_glow: Color32::from_rgb(0, 180, 216),
            accent_dark: Color32::from_rgb(0, 70, 90),
            text_primary: Color32::from_rgb(255, 255, 255),
            text_secondary: Color32::from_rgb(212, 212, 216),
            text_muted: Color32::from_rgb(113, 113, 122),
            success: Color32::from_rgb(74, 222, 128),
            warning: Color32::from_rgb(250, 204, 21),
            danger: Color32::from_rgb(248, 113, 113),
        },
        AppTheme::Cyberpunk => ThemeColors {
            bg: Color32::from_rgb(8, 8, 16),               // Night Cyber
            surface: Color32::from_rgb(17, 16, 33),
            surface_elevated: Color32::from_rgb(25, 23, 48),
            surface_hover: Color32::from_rgb(36, 32, 68),
            border: Color32::from_rgb(52, 46, 94),
            border_subtle: Color32::from_rgb(25, 22, 46),
            accent: Color32::from_rgb(255, 42, 95),        // Cyber Rose
            accent_glow: Color32::from_rgb(192, 132, 252), // Cyber Violet
            accent_dark: Color32::from_rgb(112, 10, 36),
            text_primary: Color32::from_rgb(255, 245, 247),
            text_secondary: Color32::from_rgb(228, 220, 240),
            text_muted: Color32::from_rgb(140, 130, 165),
            success: Color32::from_rgb(52, 211, 153),
            warning: Color32::from_rgb(251, 191, 36),
            danger: Color32::from_rgb(255, 42, 95),
        },
        AppTheme::Emerald => ThemeColors {
            bg: Color32::from_rgb(3, 12, 8),               // Dark Jade
            surface: Color32::from_rgb(8, 24, 17),
            surface_elevated: Color32::from_rgb(13, 36, 26),
            surface_hover: Color32::from_rgb(18, 48, 35),
            border: Color32::from_rgb(24, 66, 48),
            border_subtle: Color32::from_rgb(12, 34, 24),
            accent: Color32::from_rgb(16, 185, 129),       // Vivid Emerald
            accent_glow: Color32::from_rgb(5, 150, 105),
            accent_dark: Color32::from_rgb(6, 78, 54),
            text_primary: Color32::from_rgb(240, 253, 244),
            text_secondary: Color32::from_rgb(187, 247, 208),
            text_muted: Color32::from_rgb(94, 150, 120),
            success: Color32::from_rgb(16, 185, 129),
            warning: Color32::from_rgb(234, 179, 8),
            danger: Color32::from_rgb(239, 68, 68),
        },
        AppTheme::TokyoNight => ThemeColors {
            bg: Color32::from_rgb(15, 16, 25),             // Tokyo Indigo
            surface: Color32::from_rgb(23, 25, 40),
            surface_elevated: Color32::from_rgb(31, 34, 54),
            surface_hover: Color32::from_rgb(41, 45, 72),
            border: Color32::from_rgb(54, 59, 94),
            border_subtle: Color32::from_rgb(26, 29, 46),
            accent: Color32::from_rgb(139, 92, 246),       // Electric Violet
            accent_glow: Color32::from_rgb(167, 139, 250),
            accent_dark: Color32::from_rgb(76, 29, 149),
            text_primary: Color32::from_rgb(245, 243, 255),
            text_secondary: Color32::from_rgb(216, 180, 254),
            text_muted: Color32::from_rgb(130, 125, 160),
            success: Color32::from_rgb(134, 239, 172),
            warning: Color32::from_rgb(253, 224, 71),
            danger: Color32::from_rgb(244, 63, 94),
        },
    }
}
