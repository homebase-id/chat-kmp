package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallNotificationClickRouter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallNotificationClickRouterTest {
    @AfterTest
    fun reset() {
        CallNotificationClickRouter.onOpen = null
    }

    @Test
    fun aClickOnAButtonlessCallNotificationOpensTheRingWindowAndIsNotLeftToTheChatRouter() {
        var opened = 0
        CallNotificationClickRouter.onOpen = { opened++ }
        assertTrue(CallNotificationClickRouter.tryHandle(CallNotificationClickRouter.payload("c1")))
        assertEquals(1, opened)
    }

    @Test
    fun aChatNotificationClickIsLeftToTheChatRouter() {
        var opened = 0
        CallNotificationClickRouter.onOpen = { opened++ }
        assertFalse(CallNotificationClickRouter.tryHandle(mapOf("data" to "{}")))
        assertEquals(0, opened)
    }
}
