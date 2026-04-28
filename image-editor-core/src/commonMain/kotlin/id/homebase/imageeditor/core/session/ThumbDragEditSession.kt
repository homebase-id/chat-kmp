package id.homebase.imageeditor.core.session

import id.homebase.imageeditor.core.ControlPoint
import id.homebase.imageeditor.core.EditorElement
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.PointF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Drag of a crop-rect thumb. Translated from `ThumbDragEditSession.java`
 * in Signal-Android (AGPL-3.0).
 *
 * Conceptually: the user is "pinching" between the dragged thumb and its
 * opposite (which acts as the anchor). For 8-point thumbs the behavior is
 * an asymmetric scale around the anchor; for the 2-point scale-and-rotate
 * thumbs it is a true pinch+rotate around the element origin.
 */
internal class ThumbDragEditSession private constructor(
    selected: EditorElement,
    private val controlPoint: ControlPoint,
    inverseMatrix: Matrix2D,
    private val thumbContainerRelativeMatrix: Matrix2D,
) : ElementEditSession(selected, inverseMatrix) {

    override fun movePoint(p: Int, point: PointF) {
        setScreenEndPoint(p, point)
        val em = selected.editorMatrix
        em.reset()

        // Locate the opposite control point in element-space.
        val oppOnParent = floatArrayOf(controlPoint.opposite().x, controlPoint.opposite().y)
        val oppOnElem = FloatArray(2)
        thumbContainerRelativeMatrix.mapPoints(oppOnElem, oppOnParent, count = 1)
        val oppX = oppOnElem[0]
        val oppY = oppOnElem[1]

        val dx = endPointElement[0].x - startPointElement[0].x
        val dy = endPointElement[0].y - startPointElement[0].y
        val xEnd = controlPoint.x + dx
        val yEnd = controlPoint.y + dy

        if (controlPoint.isScaleAndRotateThumb()) {
            val opp = PointF(oppX, oppY)
            val scale = findScale(opp, startPointElement[0], endPointElement[0])
            em.postTranslate(-opp.x, -opp.y)
            em.postScale(scale, scale)
            val angle = angle(endPointElement[0], opp) - angle(startPointElement[0], opp)
            em.postRotate(((angle * 180.0) / PI).toFloat())
            em.postTranslate(opp.x, opp.y)
        } else {
            val aspectLocked = selected.flags.aspectLocked && !controlPoint.isCenter()
            val defaultScale = if (aspectLocked) 2f else 1f
            val scaleX = if (controlPoint.isVerticalCenter()) defaultScale
            else (xEnd - oppX) / (controlPoint.x - oppX)
            val scaleY = if (controlPoint.isHorizontalCenter()) defaultScale
            else (yEnd - oppY) / (controlPoint.y - oppY)
            applyScale(em, aspectLocked, scaleX, scaleY, controlPoint.opposite())
        }
    }

    override fun newPoint(newInverse: Matrix2D, point: PointF, p: Int): EditSession? = null
    override fun removePoint(newInverse: Matrix2D, p: Int): EditSession? = null

    companion object {
        fun startDrag(
            selected: EditorElement,
            inverseViewModelMatrix: Matrix2D,
            thumbContainerRelativeMatrix: Matrix2D,
            controlPoint: ControlPoint,
            point: PointF,
        ): EditSession? {
            if (!selected.flags.editable) return null
            val s = ThumbDragEditSession(
                selected,
                controlPoint,
                inverseViewModelMatrix,
                thumbContainerRelativeMatrix,
            )
            s.setScreenStartPoint(0, point)
            s.setScreenEndPoint(0, point)
            return s
        }

        private fun applyScale(
            editorMatrix: Matrix2D,
            aspectLocked: Boolean,
            scaleX: Float,
            scaleY: Float,
            around: ControlPoint,
        ) {
            val x = around.x
            val y = around.y
            editorMatrix.postTranslate(-x, -y)
            if (aspectLocked) {
                val ms = min(scaleX, scaleY)
                editorMatrix.postScale(ms, ms)
            } else {
                editorMatrix.postScale(scaleX, scaleY)
            }
            editorMatrix.postTranslate(x, y)
        }

        private fun angle(a: PointF, b: PointF): Double =
            atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())

        private fun findScale(anchor: PointF, from: PointF, to: PointF): Float {
            val originalD2 = distanceSquared(from, anchor)
            val newD2 = distanceSquared(to, anchor)
            return sqrt((newD2 / originalD2).toDouble()).toFloat()
        }

        private fun distanceSquared(a: PointF, b: PointF): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return dx * dx + dy * dy
        }
    }
}
