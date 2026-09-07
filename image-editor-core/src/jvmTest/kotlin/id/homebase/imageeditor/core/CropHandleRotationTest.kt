package id.homebase.imageeditor.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression guard for #1318: after a 90/180/270 snap-rotate or a flip, the
 * handle the user grabs must drive the [ControlPoint] that actually sits under
 * their finger, and resize against the handle that is visually opposite it.
 */
class CropHandleRotationTest {

    private val hitRadius = 90f
    private val eps = 0.5f

    private data class State(val label: String, val quarterTurns: Int, val flipped: Boolean)

    private val states = listOf(
        State("identity", 0, false),
        State("rot90", 1, false),
        State("rot180", 2, false),
        State("rot270", 3, false),
        State("flip", 0, true),
        State("rot90+flip", 1, true),
        State("rot270+flip", 3, true),
    )

    private val sizes = listOf(
        "landscape" to Size(600, 450),
        "portrait" to Size(450, 600),
        "square" to Size(500, 500),
    )

    private fun buildModel(size: Size, state: State): EditorModel {
        val model = EditorModel.create()
        model.onImageReady(size)
        model.setVisibleViewPort(RectF(0f, 0f, 1080f, 1920f))
        model.setCropAspectLock(false)
        repeat(state.quarterTurns) { model.rotate90Clockwise() }
        if (state.flipped) model.flipHorizontal()
        return model
    }

    /** Canonical [Bounds] space to canvas pixels — what the user actually sees. */
    private fun cropToCanvas(model: EditorModel): Matrix2D {
        val m = Matrix2D(model.hierarchy.view.localMatrix)
        m.preConcat(model.hierarchy.flipRotate.localMatrix)
        m.preConcat(model.hierarchy.imageCrop.localMatrix)
        m.preConcat(model.hierarchy.cropEditorElement.localMatrix)
        m.preConcat(model.hierarchy.cropEditorElement.editorMatrix)
        return m
    }

    /** The same matrix the production drag path inverts (no in-flight editor matrix). */
    private fun screenToCrop(model: EditorModel): Matrix2D {
        val chain = Matrix2D(model.hierarchy.view.localMatrix)
        chain.preConcat(model.hierarchy.flipRotate.localMatrix)
        chain.preConcat(model.hierarchy.imageCrop.localMatrix)
        chain.preConcat(model.hierarchy.cropEditorElement.localMatrix)
        val inverse = Matrix2D()
        assertTrue(chain.invert(inverse))
        return inverse
    }

    private fun visualRect(model: EditorModel): RectF {
        val dst = RectF()
        cropToCanvas(model).mapRect(dst, Bounds.fullBounds())
        return dst
    }

    private fun visualCorner(rect: RectF, corner: String): PointF = when (corner) {
        "topLeft" -> PointF(rect.left, rect.top)
        "topRight" -> PointF(rect.right, rect.top)
        "bottomRight" -> PointF(rect.right, rect.bottom)
        "bottomLeft" -> PointF(rect.left, rect.bottom)
        else -> error(corner)
    }

    private fun oppositeCornerName(corner: String): String = when (corner) {
        "topLeft" -> "bottomRight"
        "topRight" -> "bottomLeft"
        "bottomRight" -> "topLeft"
        "bottomLeft" -> "topRight"
        else -> error(corner)
    }

    private fun assertPoint(expected: PointF, actual: PointF, message: String) {
        assertTrue(
            abs(expected.x - actual.x) <= eps && abs(expected.y - actual.y) <= eps,
            "$message: expected $expected but was $actual",
        )
    }

    private fun mapped(cp: ControlPoint, m: Matrix2D): PointF {
        val out = m.mapPoint(cp.x, cp.y)
        return PointF(out[0], out[1])
    }

    @Test
    fun grabbedCornerResolvesToTheControlPointUnderTheFinger() {
        for ((sizeLabel, size) in sizes) {
            for (state in states) {
                val model = buildModel(size, state)
                val m = cropToCanvas(model)
                val rect = visualRect(model)
                for (corner in listOf("topLeft", "topRight", "bottomRight", "bottomLeft")) {
                    val touch = visualCorner(rect, corner)
                    val cp = CropHandles.hitTest(touch.x, touch.y, m, hitRadius)
                    assertNotNull(cp, "$sizeLabel/${state.label}/$corner: no handle hit")
                    assertPoint(
                        touch,
                        mapped(cp, m),
                        "$sizeLabel/${state.label}/$corner: hit $cp but it is drawn elsewhere",
                    )
                }
            }
        }
    }

    @Test
    fun oppositeOfTheGrabbedCornerIsTheDiagonallyOppositeHandle() {
        for ((sizeLabel, size) in sizes) {
            for (state in states) {
                val model = buildModel(size, state)
                val m = cropToCanvas(model)
                val rect = visualRect(model)
                for (corner in listOf("topLeft", "topRight", "bottomRight", "bottomLeft")) {
                    val touch = visualCorner(rect, corner)
                    val cp = CropHandles.hitTest(touch.x, touch.y, m, hitRadius)
                    assertNotNull(cp, "$sizeLabel/${state.label}/$corner: no handle hit")
                    assertPoint(
                        visualCorner(rect, oppositeCornerName(corner)),
                        mapped(cp.opposite(), m),
                        "$sizeLabel/${state.label}/$corner: anchor $cp.opposite() is the wrong corner",
                    )
                }
            }
        }
    }

    @Test
    fun draggingACornerMovesThatCornerAndPinsTheOppositeOne() {
        val drags = mapOf(
            "topLeft" to PointF(37f, 61f),
            "topRight" to PointF(-37f, 61f),
            "bottomRight" to PointF(-37f, -61f),
            "bottomLeft" to PointF(37f, -61f),
        )
        for ((sizeLabel, size) in sizes) {
            for (state in states) {
                for ((corner, delta) in drags) {
                    val model = buildModel(size, state)
                    val before = visualRect(model)
                    val touch = visualCorner(before, corner)
                    val cp = CropHandles.hitTest(touch.x, touch.y, cropToCanvas(model), hitRadius)
                    assertNotNull(cp, "$sizeLabel/${state.label}/$corner: no handle hit")

                    val session = model.startCropThumbDrag(
                        screenToCrop = screenToCrop(model),
                        thumbContainerRelativeMatrix = Matrix2D(),
                        controlPoint = cp,
                        screenPoint = PointF(touch.x, touch.y),
                    )
                    assertNotNull(session, "$sizeLabel/${state.label}/$corner: no drag session")
                    session.movePoint(0, PointF(touch.x + delta.x, touch.y + delta.y))

                    val after = visualRect(model)
                    val where = "$sizeLabel/${state.label}/$corner"
                    assertPoint(
                        PointF(touch.x + delta.x, touch.y + delta.y),
                        visualCorner(after, corner),
                        "$where: the grabbed corner did not follow the finger",
                    )
                    assertPoint(
                        visualCorner(before, oppositeCornerName(corner)),
                        visualCorner(after, oppositeCornerName(corner)),
                        "$where: the opposite corner moved instead of anchoring",
                    )
                }
            }
        }
    }

    @Test
    fun draggingAnEdgeHandleMovesOnlyThatEdge() {
        val drags = mapOf(
            "left" to PointF(41f, 0f),
            "right" to PointF(-41f, 0f),
            "top" to PointF(0f, 53f),
            "bottom" to PointF(0f, -53f),
        )
        for ((sizeLabel, size) in sizes) {
            for (state in states) {
                for ((edge, delta) in drags) {
                    val model = buildModel(size, state)
                    val before = visualRect(model)
                    val touch = when (edge) {
                        "left" -> PointF(before.left, before.centerY())
                        "right" -> PointF(before.right, before.centerY())
                        "top" -> PointF(before.centerX(), before.top)
                        else -> PointF(before.centerX(), before.bottom)
                    }
                    val cp = CropHandles.hitTest(
                        touch.x,
                        touch.y,
                        cropToCanvas(model),
                        hitRadius,
                        CropHandles.EDGES,
                    )
                    assertNotNull(cp, "$sizeLabel/${state.label}/$edge: no handle hit")

                    val session = model.startCropThumbDrag(
                        screenToCrop = screenToCrop(model),
                        thumbContainerRelativeMatrix = Matrix2D(),
                        controlPoint = cp,
                        screenPoint = PointF(touch.x, touch.y),
                    )
                    assertNotNull(session, "$sizeLabel/${state.label}/$edge: no drag session")
                    session.movePoint(0, PointF(touch.x + delta.x, touch.y + delta.y))

                    val after = visualRect(model)
                    val expected = RectF(before.left, before.top, before.right, before.bottom).also {
                        when (edge) {
                            "left" -> it.left += delta.x
                            "right" -> it.right += delta.x
                            "top" -> it.top += delta.y
                            else -> it.bottom += delta.y
                        }
                    }
                    val where = "$sizeLabel/${state.label}/$edge"
                    assertTrue(
                        rectsEqual(expected, after, eps),
                        "$where: expected $expected but was $after (hit $cp)",
                    )
                }
            }
        }
    }

    @Test
    fun freeRotationDoesNotDisturbTheCropHandles() {
        val model = buildModel(Size(600, 450), State("identity", 0, false))
        model.setMainImageEditorMatrixRotation(17f)
        val m = cropToCanvas(model)
        val rect = visualRect(model)
        for (corner in listOf("topLeft", "topRight", "bottomRight", "bottomLeft")) {
            val touch = visualCorner(rect, corner)
            val cp = CropHandles.hitTest(touch.x, touch.y, m, hitRadius)
            assertNotNull(cp, "freeRotation/$corner: no handle hit")
            assertPoint(touch, mapped(cp, m), "freeRotation/$corner: hit $cp but it is drawn elsewhere")
            assertPoint(
                visualCorner(rect, oppositeCornerName(corner)),
                mapped(cp.opposite(), m),
                "freeRotation/$corner: wrong anchor",
            )
        }
    }

    @Test
    fun unrotatedImageStillMapsHandlesToTheirCanonicalNames() {
        val model = buildModel(Size(600, 450), State("identity", 0, false))
        val m = cropToCanvas(model)
        val rect = visualRect(model)
        assertEquals(
            ControlPoint.TOP_LEFT,
            CropHandles.hitTest(rect.left, rect.top, m, hitRadius),
        )
        assertEquals(
            ControlPoint.TOP_RIGHT,
            CropHandles.hitTest(rect.right, rect.top, m, hitRadius),
        )
        assertEquals(
            ControlPoint.BOTTOM_RIGHT,
            CropHandles.hitTest(rect.right, rect.bottom, m, hitRadius),
        )
        assertEquals(
            ControlPoint.BOTTOM_LEFT,
            CropHandles.hitTest(rect.left, rect.bottom, m, hitRadius),
        )
    }
}
