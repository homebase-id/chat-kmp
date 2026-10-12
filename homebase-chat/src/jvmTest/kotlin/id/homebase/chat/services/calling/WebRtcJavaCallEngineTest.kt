package id.homebase.chat.services.calling

import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private fun headless() = CallEngineFactory { stun -> WebRtcJavaCallEngine(stun, HeadlessAudioDeviceModule()) }

    @Test
    fun twoRealEnginesOverLoopbackSignalingReachConnectedAndFingerprintsMatch() = runBlocking {
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
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun engineReportsConnectedAndSurvivesAnIceRestart() = runBlocking {
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
        }
    }
}
