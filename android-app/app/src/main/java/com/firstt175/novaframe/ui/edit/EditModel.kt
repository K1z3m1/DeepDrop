package com.firstt175.novaframe.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shortest piece a segment may be trimmed / split down to. */
const val MIN_SEGMENT_MS = 200L

/**
 * One piece of the source recording on the timeline. Segments are windows onto
 * the same source file, so they can overlap, repeat and be reordered freely.
 */
data class Segment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val speed: Float = 1f,
    /** 0 = muted, 1 = original, up to 2 = +6 dB boost (export only; preview caps at 1). */
    val volume: Float = 1f,
) {
    val sourceMs: Long get() = endMs - startMs

    /** Length on the output timeline once [speed] is applied. */
    val outputMs: Long get() = (sourceMs / speed).toLong()
}

enum class AspectOption(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    WIDE("16:9", 16f / 9f),
    TALL("9:16", 9f / 16f),
    SQUARE("1:1", 1f),
    CLASSIC("4:3", 4f / 3f),
    CINEMA("21:9", 21f / 9f),
}

/**
 * Colour looks. Brightness/contrast/saturation are on the same -1..1 scale as
 * the manual sliders (they add together); red/green/blue are channel gains.
 */
enum class FilterPreset(
    val label: String,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f,
) {
    NONE("None", 0f, 0f, 0f),
    VIVID("Vivid", 0.02f, 0.15f, 0.35f),
    WARM("Warm", 0.02f, 0.05f, 0.10f, red = 1.08f, blue = 0.88f),
    COOL("Cool", 0f, 0.05f, 0.05f, red = 0.90f, blue = 1.10f),
    MONO("Mono", 0f, 0.10f, -1f),
    FADE("Fade", 0.08f, -0.20f, -0.25f),
    DRAMA("Drama", -0.04f, 0.35f, -0.10f),
}

data class TextLayer(
    val text: String = "",
    /** -1 left, 0 centre, 1 right. */
    val h: Int = 0,
    /** -1 bottom, 0 middle, 1 top. */
    val v: Int = -1,
    /** Text height as a fraction of the output frame height. */
    val size: Float = 0.07f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val box: Boolean = false,
)

/** Everything that changes how the picture looks (applied to the whole clip). */
data class VisualSettings(
    /** Clockwise, multiples of 90. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val aspect: AspectOption = AspectOption.ORIGINAL,
    /** true = letterbox to the aspect, false = crop to fill it. */
    val aspectFit: Boolean = false,
    val preset: FilterPreset = FilterPreset.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val text: TextLayer? = null,
)

data class EditState(
    val segments: List<Segment>,
    val selected: Int = 0,
    val visual: VisualSettings = VisualSettings(),
) {
    val totalOutputMs: Long get() = segments.sumOf { it.outputMs }

    fun outputStartOf(index: Int): Long {
        var t = 0L
        for (i in 0 until index) t += segments[i].outputMs
        return t
    }

    /** Maps a point on the output timeline to (segment index, source position). */
    fun locate(outMs: Long): Pair<Int, Long> {
        var t = 0L
        for ((i, s) in segments.withIndex()) {
            val len = s.outputMs
            if (outMs < t + len || i == segments.lastIndex) {
                val within = (outMs - t).coerceIn(0L, len)
                val src = s.startMs + (within * s.speed).toLong()
                return i to src.coerceIn(s.startMs, s.endMs)
            }
            t += len
        }
        return 0 to 0L
    }
}

fun EditState.mapSegment(index: Int, f: (Segment) -> Segment): EditState =
    copy(segments = segments.mapIndexed { i, s -> if (i == index) f(s) else s })

private fun Long.clampTo(lo: Long, hi: Long): Long = if (hi < lo) lo else coerceIn(lo, hi)

/**
 * Holds the edit and its undo/redo history.
 *
 *  - [commit]: one discrete edit = one undo step (split, delete, pick a speed…).
 *  - [beginGesture] + [update] + [endGesture]: a drag (slider, trim handle,
 *    typing) collapses into a single undo step no matter how many updates it makes.
 */
class EditSession(initial: EditState, private val sourceDurationMs: Long) {
    var state by mutableStateOf(initial)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayList<EditState>()
    private val redoStack = ArrayList<EditState>()
    private var inGesture = false
    private var idSeq = 1000L

    private fun newId() = idSeq++

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun snapshot() {
        undoStack.add(state)
        if (undoStack.size > 80) undoStack.removeAt(0)
        redoStack.clear()
        refreshFlags()
    }

    fun commit(transform: (EditState) -> EditState) {
        inGesture = false
        snapshot()
        state = transform(state)
    }

    fun beginGesture() {
        if (!inGesture) {
            snapshot()
            inGesture = true
        }
    }

    fun update(transform: (EditState) -> EditState) {
        state = transform(state)
    }

    fun endGesture() {
        inGesture = false
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.add(state)
        state = prev
        inGesture = false
        refreshFlags()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.add(state)
        state = next
        inGesture = false
        refreshFlags()
        return true
    }

    // ---- segment operations -------------------------------------------------

    fun select(index: Int) = update { it.copy(selected = index.coerceIn(0, it.segments.lastIndex)) }

    /** Splits segment [index] at source position [srcMs]. False if a piece would be too short. */
    fun split(index: Int, srcMs: Long): Boolean {
        val s = state.segments.getOrNull(index) ?: return false
        if (srcMs - s.startMs < MIN_SEGMENT_MS || s.endMs - srcMs < MIN_SEGMENT_MS) return false
        commit { st ->
            val list = st.segments.toMutableList()
            list[index] = s.copy(endMs = srcMs)
            list.add(index + 1, s.copy(id = newId(), startMs = srcMs))
            st.copy(segments = list, selected = index + 1)
        }
        return true
    }

    fun delete(index: Int): Boolean {
        if (state.segments.size <= 1 || index !in state.segments.indices) return false
        commit { st ->
            val list = st.segments.toMutableList()
            list.removeAt(index)
            st.copy(segments = list, selected = index.coerceAtMost(list.lastIndex))
        }
        return true
    }

    fun duplicate(index: Int): Boolean {
        val s = state.segments.getOrNull(index) ?: return false
        commit { st ->
            val list = st.segments.toMutableList()
            list.add(index + 1, s.copy(id = newId()))
            st.copy(segments = list, selected = index + 1)
        }
        return true
    }

    fun move(index: Int, dir: Int): Boolean {
        val to = index + dir
        if (index !in state.segments.indices || to !in state.segments.indices) return false
        commit { st ->
            val list = st.segments.toMutableList()
            val s = list.removeAt(index)
            list.add(to, s)
            st.copy(segments = list, selected = to)
        }
        return true
    }

    fun setSpeed(index: Int, speed: Float) =
        commit { st -> st.mapSegment(index) { it.copy(speed = speed) } }

    /** Continuous: call [beginGesture] first. Returns the new source position of the moved edge. */
    fun trimEdge(index: Int, isStart: Boolean, deltaSrcMs: Long): Long {
        var edge = 0L
        update { st ->
            st.mapSegment(index) { s ->
                if (isStart) {
                    val ns = (s.startMs + deltaSrcMs).clampTo(0L, s.endMs - MIN_SEGMENT_MS)
                    edge = ns
                    s.copy(startMs = ns)
                } else {
                    val ne = (s.endMs + deltaSrcMs).clampTo(s.startMs + MIN_SEGMENT_MS, sourceDurationMs)
                    edge = ne
                    s.copy(endMs = ne)
                }
            }
        }
        return edge
    }

    // ---- visual settings ----------------------------------------------------

    fun commitVisual(f: (VisualSettings) -> VisualSettings) = commit { it.copy(visual = f(it.visual)) }

    fun updateVisual(f: (VisualSettings) -> VisualSettings) = update { it.copy(visual = f(it.visual)) }
}
