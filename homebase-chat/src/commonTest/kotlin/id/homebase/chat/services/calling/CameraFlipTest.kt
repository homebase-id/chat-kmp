package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CameraFlipTest {
    // The Redmi's Camera2 list: cycling 1 -> 2 -> 3 opened camera 3, which froze and ended the track.
    private val redmi = listOf(
        CameraInfo("0", CameraFacing.Back),
        CameraInfo("1", CameraFacing.Front),
        CameraInfo("2", CameraFacing.Back),
        CameraInfo("3", CameraFacing.Back),
    )

    @Test
    fun frontFlipsToPrimaryBackNotTheNextLens() = assertEquals("0", flipTarget("1", redmi))

    @Test
    fun backFlipsToFront() = assertEquals("1", flipTarget("0", redmi))

    @Test
    fun auxiliaryBackLensFlipsToFront() = assertEquals("1", flipTarget("3", redmi))

    @Test
    fun repeatedFlipsAlternateBetweenPrimaryCameras() {
        val visited = generateSequence("1") { flipTarget(it, redmi) }.take(6).toList()
        assertEquals(listOf("1", "0", "1", "0", "1", "0"), visited)
    }

    @Test
    fun unknownCurrentCameraHasNoTarget() = assertNull(flipTarget("9", redmi))

    @Test
    fun noOppositeFacingCameraHasNoTarget() =
        assertNull(flipTarget("0", listOf(CameraInfo("0", CameraFacing.Back), CameraInfo("2", CameraFacing.Back))))
}
