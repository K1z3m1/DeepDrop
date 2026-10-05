package com.firstt175.novaframe.ui.screens

import com.firstt175.novaframe.ui.Routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.firstt175.novaframe.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.ui.graphics.Color
import com.firstt175.novaframe.ui.components.IconBadge
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.NovaInsetDivider
import com.firstt175.novaframe.ui.theme.IosBlue
import com.firstt175.novaframe.ui.theme.IosGray
import com.firstt175.novaframe.ui.theme.IosGreen
import com.firstt175.novaframe.ui.theme.IosIndigo
import com.firstt175.novaframe.ui.theme.IosOrange
import com.firstt175.novaframe.ui.theme.IosPink
import com.firstt175.novaframe.ui.theme.IosPurple
import com.firstt175.novaframe.ui.theme.IosTeal
import com.firstt175.novaframe.ui.theme.LocalAppAppearance
import com.firstt175.novaframe.ui.theme.NovaPrimary
import com.firstt175.novaframe.ui.theme.isGlass
import com.firstt175.novaframe.ui.components.NovaTopBar
import com.firstt175.novaframe.ui.components.SectionHeader

@Composable
fun SettingsHubScreen(nav: NavHostController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NovaTopBar(
            title = stringResource(R.string.settings_title),
            onBack = { nav.popBackStack() },
        )

        SectionHeader(eyebrow = stringResource(R.string.settings_general))

        NovaCard {
            SettingsHubRow(
                icon = Icons.Filled.Speed,
                tint = IosOrange,
                title = stringResource(R.string.nav_framegen_pacing),
                subtitle = stringResource(R.string.nav_framegen_pacing_desc),
                onClick = { nav.navigate(Routes.FRAMEGEN) },
            )
            SettingsHubRow(
                icon = Icons.Filled.DisplaySettings,
                tint = IosBlue,
                title = stringResource(R.string.nav_overlay_display),
                subtitle = stringResource(R.string.nav_overlay_display_desc),
                onClick = { nav.navigate(Routes.OVERLAY_DISPLAY) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Movie,
                tint = IosPink,
                title = "Recording Gallery",
                subtitle = "Session Mode • microphone • watch and manage MP4 clips",
                onClick = { nav.navigate(Routes.RECORDINGS) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Tune,
                tint = IosPurple,
                title = stringResource(R.string.appearance_title),
                subtitle = stringResource(R.string.appearance_desc),
                onClick = { nav.navigate(Routes.APPEARANCE) },
                showDivider = false,
            )
        }

        SectionHeader(eyebrow = stringResource(R.string.settings_games))

        NovaCard {
            SettingsHubRow(
                icon = Icons.Filled.Security,
                tint = IosGreen,
                title = stringResource(R.string.nav_setup),
                subtitle = stringResource(R.string.nav_setup_desc),
                onClick = { nav.navigate(Routes.SETUP) },
            )
            SettingsHubRow(
                icon = Icons.Filled.BatteryFull,
                tint = IosTeal,
                title = stringResource(R.string.profile_title),
                subtitle = stringResource(R.string.nav_profile_desc),
                onClick = { nav.navigate(Routes.PROFILE) },
                showDivider = false,
            )
        }

        SectionHeader(eyebrow = stringResource(R.string.settings_system))

        NovaCard {
            SettingsHubRow(
                icon = Icons.Filled.Tune,
                tint = IosGray,
                title = "Lossless.dll",
                subtitle = stringResource(R.string.nav_dll_desc),
                onClick = { nav.navigate(Routes.DLL) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Info,
                tint = IosIndigo,
                title = stringResource(R.string.credits_title),
                subtitle = stringResource(R.string.nav_credits_desc),
                onClick = { nav.navigate(Routes.CREDITS) },
                showDivider = false,
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsHubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = NovaPrimary,
    showDivider: Boolean = true,
) {
    val glass = LocalAppAppearance.current.isGlass
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp, vertical = if (glass) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glass) {
                IconBadge(icon = icon, size = 32.dp, glassTint = tint)
                Spacer(Modifier.size(14.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (glass) 0.6f else 1f),
            )
        }
        if (showDivider) NovaInsetDivider(startInset = if (glass) 48.dp else 52.dp)
    }
}
