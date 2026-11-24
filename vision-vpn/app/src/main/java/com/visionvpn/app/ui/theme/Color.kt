package com.visionvpn.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object VisionColors {
    // Deep Navy Gradient Background
    val BgDeepTop = Color(0xFF060B26)
    val BgDeepBottom = Color(0xFF0B1437)
    val BgOledTop = Color(0xFF030614)
    val BgOledBottom = Color(0xFF060B26)

    // Translucent Glass Surfaces & Borders
    val CardSurfaceDark = Color(0xFF0F1535)
    val CardSurfaceElevated = Color(0xFF111C44)
    val CardBorder = Color(0xFF1F2B5B)

    // Primary Accents
    val ElectricBlue = Color(0xFF0075FF)
    val NeonPurple = Color(0xFF582CFF)

    // Secondary Accent & Semantic States
    val CyanHighlight = Color(0xFF2CD9FF)
    val EmeraldConnected = Color(0xFF01B574)
    val AmberWarning = Color(0xFFFFB547)
    val CoralError = Color(0xFFE31A1A)

    // Typography
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA0AEC0)

    val PrimaryAccentGradient = Brush.linearGradient(
        colors = listOf(ElectricBlue, NeonPurple)
    )

    val ConnectedGradient = Brush.linearGradient(
        colors = listOf(EmeraldConnected, CyanHighlight)
    )

    val GlassCardGradient = Brush.verticalGradient(
        colors = listOf(
            CardSurfaceElevated.copy(alpha = 0.84f),
            CardSurfaceDark.copy(alpha = 0.76f)
        )
    )
}
