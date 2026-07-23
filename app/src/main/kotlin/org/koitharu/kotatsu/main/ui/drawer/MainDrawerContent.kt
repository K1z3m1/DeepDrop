package org.koitharu.kotatsu.main.ui.drawer

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.settings.SettingsSection
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold

/**
 * Content of the left-side drawer opened from the top-left button on the main screen.
 * Combines quick access to the four library tabs (Favorites/Feed/History/Explore) with the
 * full settings section list. Tapping a library tab swaps the main screen content in place;
 * tapping a settings section opens that section full-screen in [org.koitharu.kotatsu.settings.SettingsActivity].
 */
@Composable
fun MainDrawerContent(
	onNavItemClick: (NavItem) -> Unit,
	onSectionClick: (SettingsSection) -> Unit,
) {
	val ctx = LocalContext.current
	DropSauceTheme {
		Surface(
			modifier = Modifier.fillMaxSize(),
			color = MaterialTheme.colorScheme.surface,
		) {
			SettingsScaffold {
				item {
					SettingsGroup(title = stringResource(R.string.library)) {
						listOf(
							NavItem.FAVORITES,
							NavItem.FEED,
							NavItem.HISTORY,
							NavItem.EXPLORE,
						).forEach { navItem ->
							item { pos ->
								SettingsItem(
									title = stringResource(navItem.title),
									icon = navItem.icon,
									iconColors = CategoryPalette.forKey("library"),
									shape = pos.shape,
									onClick = { onNavItemClick(navItem) },
								)
							}
						}
					}
				}
				item { Spacer(Modifier.height(20.dp).fillMaxWidth()) }
				item {
					SettingsGroup(title = stringResource(R.string.settings)) {
						SettingsSection.values().forEach { section ->
							item { pos ->
								val subtitle = section.summaryRes.joinToString { ctx.getString(it) }
								SettingsItem(
									title = stringResource(section.titleRes),
									subtitle = subtitle,
									icon = section.iconRes,
									iconColors = CategoryPalette.forKey(section.paletteKey),
									tintIcon = section.tintIcon,
									shape = pos.shape,
									onClick = { onSectionClick(section) },
								)
							}
						}
					}
				}
			}
		}
	}
}
