package com.visionvpn.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.visionvpn.app.R

private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val vazirmatnGoogleFont = GoogleFont("Vazirmatn")
private val plusJakartaGoogleFont = GoogleFont("Plus Jakarta Sans")

val VazirmatnFontFamily = FontFamily(
    Font(googleFont = vazirmatnGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = vazirmatnGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = vazirmatnGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = vazirmatnGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Bold)
)

val EnglishSansFontFamily = FontFamily(
    Font(googleFont = plusJakartaGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = plusJakartaGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = plusJakartaGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = plusJakartaGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Bold)
)

fun buildVisionTypography(isPersian: Boolean): Typography {
    val family = if (isPersian) VazirmatnFontFamily else EnglishSansFontFamily
    return Typography(
        headlineLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            color = VisionColors.TextPrimary
        ),
        headlineMedium = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            color = VisionColors.TextPrimary
        ),
        titleLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            color = VisionColors.TextPrimary
        ),
        titleMedium = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            color = VisionColors.TextPrimary
        ),
        bodyLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = VisionColors.TextPrimary
        ),
        bodyMedium = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = VisionColors.TextSecondary
        ),
        labelLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            color = VisionColors.TextPrimary
        ),
        labelSmall = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = VisionColors.TextSecondary
        )
    )
}
