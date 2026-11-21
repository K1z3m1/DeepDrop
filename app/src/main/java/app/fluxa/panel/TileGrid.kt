package app.fluxa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * The button grid of the in-game panel. It owns every touch on its cells so one finger can do all of it:
 *  - quick tap                      -> [Host.onTap]
 *  - hold (about [longMs]) + lift   -> [Host.onLong] (the settings sheet of that button)
 *  - keep holding (about [editMs])  -> edit mode starts and the button follows the finger (like a launcher)
 * In edit mode a button can be dragged to any slot; a plain tap calls [Host.onEditTap] (hide / show).
 * Nothing is animated: other buttons jump straight to their new slot so the game is not disturbed.
 */
class TileGrid(ctx: Context) : ViewGroup(ctx) {
    interface Host {
        fun onTap(id: String)
        fun onLong(id: String)
        fun hasLong(id: String): Boolean
        /** The long hold finished: switch the panel to edit mode (the host rebuilds the cells with [setCells]). */
        fun onEnterEdit()
        fun onEditTap(id: String)
        /** A drag finished; [order] is the new slot order. */
        fun onOrderChanged(order: List<String>)
    }

    var host: Host? = null
    var cols = 4
        set(v) { field = v.coerceAtLeast(1); requestLayout() }
    var cellHeight = 0
    var editMode = false

    /** True while a button is being dragged (the panel must not treat the swipe as "close"). */
    val dragging: Boolean get() = dragId != null

    private val ids = ArrayList<String>()
    private val views = HashMap<String, View>()
    private val ui = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val density = ctx.resources.displayMetrics.density

    private val longMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350L, 600L)
    private val editMs = longMs + 650L
    private val dragMs = 160L

    // ---- gesture state
    private var downId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var pressed = false

    // ---- drag state
    private var dragId: String? = null
    private var dragView: View? = null
    private var grabDx = 0f
    private var grabDy = 0f
    private var rawX = 0f
    private var rawY = 0f

    fun setCells(order: List<String>, map: Map<String, View>) {
        endDrag(commit = false)
        removeAllViews()
        ids.clear()
        views.clear()
        order.forEach { id ->
            val v = map[id] ?: return@forEach
            ids += id
            views[id] = v
            addView(v)
        }
        requestLayout()
    }

    // ---------------------------------------------------------------- layout

    private fun cellW(): Int = ((width - paddingLeft - paddingRight) / cols).coerceAtLeast(1)
    private fun slotX(i: Int) = paddingLeft + (i % cols) * cellW()
    private fun slotY(i: Int) = (i / cols) * cellHeight

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val cw = ((w - paddingLeft - paddingRight) / cols).coerceAtLeast(1)
        val rows = (ids.size + cols - 1) / cols
        ids.forEach { id ->
            views[id]?.measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, rows * cellHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = layoutSlots()

    private fun layoutSlots() {
        val cw = cellW()
        ids.forEachIndexed { i, id ->
            val v = views[id] ?: return@forEachIndexed
            val x = slotX(i)
            val y = slotY(i)
            v.layout(x, y, x + cw, y + cellHeight)
        }
        updateDragView()
    }

    private fun idAt(x: Float, y: Float): String? {
        if (x < paddingLeft || x >= width - paddingRight || y < 0f) return null
        val col = ((x - paddingLeft) / cellW()).toInt().coerceIn(0, cols - 1)
        val row = (y / cellHeight).toInt()
        val i = row * cols + col
        return ids.getOrNull(i)
    }

    // ---------------------------------------------------------------- touch

    private val tickRun = Runnable {
        // light tick: lifting the finger now opens the settings sheet
        val id = downId
        if (pressed && !moved && id != null && host?.hasLong(id) == true) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private val editRun = Runnable {
        val id = downId ?: return@Runnable
        if (!pressed || moved || editMode) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        host?.onEnterEdit() // rebuilds the cells in edit mode
        if (editMode) beginDrag(id, centred = true)
    }

    private val dragRun = Runnable {
        val id = downId ?: return@Runnable
        if (!pressed || moved || !editMode) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        beginDrag(id, centred = false)
    }

    private fun cancelTimers() {
        ui.removeCallbacks(tickRun)
        ui.removeCallbacks(editRun)
        ui.removeCallbacks(dragRun)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        rawX = e.rawX
        rawY = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = idAt(e.x, e.y) ?: return false
                downId = id
                downX = e.x
                downY = e.y
                downAt = SystemClock.uptimeMillis()
                moved = false
                pressed = true
                cancelTimers()
                if (editMode) {
                    ui.postDelayed(dragRun, dragMs)
                } else {
                    ui.postDelayed(tickRun, longMs)
                    ui.postDelayed(editRun, editMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragId != null) {
                    updateDragView()
                    autoScroll()
                    return true
                }
                if (pressed && !moved) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (dx * dx + dy * dy > slop * slop) {
                        moved = true
                        cancelTimers() // the parent ScrollView takes over as a normal scroll
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelTimers()
                val id = downId
                val wasDrag = dragId != null
                if (wasDrag) {
                    endDrag(commit = true)
                } else if (pressed && !moved && id != null) {
                    val held = SystemClock.uptimeMillis() - downAt
                    val h = host
                    if (editMode) h?.onEditTap(id)
                    else if (held >= longMs && h?.hasLong(id) == true) h.onLong(id)
                    else h?.onTap(id)
                }
                pressed = false
                downId = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers()
                if (dragId != null) endDrag(commit = true)
                pressed = false
                downId = null
                return true
            }
        }
        return true
    }

    // ---------------------------------------------------------------- drag

    private fun beginDrag(id: String, centred: Boolean) {
        val v = views[id] ?: return
        dragId = id
        dragView = v
        if (centred) {
            // the cells were just rebuilt under the finger: pick the button up by its middle
            grabDx = cellW() / 2f
            grabDy = cellHeight * 0.42f
        } else {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            grabDx = (rawX - loc[0]) - v.left
            grabDy = (rawY - loc[1]) - v.top
        }
        v.bringToFront()
        v.alpha = 0.88f
        v.scaleX = 1.08f
        v.scaleY = 1.08f
        parent?.requestDisallowInterceptTouchEvent(true)
        updateDragView()
    }

    /** Puts the dragged cell under the finger and moves the others out of the way when it crosses a slot. */
    private fun updateDragView() {
        val id = dragId ?: return
        val v = dragView ?: return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val cw = cellW()
        val left = (rawX - loc[0]) - grabDx
        val top = (rawY - loc[1]) - grabDy
        val rows = ((ids.size + cols - 1) / cols).coerceAtLeast(1)
        val cx = left + cw / 2f
        val cy = top + cellHeight / 2f
        val col = ((cx - paddingLeft) / cw).toInt().coerceIn(0, cols - 1)
        val row = (cy / cellHeight).toInt().coerceIn(0, rows - 1)
        val target = (row * cols + col).coerceIn(0, ids.size - 1)
        val cur = ids.indexOf(id)
        if (cur >= 0 && target != cur) {
            ids.removeAt(cur)
            ids.add(target, id)
            // re-seat every other cell directly (no animation over the game)
            ids.forEachIndexed { i, other ->
                if (other == id) return@forEachIndexed
                val ov = views[other] ?: return@forEachIndexed
                ov.layout(slotX(i), slotY(i), slotX(i) + cw, slotY(i) + cellHeight)
            }
            v.layout(slotX(target), slotY(target), slotX(target) + cw, slotY(target) + cellHeight)
        }
        val now = ids.indexOf(id).coerceAtLeast(0)
        v.translationX = left - slotX(now)
        v.translationY = top - slotY(now)
    }

    private fun endDrag(commit: Boolean) {
        val v = dragView
        if (v != null) {
            v.translationX = 0f
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = 1f
        }
        val was = dragId != null
        dragId = null
        dragView = null
        stopAutoScroll()
        if (was) {
            layoutSlots()
            if (commit) host?.onOrderChanged(ids.toList())
        }
    }

    // ---------------------------------------------------------------- auto scroll while dragging

    private val scrollRun = object : Runnable {
        override fun run() {
            if (dragId == null) return
            val sv = scrollParent() ?: return
            val loc = IntArray(2)
            sv.getLocationOnScreen(loc)
            val edge = (56 * density)
            val step = (9 * density).toInt().coerceAtLeast(4)
            val dy = when {
                rawY < loc[1] + edge -> -step
                rawY > loc[1] + sv.height - edge -> step
                else -> 0
            }
            if (dy != 0) {
                sv.scrollBy(0, dy)
                updateDragView()
            }
            ui.postDelayed(this, 16)
        }
    }
    private var scrolling = false

    private fun autoScroll() {
        if (scrolling) return
        scrolling = true
        ui.post(scrollRun)
    }

    private fun stopAutoScroll() {
        scrolling = false
        ui.removeCallbacks(scrollRun)
    }

    private fun scrollParent(): ScrollView? {
        var p = parent
        while (p != null && p !is ScrollView) p = p.parent
        return p as? ScrollView
    }

    override fun onDetachedFromWindow() {
        cancelTimers()
        stopAutoScroll()
        super.onDetachedFromWindow()
    }
}
