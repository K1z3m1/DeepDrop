package com.firstt175.novaframe.ui.screens

import com.firstt175.novaframe.ui.Routes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.novaframe.R
import com.firstt175.novaframe.prefs.FirstRunPrefs
import com.firstt175.novaframe.ui.components.IconBadge
import com.firstt175.novaframe.ui.components.NovaCard
import com.firstt175.novaframe.ui.components.NovaPrimaryButton
import com.firstt175.novaframe.ui.theme.NovaPrimary
import androidx.compose.ui.platform.LocalContext

/**
 * One-time disclosure shown before anything else the very first time the app
 * runs — before [Routes.HOME] and before any system permission dialog
 * fires. Plain-language summary of what NovaFrame asks for
 * and why, so the user knows what they're agreeing to before the first
 * permission prompt appears mid-flow.
 *
 * This is intentionally separate from [SetupScreen]: this screen only
 * informs and requires acknowledgement (no permission dialogs fire from
 * here), while SetupScreen is the reusable action screen that actually
 * requests each permission and can be revisited anytime from Settings.
 *
 * Shown at most once — see [FirstRunPrefs]. All copy comes from string
 * resources, so it renders correctly for either app language.
 */
@Composable
fun AppDisclosureScreen(nav: NavHostController) {
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.disclosure_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.disclosure_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        DisclosureItem(
            icon = Icons.Filled.Layers,
            title = stringResource(R.string.disclosure_item_overlay_title),
            description = stringResource(R.string.disclosure_item_overlay_desc),
        )
        Spacer(Modifier.height(12.dp))
        DisclosureItem(
            icon = Icons.Filled.Accessibility,
            title = stringResource(R.string.disclosure_item_a11y_title),
            description = stringResource(R.string.disclosure_item_a11y_desc),
        )
        Spacer(Modifier.height(12.dp))
        DisclosureItem(
            icon = Icons.Filled.BatteryFull,
            title = stringResource(R.string.disclosure_item_battery_title),
            description = stringResource(R.string.disclosure_item_battery_desc),
        )
        Spacer(Modifier.height(12.dp))
        DisclosureItem(
            icon = Icons.Filled.Security,
            title = stringResource(R.string.disclosure_item_display_title),
            description = stringResource(R.string.disclosure_item_display_desc),
        )
        Spacer(Modifier.height(12.dp))
        DisclosureItem(
            icon = Icons.Filled.Shield,
            title = stringResource(R.string.disclosure_item_privacy_title),
            description = stringResource(R.string.disclosure_item_privacy_desc),
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.disclosure_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        NovaPrimaryButton(
            text = stringResource(R.string.disclosure_cta),
            onClick = {
                FirstRunPrefs.setDisclosureAcknowledged(ctx)
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.DISCLOSURE) { inclusive = true }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DisclosureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
) {
    NovaCard {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(icon = icon, tint = NovaPrimary, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
