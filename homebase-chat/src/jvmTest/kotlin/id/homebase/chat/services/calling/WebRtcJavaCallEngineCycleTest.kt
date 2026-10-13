package id.homebase.chat.services.calling

import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Connected audio calls started and torn down back to back leave no threads or native memory behind. */
class WebRtcJavaCallEngineCycleTest {
    @Test
    fun threeConnectedCallsLeaveNothingBehind() = runBlocking {
        val after = (1..3).map {
            connectAndHangUp()
            ManagementFactory.getThreadMXBean().threadCount to footprintMb()
        }
        println("after each call (threads, footprintMB): $after")
        val (firstThreads, firstFootprint) = after.first()
        val (lastThreads, lastFootprint) = after.last()
        assertTrue(lastThreads <= firstThreads, "threads grew per call: $after")
        assertTrue(lastFootprint - firstFootprint < 40, "native memory grew per call: $after")
    }

    private suspend fun connectAndHangUp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        val b = WebRtcJavaCallEngine(emptyList(), HeadlessAudioDeviceModule())
        try {
            val iceA = Channel<MediaEvent.LocalIce>(Channel.UNLIMITED)
            val iceB = Channel<MediaEvent.LocalIce>(Channel.UNLIMITED)
            val connected = CompletableDeferred<Unit>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                a.events.collect {
                    if (it is MediaEvent.LocalIce) iceA.trySend(it)
                    if (it == MediaEvent.Connected) connected.complete(Unit)
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                b.events.collect { if (it is MediaEvent.LocalIce) iceB.trySend(it) }
            }
            val answer = b.answer(a.createOffer(video = false), video = false)
            a.applyRemote(answer)
            scope.launch { for (c in iceA) b.addIce(c.candidate, c.sdpMid, c.sdpMLineIndex) }
            scope.launch { for (c in iceB) a.addIce(c.candidate, c.sdpMid, c.sdpMLineIndex) }
            withTimeout(30_000) { connected.await() }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            a.close()
            b.close()
        }
    }
}
