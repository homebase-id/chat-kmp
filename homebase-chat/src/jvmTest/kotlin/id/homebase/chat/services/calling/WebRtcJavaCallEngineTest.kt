package id.homebase.chat.services.calling

import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real webrtc-java natives on real UDP loopback; also proves the natives load on this host. */
class WebRtcJavaCallEngineTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    // A blocking native call cannot be interrupted, so the body runs on a daemon thread that is abandoned on timeout; the test fails with its stack.
    private fun runWithin(timeoutMs: Long, body: () -> Unit) {
        var failure: Throwable? = null
        val worker = Thread({ try { body() } catch (e: Throwable) { failure = e } }, "native-test").apply { isDaemon = true }
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) throw AssertionError("test body still running after $timeoutMs ms:\n" + worker.stackTrace.joinToString("\n") { "  at $it" })
        failure?.let { throw it }
    }

    private fun nativeTest(body: suspend CoroutineScope.() -> Unit) = runWithin(180_000) { runBlocking(block = body) }

    private fun headless() = CallEngineFactory { stun -> WebRtcJavaCallEngine(stun, HeadlessAudioDeviceModule()) }

    @Test
    fun twoRealEnginesOverLoopbackSignalingReachConnectedAndFingerprintsMatch() = nativeTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val net = LoopbackSignaling()
            val caller = CallController(frodo, net.endpoint(frodo), headless(), scope)
            val callee = CallController(sam, net.endpoint(sam), headless(), scope)

            caller.startCall(sam, video = false)
            withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
            callee.accept()

            withTimeout(30_000) {
                caller.state.first { it.phase == CallPhase.Connected }
                callee.state.first { it.phase == CallPhase.Connected }
            }
            val callerCode = withTimeout(5_000) { caller.state.first { it.verificationCode != null } }.verificationCode
            val calleeCode = withTimeout(5_000) { callee.state.first { it.verificationCode != null } }.verificationCode
            assertNotNull(callerCode)
            assertEquals(callerCode, calleeCode)

            caller.hangup()
            withTimeout(10_000) { callee.state.first { it.phase is CallPhase.Ended } }
            assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = true)), callee.state.value.phase)
            caller.close()
            callee.close()
        } finally {
            // Join so every controller teardown (engine.close, native dispose) finishes before the next test starts.
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun engineReportsConnectedAndSurvivesAnIceRestart() = nativeTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        val b = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        try {
            val iceA = Channel<MediaEvent.LocalIce>(Channel.UNLIMITED)
            val iceB = Channel<MediaEvent.LocalIce>(Channel.UNLIMITED)
            val connectedA = CompletableDeferred<Unit>()
            val connectedB = CompletableDeferred<Unit>()
            fun watch(e: WebRtcJavaCallEngine, ice: Channel<MediaEvent.LocalIce>, connected: CompletableDeferred<Unit>) =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    e.events.collect {
                        if (it is MediaEvent.LocalIce) ice.trySend(it)
                        if (it == MediaEvent.Connected) connected.complete(Unit)
                    }
                }
            watch(a, iceA, connectedA)
            watch(b, iceB, connectedB)

            val offer = a.createOffer(video = false)
            assertTrue(offer.startsWith("v=0"))
            val answer = b.answer(offer, video = false)
            a.applyRemote(answer)
            scope.launch { for (c in iceA) b.addIce(c.candidate, c.sdpMid, c.sdpMLineIndex) }
            scope.launch { for (c in iceB) a.addIce(c.candidate, c.sdpMid, c.sdpMLineIndex) }
            withTimeout(30_000) { connectedA.await(); connectedB.await() }

            val restart = a.restartIce()
            assertTrue(restart.contains("a=ice-ufrag"))
            a.applyRemote(b.answerRestart(restart))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            a.close()
            b.close()
            // A candidate that lands after close() must be dropped, not hit a disposed native connection.
            a.addIce("candidate:1 1 udp 2122260223 127.0.0.1 50000 typ host", "0", 0)
            a.close()
        }
    }

    @Test
    fun deviceListIsReportedGracefullyAndCloseUnregistersTheListenerTwiceOver() = nativeTest {
        val engine = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        val devices = engine.mediaDevices.devices.value
        val selected = engine.mediaDevices.selected.value
        MediaDeviceKind.entries.forEach { kind ->
            val ofKind = devices.filter { it.kind == kind }
            if (ofKind.isEmpty()) assertEquals(null, selected[kind]) else assertTrue(ofKind.any { it.id == selected[kind] }, "$kind has a selection")
        }
        engine.close()
        engine.close()
    }

    @Test
    fun aDeviceListenerTeardownThatNeverReturnsDoesNotBlockClose() = nativeTest {
        val engine = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        val registered = engine.listenerRegistered.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.unregisterDeviceListener = { entered.countDown(); release.await(); MediaDevices.removeDeviceChangeListener(it) }
        try {
            val start = System.nanoTime()
            runWithin(10_000) { engine.close() }
            val tookMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(tookMs < 3_000, "close took $tookMs ms")
            if (registered) assertTrue(entered.count == 0L, "close never reached the listener teardown")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aNativeCallThatNeverReturnsFailsTheTestInsteadOfBlockingGradle() {
        val failure = assertFailsWith<AssertionError> { runWithin(200) { Thread.sleep(Long.MAX_VALUE) } }
        assertTrue("still running after 200 ms" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun switchingMicAndSpeakerOnAConnectedCallKeepsItConnectedWithTheTrackEnabled() = nativeTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engines = mutableListOf<WebRtcJavaCallEngine>()
        val factory = CallEngineFactory { stun -> WebRtcJavaCallEngine(stun, HeadlessAudioDeviceModule()).also { engines += it } }
        try {
            val net = LoopbackSignaling()
            val caller = CallController(frodo, net.endpoint(frodo), factory, scope)
            val callee = CallController(sam, net.endpoint(sam), headless(), scope)
            caller.startCall(sam, video = false)
            withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
            callee.accept()
            withTimeout(30_000) { caller.state.first { it.phase == CallPhase.Connected } }

            val devices = withTimeout(5_000) { caller.mediaDevices.first { it != null } }!!
            val offersBefore = net.sent.count { it.signal is CallSignal.Offer }
            for (kind in listOf(MediaDeviceKind.Mic, MediaDeviceKind.Speaker)) {
                val other = devices.devices.value.firstOrNull { it.kind == kind && it.id != devices.selected.value[kind] } ?: continue
                devices.select(kind, other.id)
                // A host that refuses the switch keeps its selection; either way the call must carry on.
                assertEquals(CallPhase.Connected, caller.state.value.phase)
            }
            assertTrue(engines.single().isAudioEnabled())
            assertEquals(offersBefore, net.sent.count { it.signal is CallSignal.Offer }, "device switches never renegotiate")

            caller.hangup()
            caller.close()
            callee.close()
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun switchingCameraOnAVideoCallSwapsTheSourceWithoutANewOffer() = nativeTest {
        val cameras = runCatching { dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices() }.getOrDefault(emptyList())
        if (cameras.size < 2) return@nativeTest
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val net = LoopbackSignaling()
            val caller = CallController(frodo, net.endpoint(frodo), headless(), scope)
            val callee = CallController(sam, net.endpoint(sam), headless(), scope)
            caller.startCall(sam, video = true)
            withTimeout(15_000) { callee.state.first { it.phase == CallPhase.Incoming && it.pendingOfferSdp != null } }
            callee.accept()
            withTimeout(30_000) { caller.state.first { it.phase == CallPhase.Connected } }

            val devices = withTimeout(5_000) { caller.mediaDevices.first { it != null } }!!
            val current = devices.selected.value[MediaDeviceKind.Camera]
            val other = devices.devices.value.first { it.kind == MediaDeviceKind.Camera && it.id != current }
            val offersBefore = net.sent.count { it.signal is CallSignal.Offer }
            devices.select(MediaDeviceKind.Camera, other.id)
            assertEquals(CallPhase.Connected, caller.state.value.phase)
            assertEquals(offersBefore, net.sent.count { it.signal is CallSignal.Offer })
            caller.hangup()
            caller.close()
            callee.close()
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
