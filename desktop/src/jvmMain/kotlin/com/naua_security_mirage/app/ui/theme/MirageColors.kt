package com.naua_security_mirage.app.ui.theme

import androidx.compose.ui.graphics.Color

object MirageColors {

    // Background & Card Colors (Aligned with Android colors.xml)
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

    // Pill and Button active states
    val PillActiveBg = Color(0xFF2A241C)
    val PillActiveBorder = Color(0xFFE8A33D)

    // Aurora shell palette
    val AuBg = Color(0xFF06070B)
    val AuFrame = Color(0x1AFFFFFF)
    val AuFg = Color(0xFFF6F1EA)
    val AuFg2 = Color(0xB3F6F1EA)
    val AuFg3 = Color(0x75F6F1EA)
    val AuSep = Color(0x14FFFFFF)
    val AuAccent = Color(0xFFFFA928)
    val AuAccentHi = Color(0xFFFFD089)
    val AuAccentLo = Color(0xFF996518)
    val AuAccentText = Color(0xFFFCBD5E)
    val AuOnAccent = Color(0xFF2A1600)
    val AuGood = Color(0xFF5EE0A0)
    val AuBad = Color(0xFFFF7A7A)
    val AuCool = Color(0xFF62C8FF)
    val AuSolid = Color(0xFF171A24)

    val PowerIconConnected = Color(0xFF2A1706)
    val PowerIconDisconnected = Color(0xFFE8A33D)

    val GlowAmber = Color(0x73E8A33D)
    val GlowAmberRing = Color(0x26E8A33D)
    val SwitchOff = Color(0x26FFFFFF)

    val StatusConnected = Color(0xFF10B981)
    val StatusConnecting = Color(0xFFF59E0B)
    val StatusDisconnected = Color(0xFFEF4444)

    // Appearance Palette Swatches (Matching Android App_Slide_4.jpg)
    val Swatches = listOf(
        0xFFE8A33D.toInt(), // Amber (Default)
        0xFF00E5FF.toInt(), // Cyan
        0xFF10B981.toInt(), // Emerald Green
        0xFF8B5CF6.toInt(), // Violet
        0xFFEF4444.toInt(), // Red
        0xFFEC4899.toInt(), // Pink
        0xFF3B82F6.toInt(), // Blue
        0xFFF97316.toInt()  // Orange
    )

    val ActionBtnSwatches = listOf(
        0xFFF3F1EA.toInt(), // White
        0xFFE8A33D.toInt(), // Amber
        0xFF00E5FF.toInt(), // Cyan
        0xFF10B981.toInt(), // Emerald
        0xFF8B5CF6.toInt(), // Violet
        0xFFEF4444.toInt(), // Red
        0xFF94A3B8.toInt()  // Slate
    )

    val TextSwatches = listOf(
        0xFFF3F1EA.toInt(), // White
        0xFFE2E8F0.toInt(), // Light slate
        0xFFFEF08A.toInt(), // Pale Yellow
        0xFFA7F3D0.toInt(), // Pale Mint
        0xFFC7D2FE.toInt(), // Pale Lavender
        0xFFFECDD3.toInt(), // Pale Rose
        0xFF94A3B8.toInt()  // Slate
    )

    val SwitchSwatches = listOf(
        0xFFE8A33D.toInt(), // Amber
        0xFF00E5FF.toInt(), // Cyan
        0xFF10B981.toInt(), // Emerald
        0xFF8B5CF6.toInt(), // Violet
        0xFFEF4444.toInt(), // Red
        0xFFF59E0B.toInt(), // Yellow
        0xFFEC4899.toInt()  // Pink
    )
}
