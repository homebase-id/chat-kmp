package id.homebase.imageeditor.core.session

import id.homebase.imageeditor.core.EditorElement
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.PointF

/**
 * One in-flight edit (drag, scale, thumb-drag).
 *
 * Translated from `EditSession.java` + `ElementEditSession.java` in
 * Signal-Android (AGPL-3.0). Compose's `pointerInput` translates raw touches
 * into [movePoint] / [newPoint] / [removePoint] / [commit] calls.
 */
interface EditSession {
    /** The element being mutated. */
    val selected: EditorElement

    /** Update the touch position for pointer index [p]. */
    fun movePoint(p: Int, point: PointF)

    /**
     * Add a second pointer. Returning null aborts the gesture (e.g. a thumb
     * drag with a stray second finger).
     */
    fun newPoint(newInverse: Matrix2D, point: PointF, p: Int): EditSession?

    /** Drop pointer [p] (transitions a scale back to a drag). */
    fun removePoint(newInverse: Matrix2D, p: Int): EditSession?

    /** Commit the in-flight edit into the element's local matrix. */
    fun commit()
}

/** Common base for the three concrete sessions. */
internal abstract class ElementEditSession(
    final override val selected: EditorElement,
    private val inverseMatrix: Matrix2D,
) : EditSession {

    val startPointElement: Array<PointF> = Array(2) { PointF() }
    val endPointElement: Array<PointF> = Array(2) { PointF() }
    val startPointScreen: Array<PointF> = Array(2) { PointF() }
    val endPointScreen: Array<PointF> = Array(2) { PointF() }

    fun setScreenStartPoint(p: Int, point: PointF) {
        startPointScreen[p].set(point)
        mapPoint(startPointElement[p], inverseMatrix, point)
    }

    fun setScreenEndPoint(p: Int, point: PointF) {
        endPointScreen[p].set(point)
        mapPoint(endPointElement[p], inverseMatrix, point)
    }

    override fun commit() {
        selected.commitEditorMatrix()
    }

    private fun mapPoint(dst: PointF, matrix: Matrix2D, src: PointF) {
        val out = matrix.mapPoint(src.x, src.y)
        dst.set(out[0], out[1])
    }
}
