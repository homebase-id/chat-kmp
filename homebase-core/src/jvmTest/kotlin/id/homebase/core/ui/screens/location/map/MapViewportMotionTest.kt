package id.homebase.core.ui.screens.location.map

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class MapViewportMotionTest {

    private val vp = MapViewport(centerX = 0.5, centerY = 0.5, unitsPerPx = 1e-5)

    @Test
    fun zoomKeepsTheAnchorPointFixedOnScreen() {
        val anchor = Offset(120f, -80f)
        val before = unitAt(vp, anchor)
        val zoomed = vp.transformed(anchor, Offset.Zero, 2f)
        assertEquals(vp.unitsPerPx / 2, zoomed.unitsPerPx, 1e-15)
        val after = unitAt(zoomed, anchor)
        assertEquals(before.first, after.first, 1e-12)
        assertEquals(before.second, after.second, 1e-12)
    }

    @Test
    fun panMovesTheCenterAgainstTheDrag() {
        val panned = vp.transformed(Offset.Zero, Offset(10f, 0f), 1f)
        assertEquals(vp.centerX - 10 * vp.unitsPerPx, panned.centerX, 1e-15)
        assertEquals(vp.centerY, panned.centerY, 1e-15)
    }

    @Test
    fun lerpZoomsGeometricallyAndHitsBothEnds() {
        val target = MapViewport(centerX = 0.7, centerY = 0.3, unitsPerPx = 1e-7)
        assertEquals(vp, vp.lerpTo(target, 0f))
        val end = vp.lerpTo(target, 1f)
        assertEquals(target.centerX, end.centerX, 1e-12)
        assertEquals(target.unitsPerPx, end.unitsPerPx, 1e-15)
        assertEquals(1e-6, vp.lerpTo(target, 0.5f).unitsPerPx, 1e-15)
    }

    private fun unitAt(v: MapViewport, px: Offset) =
        (v.centerX + px.x * v.unitsPerPx) to (v.centerY + px.y * v.unitsPerPx)
}
