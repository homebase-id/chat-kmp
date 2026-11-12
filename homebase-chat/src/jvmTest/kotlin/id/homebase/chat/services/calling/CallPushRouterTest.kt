package id.homebase.chat.services.calling

import id.homebase.core.calling.VoipTokenRelay
import id.homebase.core.notifications.CallPushRouter
import id.homebase.core.notifications.PushInterceptor
import id.homebase.core.notifications.PushInterceptors
import id.homebase.core.notifications.PushNotification
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallPushRouterTest {
    private val other = "22222222-2222-2222-2222-222222222222"

    private class Recorder : CallPushRouter.Handler {
        val events = mutableListOf<String>()
        override fun onRing(callId: String, from: String, sentAtMs: Long) { events += "ring $callId $from" }
        override fun onHangup(callId: String, from: String) { events += "hangup $callId $from" }
    }

    @AfterTest
    fun reset() {
        CallPushRouter.handler = null
        PushInterceptors.unregister(listOf(other))
    }

    private fun payload(typeId: String, tagId: String) = mapOf(
        "data" to """{"senderId":"sam","timestamp":0,"options":{"appId":"app","typeId":"$typeId","tagId":"$tagId"}}""",
    )

    @Test
    fun ringAndHangupReachTheCallHandlerThroughTheRegistry() {
        val handler = Recorder().also { CallPushRouter.handler = it }

        assertTrue(PushInterceptors.tryHandle(payload(CallPushRouter.RING_TYPE_ID.uppercase(), "c1")))
        assertTrue(PushInterceptors.tryHandle(payload(CallPushRouter.HANGUP_TYPE_ID, "c1")))

        assertEquals(listOf("ring c1 sam", "hangup c1 sam"), handler.events)
    }

    @Test
    fun noHandlerMeansNothingIsClaimed() {
        assertFalse(PushInterceptors.tryHandle(payload(CallPushRouter.RING_TYPE_ID, "c1")))
        CallPushRouter.handler = Recorder()
        CallPushRouter.handler = null
        assertFalse(PushInterceptors.tryHandle(payload(CallPushRouter.RING_TYPE_ID, "c1")))
    }

    @Test
    fun unrelatedAndMalformedPushesAreNotClaimed() {
        CallPushRouter.handler = Recorder()
        assertFalse(PushInterceptors.tryHandle(payload(other, "c1")))
        assertFalse(PushInterceptors.tryHandle(mapOf("data" to "not json")))
        assertFalse(PushInterceptors.tryHandle(emptyMap()))
    }

    @Test
    fun anotherFeatureRegistersItsOwnTypeIdWithoutTouchingTheCallRouter() {
        val seen = mutableListOf<PushNotification>()
        PushInterceptors.register(listOf(other), PushInterceptor { seen += it })
        val handler = Recorder().also { CallPushRouter.handler = it }

        assertTrue(PushInterceptors.tryHandle(payload(other, "t1")))
        assertTrue(PushInterceptors.tryHandle(payload(CallPushRouter.RING_TYPE_ID, "c1")))

        assertEquals(listOf("t1"), seen.map { it.options.tagId })
        assertEquals(listOf("ring c1 sam"), handler.events)
    }

    @Test
    fun voipTokenArrivingBeforeTheSinkIsReplayedOnAttach() {
        val relay = VoipTokenRelay()
        val received = mutableListOf<String>()

        relay.onToken("t1")
        relay.attach { received += it }
        relay.onToken("t2")

        assertEquals(listOf("t1", "t2"), received)
    }
}
