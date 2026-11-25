package com.visionvpn.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.visionvpn.app.data.model.AppLanguage
import com.visionvpn.app.data.model.VisionThemeVariant

private val VisionDarkColorScheme = darkColorScheme(
    primary = VisionColors.ElectricBlue,
    onPrimary = VisionColors.TextPrimary,
    secondary = VisionColors.NeonPurple,
    onSecondary = VisionColors.TextPrimary,
    tertiary = VisionColors.CyanHighlight,
    onTertiary = VisionColors.BgDeepTop,
    background = VisionColors.BgDeepTop,
    onBackground = VisionColors.TextPrimary,
    surface = VisionColors.CardSurfaceDark,
    onSurface = VisionColors.TextPrimary,
    surfaceVariant = VisionColors.CardSurfaceElevated,
    onSurfaceVariant = VisionColors.TextSecondary,
    outline = VisionColors.CardBorder,
    error = VisionColors.CoralError,
    onError = VisionColors.TextPrimary
)

@Composable
fun VisionVpnTheme(
    language: AppLanguage = AppLanguage.ENGLISH,
    themeVariant: VisionThemeVariant = VisionThemeVariant.VISION_NAVY,
    content: @Composable () -> Unit
) {
    val direction = if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val scheme = if (themeVariant == VisionThemeVariant.DEEP_SPACE) {
        VisionDarkColorScheme.copy(
            background = VisionColors.BgOledTop,
            surface = VisionColors.BgDeepTop
        )
    } else {
        VisionDarkColorScheme
    }

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(
            colorScheme = scheme,
            typography = buildVisionTypography(isPersian = language == AppLanguage.PERSIAN),
            content = content
        )
    }
}
