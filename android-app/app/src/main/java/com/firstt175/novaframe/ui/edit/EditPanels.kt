package com.firstt175.novaframe.ui.edit

import com.firstt175.novaframe.ui.components.NovaFilterChip
import com.firstt175.novaframe.ui.components.NovaSlider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class EditTool(val label: String, val icon: ImageVector) {
    CLIP("Clip", Icons.Filled.ContentCut),
    AI("AI FG", Icons.Filled.Psychology),
}

@Composable
fun ToolTabs(selected: EditTool, onSelect: (EditTool) -> Unit, highlighted: Set<EditTool> = emptySet()) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EditTool.entries.forEach { tool ->
            val active = tool == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(tool) }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Tools that currently change the result get the accent colour.
                val tint = if (tool in highlighted) MaterialTheme.colorScheme.primary else LocalContentColor.current
                Icon(tool.icon, contentDescription = tool.label, tint = tint)
                Text(tool.label, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        NovaSlider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(valueText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Clip: split / delete / duplicate / reorder, plus speed and volume of the selected segment.
// ---------------------------------------------------------------------------------------------

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

@Composable
fun ClipPanel(
    session: EditSession,
    hasAudio: Boolean,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val st = session.state
    val sel = st.selected
    val seg = st.segments.getOrNull(sel) ?: return
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ActionButton(Icons.Filled.ContentCut, "Split", onClick = onSplit)
            ActionButton(Icons.Filled.Delete, "Delete", enabled = st.segments.size > 1, onClick = onDelete)
            ActionButton(Icons.Filled.ContentCopy, "Duplicate", onClick = onDuplicate)
            ActionButton(Icons.Filled.ChevronLeft, "Move left", enabled = sel > 0) { onMove(-1) }
            ActionButton(Icons.Filled.ChevronRight, "Move right", enabled = sel < st.segments.lastIndex) { onMove(1) }
        }

        Text("Speed", style = MaterialTheme.typography.labelMedium)
        ChipRow {
            SPEEDS.forEach { s ->
                NovaFilterChip(
                    selected = seg.speed == s,
                    onClick = { session.setSpeed(sel, s) },
                    label = { Text(speedLabel(s)) },
                )
            }
        }

        if (hasAudio) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    session.commit { it.mapSegment(sel) { s -> s.copy(volume = if (s.volume == 0f) 1f else 0f) } }
                }) {
                    Icon(
                        if (seg.volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (seg.volume == 0f) "Unmute" else "Mute",
                    )
                }
                NovaSlider(
                    value = seg.volume,
                    onValueChange = { v ->
                        session.beginGesture()
                        session.update { it.mapSegment(sel) { s -> s.copy(volume = (v * 20).roundToInt() / 20f) } }
                    },
                    onValueChangeFinished = { session.endGesture() },
                    valueRange = 0f..2f,
                    modifier = Modifier.weight(1f),
                )
                Text("${(seg.volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
            }
            if (seg.volume > 1f) {
                Text(
                    "Boost above 100% is applied on export; the preview plays at 100%.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Text("This recording has no audio track.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// AI frame generation (runs at export time).
// ---------------------------------------------------------------------------------------------

@Composable
fun AiPanel(opts: ExportOptions, src: SourceInfo, onChange: (ExportOptions) -> Unit) {
    val on = opts.aiMultiplier > 0
    val mult = if (on) opts.aiMultiplier else 2
    val inFps = src.frameRate.roundToInt()
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (on) {
            Button(
                onClick = { onChange(opts.copy(aiMultiplier = 0)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("AI Frame Gen: ON · ×$mult  (~${inFps * mult} fps)") }
        } else {
            OutlinedButton(
                onClick = { onChange(opts.copy(aiMultiplier = mult)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enable AI Frame Gen") }
        }
        ChipRow {
            listOf(2, 3, 4).forEach { m ->
                NovaFilterChip(
                    selected = on && opts.aiMultiplier == m,
                    onClick = { onChange(opts.copy(aiMultiplier = m)) },
                    label = { Text("×$m  ${inFps * m} fps") },
                )
            }
        }
        Text(
            "Adds AI-generated in-between frames (RIFE / IFRNet, whichever is set in Settings) " +
                "so motion looks smoother. It runs when you export, frame by frame, so it takes a " +
                "while — export at 720p to make it much faster. Audio and clip length stay the same, " +
                "and no frames are blended across cuts between your clips.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
