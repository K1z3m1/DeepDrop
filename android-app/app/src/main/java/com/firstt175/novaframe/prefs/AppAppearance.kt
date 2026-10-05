package com.firstt175.novaframe.prefs

import android.content.Context

enum class AppThemeMode(val value: String) {
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.value == value } ?: DARK
    }
}

/** Overall visual style. NORMAL = the original solid Material look; LIQUID_GLASS = iOS 27 Liquid Glass. */
enum class AppStyle(val value: String) {
    NORMAL("normal"),
    LIQUID_GLASS("liquid_glass");

    companion object {
        fun fromValue(value: String?): AppStyle =
            entries.firstOrNull { it.value == value } ?: NORMAL
    }
}

data class AppAppearance(
    val theme: AppThemeMode = AppThemeMode.DARK,
    val style: AppStyle = AppStyle.NORMAL,
    /** iOS 27 Liquid Glass slider: 0f = Clear (most transparent), 1f = Tinted (most opaque). */
    val glassIntensity: Float = 0.45f,
    val surfaceOpacity: Float = 1f,
    val borderOpacity: Float = 0.35f,
    val cornerRadius: Float = 18f,
    val shadowElevation: Float = 2f,
    val animationsEnabled: Boolean = true,
    val compactMode: Boolean = false,
)

object AppAppearancePrefs {
    private const val NAME = "lsfg_appearance"
    private const val THEME = "theme"
    private const val STYLE = "style"
    private const val GLASS_INTENSITY = "glass_intensity"
    private const val SURFACE_OPACITY = "surface_opacity"
    private const val BORDER_OPACITY = "border_opacity"
    private const val CORNER_RADIUS = "corner_radius"
    private const val SHADOW = "shadow"
    private const val ANIMATIONS = "animations"
    private const val COMPACT = "compact"

    fun get(ctx: Context): AppAppearance {
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return AppAppearance(
            theme = AppThemeMode.fromValue(p.getString(THEME, null)),
            style = AppStyle.fromValue(p.getString(STYLE, null)),
            glassIntensity = p.getFloat(GLASS_INTENSITY, 0.45f).coerceIn(0f, 1f),
            surfaceOpacity = p.getFloat(SURFACE_OPACITY, 1f).coerceIn(0.45f, 1f),
            borderOpacity = p.getFloat(BORDER_OPACITY, 0.35f).coerceIn(0f, 1f),
            cornerRadius = p.getFloat(CORNER_RADIUS, 18f).coerceIn(4f, 32f),
            shadowElevation = p.getFloat(SHADOW, 2f).coerceIn(0f, 16f),
            animationsEnabled = p.getBoolean(ANIMATIONS, true),
            compactMode = p.getBoolean(COMPACT, false),
        )
    }

    fun set(ctx: Context, value: AppAppearance) {
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(THEME, value.theme.value)
            .putString(STYLE, value.style.value)
            .putFloat(GLASS_INTENSITY, value.glassIntensity)
            .putFloat(SURFACE_OPACITY, value.surfaceOpacity)
            .putFloat(BORDER_OPACITY, value.borderOpacity)
            .putFloat(CORNER_RADIUS, value.cornerRadius)
            .putFloat(SHADOW, value.shadowElevation)
            .putBoolean(ANIMATIONS, value.animationsEnabled)
            .putBoolean(COMPACT, value.compactMode)
            .apply()
    }
}
