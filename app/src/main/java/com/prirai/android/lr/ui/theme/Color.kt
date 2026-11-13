package com.prirai.android.lr.ui.theme

import androidx.compose.ui.graphics.Color

// Normal mode: "Deep Teal" palette. Must stay in sync with the m3_* colors in
// res/values/colors.xml and res/values-night/colors.xml (used by View screens).
fun hex(v: Long) = Color(0xFF000000 or v)

// Private mode colors (Purple theme like Firefox)
val PrivatePurple80 = Color(0xFF8F4FFF)
val PrivatePurple60 = Color(0xFF7542E5)
val PrivatePurple40 = Color(0xFF5D2CB8)
val PrivatePurple20 = Color(0xFF3D1C7A)
val PrivatePurple10 = Color(0xFF2E1A47)
val PrivatePurple5 = Color(0xFF1E0E34)
val PrivateOnDarkSurface = Color(0xFFE0D5F0)

val PrivateLightBackground = Color(0xFFF5F0FF)
val PrivateLightSurface = Color(0xFFEBDCFF)
val PrivateDarkBackground = Color(0xFF1E0E34)
val PrivateDarkSurface = Color(0xFF2E1A47)

// AMOLED mode colors (pure black)
val AmoledBlack = Color(0xFF000000)
val AmoledSurfaceVariant = Color(0xFF0A0A0A)
val AmoledSurfaceDim = Color(0xFF141414)
