package com.naua_security_mirage.app.ui.theme

import androidx.compose.ui.graphics.Color

object MirageColors {
    // Primary palette matching Android res/values/colors.xml
    val Bg = Color(0xFF0A0B10)
    val BgLight = Color(0xFFF8FAFC)
    val BgAmoled = Color(0xFF000000)

    val Card = Color(0xFF161821)
    val CardLight = Color(0xFFFFFFFF)
    val CardAmoled = Color(0xFF080808)
    val CardStroke = Color(0x14FFFFFF)
    val CardStrokeLight = Color(0x1A000000)

    val Ink = Color(0xFFF3F1EA)
    val InkLight = Color(0xFF0F172A)
    val InkSoft = Color(0xFF8A8D9E)
    val InkMuted = Color(0xFF8A8D9E)
    val InkFaint = Color(0xFF828699)

    val Amber = Color(0xFFE8A33D)
    val AmberDeep = Color(0xFFC9722A)
    val AmberLight = Color(0xFFF3B65C)

    val PowerIconConnected = Color(0xFF2A1706)
    val PowerIconDisconnected = Color(0xFFE8A33D)

    val GlowAmber = Color(0x73E8A33D)
    val GlowAmberRing = Color(0x26E8A33D)
    val SwitchOff = Color(0x26FFFFFF)

    val StatusConnected = Color(0xFF10B981) // Green
    val StatusConnecting = Color(0xFFF59E0B) // Amber
    val StatusDisconnected = Color(0xFFEF4444) // Red

    // Custom theme preset swatches matching Android AppearanceScreen
    val Swatches = listOf(
        0xFFE8A33D.toInt(), // Amber (Default)
        0xFF3B82F6.toInt(), // Cyber Blue
        0xFF10B981.toInt(), // Emerald Green
        0xFF8B5CF6.toInt(), // Violet / Purple
        0xFFEC4899.toInt(), // Neon Pink
        0xFFEF4444.toInt(), // Crimson Red
        0xFF06B6D4.toInt(), // Electric Cyan
        0xFFF97316.toInt()  // Sunset Orange
    )
}
