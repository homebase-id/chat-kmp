package id.homebase.agent

import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.websockets.ClientNotificationPayload
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.calling.CallController
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallEngine
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.LoopbackSignaling
import id.homebase.chat.services.calling.WebRtcJavaCallEngine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CallsTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private fun runWithin(timeoutMs: Long, body: () -> Unit) {
        var failure: Throwable? = null
        val worker = Thread({ try { body() } catch (e: Throwable) { failure = e } }, "native-test").apply { isDaemon = true }
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) throw AssertionError("test body still running after $timeoutMs ms:\n" + worker.stackTrace.joinToString("\n") { "  at $it" })
        failure?.let { throw it }
    }

    private fun nativeTest(body: suspend CoroutineScope.() -> Unit) = runWithin(180_000) { runBlocking(block = body) }

    private fun tone(ms: Int, hz: Double = 440.0): ByteArray {
        val samples = CALL_SAMPLE_RATE * ms / 1000
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * hz * i / CALL_SAMPLE_RATE) * 0.5 * Short.MAX_VALUE).toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private class Engines(private val make: () -> WebRtcJavaCallEngine) {
        val created = CopyOnWriteArrayList<WebRtcJavaCallEngine>()
        val factory = CallEngineFactory { stun -> make().also { created += it } }
    }

    @Test
    fun calleeHearsThePlayedToneAndBothSidesEndCleanly() = nativeTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callerPlayer = PcmPlayer()
        val calleePlayer = PcmPlayer()
        val callerRemote = RemoteAudio()
        val calleeRemote = RemoteAudio()
        try {
            val net = LoopbackSignaling()
            val callerEngines = Engines { WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule(), audioSource = { callerPlayer.source }, remoteAudioSink = callerRemote, renderVideo = false) }
            val calleeEngines = Engines { WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule(), audioSource = { calleePlayer.source }, remoteAudioSink = calleeRemote, renderVideo = false) }
            val caller = CallController(frodo, net.endpoint(frodo), callerEngines.factory, scope)
            val callee = CallController(sam, net.endpoint(sam), calleeEngines.factory, scope)

            caller.startCall(sam, video = false)
            withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
            callee.accept()
            withTimeout(30_000) {
                caller.state.first { it.phase == CallPhase.Connected }
                callee.state.first { it.phase == CallPhase.Connected }
            }

            val heard = async { withTimeout(15_000) { calleeRemote.frames.first { rms(it) > 0.05 } } }
            val played = async { callerPlayer.play(tone(2_000)) }
            val frame = heard.await()
            assertTrue(rms(frame) > 0.05, "callee heard rms ${rms(frame)}")
            played.await()

            caller.hangup()
            withTimeout(10_000) {
                caller.state.first { it.phase is CallPhase.Ended }
                callee.state.first { it.phase is CallPhase.Ended }
            }
            caller.close()
            callee.close()
            assertEquals(1, callerEngines.created.size)
            assertEquals(1, calleeEngines.created.size)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            callerPlayer.stop()
            calleePlayer.stop()
        }
    }

    @Test
    fun renderVideoFalseNeverBuildsARemoteVideoHandleEvenWhenThePeerOffersVideo() = nativeTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = PcmPlayer()
        try {
            val net = LoopbackSignaling()
            val callerEngines = Engines { WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule(), cameraSource = { CustomVideoSource() }) }
            val calleeEngines = Engines { WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule(), audioSource = { player.source }, remoteAudioSink = RemoteAudio(), renderVideo = false) }
            val caller = CallController(frodo, net.endpoint(frodo), callerEngines.factory, scope)
            val callee = CallController(sam, net.endpoint(sam), calleeEngines.factory, scope)

            caller.startCall(sam, video = true)
            withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
            callee.accept()
            withTimeout(30_000) { callee.state.first { it.phase == CallPhase.Connected } }

            assertNull(calleeEngines.created.single().remoteVideo.value)
            assertNull(calleeEngines.created.single().localVideo.value)
            caller.hangup()
            withTimeout(10_000) { callee.state.first { it.phase is CallPhase.Ended } }
            caller.close()
            callee.close()
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            player.stop()
        }
    }

    @Test
    fun relayEventsDecodesALiveRelayNotificationAndTheHandshakeStunUrls() {
        val relay = OdinSystemSerializer.deserialize<ClientNotificationPayload>(
            """{"notificationType":"liveRelay","data":"{\"senderOdinId\":\"$frodo\",\"channelKey\":\"call-abc\",\"blob\":\"AAEC\",\"receivedAt\":1700000000123}"}""",
        )
        val events = relayEvents(relay)
        val event = events.single() as BackendEvent.LiveRelayReceived
        assertEquals(frodo, event.senderOdinId.domainName)
        assertEquals("call-abc", event.channelKey)
        assertEquals("AAEC", event.blob)
        assertEquals(1700000000123L, event.receivedAt)

        val handshake = OdinSystemSerializer.deserialize<ClientNotificationPayload>(
            """{"notificationType":"deviceHandshakeSuccess","data":"{}","stunUrls":["stun:stun.example.com:3478","turn:turn.example.com:3478"]}""",
        )
        assertEquals(listOf<BackendEvent>(BackendEvent.StunUrlsReceived(listOf("stun:stun.example.com:3478", "turn:turn.example.com:3478"))), relayEvents(handshake))

        assertTrue(relayEvents(ClientNotificationPayload(notificationType = id.homebase.api.client.websockets.ClientNotificationType.deviceHandshakeSuccess)).isEmpty())
        assertTrue(relayEvents(OdinSystemSerializer.deserialize("""{"notificationType":"liveRelay","data":"not json"}""")).isEmpty())
        assertTrue(relayEvents(ClientNotificationPayload(notificationType = id.homebase.api.client.websockets.ClientNotificationType.pong)).isEmpty())
    }

    @Test
    fun pacerPushesTenMillisecondFramesAndSilenceWhenIdle() = runBlocking {
        val pushed = ArrayList<ByteArray>()
        val pacer = PcmPacer(CALL_SAMPLE_RATE) { pushed += it }
        pacer.tick()
        assertEquals(1, pushed.size)
        assertEquals(960, pushed[0].size)
        assertTrue(pushed[0].all { it == 0.toByte() })

        val pcm = ByteArray(960 * 2 + 400) { 7 }
        val done = pacer.enqueue(pcm)
        pacer.tick(); pacer.tick()
        assertTrue(!done.isCompleted)
        pacer.tick()
        assertTrue(done.isCompleted)
        assertEquals(4, pushed.size)
        assertTrue(pushed[1].all { it == 7.toByte() })
        assertTrue(pushed[3].take(400).all { it == 7.toByte() } && pushed[3].drop(400).all { it == 0.toByte() })
        assertTrue(pacer.idle)

        pacer.tick()
        assertTrue(pushed[4].all { it == 0.toByte() })

        val dropped = pacer.enqueue(ByteArray(9_600))
        pacer.flush()
        assertTrue(dropped.isCompleted)
        assertTrue(pacer.idle)
    }

    private val owner = OdinId(sam)

    private fun driver(
        calls: CallSession,
        operators: Set<OdinId> = setOf(owner),
        enabled: Boolean = true,
        transport: Transport = Transport.AUTO,
        synthesize: suspend (String) -> ByteArray? = { ByteArray(960) },
        play: suspend (ByteArray) -> Unit = {},
        connectTimeoutMs: Long = 2_000,
    ) = OwnerCallDriver(operators, enabled, transport, calls, synthesize, play, connectTimeoutMs, settleMs = 5, tailMs = 5)

    private class ScriptedCalls(val outcome: (ScriptedCalls) -> Unit) : CallSession {
        private val _state = MutableStateFlow(CallState())
        override val state: StateFlow<CallState> = _state.asStateFlow()
        override val transitions: Flow<CallState> = _state
        override val videos: StateFlow<CallVideos> = MutableStateFlow(CallVideos())
        val started = CopyOnWriteArrayList<String>()
        val hangups = CopyOnWriteArrayList<Unit>()
        private var seq = 0

        fun phase(phase: CallPhase) {
            _state.value = CallState(phase = phase, callId = "call-$seq", peer = started.lastOrNull())
        }

        override fun startCall(peer: String, video: Boolean) {
            started += peer
            seq++
            phase(CallPhase.Outgoing)
            outcome(this)
        }

        override fun accept() = Unit
        override fun decline() = Unit
        override fun hangup() { hangups += Unit }
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideo(on: Boolean) = Unit
        override fun switchCamera() = Unit
        override fun setAudioRoute(route: AudioRoute) = Unit
    }

    @Test
    fun guardRefusesNonOperatorsCallsOffAndPollTransportWithoutDialing() = runBlocking {
        val calls = ScriptedCalls { it.phase(CallPhase.Connected) }
        var synthesized = 0
        val synth: suspend (String) -> ByteArray? = { synthesized++; ByteArray(960) }
        assertEquals(CallOutcome.UNAVAILABLE, driver(calls, operators = setOf(OdinId("someone.else.demo.rocks")), synthesize = synth).call(owner, "hi"))
        assertEquals(CallOutcome.UNAVAILABLE, driver(calls, enabled = false, synthesize = synth).call(owner, "hi"))
        assertEquals(CallOutcome.UNAVAILABLE, driver(calls, transport = Transport.POLL, synthesize = synth).call(owner, "hi"))
        assertTrue(calls.started.isEmpty())
        assertEquals(0, synthesized)
    }

    @Test
    fun answeredCallPlaysTheMessageThenHangsUp() = runBlocking {
        val calls = ScriptedCalls { it.phase(CallPhase.Connected) }
        val played = CopyOnWriteArrayList<Int>()
        val outcome = driver(calls, play = { played += it.size }).call(owner, "hi")
        assertEquals(CallOutcome.ANSWERED, outcome)
        assertEquals(listOf(960), played)
        assertEquals(listOf(sam), calls.started)
        assertEquals(1, calls.hangups.size)
    }

    @Test
    fun endedBeforeConnectingMapsTheReason() = runBlocking {
        fun outcomeFor(reason: CallEndReason): CallOutcome {
            val calls = ScriptedCalls { it.phase(CallPhase.Ended(reason)) }
            return runBlocking { driver(calls).call(owner, "hi") }
        }
        assertEquals(CallOutcome.MISSED, outcomeFor(CallEndReason.Missed))
        assertEquals(CallOutcome.DECLINED, outcomeFor(CallEndReason.Declined))
        assertEquals(CallOutcome.FAILED, outcomeFor(CallEndReason.Failed(id.homebase.chat.services.calling.FailReason.Other)))
    }

    @Test
    fun staleEndedStateFromAnEarlierCallDoesNotSettleTheNextOne() = runBlocking {
        val calls = ScriptedCalls { }
        calls.phase(CallPhase.Ended(CallEndReason.Declined))
        assertEquals(CallOutcome.MISSED, driver(calls, connectTimeoutMs = 300).call(owner, "hi"))
        assertEquals(1, calls.hangups.size)
    }

    @Test
    fun aSecondRequestWhileOneIsInProgressIsUnavailable() = runBlocking {
        val calls = ScriptedCalls { it.phase(CallPhase.Connected) }
        val gate = CompletableDeferred<ByteArray?>()
        val d = driver(calls, synthesize = { gate.await() })
        val first = async { d.call(owner, "one") }
        delay(100)
        assertEquals(CallOutcome.UNAVAILABLE, d.call(owner, "two"))
        gate.complete(ByteArray(960))
        assertEquals(CallOutcome.ANSWERED, first.await())
        assertEquals(CallOutcome.ANSWERED, d.call(owner, "three"))
    }

    @Test
    fun failedSynthesisNeverDials() = runBlocking {
        val calls = ScriptedCalls { it.phase(CallPhase.Connected) }
        assertEquals(CallOutcome.FAILED, driver(calls, synthesize = { null }).call(owner, "hi"))
        assertTrue(calls.started.isEmpty())
    }

    @Test
    fun hangupDuringTheMessageStopsPlayingAndStillCountsAsAnswered() = runBlocking {
        val calls = ScriptedCalls { it.phase(CallPhase.Connected) }
        var cancelled = false
        val outcome = driver(calls, play = {
            launchEnd(calls)
            try { delay(10_000) } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
        }).call(owner, "hi")
        assertEquals(CallOutcome.ANSWERED, outcome)
        assertTrue(cancelled)
    }

    private fun launchEnd(calls: ScriptedCalls) {
        Thread { Thread.sleep(100); calls.phase(CallPhase.Ended(CallEndReason.Hangup(remote = true))) }.apply { isDaemon = true }.start()
    }

    @Test
    fun remoteAudioIsDownmixedAndResampledTo48kMono() {
        val stereo = ShortArray(960) { if (it % 2 == 0) 1000 else 3000 }
        assertEquals(480, toMono48k(stereo, CALL_SAMPLE_RATE, 2).size)
        assertEquals(2000, toMono48k(stereo, CALL_SAMPLE_RATE, 2)[0].toInt())
        assertEquals(960, toMono48k(ShortArray(480), 24_000, 1).size)
    }
}
