@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.tabs.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prirai.android.lr.R
import com.prirai.android.lr.ui.components.LRAlertDialog
import androidx.compose.ui.platform.LocalContext
import com.prirai.android.lr.browser.profile.ProfileManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameGroupDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_island)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.island_name)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.rename_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerDialog(
    currentColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = remember {
        listOf(
            0xFFE57373.toInt(), // Red
            0xFFF06292.toInt(), // Pink
            0xFFBA68C8.toInt(), // Purple
            0xFF9575CD.toInt(), // Deep Purple
            0xFF7986CB.toInt(), // Indigo
            0xFF64B5F6.toInt(), // Blue
            0xFF4FC3F7.toInt(), // Light Blue
            0xFF4DD0E1.toInt(), // Cyan
            0xFF4DB6AC.toInt(), // Teal
            0xFF81C784.toInt(), // Green
            0xFFAED581.toInt(), // Light Green
            0xFFDCE775.toInt(), // Lime
            0xFFFFF176.toInt(), // Yellow
            0xFFFFD54F.toInt(), // Amber
            0xFFFFB74D.toInt(), // Orange
            0xFFFF8A65.toInt()  // Deep Orange
        )
    }

    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_color)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.height(220.dp)
            ) {
                items(colors) { color ->
                    val isSelected = color == currentColor
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.1f else 1f,
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        label = "colorScale"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(Color(color))
                            .clickable { onConfirm(color) }
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.selected),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePickerDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val profiles = remember {
        ProfileManager
            .getInstance(context)
            .getAllProfiles()
            .map { it.id to it.name }
    }

    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_profile)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                profiles.forEach { (profileId, profileName) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onConfirm(profileId)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = profileName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
