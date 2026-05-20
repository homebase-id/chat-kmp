package id.homebase.core.media

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomStateTest {

    @Test
    fun initial_state_is_not_zoomed() {
        val state = ZoomState()
        assertFalse(state.isZoomed)
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun isZoomed_true_when_above_minScale() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 2f)
        assertTrue(state.isZoomed)
    }

    @Test
    fun resetZoom_returns_to_initial() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 3f, offsetDelta = Offset(100f, 200f), viewportWidth = 1000f, viewportHeight = 2000f)
        assertTrue(state.isZoomed)

        state.resetZoom()
        assertFalse(state.isZoomed)
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun scale_clamps_to_maxScale() {
        val state = ZoomState(maxScale = 5f)
        state.applyTransform(scaleFactor = 10f)
        assertEquals(5f, state.scale)
    }

    @Test
    fun scale_clamps_to_minScale() {
        val state = ZoomState(minScale = 1f)
        state.applyTransform(scaleFactor = 0.1f)
        assertEquals(1f, state.scale)
    }

    @Test
    fun offset_is_zero_at_minScale() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 1f, offsetDelta = Offset(50f, 50f), viewportWidth = 500f, viewportHeight = 500f)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun offset_clamps_to_viewport_bounds() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 2f, viewportWidth = 1000f, viewportHeight = 2000f)
        state.applyTransform(scaleFactor = 1f, offsetDelta = Offset(99999f, 99999f), viewportWidth = 1000f, viewportHeight = 2000f)
        val maxX = (1000f * 2f - 1000f) / 2f
        val maxY = (2000f * 2f - 2000f) / 2f
        assertEquals(maxX, state.offset.x, 0.01f)
        assertEquals(maxY, state.offset.y, 0.01f)
    }

    @Test
    fun custom_minScale_and_maxScale() {
        val state = ZoomState(minScale = 0.5f, maxScale = 3f)
        assertEquals(0.5f, state.scale)
        assertFalse(state.isZoomed)
        state.applyTransform(scaleFactor = 7f)
        assertEquals(3f, state.scale)
    }

    @Test
    fun toggleDoubleTapZoom_from_min_zooms_to_2x() {
        val state = ZoomState()
        state.toggleDoubleTapZoom()
        assertEquals(2f, state.scale)
        assertTrue(state.isZoomed)
    }

    @Test
    fun toggleDoubleTapZoom_from_zoomed_resets() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 3f, offsetDelta = Offset(50f, 50f), viewportWidth = 500f, viewportHeight = 500f)
        state.toggleDoubleTapZoom()
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
        assertFalse(state.isZoomed)
    }

    @Test
    fun toggleDoubleTapZoom_clamps_to_maxScale_when_below_2x() {
        val state = ZoomState(minScale = 1f, maxScale = 1.5f)
        state.toggleDoubleTapZoom()
        assertEquals(1.5f, state.scale)
        assertTrue(state.isZoomed)
    }

    @Test
    fun velocity_factor_doubles_offset_delta() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 2f, viewportWidth = 1000f, viewportHeight = 1000f)
        state.applyTransform(
            scaleFactor = 1f,
            offsetDelta = Offset(10f, 10f),
            viewportWidth = 1000f,
            viewportHeight = 1000f,
        )
        assertEquals(20f, state.offset.x, 0.01f)
        assertEquals(20f, state.offset.y, 0.01f)
    }

    @Test
    fun offset_clamps_negative_direction() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 2f, viewportWidth = 1000f, viewportHeight = 1000f)
        state.applyTransform(
            scaleFactor = 1f,
            offsetDelta = Offset(-99999f, -99999f),
            viewportWidth = 1000f,
            viewportHeight = 1000f,
        )
        val maxX = (1000f * 2f - 1000f) / 2f
        assertEquals(-maxX, state.offset.x, 0.01f)
        assertEquals(-maxX, state.offset.y, 0.01f)
    }

    @Test
    fun zoom_out_past_minScale_resets_offset() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 3f, offsetDelta = Offset(50f, 50f), viewportWidth = 500f, viewportHeight = 500f)
        assertTrue(state.isZoomed)
        assertTrue(state.offset != Offset.Zero)

        state.applyTransform(scaleFactor = 0.2f)
        assertFalse(state.isZoomed)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun multiple_transforms_accumulate() {
        val state = ZoomState()
        state.applyTransform(scaleFactor = 1.5f)
        state.applyTransform(scaleFactor = 2f)
        assertEquals(3f, state.scale, 0.01f)
    }

}
