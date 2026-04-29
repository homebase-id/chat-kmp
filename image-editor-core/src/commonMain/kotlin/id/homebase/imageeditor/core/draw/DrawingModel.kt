package id.homebase.imageeditor.core.draw

import id.homebase.api.image.draw.PathCommand
import kotlin.math.sqrt

/**
 * Top-level facade for the draw editor. Owns the committed stroke list, the
 * in-flight stroke being drawn, and undo/redo history.
 *
 * Coordinates are bounds-space throughout (see [Stroke]). Conversions from
 * screen coordinates are the UI layer's responsibility.
 *
 * Undo discipline: callers MUST invoke [pushUndoPoint] *before* mutating —
 * `beginStroke` and `clearAll` already do this internally. Capturing
 * post-mutation state was the bug we hit in the cropper's "off-by-one undo"
 * regression; the same trap applies here.
 */
class DrawingModel {
    private val _strokes: MutableList<Stroke> = mutableListOf()
    private val undoStack: ArrayDeque<List<Stroke>> = ArrayDeque()
    private val redoStack: ArrayDeque<List<Stroke>> = ArrayDeque()
    private var inFlight: InFlight? = null

    val strokes: List<Stroke> get() = _strokes

    /** Live preview while the user's finger is still down. UI reads this. */
    val inFlightStroke: InFlight? get() = inFlight

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** Snapshot the current stroke list onto the undo stack. */
    fun pushUndoPoint() {
        undoStack.addLast(_strokes.toList())
        if (undoStack.size > UNDO_CAPACITY) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Begin a new stroke. Pushes an undo point first (so [undo] returns the
     * pre-stroke state). Cancels any in-flight stroke without committing.
     */
    fun beginStroke(brush: BrushType, colorArgb: Int, thicknessBoundsUnits: Float, x: Float, y: Float) {
        pushUndoPoint()
        inFlight = InFlight(brush, colorArgb, thicknessBoundsUnits).also { it.addPoint(x, y) }
    }

    /**
     * Append a point to the in-flight stroke. Points within
     * [thicknessBoundsUnits] of the previous point are dropped (matches
     * Signal's `addPointFiltered` to keep the bezier well-conditioned).
     */
    fun extendStroke(x: Float, y: Float) {
        val s = inFlight ?: return
        s.addPointFiltered(x, y)
    }

    /**
     * Finalize the in-flight stroke. Returns the committed [Stroke] or null
     * if there was nothing in flight or it had only zero points after
     * filtering.
     */
    fun commitStroke(): Stroke? {
        val s = inFlight ?: return null
        inFlight = null
        if (s.count == 0) {
            // Nothing was drawn — undo the speculative pushUndoPoint so the
            // user doesn't have to press undo on a no-op.
            undoStack.removeLastOrNull()
            return null
        }
        val raw = s.toFloatArray()
        val commands = BezierSmoother.smooth(raw)
        val stroke = Stroke(s.brush, s.colorArgb, s.thicknessBoundsUnits, raw, commands)
        _strokes.add(stroke)
        return stroke
    }

    /** Discard the in-flight stroke without committing. Pairs with [beginStroke]'s undo push. */
    fun cancelStroke() {
        if (inFlight == null) return
        inFlight = null
        undoStack.removeLastOrNull()
    }

    /** Wipe everything; undoable. */
    fun clearAll() {
        if (_strokes.isEmpty() && inFlight == null) return
        pushUndoPoint()
        _strokes.clear()
        inFlight = null
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = _strokes.toList()
        val popped = undoStack.removeLast()
        redoStack.addLast(current)
        _strokes.clear()
        _strokes.addAll(popped)
        inFlight = null
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = _strokes.toList()
        val popped = redoStack.removeLast()
        undoStack.addLast(current)
        _strokes.clear()
        _strokes.addAll(popped)
        inFlight = null
    }

    /** Internal mutable buffer for the live stroke. */
    class InFlight(
        val brush: BrushType,
        val colorArgb: Int,
        val thicknessBoundsUnits: Float,
    ) {
        private var data: FloatArray = FloatArray(INITIAL_CAPACITY)
        var count: Int = 0
            private set

        fun addPoint(x: Float, y: Float) {
            ensureCapacity()
            data[count * 2] = x
            data[count * 2 + 1] = y
            count++
        }

        fun addPointFiltered(x: Float, y: Float) {
            if (count > 0) {
                val px = data[(count - 1) * 2]
                val py = data[(count - 1) * 2 + 1]
                val dx = px - x
                val dy = py - y
                if (dx * dx + dy * dy < thicknessBoundsUnits * thicknessBoundsUnits * MIN_SPACING_FACTOR) return
            }
            addPoint(x, y)
        }

        fun toFloatArray(): FloatArray = data.copyOfRange(0, count * 2)

        /** Live cubic-spline smoothing for the in-flight preview. */
        fun previewPath(): List<PathCommand> {
            if (count == 0) return emptyList()
            return BezierSmoother.smooth(toFloatArray())
        }

        private fun ensureCapacity() {
            if ((count + 1) * 2 > data.size) {
                data = data.copyOf(data.size * 2)
            }
        }

        @Suppress("unused")
        private fun pointDistance(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx
            val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }

        companion object {
            private const val INITIAL_CAPACITY = 256 // 128 points
            private const val MIN_SPACING_FACTOR = 0.25f // dist >= thickness/2
        }
    }

    companion object {
        private const val UNDO_CAPACITY = 50
    }
}
