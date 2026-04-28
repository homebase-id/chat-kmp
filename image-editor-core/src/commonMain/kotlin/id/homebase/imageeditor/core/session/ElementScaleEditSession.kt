package id.homebase.imageeditor.core.session

import id.homebase.imageeditor.core.EditorElement
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.PointF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Two-pointer pinch/rotate. Translated from `ElementScaleEditSession.java`
 * in Signal-Android (AGPL-3.0).
 */
internal class ElementScaleEditSession private constructor(
    selected: EditorElement,
    inverseMatrix: Matrix2D,
) : ElementEditSession(selected, inverseMatrix) {

    override fun movePoint(p: Int, point: PointF) {
        setScreenEndPoint(p, point)
        val em = selected.editorMatrix
        em.reset()

        if (selected.flags.aspectLocked) {
            val scale = findScale(startPointElement, endPointElement)
            em.postTranslate(-startPointElement[0].x, -startPointElement[0].y)
            em.postScale(scale, scale)
            val angle = angle(endPointElement[0], endPointElement[1]) -
                angle(startPointElement[0], startPointElement[1])
            if (!selected.flags.rotateLocked) {
                em.postRotate(((angle * 180.0) / PI).toFloat())
            }
            em.postTranslate(endPointElement[0].x, endPointElement[0].y)
        } else {
            em.postTranslate(-startPointElement[0].x, -startPointElement[0].y)
            val scaleX = (endPointElement[1].x - endPointElement[0].x) /
                (startPointElement[1].x - startPointElement[0].x)
            val scaleY = (endPointElement[1].y - endPointElement[0].y) /
                (startPointElement[1].y - startPointElement[0].y)
            em.postScale(scaleX, scaleY)
            em.postTranslate(endPointElement[0].x, endPointElement[0].y)
        }
    }

    override fun newPoint(newInverse: Matrix2D, point: PointF, p: Int): EditSession? = this

    override fun removePoint(newInverse: Matrix2D, p: Int): EditSession? =
        ElementDragEditSession.startDrag(selected, newInverse, endPointScreen[1 - p])

    companion object {
        fun startScale(
            session: ElementDragEditSession,
            inverseMatrix: Matrix2D,
            point: PointF,
            p: Int,
        ): ElementScaleEditSession {
            session.commit()
            val newSession = ElementScaleEditSession(session.selected, inverseMatrix)
            newSession.setScreenStartPoint(1 - p, session.endPointScreen[0])
            newSession.setScreenEndPoint(1 - p, session.endPointScreen[0])
            newSession.setScreenStartPoint(p, point)
            newSession.setScreenEndPoint(p, point)
            return newSession
        }

        private fun angle(a: PointF, b: PointF): Double =
            atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())

        private fun findScale(from: Array<PointF>, to: Array<PointF>): Float {
            val originalD2 = distanceSquared(from[0], from[1])
            val newD2 = distanceSquared(to[0], to[1])
            return sqrt((newD2 / originalD2).toDouble()).toFloat()
        }

        private fun distanceSquared(a: PointF, b: PointF): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return dx * dx + dy * dy
        }
    }
}
