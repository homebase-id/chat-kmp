package id.homebase.imageeditor.core.session

import id.homebase.imageeditor.core.EditorElement
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.PointF

/**
 * Single-pointer drag. Translated from `ElementDragEditSession.java` in
 * Signal-Android (AGPL-3.0).
 */
internal class ElementDragEditSession private constructor(
    selected: EditorElement,
    inverseMatrix: Matrix2D,
) : ElementEditSession(selected, inverseMatrix) {

    override fun movePoint(p: Int, point: PointF) {
        setScreenEndPoint(p, point)
        val em = selected.editorMatrix
        em.reset()
        em.postTranslate(
            endPointElement[0].x - startPointElement[0].x,
            endPointElement[0].y - startPointElement[0].y,
        )
    }

    override fun newPoint(newInverse: Matrix2D, point: PointF, p: Int): EditSession? =
        ElementScaleEditSession.startScale(this, newInverse, point, p)

    override fun removePoint(newInverse: Matrix2D, p: Int): EditSession = this

    companion object {
        fun startDrag(
            selected: EditorElement,
            inverseViewModelMatrix: Matrix2D,
            point: PointF,
        ): ElementDragEditSession? {
            if (!selected.flags.editable) return null
            val s = ElementDragEditSession(selected, inverseViewModelMatrix)
            s.setScreenStartPoint(0, point)
            s.setScreenEndPoint(0, point)
            return s
        }
    }
}
