package com.firstt175.novaframe.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.AppAppearancePrefs
import com.firstt175.novaframe.prefs.AppStyle
import com.firstt175.novaframe.prefs.AppThemeMode
import com.firstt175.novaframe.ui.components.IconBadge
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.NovaPrimaryButton
import com.firstt175.novaframe.ui.components.NovaSegmentedControl
import com.firstt175.novaframe.ui.components.NovaSlider
import com.firstt175.novaframe.ui.components.NovaSwitch
import com.firstt175.novaframe.ui.components.NovaTopBar
import com.firstt175.novaframe.ui.theme.IosBlue
import com.firstt175.novaframe.ui.theme.IosIndigo
import com.firstt175.novaframe.ui.theme.IosOrange
import com.firstt175.novaframe.ui.theme.IosPurple
import com.firstt175.novaframe.ui.theme.LiquidGlassBackdrop
import com.firstt175.novaframe.ui.theme.NovaPrimary
import com.firstt175.novaframe.ui.theme.glassSurface

@Composable
fun AppearanceSettingsScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember { mutableStateOf(AppAppearancePrefs.get(context)) }
    val glassSelected = state.style == AppStyle.LIQUID_GLASS

    fun apply() {
        AppAppearancePrefs.set(context, state)
        (context as? Activity)?.recreate()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NovaTopBar(title = stringResource(R.string.appearance_title), onBack = { nav.popBackStack() })

        NovaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Palette, size = 32.dp, glassTint = IosIndigo)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.appearance_theme_desc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            val themeModes = listOf(AppThemeMode.DARK, AppThemeMode.LIGHT)
            NovaSegmentedControl(
                options = listOf(stringResource(R.string.appearance_dark), stringResource(R.string.appearance_light)),
                selectedIndex = themeModes.indexOf(state.theme).coerceAtLeast(0),
                onSelect = { state = state.copy(theme = themeModes[it]) },
            )
        }

        NovaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.AutoAwesome, size = 32.dp, glassTint = IosPurple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.appearance_style), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.appearance_style_desc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            val styles = listOf(AppStyle.NORMAL, AppStyle.LIQUID_GLASS)
            NovaSegmentedControl(
                options = listOf(stringResource(R.string.appearance_style_normal), stringResource(R.string.appearance_style_glass)),
                selectedIndex = styles.indexOf(state.style).coerceAtLeast(0),
                onSelect = { state = state.copy(style = styles[it]) },
            )
        }

        if (glassSelected) {
            // iOS 27's headline feature: one slider from Clear to Tinted, previewed live.
            NovaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.WaterDrop, size = 32.dp, glassTint = IosBlue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.appearance_glass_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.appearance_glass_desc),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                GlassPreview(intensity = state.glassIntensity, dark = state.theme == AppThemeMode.DARK)
                Spacer(Modifier.height(6.dp))
                NovaSlider(
                    value = state.glassIntensity,
                    onValueChange = { state = state.copy(glassIntensity = it) },
                    valueRange = 0f..1f,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.appearance_glass_clear),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.appearance_glass_tinted),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            NovaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Tune, size = 32.dp, glassTint = IosOrange)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.appearance_fine_tuning), style = MaterialTheme.typography.titleMedium)
                }
                AppearanceSlider(R.string.appearance_surface_opacity, state.surfaceOpacity, 0.45f, 1f) {
                    state = state.copy(surfaceOpacity = it)
                }
                AppearanceSlider(R.string.appearance_border_opacity, state.borderOpacity, 0f, 1f) {
                    state = state.copy(borderOpacity = it)
                }
                AppearanceSlider(R.string.appearance_corner_radius, state.cornerRadius, 4f, 32f, suffix = " dp") {
                    state = state.copy(cornerRadius = it)
                }
                AppearanceSlider(R.string.appearance_shadow, state.shadowElevation, 0f, 16f, suffix = " dp") {
                    state = state.copy(shadowElevation = it)
                }
            }
        }

        NovaCard {
            SettingSwitch(
                title = stringResource(R.string.appearance_animations),
                desc = stringResource(R.string.appearance_animations_desc),
                checked = state.animationsEnabled,
                onCheckedChange = { state = state.copy(animationsEnabled = it) },
            )
            SettingSwitch(
                title = stringResource(R.string.appearance_compact),
                desc = stringResource(R.string.appearance_compact_desc),
                checked = state.compactMode,
                onCheckedChange = { state = state.copy(compactMode = it) },
            )
        }

        NovaPrimaryButton(
            text = stringResource(R.string.appearance_apply),
            onClick = ::apply,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Live sample of the glass at the chosen intensity, drawn on the same backdrop the app uses.
 * Colours are derived from the *pending* theme (not the saved one) so the preview is honest
 * before Apply is pressed.
 */
@Composable
private fun GlassPreview(intensity: Float, dark: Boolean) {
    val ink = if (dark) Color(0xFFF7F7FB) else Color(0xFF111318)
    Box(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(22.dp)),
    ) {
        LiquidGlassBackdrop(dark = dark, modifier = Modifier.matchParentSize())
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .glassSurface(RoundedCornerShape(22.dp), dark, intensity = intensity)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(Icons.Filled.AutoAwesome, size = 34.dp, glassTint = NovaPrimary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("NovaFrame", color = ink, style = MaterialTheme.typography.titleMedium)
                    Text("Frame generation", color = ink.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(
                Modifier
                    .size(52.dp)
                    .glassSurface(CircleShape, dark, strong = true, intensity = intensity),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tune, null, tint = ink)
            }
        }
    }
}

@Composable
private fun AppearanceSlider(
    labelRes: Int, value: Float, min: Float, max: Float, suffix: String = "%",
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(labelRes))
            Text(if (suffix == "%") "${(value * 100).toInt()}%" else "${value.toInt()}$suffix",
                color = MaterialTheme.colorScheme.primary)
        }
        NovaSlider(value = value, onValueChange = onValueChange, valueRange = min..max)
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NovaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
