package com.firstt175.novaframe.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val TimelineHeight = 64.dp
private val HandleWidth = 16.dp
private val PlayheadColor = Color(0xFFFF5252)

/**
 * CapCut-style timeline: one block per segment (filmstrip inside, sized by its
 * output length so speed changes are visible), a red playhead, and drag handles
 * on the selected segment for trimming. Drag anywhere else to scrub, tap a
 * block to select it.
 */
@Composable
fun EditTimeline(
    state: EditState,
    filmstrip: Filmstrip,
    sourceDurationMs: Long,
    thumbAspect: Float,
    playheadOutMs: () -> Long,
    onScrub: (outMs: Long) -> Unit,
    onTap: (index: Int, outMs: Long) -> Unit,
    onTrimBegin: () -> Unit,
    onTrim: (index: Int, isStart: Boolean, deltaSrcMs: Long) -> Unit,
    onTrimEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().height(TimelineHeight)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val total = state.totalOutputMs.coerceAtLeast(1L)

        // While a trim handle is held the scale is frozen, so the block under the
        // finger doesn't jitter as the total length changes; it re-fits on release.
        var frozenTotal by remember { mutableLongStateOf(total) }
        var trimming by remember { mutableStateOf(false) }
        val effectiveTotal = if (trimming) max(frozenTotal, total) else total
        val pxPerMs = widthPx / effectiveTotal
        val pxPerMsNow = rememberUpdatedState(pxPerMs)
        val stateNow = rememberUpdatedState(state)
        val onScrubNow = rememberUpdatedState(onScrub)
        val onTapNow = rememberUpdatedState(onTap)

        val starts = remember(state.segments) {
            val list = ArrayList<Long>(state.segments.size)
            var t = 0L
            for (s in state.segments) { list.add(t); t += s.outputMs }
            list
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val st = stateNow.value
                        val ms = (pos.x / pxPerMsNow.value).toLong().coerceIn(0L, st.totalOutputMs)
                        onTapNow.value(st.locate(ms).first, ms)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val st = stateNow.value
                        val ms = (change.position.x / pxPerMsNow.value).toLong().coerceIn(0L, st.totalOutputMs)
                        onScrubNow.value(ms)
                    }
                },
        ) {
            state.segments.forEachIndexed { i, seg ->
                key(seg.id) {
                    SegmentBlock(
                        seg = seg,
                        selected = i == state.selected,
                        filmstrip = filmstrip,
                        sourceDurationMs = sourceDurationMs,
                        thumbAspect = thumbAspect,
                        xPx = starts[i] * pxPerMs,
                        wPx = max(seg.outputMs * pxPerMs, 4f),
                    )
                }
            }

            val sel = state.selected
            val selSeg = state.segments.getOrNull(sel)
            if (selSeg != null) {
                val handlePx = with(density) { HandleWidth.toPx() }
                val x = starts[sel] * pxPerMs
                val w = max(selSeg.outputMs * pxPerMs, 4f)
                val speedNow = rememberUpdatedState(selSeg.speed)
                val beginTrim = {
                    frozenTotal = total
                    trimming = true
                    onTrimBegin()
                }
                val endTrim = {
                    trimming = false
                    onTrimEnd()
                }
                TrimHandle(x, isStart = true, pxPerMs = pxPerMsNow, speed = speedNow, onBegin = beginTrim, onDelta = { onTrim(sel, true, it) }, onEnd = endTrim)
                TrimHandle(x + w - handlePx, isStart = false, pxPerMs = pxPerMsNow, speed = speedNow, onBegin = beginTrim, onDelta = { onTrim(sel, false, it) }, onEnd = endTrim)
            }

            val lineW = with(density) { 2.dp.roundToPx() }
            Box(
                Modifier
                    .offset { IntOffset((playheadOutMs() * pxPerMs).roundToInt().coerceIn(0, widthPx.roundToInt()) - lineW / 2, 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(PlayheadColor),
            )
        }
    }
}

@Composable
private fun SegmentBlock(
    seg: Segment,
    selected: Boolean,
    filmstrip: Filmstrip,
    sourceDurationMs: Long,
    thumbAspect: Float,
    xPx: Float,
    wPx: Float,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(with(density) { wPx.toDp() })
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(shape)
            .background(Color(0xFF2B2B2B))
            .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawFilmstrip(seg, filmstrip, sourceDurationMs, thumbAspect)
        }
        if (seg.speed != 1f) {
            Text(
                speedLabel(seg.speed),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(3.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp),
            )
        }
        if (seg.volume == 0f) {
            Icon(
                Icons.Filled.VolumeOff,
                contentDescription = "Muted",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(14.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(3.dp)),
            )
        }
    }
}

private fun DrawScope.drawFilmstrip(seg: Segment, filmstrip: Filmstrip, sourceDurationMs: Long, thumbAspect: Float) {
    val frames = filmstrip.frames
    if (frames.isEmpty() || sourceDurationMs <= 0L || size.width <= 0f) return
    val h = size.height
    val tileW = (h * thumbAspect).coerceAtLeast(8f)
    var x = 0f
    while (x < size.width) {
        val frac = ((x + tileW / 2f) / size.width).coerceIn(0f, 1f)
        val srcMs = seg.startMs + (frac * seg.sourceMs).toLong()
        val idx = ((srcMs.toDouble() / sourceDurationMs) * frames.size).toInt().coerceIn(0, frames.size - 1)
        frames[idx]?.let { img ->
            drawImage(
                img,
                dstOffset = IntOffset(x.roundToInt(), 0),
                dstSize = IntSize(ceil(tileW).toInt(), h.roundToInt()),
            )
        }
        x += tileW
    }
}

@Composable
private fun TrimHandle(
    xPx: Float,
    isStart: Boolean,
    pxPerMs: State<Float>,
    speed: State<Float>,
    onBegin: () -> Unit,
    onDelta: (Long) -> Unit,
    onEnd: () -> Unit,
) {
    val onBeginNow = rememberUpdatedState(onBegin)
    val onDeltaNow = rememberUpdatedState(onDelta)
    val onEndNow = rememberUpdatedState(onEnd)
    val shape = if (isStart) {
        RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    }
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(HandleWidth)
            .fillMaxHeight()
            .background(Color.White, shape)
            .pointerInput(isStart) {
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f; onBeginNow.value() },
                    onDragEnd = { onEndNow.value() },
                    onDragCancel = { onEndNow.value() },
                ) { change, dx ->
                    change.consume()
                    acc += dx / pxPerMs.value * speed.value
                    val whole = acc.toLong()
                    if (whole != 0L) {
                        acc -= whole
                        onDeltaNow.value(whole)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(18.dp).background(Color(0xFF555555), RoundedCornerShape(1.dp)))
    }
}

fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"
