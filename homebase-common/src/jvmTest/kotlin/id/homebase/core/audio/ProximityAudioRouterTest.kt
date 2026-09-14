package id.homebase.core.audio

import kotlin.test.Test
import kotlin.test.assertFalse

class ProximityAudioRouterTest {

    @Test
    fun `desktop router is inert and start_stop are idempotent`() {
        val router = getProximityAudioRouter()
        assertFalse(router.isNearEar.value)

        router.start()
        router.start()
        assertFalse(router.isNearEar.value)

        router.stop()
        router.stop()
        assertFalse(router.isNearEar.value)
    }
}
