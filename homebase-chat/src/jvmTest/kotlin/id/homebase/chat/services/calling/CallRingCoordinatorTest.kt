package id.homebase.chat.services.calling

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.StunUrlStore
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.core.notifications.CallPushRouter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallRingCoordinatorTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class FakeSink : CallRingSink {
        val events = mutableListOf<String>()
        override fun ring(callId: String, label: String) { events += "ring:$callId:$label" }
        override fun dialing(callId: String, peer: String) { events += "dialing:$callId:$peer" }
        override fun stopRinging(callId: String) { events += "stop:$callId" }
        override fun connected(callId: String) { events += "connected:$callId" }
        override fun ended(callId: String, reason: CallEndReason) { events += "ended:$callId" }
        override fun setLive(live: Boolean, video: Boolean, callId: String?) { events += "live:$live:$callId" }
        val ringing: Boolean
            get() = events.lastOrNull { it.startsWith("ring:") || it.startsWith("stop:") }?.startsWith("ring:") == true
    }

    private class Rig(scope: TestScope, selfId: String, labelFor: suspend (String) -> String?) {
        val sink = FakeSink()
        val controller = CallController(
            selfOdinId = selfId,
            signaling = LoopbackSignaling(now = { scope.testScheduler.currentTime }).endpoint(selfId),
            engineFactory = CallEngineFactory { _ ->
                FakeCallEngine(scope.backgroundScope, FakeCallScript(), peerLabel = "peer", selfLabel = selfId)
            },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val coordinator = CallRingCoordinator(
            target = object : CallPushTarget {
                override fun pushRing(callId: String, from: String, sentAtMs: Long) = controller.pushRing(callId, from, sentAtMs)
                override fun onPushHangup(callId: String) = controller.onPushHangup(callId)
                override fun accept() = controller.accept()
                override fun decline() = controller.decline()
            },
            transitions = controller.transitions,
            sink = sink,
            labelFor = labelFor,
            scope = scope.backgroundScope,
        ).also { it.start(); CallPushRouter.handler = it }
    }

    @AfterTest
    fun clearHandler() {
        CallPushRouter.handler = null
    }

    private fun payload(typeId: String, callId: String, from: String = sam, message: String? = null): Map<String, String> {
        val msg = message?.let { ""","unEncryptedMessage":"$it"""" }.orEmpty()
        return mapOf(
            "data" to """{"senderId":"$from","timestamp":0,"options":{"appId":"app","typeId":"$typeId","tagId":"$callId"$msg}}""",
        )
    }

    private fun ring(callId: String) = CallPushRouter.tryHandle(payload(CallPushRouter.RING_TYPE_ID, callId, message = "Sam is calling"))
    private fun hangup(callId: String) = CallPushRouter.tryHandle(payload(CallPushRouter.HANGUP_TYPE_ID, callId))

    @Test
    fun ringPushPostsTheRingAndHangupPushRetractsIt() = runTest {
        val rig = Rig(this, frodo) { "Sam" }
        assertTrue(ring("c1"))
        runCurrent()
        assertEquals(listOf("ring:c1:$sam", "ring:c1:Sam"), rig.sink.events.filter { it.startsWith("ring") })
        assertTrue(rig.sink.ringing)

        assertTrue(hangup("c1"))
        runCurrent()
        assertFalse(rig.sink.ringing)
        assertEquals("stop:c1", rig.sink.events.last { it.startsWith("stop") })
    }

    @Test
    fun hangupDuringTheNameLookupLeavesNothingRinging() = runTest {
        val lookup = CompletableDeferred<String?>()
        val rig = Rig(this, frodo) { lookup.await() }
        ring("c1")
        runCurrent()
        hangup("c1")
        runCurrent()
        lookup.complete("Sam")
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(rig.sink.ringing)
        assertEquals(listOf("ring:c1:$sam", "stop:c1"), rig.sink.events.filter { it.startsWith("ring") || it.startsWith("stop") })
    }

    @Test
    fun ringWhileAlreadyInACallNeverRings() = runTest {
        val rig = Rig(this, frodo) { "Sam" }
        rig.controller.startCall("merry.demo.rocks", video = false)
        runCurrent()
        ring("c2")
        runCurrent()
        assertTrue(rig.sink.events.none { it.startsWith("ring:") || it.startsWith("stop:") })
    }

    @Test
    fun acceptAndDeclineBothStopTheRing() = runTest {
        val accepted = Rig(this, frodo) { null }
        ring("c1")
        runCurrent()
        accepted.controller.accept()
        runCurrent()
        assertFalse(accepted.sink.ringing)
        assertTrue("live:true:c1" in accepted.sink.events)

        CallPushRouter.handler = null
        val declined = Rig(this, frodo) { null }
        ring("c3")
        runCurrent()
        declined.controller.decline()
        runCurrent()
        assertFalse(declined.sink.ringing)
        assertTrue("ended:c3" in declined.sink.events)
    }

    private class ServiceRig(scope: TestScope) {
        val sink = FakeSink()
        val credentials = CredentialsManager()
        val relayed = mutableListOf<String>()
        val service = CallService(
            credentialsManager = credentials,
            eventBus = EventBus(),
            relay = { _, _, blob, _ -> relayed += blob; true },
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope, FakeCallScript(), "peer", "me") },
            stunUrls = StunUrlStore(EventBus(), kotlinx.coroutines.flow.flowOf(null), scope.backgroundScope),
            scope = scope.backgroundScope,
        )
        val coordinator = CallRingCoordinator(service, service.transitions, sink, { null }, scope.backgroundScope)
            .also { it.start(); CallPushRouter.handler = it }

        suspend fun signIn() = credentials.setActiveCredentials(
            ApiCredentials.create(OdinId("frodo.baggins.demo.rocks"), "token", SecureByteArray(ByteArray(16))),
        )
    }

    @Test
    fun ringAndHangupPushedBeforeTheIdentityBindsEndTheCallCancelledWithNoRingLeft() = runTest {
        val rig = ServiceRig(this)
        runCurrent()
        assertTrue(ring("c1"))
        assertTrue(hangup("c1"))
        runCurrent()
        assertEquals(CallPhase.Idle, rig.service.state.value.phase)

        rig.signIn()
        runCurrent()
        assertEquals(CallPhase.Ended(CallEndReason.Cancelled), rig.service.state.value.phase)
        assertFalse(rig.sink.ringing)
    }

    @Test
    fun signingOutWhileACallRingsDeclinesItToThePeer() = runTest {
        val rig = ServiceRig(this)
        rig.signIn()
        runCurrent()
        assertTrue(ring("c1"))
        runCurrent()
        assertEquals(CallPhase.Incoming, rig.service.state.value.phase)
        val before = rig.relayed.size

        rig.credentials.removeAllCredentials()
        runCurrent()
        assertTrue(rig.relayed.size > before, "a hangup was relayed to the caller")
    }

    @Test
    fun ringPushedBeforeTheIdentityBindsRingsOnceItDoes() = runTest {
        val rig = ServiceRig(this)
        runCurrent()
        assertTrue(ring("c1"))
        runCurrent()
        assertFalse(rig.sink.ringing)

        rig.signIn()
        runCurrent()
        assertEquals(CallPhase.Incoming, rig.service.state.value.phase)
        assertTrue(rig.sink.ringing)
        assertTrue(rig.service.state.value.isRingingFor("c1"))
    }

    @Test
    fun aRingTheOsAcknowledgesAfterTheHangupIsNotRingableAnyMore() = runTest {
        val rig = Rig(this, frodo) { null }
        ring("c1")
        runCurrent()
        assertTrue(rig.controller.state.value.isRingingFor("c1"))
        hangup("c1")
        runCurrent()
        assertTrue(rig.sink.events.any { it == "ring:c1:$sam" })
        assertFalse(rig.controller.state.value.isRingingFor("c1"))
        assertFalse(rig.sink.ringing)
    }

    @Test
    fun aCallPushWithNoHandlerYetFallsThroughToTheChatNotification() {
        CallPushRouter.handler = null
        assertFalse(ring("c1"))
        assertFalse(hangup("c1"))
    }

    @Test
    fun nonCallPayloadsAreNotConsumed() {
        assertFalse(CallPushRouter.tryHandle(payload("11111111-1111-1111-1111-111111111111", "c1")))
        assertFalse(CallPushRouter.tryHandle(mapOf("data" to "not json")))
        assertFalse(CallPushRouter.tryHandle(emptyMap()))
    }

    @Test
    fun manifestDeclaresTheCallingPermissionsAndServices() {
        val manifest = listOf("../androidApp/src/main/AndroidManifest.xml", "androidApp/src/main/AndroidManifest.xml")
            .map(::File).first { it.exists() }.readText()
        listOf(
            "MANAGE_OWN_CALLS", "USE_FULL_SCREEN_INTENT", "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_CAMERA",
            "RECORD_AUDIO", "POST_NOTIFICATIONS",
        ).forEach { assertTrue("android.permission.$it" in manifest, it) }
        assertTrue("android.telecom.ConnectionService" in manifest)
        assertTrue("microphone|camera" in manifest)
    }

    @Test
    fun aPlacedCallIsReportedAsDialingOnceAndARingNever() = runTest {
        val rig = Rig(this, frodo) { null }
        rig.controller.startCall(sam, video = false, callId = "out1")
        runCurrent()
        rig.controller.setMuted(true)
        runCurrent()
        assertEquals(listOf("dialing:out1:$sam"), rig.sink.events.filter { it.startsWith("dialing") })
        rig.controller.hangup()
        runCurrent()

        assertTrue(ring("in1"))
        runCurrent()
        assertEquals(listOf("dialing:out1:$sam"), rig.sink.events.filter { it.startsWith("dialing") })
    }
}

