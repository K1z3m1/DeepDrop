@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prirai.android.lr.R
import com.prirai.android.lr.browser.profile.BrowserProfile
import com.prirai.android.lr.browser.profile.ProfileIcons
import com.prirai.android.lr.ui.components.mutedAdaptive
import com.prirai.android.lr.ui.components.pressScale

/** One cell of the profile card grid. */
private sealed interface ProfileCardItem {
    data class Profile(val profile: BrowserProfile) : ProfileCardItem
    data object Private : ProfileCardItem
    data object Add : ProfileCardItem
}

/**
 * The "Profiles" page of the tab sheet: a grid of profile cards - one per
 * profile, the Private card and an add-profile card. It swaps with the tab
 * cards page through the sheet's tab row. Tapping a card switches to that
 * profile; long-pressing a profile card opens its edit dialog.
 */
@Composable
fun ProfileCardsPage(
    profiles: List<BrowserProfile>,
    currentProfileId: String,
    isPrivateMode: Boolean,
    tabCountFor: (BrowserProfile) -> Int,
    privateTabCount: Int,
    onProfileClick: (BrowserProfile) -> Unit,
    onProfileLongClick: (BrowserProfile) -> Unit,
    onPrivateClick: () -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(profiles) {
        profiles.map<BrowserProfile, ProfileCardItem> { ProfileCardItem.Profile(it) } +
            ProfileCardItem.Private + ProfileCardItem.Add
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = {
            when (it) {
                is ProfileCardItem.Profile -> "profile_${it.profile.id}"
                ProfileCardItem.Private -> "private"
                ProfileCardItem.Add -> "add"
            }
        }) { item ->
            when (item) {
                is ProfileCardItem.Profile -> ProfileCard(
                    icon = ProfileIcons.iconFor(item.profile.emoji),
                    iconTint = Color(item.profile.color).mutedAdaptive(),
                    label = item.profile.name,
                    tabCount = tabCountFor(item.profile),
                    active = !isPrivateMode && item.profile.id == currentProfileId,
                    onClick = { onProfileClick(item.profile) },
                    onLongClick = { onProfileLongClick(item.profile) },
                )

                ProfileCardItem.Private -> ProfileCard(
                    icon = Icons.Filled.Shield,
                    iconTint = Color(
                        com.prirai.android.lr.theme.ColorConstants.PrivateMode.PURPLE
                    ).mutedAdaptive(),
                    label = stringResource(R.string.private_mode_short),
                    tabCount = privateTabCount,
                    active = isPrivateMode,
                    onClick = onPrivateClick,
                    onLongClick = null,
                )

                ProfileCardItem.Add -> AddProfileCard(onClick = onAddClick)
            }
        }
    }
}

@Composable
private fun ProfileCard(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    tabCount: Int,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "profileCardContainer",
    )
    val onContainer = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .pressScale(interactionSource),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    interactionSource = interactionSource,
                    indication = null,
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column {
                Text(
                    text = label,
                    style = if (active) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.tab_count, tabCount, tabCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AddProfileCard(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .pressScale(interactionSource),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = onClick,
                    interactionSource = interactionSource,
                    indication = null,
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.create_profile),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
