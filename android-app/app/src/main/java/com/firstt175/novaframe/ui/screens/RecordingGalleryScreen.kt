package com.firstt175.novaframe.ui.screens

import com.firstt175.novaframe.ui.components.NovaFilterChip
import android.content.ContentUris
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import java.util.Locale

private data class RecordingItem(val uri: android.net.Uri, val name: String, val size: Long, val durationMs: Long)

@Composable
fun RecordingGalleryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val recordingPrefs = remember { ctx.getSharedPreferences("recording", android.content.Context.MODE_PRIVATE) }
    var sessionRecording by remember { mutableStateOf(recordingPrefs.getBoolean("session_recording", false)) }
    // Audio source for recordings: "off", "mic" (microphone) or "app" (what the game plays).
    fun currentAudioSource(): String =
        recordingPrefs.getString("audio_source", null)
            ?: if (recordingPrefs.getBoolean("mic", false)) "mic" else "off"
    var audioSource by remember { mutableStateOf(currentAudioSource()) }
    // Source waiting on the RECORD_AUDIO prompt (both mic and app audio need it).
    var pendingAudioSource by remember { mutableStateOf<String?>(null) }
    fun applyAudioSource(value: String) {
        audioSource = value
        recordingPrefs.edit()
            .putString("audio_source", value)
            .putBoolean("mic", value == "mic") // kept in sync for older readers
            .apply()
    }
    // Fixed video orientation for the clip: "auto", "portrait" or "landscape".
    var orientation by remember {
        mutableStateOf(recordingPrefs.getString("orientation", null) ?: "auto")
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val wanted = pendingAudioSource
        pendingAudioSource = null
        applyAudioSource(if (granted && wanted != null) wanted else "off")
    }
    var items by remember { mutableStateOf(loadRecordings(ctx)) }
    var selected by remember { mutableStateOf<RecordingItem?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recordings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { items = loadRecordings(ctx) }) { Text("Refresh") }
        }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Session Mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose how the next game session starts. Recording is saved to this gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NovaFilterChip(
                        selected = !sessionRecording,
                        onClick = {
                            sessionRecording = false
                            recordingPrefs.edit().putBoolean("session_recording", false).apply()
                        },
                        label = { Text("Normal") },
                        modifier = Modifier.weight(1f),
                    )
                    NovaFilterChip(
                        selected = sessionRecording,
                        onClick = {
                            sessionRecording = true
                            recordingPrefs.edit().putBoolean("session_recording", true).apply()
                        },
                        label = { Text("Record") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("Audio", style = MaterialTheme.typography.titleSmall)
                Text(
                    "App audio records what the game plays (Android 10+); a game that blocks " +
                        "audio capture comes out silent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("off" to "Off", "mic" to "Microphone", "app" to "App audio")
                        .forEach { (value, title) ->
                            NovaFilterChip(
                                selected = audioSource == value,
                                enabled = sessionRecording,
                                onClick = {
                                    if (value == "off" ||
                                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        applyAudioSource(value)
                                    } else {
                                        pendingAudioSource = value
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                label = { Text(title) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                }
                Text("Video orientation", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Fixed for the whole clip. If the screen rotates while recording, the picture is " +
                        "fitted with black bars instead of being stretched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("auto" to "Auto", "portrait" to "Portrait", "landscape" to "Landscape")
                        .forEach { (value, title) ->
                            NovaFilterChip(
                                selected = orientation == value,
                                enabled = sessionRecording,
                                onClick = {
                                    orientation = value
                                    recordingPrefs.edit().putString("orientation", value).apply()
                                },
                                label = { Text(title) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                }
            }
        }

        }

        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No NovaFrame recordings yet.", modifier = Modifier.padding(top = 24.dp))
            }
        } else {
            items(items, key = { it.uri.toString() }) { item ->
                RecordingTile(
                    item = item,
                    onOpen = { selected = item },
                    onDelete = {
                        ctx.contentResolver.delete(item.uri, null, null)
                        items = loadRecordings(ctx)
                    },
                )
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.name) },
            text = {
                Column {
                    Text("Duration: ${formatDuration(item.durationMs)}")
                    Text("Size: ${formatBytes(item.size)}")
                    Spacer(Modifier.height(12.dp))
                    AndroidVideoPlayer(item.uri)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = ContentUris.parseId(item.uri)
                    selected = null
                    nav.navigate("${com.firstt175.novaframe.ui.Routes.VIDEO_EDIT}/$id")
                }) { Text("Edit") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun RecordingTile(item: RecordingItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.loadThumbnail(item.uri, android.util.Size(480, 270), null).asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(
                formatDuration(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatBytes(item.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, "Delete", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AndroidVideoPlayer(uri: android.net.Uri) {
    val ctx = LocalContext.current
    val view = remember(uri) { android.widget.VideoView(ctx).apply {
        setVideoURI(uri)
        setOnPreparedListener { it.isLooping = false; start() }
    }}
    androidx.compose.ui.viewinterop.AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(220.dp))
}

private fun loadRecordings(ctx: android.content.Context): List<RecordingItem> {
    val out = ArrayList<RecordingItem>()
    val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH)
    ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%NovaFrame%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        while (c.moveToNext()) {
            out += RecordingItem(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id)), c.getString(name) ?: "Recording.mp4", c.getLong(size), c.getLong(dur))
        }
    }
    return out
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
private fun formatBytes(v: Long): String = when {
    v >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", v / 1024f / 1024f)
    v >= 1024L -> String.format(Locale.US, "%.1f KB", v / 1024f)
    else -> "$v B"
}
