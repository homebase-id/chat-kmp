package id.homebase.imageeditor.core.draw

import id.homebase.api.image.draw.PathCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BezierSmootherTest {

    @Test fun emptyInputProducesEmptyPath() {
        val out = BezierSmoother.smooth(FloatArray(0))
        assertTrue(out.isEmpty())
    }

    @Test fun singlePointProducesMoveAndDegenerateLine() {
        val out = BezierSmoother.smooth(floatArrayOf(5f, 7f))
        assertEquals(2, out.size)
        assertEquals(PathCommand.MoveTo(5f, 7f), out[0])
        assertEquals(PathCommand.LineTo(5f, 7f), out[1])
    }

    @Test fun twoPointsProduceMoveAndLine() {
        val out = BezierSmoother.smooth(floatArrayOf(0f, 0f, 10f, 4f))
        assertEquals(2, out.size)
        assertEquals(PathCommand.MoveTo(0f, 0f), out[0])
        assertEquals(PathCommand.LineTo(10f, 4f), out[1])
    }

    @Test fun threePointsProduceOneCubicSegment() {
        val out = BezierSmoother.smooth(floatArrayOf(0f, 0f, 10f, 10f, 20f, 0f))
        // 1 MoveTo + (count-2) = 1 CubicTo
        assertEquals(2, out.size)
        assertEquals(PathCommand.MoveTo(0f, 0f), out[0])
        assertTrue(out[1] is PathCommand.CubicTo)
        val cubic = out[1] as PathCommand.CubicTo
        assertEquals(20f, cubic.x)
        assertEquals(0f, cubic.y)
    }

    @Test fun curvePassesThroughEveryKnot() {
        // For 5 collinear points along y=x, the smoothed curve should still
        // anchor at each knot — verify the cubic destination for each
        // intermediate point matches the input.
        val out = BezierSmoother.smooth(
            floatArrayOf(
                0f, 0f,
                10f, 10f,
                20f, 20f,
                30f, 30f,
                40f, 40f,
            )
        )
        assertEquals(PathCommand.MoveTo(0f, 0f), out[0])
        // 3 cubic segments for 5 points (MoveTo + 3 cubics).
        assertEquals(4, out.size)
        val expected = listOf(10f to 10f, 20f to 20f, 30f to 30f)
        // Note: the smoother's loop emits CubicTo for indices 1..n-1 ending at
        // points[i+1]; with 5 points (n=4), i ranges 1..3 → ends at points 2,3,4.
        // So the destination of each cubic should be 20, 30, 40.
        val expectedEnds = listOf(20f to 20f, 30f to 30f, 40f to 40f)
        for ((i, exp) in expectedEnds.withIndex()) {
            val cubic = out[1 + i] as PathCommand.CubicTo
            assertEquals(exp.first, cubic.x, "cubic[$i].x")
            assertEquals(exp.second, cubic.y, "cubic[$i].y")
        }
    }
}
