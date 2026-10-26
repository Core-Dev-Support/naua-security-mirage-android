package com.naua_security_mirage.app.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.naua_security_mirage.app.data.repository.SettingsRepository

data class MirageThemeColors(
    val bg: Color,
    val card: Color,
    val cardStroke: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentLight: Color,
    val accentDeep: Color,
    val isDark: Boolean
)

val LocalMirageColors = staticCompositionLocalOf {
    MirageThemeColors(
        bg = MirageColors.Bg,
        card = MirageColors.Card,
        cardStroke = MirageColors.CardStroke,
        ink = MirageColors.Ink,
        inkSoft = MirageColors.InkSoft,
        inkMuted = MirageColors.InkMuted,
        inkFaint = MirageColors.InkFaint,
        accent = MirageColors.Amber,
        accentLight = MirageColors.AmberLight,
        accentDeep = MirageColors.AmberDeep,
        isDark = true
    )
}

@Composable
fun MirageTheme(
    settingsRepository: SettingsRepository,
    content: @Composable () -> Unit
) {
    val preset = settingsRepository.themePreset
    val customAccent = if (settingsRepository.customConnectBtnColor != 0) {
        Color(settingsRepository.customConnectBtnColor.toLong() and 0xFFFFFFFFL)
    } else {
        MirageColors.Amber
    }

    val customBg = if (settingsRepository.customBgColor != 0) {
        Color(settingsRepository.customBgColor.toLong() and 0xFFFFFFFFL)
    } else {
        MirageColors.Bg
    }

    val isLight = preset == SettingsRepository.THEME_LIGHT
    val isAmoled = preset == "amoled"

    val colors = when {
        preset == SettingsRepository.THEME_LIGHT -> MirageThemeColors(
            bg = MirageColors.BgLight,
            card = MirageColors.CardLight,
            cardStroke = MirageColors.CardStrokeLight,
            ink = MirageColors.InkLight,
            inkSoft = Color(0xFF64748B),
            inkMuted = MirageColors.InkFaint,
            inkFaint = Color(0xFF94A3B8),
            accent = customAccent,
            accentLight = customAccent.copy(alpha = 0.8f),
            accentDeep = customAccent.copy(alpha = 0.9f),
            isDark = false
        )
        isAmoled -> MirageThemeColors(
            bg = MirageColors.BgAmoled,
            card = MirageColors.CardAmoled,
            cardStroke = MirageColors.CardStroke,
            ink = MirageColors.Ink,
            inkSoft = MirageColors.InkSoft,
            inkMuted = MirageColors.InkMuted,
            inkFaint = MirageColors.InkFaint,
            accent = customAccent,
            accentLight = customAccent.copy(alpha = 0.8f),
            accentDeep = customAccent.copy(alpha = 0.9f),
            isDark = true
        )
        preset == SettingsRepository.THEME_CUSTOM -> MirageThemeColors(
            bg = customBg,
            card = MirageColors.Card,
            cardStroke = MirageColors.CardStroke,
            ink = MirageColors.Ink,
            inkSoft = MirageColors.InkSoft,
            inkMuted = MirageColors.InkMuted,
            inkFaint = MirageColors.InkFaint,
            accent = customAccent,
            accentLight = customAccent.copy(alpha = 0.8f),
            accentDeep = customAccent.copy(alpha = 0.9f),
            isDark = true
        )
        else -> MirageThemeColors(
            bg = MirageColors.Bg,
            card = MirageColors.Card,
            cardStroke = MirageColors.CardStroke,
            ink = MirageColors.Ink,
            inkSoft = MirageColors.InkSoft,
            inkMuted = MirageColors.InkMuted,
            inkFaint = MirageColors.InkFaint,
            accent = customAccent,
            accentLight = customAccent.copy(alpha = 0.8f),
            accentDeep = customAccent.copy(alpha = 0.9f),
            isDark = true
        )
    }

    val materialColors = if (colors.isDark) {
        darkColors(
            primary = colors.accent,
            background = colors.bg,
            surface = colors.card,
            onBackground = colors.ink,
            onSurface = colors.ink
        )
    } else {
        lightColors(
            primary = colors.accent,
            background = colors.bg,
            surface = colors.card,
            onBackground = colors.ink,
            onSurface = colors.ink
        )
    }

    CompositionLocalProvider(LocalMirageColors provides colors) {
        MaterialTheme(
            colors = materialColors,
            content = content
        )
    }
}
