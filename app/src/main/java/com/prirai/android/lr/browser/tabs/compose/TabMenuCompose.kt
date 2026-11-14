@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prirai.android.lr.R
import com.prirai.android.lr.ui.components.rememberLRSheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabContextMenu(
    tab: TabSessionState,
    isInGroup: Boolean,
    onDismiss: () -> Unit,
    viewModel: TabViewModel,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberLRSheetState(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            TabMenuHeader(tab)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            TabMenuItem(
                icon = { Icon(painterResource(R.drawable.control_point_duplicate_24px), null) },
                text = stringResource(R.string.duplicate_tab),
                onClick = {
                    scope.launch {
                        viewModel.duplicateTab(tab.id)
                        onDismiss()
                    }
                }
            )

            if (isInGroup) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                TabMenuItem(
                    icon = { Icon(painterResource(R.drawable.ungroup_24px), null) },
                    text = stringResource(R.string.remove_from_island),
                    onClick = {
                        scope.launch {
                            viewModel.removeTabFromGroup(tab.id)
                            onDismiss()
                        }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            TabMenuItem(
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_close_small),
                        null,
                        modifier = Modifier.size(32.dp)
                    )
                },
                text = stringResource(R.string.menu_close_tab),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = {
                    scope.launch {
                        viewModel.closeTab(tab.id)
                        onDismiss()
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupContextMenu(
    groupId: String,
    groupName: String,
    onDismiss: () -> Unit,
    viewModel: TabViewModel,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showProfilePicker by remember { mutableStateOf(false) }

    val groups by viewModel.groups.collectAsState()
    val currentGroup = groups.find { it.id == groupId }
    val groupAccentColor = currentGroup?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberLRSheetState(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            var editedName by remember { mutableStateOf(groupName) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        bottomStart = 12.dp,
                        topEnd = 0.dp,
                        bottomEnd = 0.dp
                    )
                )

                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(
                        topStart = 0.dp,
                        bottomStart = 0.dp,
                        topEnd = 12.dp,
                        bottomEnd = 12.dp
                    ),
                    color = if (editedName.isNotBlank() && editedName != groupName) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    onClick = {
                        if (editedName.isNotBlank() && editedName != groupName) {
                            scope.launch {
                                viewModel.renameGroup(groupId, editedName)
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.confirm_rename),
                            tint = if (editedName.isNotBlank() && editedName != groupName) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            TabMenuItem(
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_palette),
                        null,
                        tint = groupAccentColor
                    )
                },
                text = stringResource(R.string.change_color),
                onClick = {
                    showColorPicker = true
                }
            )

            TabMenuItem(
                icon = { Icon(painterResource(R.drawable.ic_expand_more), null) },
                text = stringResource(R.string.collapse_expand),
                onClick = {
                    scope.launch {
                        viewModel.toggleGroupExpanded(groupId)
                        onDismiss()
                    }
                }
            )

            TabMenuItem(
                icon = { Icon(painterResource(R.drawable.ic_pin_outline), null) },
                text = stringResource(R.string.pin_island),
                onClick = {
                    scope.launch {
                        viewModel.pinGroup(groupId)
                        onDismiss()
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            TabMenuItem(
                icon = { Icon(painterResource(R.drawable.ungroup_24px), null) },
                text = stringResource(R.string.ungroup_all_tabs),
                onClick = {
                    scope.launch {
                        viewModel.ungroupAllTabs(groupId)
                        onDismiss()
                    }
                }
            )

            TabMenuItem(
                icon = { Icon(painterResource(R.drawable.ic_close_small), null) },
                text = stringResource(R.string.close_all_tabs),
                contentColor = MaterialTheme.colorScheme.error,
                onClick = {
                    scope.launch {
                        viewModel.closeAllTabsInGroup(groupId)
                        onDismiss()
                    }
                }
            )

            TabMenuItem(
                icon = { Icon(painterResource(R.drawable.ic_profile), null) },
                text = stringResource(R.string.move_to_profile),
                onClick = {
                    showProfilePicker = true
                }
            )
        }
    }

    if (showRenameDialog && currentGroup != null) {
        RenameGroupDialog(
            currentName = currentGroup.name,
            onConfirm = { newName ->
                scope.launch {
                    viewModel.renameGroup(groupId, newName)
                    showRenameDialog = false
                    onDismiss()
                }
            },
            onDismiss = {
                showRenameDialog = false
                onDismiss()
            }
        )
    }

    if (showColorPicker && currentGroup != null) {
        ColorPickerDialog(
            currentColor = currentGroup.color,
            onConfirm = { newColor ->
                scope.launch {
                    viewModel.changeGroupColor(groupId, newColor)
                    showColorPicker = false
                    onDismiss()
                }
            },
            onDismiss = {
                showColorPicker = false
                onDismiss()
            }
        )
    }

    if (showProfilePicker) {
        ProfilePickerDialog(
            onConfirm = { profileId ->
                scope.launch {
                    viewModel.moveGroupToProfile(groupId, profileId)
                    showProfilePicker = false
                    onDismiss()
                }
            },
            onDismiss = {
                showProfilePicker = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun TabMenuHeader(tab: TabSessionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FaviconImage(
            tab = tab,
            size = 24.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab.content.title.ifEmpty { stringResource(R.string.tab_history_new_tab) },
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1
            )
            Text(
                text = tab.content.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TabMenuItem(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    contentColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = {
            Text(
                text = text,
                color = contentColor
            )
        },
        leadingContent = {
            CompositionLocalProvider(
                LocalContentColor provides if (contentColor != Color.Unspecified) contentColor else LocalContentColor.current
            ) {
                icon()
            }
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}
