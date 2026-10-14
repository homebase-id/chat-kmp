package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallNotificationClicksTest {
    @Test
    fun aClickOnAButtonlessCallNotificationOpensThatCallsRingWindow() {
        val clicks = CallNotificationClicks()
        var opened = 0
        clicks.register("c1", { opened++ })
        assertTrue(clicks.handle(clicks.payload("c1")))
        assertEquals(1, opened)
    }

    @Test
    fun aChatNotificationClickIsNotACallClick() {
        val clicks = CallNotificationClicks()
        var opened = 0
        clicks.register("c1", { opened++ })
        assertFalse(clicks.handle(mapOf("data" to "{}")))
        assertEquals(0, opened)
    }

    @Test
    fun aRetractedCallNoLongerOpens() {
        val clicks = CallNotificationClicks()
        var opened = 0
        clicks.register("c1", { opened++ })
        clicks.forget("c1")
        clicks.handle(clicks.payload("c1"))
        assertEquals(0, opened)
    }
}
