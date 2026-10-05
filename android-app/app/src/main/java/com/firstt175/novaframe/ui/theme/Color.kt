package com.firstt175.novaframe.ui.theme

import androidx.compose.ui.graphics.Color

// Strict orange + black theme — every color below is a shade of orange or
// a neutral black/gray, no other hues. Refreshed for a richer, more
// "premium ember" look: primary is a touch deeper/more saturated, and a
// couple of glow/gradient accents were added for the redesigned cards and
// buttons.
val NovaPrimary = Color(0xFFFF9F1C)
val NovaOnPrimary = Color(0xFF1A0F00)
val NovaPrimaryContainer = Color(0xFF4A2C00)
val NovaOnPrimaryContainer = Color(0xFFFFE0A8)

val NovaSecondary = Color(0xFFFFB74D)
val NovaOnSecondary = Color(0xFF1F1200)
val NovaSecondaryContainer = Color(0xFF3D2400)
val NovaOnSecondaryContainer = Color(0xFFFFE8C2)

val NovaTertiary = Color(0xFFFFCC80)
val NovaOnTertiary = Color(0xFF1A0F00)
val NovaTertiaryContainer = Color(0xFF3A2200)
val NovaOnTertiaryContainer = Color(0xFFFFE8C2)

// Error uses a deep, saturated orange (not red) to stay within the
// orange/black palette while still reading as an alert state.
val NovaError = Color(0xFFE65100)
val NovaOnError = Color(0xFF130800)
val NovaErrorContainer = Color(0xFF3D1600)
val NovaOnErrorContainer = Color(0xFFFFD9B8)

val NovaBackground = Color(0xFF0A0A0A)
val NovaOnBackground = Color(0xFFF5F0E8)

val NovaSurface = Color(0xFF0D0D0D)
val NovaOnSurface = Color(0xFFF5F0E8)
val NovaOnSurfaceVariant = Color(0xFFB8B0A6)

val NovaSurfaceDim = Color(0xFF0A0A0A)
val NovaSurfaceBright = Color(0xFF2C2C2C)
val NovaSurfaceContainerLowest = Color(0xFF050505)
val NovaSurfaceContainerLow = Color(0xFF131313)
val NovaSurfaceContainer = Color(0xFF181818)
val NovaSurfaceContainerHigh = Color(0xFF212121)
val NovaSurfaceContainerHighest = Color(0xFF2B2B2B)

val NovaOutline = Color(0xFF5C5648)
val NovaOutlineVariant = Color(0xFF332F26)

// Status tones — both orange, distinguished by intensity: a bright amber
// for "good/active" and a deep burnt orange for "warn".
val NovaStatusGood = Color(0xFFFFC107)
val NovaStatusWarn = Color(0xFFFF6F00)
val NovaStatusBad = Color(0xFFD32F2F)

// New in the redesign: a soft glow tone for highlighted rings/shadows behind
// icons and buttons, plus a warmer gradient partner for two-tone fills
// (primary buttons). Both stay in the orange family.
val NovaGlow = Color(0xFFFFC26B)
val NovaGradientEnd = Color(0xFFE85D04)

/** Light theme palette. */
val NovaLightPrimary = Color(0xFFE47700)
val NovaLightOnPrimary = Color(0xFFFFFFFF)
val NovaLightPrimaryContainer = Color(0xFFFFDDB8)
val NovaLightOnPrimaryContainer = Color(0xFF2A1600)
val NovaLightSecondary = Color(0xFF8A4F00)
val NovaLightOnSecondary = Color(0xFFFFFFFF)
val NovaLightSecondaryContainer = Color(0xFFFFDDB8)
val NovaLightOnSecondaryContainer = Color(0xFF2A1600)
val NovaLightTertiary = Color(0xFF765A00)
val NovaLightOnTertiary = Color(0xFFFFFFFF)
val NovaLightTertiaryContainer = Color(0xFFFFE08B)
val NovaLightOnTertiaryContainer = Color(0xFF241A00)
val NovaLightError = Color(0xFFBA1A1A)
val NovaLightOnError = Color(0xFFFFFFFF)
val NovaLightErrorContainer = Color(0xFFFFDAD6)
val NovaLightOnErrorContainer = Color(0xFF410002)
val NovaLightBackground = Color(0xFFFAF6EF)
val NovaLightOnBackground = Color(0xFF1C1710)
val NovaLightSurface = Color(0xFFFCF9F3)
val NovaLightOnSurface = Color(0xFF1C1710)
val NovaLightOnSurfaceVariant = Color(0xFF6B6354)
val NovaLightOutline = Color(0xFF857B69)
val NovaLightOutlineVariant = Color(0xFFE3D9C6)

// iOS 27 system tints — used for the coloured "app icon" badges on glass rows and for status
// dots. Only shown in the Liquid Glass style; Normal keeps the strict orange palette.
val IosBlue = Color(0xFF0A84FF)
val IosGreen = Color(0xFF30D158)
val IosOrange = Color(0xFFFF9F0A)
val IosPurple = Color(0xFFBF5AF2)
val IosPink = Color(0xFFFF375F)
val IosTeal = Color(0xFF40C8E0)
val IosIndigo = Color(0xFF5E5CE6)
val IosGray = Color(0xFF8E8E93)
