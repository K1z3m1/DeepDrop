package com.prirai.android.lr.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LRFontFamily: FontFamily = FontFamily.Default

private val MaterialScale = Typography()

private fun baseTypography(family: FontFamily) =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.sp,
            ),
        displaySmall = MaterialScale.displaySmall.copy(fontFamily = family),
        headlineLarge =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        headlineSmall = MaterialScale.headlineSmall.copy(fontFamily = family),
        titleLarge =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.1.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = family,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )

private fun TextStyle.emphasized(weight: FontWeight = FontWeight.Bold): TextStyle = copy(fontWeight = weight)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun lrTypography(family: FontFamily): Typography {
    val base = baseTypography(family)
    return base.copy(
        displayLargeEmphasized = base.displayLarge.emphasized(FontWeight.ExtraBold),
        displayMediumEmphasized = base.displayMedium.emphasized(FontWeight.ExtraBold),
        displaySmallEmphasized = base.displaySmall.emphasized(),
        headlineLargeEmphasized = base.headlineLarge.emphasized(),
        headlineMediumEmphasized = base.headlineMedium.emphasized(),
        headlineSmallEmphasized = base.headlineSmall.emphasized(),
        titleLargeEmphasized = base.titleLarge.emphasized(),
        titleMediumEmphasized = base.titleMedium.emphasized(),
        titleSmallEmphasized = base.titleSmall.emphasized(),
        bodyLargeEmphasized = base.bodyLarge.emphasized(FontWeight.Medium),
        bodyMediumEmphasized = base.bodyMedium.emphasized(FontWeight.Medium),
        bodySmallEmphasized = base.bodySmall.emphasized(FontWeight.Medium),
        labelLargeEmphasized = base.labelLarge.emphasized(),
        labelMediumEmphasized = base.labelMedium.emphasized(),
        labelSmallEmphasized = base.labelSmall.emphasized(),
    )
}

val Typography: Typography = lrTypography(LRFontFamily)
