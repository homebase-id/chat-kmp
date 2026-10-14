package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreferredRouteTest {
    private val builtIn = listOf(AudioRoute.Earpiece, AudioRoute.Speaker)

    @Test
    fun aCallStartsOnAHeadsetWhenOneIsThereElseByMedia() {
        assertEquals(AudioRoute.Wired, preferredRoute(builtIn + AudioRoute.Bluetooth + AudioRoute.Wired, emptyList(), null, video = false))
        assertEquals(AudioRoute.Bluetooth, preferredRoute(builtIn + AudioRoute.Bluetooth, emptyList(), null, video = true))
        assertEquals(AudioRoute.Speaker, preferredRoute(builtIn, emptyList(), null, video = true))
        assertEquals(AudioRoute.Earpiece, preferredRoute(builtIn, emptyList(), null, video = false))
    }

    @Test
    fun aHeadsetThatArrivesMidCallTakesItAndLeavingFallsBack() {
        assertEquals(AudioRoute.Wired, preferredRoute(builtIn + AudioRoute.Wired, builtIn, AudioRoute.Speaker, video = false))
        assertEquals(AudioRoute.Earpiece, preferredRoute(builtIn, builtIn + AudioRoute.Wired, AudioRoute.Wired, video = false))
        assertEquals(AudioRoute.Speaker, preferredRoute(builtIn, builtIn + AudioRoute.Bluetooth, AudioRoute.Bluetooth, video = true))
    }

    @Test
    fun anUnrelatedDeviceChangeKeepsTheRouteTheUserPicked() {
        assertNull(preferredRoute(builtIn + AudioRoute.Wired, builtIn + AudioRoute.Wired, AudioRoute.Speaker, video = false))
        assertNull(preferredRoute(builtIn, builtIn + AudioRoute.Bluetooth, AudioRoute.Speaker, video = false))
    }

    @Test
    fun iosOffersOnlyTheRoutesItCanReach() {
        assertEquals(builtIn, offeredRoutes(emptySet()))
        assertEquals(builtIn + AudioRoute.Bluetooth, offeredRoutes(setOf(AudioRoute.Bluetooth)))
        assertEquals(listOf(AudioRoute.Speaker, AudioRoute.Wired), offeredRoutes(setOf(AudioRoute.Wired)))
        assertEquals(listOf(AudioRoute.Speaker, AudioRoute.Bluetooth, AudioRoute.Wired), offeredRoutes(setOf(AudioRoute.Wired, AudioRoute.Bluetooth)))
    }
}
