@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package id.homebase.chat.services.calling

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.liverelay.LiveRelayProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LiveRelayCallSignalingTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"
    private val secret = ByteArray(16)

    private class Captured(val path: String, val body: JsonObject)

    private suspend fun signaling(
        status: HttpStatusCode = HttpStatusCode.NoContent,
        sink: MutableList<Captured>,
        bus: EventBus = EventBus(),
    ): LiveRelayCallSignaling {
        val creds = CredentialsManager()
        creds.setActiveCredentials(
            ApiCredentials.create(OdinId(frodo), "token", SecureByteArray(secret.copyOf())),
        )
        val engine = MockEngine { request ->
            val plain = CryptoHelper.decryptContentAsString(request.body.toByteArray().decodeToString(), secret)
            sink += Captured(request.url.encodedPath, Json.parseToJsonElement(plain).jsonObject)
            respond("", status)
        }
        val provider = LiveRelayProvider(HttpClient(engine), creds)
        return LiveRelayCallSignaling(frodo, { channelKey, recipients, blob, push -> provider.relay(channelKey, recipients, blob, push, wakeCause = null) }, bus)
    }

    @Test
    fun offerIsPostedAsRingWithTheCallIdAsTag() = runTest {
        val sink = mutableListOf<Captured>()
        val s = signaling(sink = sink)
        val offer = CallSignal.Offer("0b1a2c3d-0000-4000-8000-000000000001", "v=0", video = false)

        s.send(CallChannel.outbound(sam, offer))

        val sent = sink.single()
        assertEquals("/api/v2/live-relay", sent.path)
        assertEquals(setOf("channelKey", "recipients", "blob", "push"), sent.body.keys)
        assertEquals(CallChannel.keyFor(sam), sent.body.getValue("channelKey").jsonPrimitive.content)
        assertEquals(listOf(sam), (sent.body.getValue("recipients") as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content })
        assertEquals(offer, CallSignalCodec.decode(sent.body.getValue("blob").jsonPrimitive.content))
        val push = sent.body.getValue("push").jsonObject
        assertEquals(setOf("kind", "typeId", "tagId", "unEncryptedMessage"), push.keys)
        assertEquals("ring", push.getValue("kind").jsonPrimitive.content)
        assertEquals(CallChannel.RING_TYPE_ID, push.getValue("typeId").jsonPrimitive.content)
        assertEquals(offer.callId, push.getValue("tagId").jsonPrimitive.content)
        assertEquals("Incoming call", push.getValue("unEncryptedMessage").jsonPrimitive.content)
    }

    @Test
    fun cancelledHangupRetractsWithKindHangupAndSameTag() = runTest {
        val sink = mutableListOf<Captured>()
        val s = signaling(sink = sink)
        s.send(CallChannel.outbound(sam, CallSignal.Hangup("call-1", HangupReason.Cancelled)))
        val push = sink.single().body.getValue("push").jsonObject
        assertEquals("hangup", push.getValue("kind").jsonPrimitive.content)
        assertEquals("call-1", push.getValue("tagId").jsonPrimitive.content)
    }

    @Test
    fun answerAndIceCarryNoPushObject() = runTest {
        val sink = mutableListOf<Captured>()
        val s = signaling(sink = sink)
        s.send(CallChannel.outbound(sam, CallSignal.Answer("c", "sdp")))
        s.send(CallChannel.outbound(sam, CallSignal.Ice("c", "cand")))
        s.send(CallChannel.outbound(sam, CallSignal.Hangup("c", HangupReason.Normal)))
        assertEquals(3, sink.size)
        assertTrue(sink.none { "push" in it.body.keys })
    }

    @Test
    fun refusedOfferThrowsButRefusedIceDoesNot() = runTest {
        val sink = mutableListOf<Captured>()
        val s = signaling(status = HttpStatusCode.BadRequest, sink = sink)
        s.send(CallChannel.outbound(sam, CallSignal.Ice("c", "cand")))
        assertFailsWith<CallEngineException> {
            s.send(CallChannel.outbound(sam, CallSignal.Offer("c", "sdp", false)))
        }
    }

    @Test
    fun relayNotificationOnOwnChannelDecodesToInboundSignalWithSenderAndReceivedAt() = runTest {
        val bus = EventBus()
        val s = signaling(sink = mutableListOf(), bus = bus)
        val offer = CallSignal.Offer("c9", "sdp", true)
        val got = mutableListOf<InboundSignal>()
        val job = launch { s.incoming.collect { got += it } }
        runCurrent()

        bus.emit(BackendEvent.LiveRelayReceived(OdinId(sam), CallChannel.keyFor(frodo), CallSignalCodec.encode(offer), 1_700L))
        bus.emit(BackendEvent.LiveRelayReceived(OdinId(sam), "7a1e9c40-1b2c-4d3e-9f50-0a1b2c3d4e5f", CallSignalCodec.encode(offer), 1L))
        bus.emit(BackendEvent.LiveRelayReceived(OdinId(sam), CallChannel.keyFor(frodo), "not-a-signal", 2L))
        runCurrent()
        job.cancel()

        assertEquals(listOf(InboundSignal(sam, offer, 1_700L)), got)
    }

    @Test
    fun everySignalGoesOutAsExactJsonOnTheWireWithTheDocumentedPushKinds() = runTest {
        val sink = mutableListOf<Captured>()
        val s = signaling(sink = sink)
        val id = "0b1a2c3d-0000-4000-8000-000000000001"
        val cases = listOf(
            CallSignal.Offer(id, "v=0", video = true) to """{"type":"offer","callId":"$id","sdp":"v=0","video":true}""",
            CallSignal.Offer(id, "v=0", video = true, seq = 2) to """{"type":"offer","callId":"$id","sdp":"v=0","video":true,"seq":2}""",
            CallSignal.Ringing(id) to """{"type":"ringing","callId":"$id"}""",
            CallSignal.Answer(id, "v=0") to """{"type":"answer","callId":"$id","sdp":"v=0"}""",
            CallSignal.Ice(id, "candidate:1", "0", 0) to """{"type":"ice","callId":"$id","candidate":"candidate:1","sdpMid":"0","sdpMLineIndex":0}""",
            CallSignal.Hangup(id, HangupReason.Cancelled) to """{"type":"hangup","callId":"$id","reason":"Cancelled"}""",
            CallSignal.Hangup(id, HangupReason.Missed) to """{"type":"hangup","callId":"$id","reason":"Missed"}""",
            CallSignal.Hangup(id, HangupReason.Declined) to """{"type":"hangup","callId":"$id","reason":"Declined"}""",
            CallSignal.Hangup(id) to """{"type":"hangup","callId":"$id"}""",
        )
        val pushKinds = listOf("ring", null, null, null, null, "hangup", "hangup", null, null)

        cases.forEach { (signal, _) -> s.send(CallChannel.outbound(sam, signal)) }

        assertEquals(cases.size, sink.size)
        sink.forEachIndexed { i, sent ->
            val blob = sent.body.getValue("blob").jsonPrimitive.content
            assertEquals(cases[i].second, kotlin.io.encoding.Base64.decode(blob).decodeToString(), "blob #$i")
            assertEquals(pushKinds[i], sent.body["push"]?.jsonObject?.getValue("kind")?.jsonPrimitive?.content, "push kind #$i")
        }
        val ring = sink.first().body.getValue("push").jsonObject
        val hangup = sink[5].body.getValue("push").jsonObject
        assertEquals(CallChannel.RING_TYPE_ID, ring.getValue("typeId").jsonPrimitive.content)
        assertEquals(CallChannel.HANGUP_TYPE_ID, hangup.getValue("typeId").jsonPrimitive.content)
        assertEquals(id, hangup.getValue("tagId").jsonPrimitive.content)
    }

    @Test
    fun ringAndHangupPushTypeIdsAreDistinctWellFormedAndSingleSourced() {
        val guid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        assertTrue(guid.matches(CallChannel.RING_TYPE_ID))
        assertTrue(guid.matches(CallChannel.HANGUP_TYPE_ID))
        assertTrue(CallChannel.RING_TYPE_ID != CallChannel.HANGUP_TYPE_ID)
        assertEquals(id.homebase.core.notifications.CallPushRouter.RING_TYPE_ID, CallChannel.RING_TYPE_ID)
        assertEquals(id.homebase.core.notifications.CallPushRouter.HANGUP_TYPE_ID, CallChannel.HANGUP_TYPE_ID)
    }
}
