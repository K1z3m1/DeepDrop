@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.prirai.android.lr.browser.bookmark.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.prirai.android.lr.R
import com.prirai.android.lr.browser.bookmark.items.BookmarkFolderItem
import com.prirai.android.lr.browser.bookmark.items.BookmarkItem
import com.prirai.android.lr.browser.bookmark.items.BookmarkSiteItem
import com.prirai.android.lr.browser.bookmark.repository.BookmarkManager
import com.prirai.android.lr.ext.components
import com.prirai.android.lr.preferences.UserPreferences
import com.prirai.android.lr.ui.components.LRAlertDialog
import com.prirai.android.lr.utils.BookmarkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun BookmarkSiteEditDialog(
    manager: BookmarkManager,
    item: BookmarkSiteItem?,
    initialTitle: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val editing = item
    val isCreate = editing == null
    var title by remember { mutableStateOf(initialTitle) }
    var url by remember { mutableStateOf(initialUrl) }
    var showTitleError by remember { mutableStateOf(false) }
    var showUrlError by remember { mutableStateOf(false) }
    var targetFolder by remember { mutableStateOf(defaultBookmarkFolder(context, manager)) }
    var addToTop by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    fun save() {
        showTitleError = title.isBlank()
        showUrlError = url.isBlank()
        if (showTitleError || showUrlError) return

        val trimmedUrl = url.trim()
        if (editing == null) {
            val newItem = BookmarkSiteItem(title, trimmedUrl, BookmarkUtils.getNewId())
            if (addToTop) {
                manager.addFirst(targetFolder, newItem)
            } else {
                manager.add(targetFolder, newItem)
            }
        } else {
            editing.title = title
            editing.url = trimmedUrl
        }

        if (manager.save()) {
            Toast.makeText(context, R.string.successful, Toast.LENGTH_SHORT).show()
            syncBookmarkToPlaces(context, trimmedUrl, title)
            onSaved()
        } else {
            Toast.makeText(context, R.string.failed, Toast.LENGTH_LONG).show()
        }
    }

    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (isCreate) R.string.add_bookmark else R.string.edit_bookmark))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        showTitleError = false
                    },
                    label = { Text(stringResource(R.string.title)) },
                    isError = showTitleError,
                    supportingText = if (showTitleError) {
                        { Text(stringResource(R.string.title_empty_mes)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        showUrlError = false
                    },
                    label = { Text(stringResource(R.string.url)) },
                    isError = showUrlError,
                    supportingText = if (showUrlError) {
                        { Text(stringResource(R.string.url_empty_mes)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isCreate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showFolderPicker = true }) {
                            Text(
                                text = folderDisplayName(context, manager, targetFolder),
                                style = MaterialTheme.typography.bodyMediumEmphasized,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = addToTop,
                                onValueChange = { addToTop = it },
                                role = Role.Checkbox,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.add_to_top),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(checked = addToTop, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { save() },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    if (showFolderPicker) {
        FolderPickerDialog(
            manager = manager,
            startFolder = targetFolder,
            excludeItems = emptyList(),
            onDismiss = { showFolderPicker = false },
            onSelect = { folder ->
                targetFolder = folder
                showFolderPicker = false
            },
        )
    }
}

@Composable
internal fun FolderEditDialog(
    manager: BookmarkManager,
    parent: BookmarkFolderItem?,
    item: BookmarkFolderItem?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val editing = item
    val isCreate = editing == null
    var name by remember { mutableStateOf(editing?.title.orEmpty()) }
    var showNameError by remember { mutableStateOf(false) }
    var addToTop by remember { mutableStateOf(false) }

    fun save() {
        showNameError = name.isBlank()
        if (showNameError) return

        val targetParent = parent ?: manager.root
        if (editing == null) {
            val newFolder = BookmarkFolderItem(name, targetParent, BookmarkUtils.getNewId())
            if (addToTop) {
                targetParent.addAtStart(newFolder)
            } else {
                targetParent.add(newFolder)
            }
        } else {
            editing.title = name
        }

        if (manager.save()) {
            Toast.makeText(context, R.string.successful, Toast.LENGTH_SHORT).show()
            onSaved()
        } else {
            Toast.makeText(context, R.string.failed, Toast.LENGTH_LONG).show()
        }
    }

    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (isCreate) R.string.add_folder else R.string.edit_folder))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showNameError = false
                    },
                    label = { Text(stringResource(R.string.new_folder_name)) },
                    isError = showNameError,
                    supportingText = if (showNameError) {
                        { Text(stringResource(R.string.title_empty_mes)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isCreate) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = addToTop,
                                onValueChange = { addToTop = it },
                                role = Role.Checkbox,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.add_to_top),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(checked = addToTop, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { save() },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun FolderPickerDialog(
    manager: BookmarkManager,
    startFolder: BookmarkFolderItem,
    excludeItems: List<BookmarkItem>,
    onDismiss: () -> Unit,
    onSelect: (BookmarkFolderItem) -> Unit,
) {
    var currentFolder by remember { mutableStateOf(startFolder) }
    var revision by remember { mutableIntStateOf(0) }
    var showCreateFolder by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val childFolders = remember(currentFolder, revision) {
        currentFolder.itemList
            .filterIsInstance<BookmarkFolderItem>()
            .filterNot { folder ->
                excludeItems.any { excluded ->
                    folder === excluded ||
                        (excluded is BookmarkFolderItem && isDescendantOf(folder, excluded))
                }
            }
    }

    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_folder)) },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = folderDisplayName(context, manager, currentFolder),
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (currentFolder.parent != null) {
                        IconButton(onClick = { currentFolder.parent?.let { currentFolder = it } }) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                    IconButton(onClick = { showCreateFolder = true }) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = stringResource(R.string.create_folder),
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    itemsIndexed(childFolders, key = { index, folder -> "${folder.id}-$index" }) { _, folder ->
                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable { currentFolder = folder },
                        ) {
                            Text(
                                text = folder.title
                                    ?: stringResource(R.string.bookmark_unnamed_folder),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(currentFolder) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.select_folder_confirm, folderDisplayName(context, manager, currentFolder)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    if (showCreateFolder) {
        FolderEditDialog(
            manager = manager,
            parent = currentFolder,
            item = null,
            onDismiss = { showCreateFolder = false },
            onSaved = {
                showCreateFolder = false
                revision++
            },
        )
    }
}

@Composable
internal fun ConfirmBookmarksDeleteDialog(
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun ConfirmFlattenFolderDialog(
    folderTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flatten_folder)) },
        text = {
            Text(stringResource(R.string.flatten_folder_confirm_formatted, folderTitle))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.flatten_folder))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun SortBookmarksDialog(
    onDismiss: () -> Unit,
    onSortPicked: () -> Unit,
) {
    LRAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SortOption(label = stringResource(R.string.sort_by_name), onClick = onSortPicked)
                SortOption(label = stringResource(R.string.sort_by_url), onClick = onSortPicked)
                SortOption(label = stringResource(R.string.sort_by_date_added), onClick = onSortPicked)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SortOption(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLargeEmphasized)
    }
}

internal fun defaultBookmarkFolder(context: Context, manager: BookmarkManager): BookmarkFolderItem {
    val prefs = UserPreferences(context)
    if (prefs.bookmarkFolder) {
        val item = manager[prefs.bookmarkFolderId]
        if (item is BookmarkFolderItem) {
            return item
        }
    }
    return manager.root
}

internal fun syncBookmarkToPlaces(context: Context, url: String, title: String?) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            context.components.bookmarksStorage.addItem(
                "toolbar_____",
                url,
                title ?: url,
                UInt.MAX_VALUE,
            )
        } catch (_: Exception) {
        }
    }
}

internal fun isDescendantOf(folder: BookmarkFolderItem, ancestor: BookmarkFolderItem): Boolean {
    var current = folder.parent
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent
    }
    return false
}

internal fun folderDisplayName(context: Context, manager: BookmarkManager, folder: BookmarkFolderItem): String =
    if (folder === manager.root) {
        context.getString(R.string.bookmark_root_folder)
    } else {
        folder.title ?: context.getString(R.string.bookmark_unnamed_folder)
    }

internal fun shareBookmarkUrl(context: Context, url: String) {
    val shareIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, url)
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share)))
}
