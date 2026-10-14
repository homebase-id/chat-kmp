package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallDevicePressureTest {
    private fun stats(target: Double) = listOf(
        StatsEntry("media-source", mapOf("kind" to "video", "width" to 1280, "height" to 720)),
        StatsEntry("outbound-rtp", mapOf("kind" to "video", "targetBitrate" to target, "frameWidth" to 1280, "frameHeight" to 720)),
    )

    @Test
    fun policyCapsByThermalAndLowPower() {
        assertEquals(SendCap.NONE, DevicePressure().cap)
        assertEquals(SendCap.NONE, DevicePressure(ThermalLevel.Fair).cap)
        assertEquals(SendCap(540, 30), DevicePressure(ThermalLevel.Serious).cap)
        assertEquals(SendCap(360, 15), DevicePressure(ThermalLevel.Critical).cap)
        assertEquals(SendCap(360, 15), DevicePressure(ThermalLevel.Nominal, lowPower = true).cap)
        assertEquals(SendCap(360, 15), DevicePressure(ThermalLevel.Serious, lowPower = true).cap)
        assertEquals("device pressure thermal=serious lowPower=false cap=540p@30", DevicePressure(ThermalLevel.Serious).describe())
    }

    @Test
    fun ladderAppliesThePressureCapAndLiftsItWhenCleared() {
        var scale: Double? = null
        var fps: Int? = null
        val ladder = SendResolutionLadder(applyFps = { fps = it }) { scale = it }

        ladder.onStats(stats(2_500_000.0))
        assertNull(scale)

        ladder.setPressure(DevicePressure(ThermalLevel.Serious))
        ladder.onStats(stats(2_500_000.0))
        assertEquals(720.0 / 540, scale)
        assertNull(fps)

        ladder.setPressure(DevicePressure(ThermalLevel.Critical))
        ladder.onStats(stats(2_500_000.0))
        assertEquals(2.0, scale)
        assertEquals(15, fps)

        ladder.setPressure(DevicePressure())
        ladder.onStats(stats(2_500_000.0))
        assertEquals(1.0, scale)
        assertEquals(30, fps)
    }

    @Test
    fun theLowerOfNetworkAndPressureWins() {
        var scale: Double? = null
        val ladder = SendResolutionLadder { scale = it }
        ladder.onStats(stats(100_000.0))
        assertEquals(4.0, scale)
        ladder.setPressure(DevicePressure(ThermalLevel.Critical))
        ladder.onStats(stats(100_000.0))
        assertEquals(4.0, scale)
        val roomy = SendResolutionLadder { scale = it }
        roomy.setPressure(DevicePressure(ThermalLevel.Critical))
        roomy.onStats(stats(2_500_000.0))
        assertEquals(2.0, scale)
    }
}
