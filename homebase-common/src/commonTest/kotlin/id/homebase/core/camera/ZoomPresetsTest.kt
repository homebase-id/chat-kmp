package id.homebase.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZoomPresetsTest {
    private fun ratios(min: Float, max: Float, switches: List<Float> = emptyList()) =
        ZoomPresets.available(min, max, switches).map { it.ratio }

    @Test
    fun wideOnlyLensOffersOneAndTwo() {
        assertEquals(listOf(1f, 2f), ratios(1f, 8f))
    }

    @Test
    fun tripleCameraOffersUltraWideAndTelephoto() {
        assertEquals(listOf(0.5f, 1f, 2f, 5f), ratios(0.5f, 10f, listOf(1f, 5f)))
    }

    @Test
    fun ultraWideLabelUsesTheLensMinimum() {
        val presets = ZoomPresets.available(0.6f, 8f, emptyList())
        assertEquals("0.6", presets.first().label)
        assertEquals(listOf("0.6", "1", "2"), presets.map { it.label })
    }

    @Test
    fun fusedUltraWideRoundsToHalf() {
        assertEquals("0.5", ZoomPresets.available(0.506f, 8f, emptyList()).first().label)
    }

    @Test
    fun lensThatStopsShortOfTwoOffersOnlyOne() {
        assertEquals(listOf(1f), ratios(1f, 1.5f))
    }

    @Test
    fun lensThatDoesNotZoomOffersOnlyOne() {
        assertEquals(listOf(1f), ratios(1f, 1f))
    }

    @Test
    fun minimumWithinToleranceOfOneIsNotAnUltraWide() {
        assertEquals(listOf(1f, 2f), ratios(0.99f, 8f))
    }

    @Test
    fun telephotoAtTwoIsNotDuplicated() {
        assertEquals(listOf(1f, 2f), ratios(1f, 10f, listOf(2f)))
    }

    @Test
    fun telephotoBeyondMaxIsWithheld() {
        assertEquals(listOf(0.5f, 1f, 2f), ratios(0.5f, 4f, listOf(1f, 5f)))
    }

    @Test
    fun selectedMatchesWithinTolerance() {
        val presets = ZoomPresets.available(0.5f, 10f, listOf(1f, 5f))
        assertEquals(1f, ZoomPresets.selected(1.015f, presets)?.ratio)
        assertEquals(0.5f, ZoomPresets.selected(0.506f, presets)?.ratio)
        assertNull(ZoomPresets.selected(1.5f, presets))
        assertNull(ZoomPresets.selected(1.05f, presets))
    }

    @Test
    fun labelsDropTrailingZero() {
        assertEquals("1", ZoomPresets.label(1f))
        assertEquals("2", ZoomPresets.label(2.0f))
        assertEquals("0.5", ZoomPresets.label(0.5f))
        assertEquals("2.5", ZoomPresets.label(2.5f))
    }
}
