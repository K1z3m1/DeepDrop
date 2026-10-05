package com.firstt175.novaframe.ui.screens

import com.firstt175.novaframe.ui.components.NovaFilterChip
import android.content.ContentUris
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.firstt175.novaframe.ui.edit.AiPanel
import com.firstt175.novaframe.ui.edit.ClipPanel
import com.firstt175.novaframe.ui.edit.EditSession
import com.firstt175.novaframe.ui.edit.EditState
import com.firstt175.novaframe.ui.edit.EditTimeline
import com.firstt175.novaframe.ui.edit.EditTool
import com.firstt175.novaframe.ui.edit.ExportHandle
import com.firstt175.novaframe.ui.edit.ExportOptions
import com.firstt175.novaframe.ui.edit.ExportQuality
import com.firstt175.novaframe.ui.edit.Filmstrip
import com.firstt175.novaframe.ui.edit.MIN_SEGMENT_MS
import com.firstt175.novaframe.ui.edit.Segment
import com.firstt175.novaframe.ui.edit.SourceInfo
import com.firstt175.novaframe.ui.edit.ToolTabs
import com.firstt175.novaframe.ui.edit.VisualSettings
import com.firstt175.novaframe.ui.edit.OutputPlan
import com.firstt175.novaframe.ui.edit.buildVideoEffects
import com.firstt175.novaframe.ui.edit.fillFilmstrip
import com.firstt175.novaframe.ui.edit.loadSourceInfo
import com.firstt175.novaframe.ui.edit.planOutput
import com.firstt175.novaframe.ui.edit.resolveAiModel
import com.firstt175.novaframe.ui.edit.runAiFrameGen
import com.firstt175.novaframe.ui.edit.saveVideoToGallery
import com.firstt175.novaframe.ui.edit.startExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * CapCut-style clip editor for recordings. Entry point kept as-is for the nav
 * graph; the work is split across com.firstt175.novaframe.ui.edit:
 *
 *  - EditModel     segments / visual settings / undo-redo session
 *  - EditEffects   Media3 effect chain shared by preview and export
 *  - EditTimeline  filmstrip timeline with scrub + trim handles
 *  - EditPanels    Clip, Filter, Adjust, Frame and Text tool panels
 *  - EditExporter  multi-segment export (speed, volume, resolution, quality, HEVC)
 *  - AiFrameGen    optional 2nd export stage: AI-interpolated frames (RIFE / IFRNet)
 */
@Composable
fun VideoEditScreen(nav: NavHostController, recordingId: Long) {
    val ctx = LocalContext.current
    val uri = remember(recordingId) {
        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, recordingId)
    }
    var src by remember(uri) { mutableStateOf<SourceInfo?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val info = loadSourceInfo(ctx, uri)
        if (info == null || info.durationMs <= 0L) failed = true else src = info
    }

    val info = src
    when {
        failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't open this recording.")
                TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
        info == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> VideoEditor(nav, uri, info)
    }
}

private class Holder<T>(var value: T)

private const val TAG = "NovaFrameEdit"

@OptIn(UnstableApi::class)
@Composable
private fun VideoEditor(nav: NavHostController, uri: Uri, src: SourceInfo) {
    val ctx = LocalContext.current
    val session = remember(uri) {
        EditSession(EditState(listOf(Segment(id = 1L, startMs = 0L, endMs = src.durationMs))), src.durationMs)
    }
    val st = session.state

    // ---- preview player -------------------------------------------------------------------
    // One player over the untouched source. The edit is *emulated* on top of it (jump between
    // segments, per-segment speed/volume), so trimming and dragging never rebuild the player.
    // Playhead state lives above the player so a rebuilt player can resume where it was.
    var curSeg by remember { mutableIntStateOf(0) }            // segment the playhead is in
    var playheadSrcMs by remember { mutableLongStateOf(0L) }   // player position, source ms

    // If the Media3 effects pipeline fails (or never draws a frame) on this GPU/driver, the
    // preview is rebuilt as a plain ExoPlayer. Export uses Transformer and is unaffected.
    var useEffects by remember(uri) { mutableStateOf(true) }
    var previewError by remember(uri) { mutableStateOf<String?>(null) }

    val player = remember(uri, useEffects) {
        ExoPlayer.Builder(ctx).build().apply {
            if (useEffects) {
                // Must be called at least once before prepare() to set up the effects pipeline.
                setVideoEffects(buildVideoEffects(VisualSettings(), src))
            }
            setMediaItem(MediaItem.fromUri(uri))
            seekTo(playheadSrcMs)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var firstFrameRendered by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "preview error ${error.errorCodeName} effects=$useEffects", error)
                if (useEffects) useEffects = false
                else previewError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
            }
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                Log.i(TAG, "preview first frame rendered effects=$useEffects")
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.i(TAG, "preview video size ${videoSize.width}x${videoSize.height} rot=${videoSize.unappliedRotationDegrees}")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Watchdog: no frame drawn after a few seconds with effects on -> assume the pipeline is broken.
    LaunchedEffect(player) {
        delay(3_000L)
        if (!firstFrameRendered && useEffects) {
            Log.w(TAG, "no first frame after 3s with video effects; falling back to plain preview")
            useEffects = false
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(EditTool.CLIP) }

    fun seekTo(index: Int, srcMs: Long) {
        curSeg = index
        playheadSrcMs = srcMs
        player.seekTo(srcMs)
    }

    // Playback engine: when the playhead reaches the end of a segment, hop to the next one.
    LaunchedEffect(player) {
        while (true) {
            val segs = session.state.segments
            if (curSeg > segs.lastIndex) curSeg = segs.lastIndex
            val seg = segs[curSeg]
            val pos = player.currentPosition
            playheadSrcMs = pos
            val playing = player.isPlaying
            if (playing != isPlaying) isPlaying = playing
            val ended = player.playbackState == Player.STATE_ENDED && player.playWhenReady
            if (playing || ended) {
                if (ended || pos >= seg.endMs - (8 * seg.speed).toLong()) {
                    if (curSeg < segs.lastIndex) {
                        curSeg += 1
                        player.seekTo(segs[curSeg].startMs)
                    } else {
                        player.pause()
                        player.seekTo(seg.endMs)
                    }
                } else if (pos < seg.startMs - 100L) {
                    player.seekTo(seg.startMs)
                }
            }
            delay(if (playing) 16L else 100L)
        }
    }

    // Per-segment speed / volume follow the segment under the playhead.
    val curSegment = st.segments.getOrNull(curSeg)
    LaunchedEffect(player, curSegment?.speed, curSegment?.volume) {
        player.setPlaybackSpeed(curSegment?.speed ?: 1f)
        player.volume = (curSegment?.volume ?: 1f).coerceAtMost(1f)
    }

    // Preview colour / crop / rotate / text, debounced so sliders and typing stay smooth.
    val applied = remember(player) { Holder(VisualSettings()) }
    LaunchedEffect(player, st.visual, useEffects) {
        if (useEffects && st.visual != applied.value) {
            delay(120)
            player.setVideoEffects(buildVideoEffects(st.visual, src))
            applied.value = st.visual
        }
    }

    // Timeline thumbnails.
    val filmstrip = remember(uri) { Filmstrip((src.durationMs / 1500L).toInt().coerceIn(8, 40)) }
    LaunchedEffect(filmstrip) { fillFilmstrip(ctx, uri, src, filmstrip) }

    // ---- export state ---------------------------------------------------------------------
    var showExportOptions by remember { mutableStateOf(false) }
    var exportOpts by remember { mutableStateOf(ExportOptions()) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportHandle by remember { mutableStateOf<ExportHandle?>(null) }
    var exportStatus by remember { mutableStateOf("Exporting") }
    var exportDetail by remember { mutableStateOf("") }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var exportDoneName by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { exportHandle?.cancel() } }
    // A long AI render must not be interrupted by the screen turning off.
    val view = LocalView.current
    DisposableEffect(exporting) {
        view.keepScreenOn = exporting
        onDispose { view.keepScreenOn = false }
    }

    // ---- actions --------------------------------------------------------------------------
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // Always intercept Back: an export in progress must be cancelled explicitly, not by accident.
    BackHandler {
        when {
            exporting -> toast("Cancel the export first.")
            session.canUndo -> showDiscard = true
            else -> nav.popBackStack()
        }
    }

    fun playheadOut(): Long {
        val s = session.state
        val i = curSeg.coerceIn(0, s.segments.lastIndex)
        val seg = s.segments[i]
        val within = (playheadSrcMs - seg.startMs).coerceIn(0L, seg.sourceMs)
        return s.outputStartOf(i) + (within / seg.speed).toLong()
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        val segs = session.state.segments
        val idx = curSeg.coerceIn(0, segs.lastIndex)
        val seg = segs[idx]
        val pos = player.currentPosition
        when {
            pos >= seg.endMs - 30L && idx == segs.lastIndex -> seekTo(0, segs[0].startMs)
            pos >= seg.endMs - 30L -> seekTo(idx + 1, segs[idx + 1].startMs)
            pos < seg.startMs -> seekTo(idx, seg.startMs)
        }
        player.play()
    }

    fun stepFrame(dir: Int) {
        player.pause()
        val seg = session.state.segments[curSeg.coerceIn(0, session.state.segments.lastIndex)]
        val step = (1000f / src.frameRate).toLong().coerceAtLeast(1L)
        seekTo(curSeg, (player.currentPosition + dir * step).coerceIn(seg.startMs, seg.endMs))
    }

    fun afterHistoryChange() {
        player.pause()
        val s = session.state
        curSeg = curSeg.coerceIn(0, s.segments.lastIndex)
        val seg = s.segments[curSeg]
        val p = player.currentPosition
        if (p < seg.startMs || p > seg.endMs) seekTo(curSeg, seg.startMs)
    }

    fun finishExport() {
        exporting = false
        exportHandle = null
        aiJob = null
    }

    /** Stage 2 (only with AI frame gen): interpolate the rendered edit, then save it. */
    fun startAiStage(rendered: File, plan: OutputPlan, opts: ExportOptions) {
        exportHandle = null
        exportStatus = "Loading AI model…"
        exportDetail = ""
        aiJob = scope.launch {
            val aiOut = File(ctx.cacheDir, "edit_ai_${System.currentTimeMillis()}.mp4")
            var saved = false
            try {
                val model = withContext(Dispatchers.IO) { resolveAiModel(ctx) }
                exportStatus = "AI frame gen · ${model.label}"
                val t0 = SystemClock.elapsedRealtime()
                runAiFrameGen(rendered, aiOut, opts.aiMultiplier, plan.finalBitrate, opts.hevc, model) { done, total ->
                    exportProgress = RENDER_SHARE + (1f - RENDER_SHARE) * (done.toFloat() / total.coerceAtLeast(1))
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    val etaSec = if (done > 0) elapsed * (total - done) / done / 1000L else -1L
                    exportDetail = "$done / $total frame pairs" +
                        (if (etaSec >= 0) " · about ${formatEta(etaSec)} left" else "")
                }
                exportStatus = "Saving…"
                exportDetail = ""
                val name = withContext(Dispatchers.IO) {
                    saveVideoToGallery(ctx, aiOut, "_AI${opts.aiMultiplier}x")
                }
                saved = true
                finishExport()
                exportDoneName = name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finishExport()
                exportError = e.message ?: "AI frame gen failed"
            } finally {
                rendered.delete()
                if (!saved) aiOut.delete()
            }
        }
    }

    fun startRender() {
        player.pause()
        val opts = exportOpts
        val ai = opts.aiMultiplier > 0
        val plan = planOutput(session.state, src, opts)
        exporting = true
        exportProgress = 0f
        exportError = null
        exportStatus = if (ai) "Rendering edit…" else "Exporting"
        exportDetail = ""
        exportHandle = startExport(
            ctx = ctx,
            sourceUri = uri,
            src = src,
            state = session.state,
            opts = opts,
            onProgress = { exportProgress = if (ai) it * RENDER_SHARE else it },
            onDone = { name ->
                finishExport()
                exportDoneName = name
            },
            onError = { msg ->
                finishExport()
                exportError = msg
            },
            renderOnly = ai,
            onRendered = { file -> startAiStage(file, plan, opts) },
        )
    }

    // ---- layout ---------------------------------------------------------------------------
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (session.canUndo) showDiscard = true else nav.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Edit clip", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(enabled = session.canUndo, onClick = { if (session.undo()) afterHistoryChange() }) {
                Icon(Icons.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(enabled = session.canRedo, onClick = { if (session.redo()) afterHistoryChange() }) {
                Icon(Icons.Filled.Redo, contentDescription = "Redo")
            }
            Button(
                enabled = !exporting,
                onClick = { showExportOptions = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            ) { Text("Export") }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false } },
                // Re-bind when the player is rebuilt (effects fallback).
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            val note = previewError?.let { "Preview failed: $it" }
                ?: if (!useEffects) "Preview without live effects (export still applies them)" else null
            if (note != null) {
                Text(
                    note,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                player.pause()
                seekTo(0, session.state.segments[0].startMs)
            }) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Go to start") }
            IconButton(onClick = { togglePlay() }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            TimeLabel(playheadOut = { playheadOut() }, totalMs = st.totalOutputMs, modifier = Modifier.weight(1f))
            IconButton(onClick = { stepFrame(-1) }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous frame") }
            IconButton(onClick = { stepFrame(1) }) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next frame") }
        }

        EditTimeline(
            state = st,
            filmstrip = filmstrip,
            sourceDurationMs = src.durationMs,
            thumbAspect = src.width.toFloat() / src.height,
            playheadOutMs = { playheadOut() },
            onScrub = { outMs ->
                val (i, srcMs) = session.state.locate(outMs)
                seekTo(i, srcMs)
            },
            onTap = { index, outMs ->
                session.select(index)
                val (i, srcMs) = session.state.locate(outMs)
                seekTo(i, srcMs)
            },
            onTrimBegin = {
                player.pause()
                session.beginGesture()
            },
            onTrim = { index, isStart, delta ->
                val edge = session.trimEdge(index, isStart, delta)
                val seg = session.state.segments[index]
                // Show the frame at the edge being dragged (a hair inside for the end handle).
                seekTo(index, if (isStart) edge else (edge - 40L).coerceAtLeast(seg.startMs))
            },
            onTrimEnd = { session.endGesture() },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        val activeTools = buildSet {
            if (exportOpts.aiMultiplier > 0) add(EditTool.AI)
        }
        ToolTabs(selected = tool, onSelect = { session.endGesture(); tool = it }, highlighted = activeTools)

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 130.dp, max = 210.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = 6.dp),
        ) {
            when (tool) {
                EditTool.CLIP -> ClipPanel(
                    session = session,
                    hasAudio = src.hasAudio,
                    onSplit = {
                        val idx = curSeg
                        if (session.split(idx, player.currentPosition)) {
                            curSeg = idx + 1
                        } else {
                            toast("Move the playhead inside a clip (at least ${MIN_SEGMENT_MS / 1000f}s from its edges).")
                        }
                    },
                    onDelete = {
                        if (session.delete(session.state.selected)) {
                            val s = session.state
                            seekTo(s.selected, s.segments[s.selected].startMs)
                        } else {
                            toast("A video needs at least one clip.")
                        }
                    },
                    onDuplicate = {
                        if (session.duplicate(session.state.selected)) {
                            val s = session.state
                            seekTo(s.selected, s.segments[s.selected].startMs)
                        }
                    },
                    onMove = { dir ->
                        if (session.move(session.state.selected, dir)) curSeg = session.state.selected
                    },
                )
                EditTool.AI -> AiPanel(exportOpts, src) { exportOpts = it }
            }
        }
    }

    // ---- dialogs --------------------------------------------------------------------------
    if (showExportOptions) {
        val plan = planOutput(st, src, exportOpts)
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text("Export") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Resolution", style = MaterialTheme.typography.labelMedium)
                    OptionRow(
                        options = listOf(0 to "Original", 1080 to "1080p", 720 to "720p", 480 to "480p"),
                        selected = exportOpts.maxHeight,
                        onSelect = { exportOpts = exportOpts.copy(maxHeight = it) },
                    )
                    Text("Quality", style = MaterialTheme.typography.labelMedium)
                    OptionRow(
                        options = ExportQuality.entries.map { it to it.label },
                        selected = exportOpts.quality,
                        onSelect = { exportOpts = exportOpts.copy(quality = it) },
                    )
                    Text("Format", style = MaterialTheme.typography.labelMedium)
                    OptionRow(
                        options = listOf(false to "H.264 (compatible)", true to "HEVC (smaller)"),
                        selected = exportOpts.hevc,
                        onSelect = { exportOpts = exportOpts.copy(hevc = it) },
                    )
                    Text("AI frame gen", style = MaterialTheme.typography.labelMedium)
                    OptionRow(
                        options = listOf(0 to "Off", 2 to "×2", 3 to "×3", 4 to "×4"),
                        selected = exportOpts.aiMultiplier,
                        onSelect = { exportOpts = exportOpts.copy(aiMultiplier = it) },
                    )
                    Text(
                        "${plan.width}×${plan.height}" +
                            (if (exportOpts.aiMultiplier > 0) " @ ~${plan.outFps} fps" else "") +
                            " · ${formatMs(st.totalOutputMs)} · ≈ " +
                            Formatter.formatShortFileSize(ctx, plan.approxBytes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (exportOpts.aiMultiplier > 0) {
                        Text(
                            "AI frame gen re-renders every frame with the AI model, so it can take " +
                                "several minutes. Keep the app open; 720p is much faster.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportOptions = false; startRender() }) {
                    Text(if (exportOpts.aiMultiplier > 0) "Export + AI" else "Export")
                }
            },
            dismissButton = { TextButton(onClick = { showExportOptions = false }) { Text("Cancel") } },
        )
    }

    if (exporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(exportStatus) },
            text = {
                Column {
                    LinearProgressIndicator(progress = { exportProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(exportProgress * 100).toInt()}%")
                    if (exportDetail.isNotEmpty()) Text(exportDetail, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportHandle?.cancel()
                    aiJob?.cancel()
                    finishExport()
                }) { Text("Cancel") }
            },
        )
    }

    exportDoneName?.let { name ->
        AlertDialog(
            onDismissRequest = { exportDoneName = null; nav.popBackStack() },
            title = { Text("Saved") },
            text = { Text("Saved as $name in Movies/NovaFrame.") },
            confirmButton = {
                TextButton(onClick = { exportDoneName = null; nav.popBackStack() }) { Text("Done") }
            },
        )
    }

    exportError?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text("Export failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("OK") } },
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard edits?") },
            text = { Text("Your changes haven't been exported.") },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; nav.popBackStack() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun <T> OptionRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            NovaFilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Reads the playhead inside its own scope so only this label recomposes while playing. */
@Composable
private fun TimeLabel(playheadOut: () -> Long, totalMs: Long, modifier: Modifier = Modifier) {
    Text(
        "${formatMs(playheadOut())} / ${formatMs(totalMs)}",
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

private fun formatMs(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val s = clamped / 1000
    return String.format(Locale.US, "%02d:%02d.%d", s / 60, s % 60, (clamped % 1000) / 100)
}

/** Share of the progress bar taken by rendering the edit when AI frame gen follows it. */
private const val RENDER_SHARE = 0.1f

private fun formatEta(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0L)
    return if (s >= 3600) String.format(Locale.US, "%dh %02dm", s / 3600, (s % 3600) / 60)
    else if (s >= 60) String.format(Locale.US, "%dm %02ds", s / 60, s % 60)
    else "${s}s"
}
