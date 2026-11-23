package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RingbackToneTest {
    private class Recorder : RingbackTone {
        val events = mutableListOf<String>()
        override fun start() { events += "start" }
        override fun stop() { events += "stop" }
    }

    private fun outgoing(ringing: Boolean = false) = CallState(phase = CallPhase.Outgoing, peerRinging = ringing)

    @Test
    fun startsWhenPeerRingsAndStopsWhenConnecting() = runTest {
        val tone = Recorder()
        val state = MutableStateFlow(outgoing())
        val job = launch { state.driveRingback(tone, fallbackMs = 4_000) }
        runCurrent()
        state.value = outgoing(ringing = true)
        runCurrent()
        assertEquals(listOf("start"), tone.events.filter { it == "start" })
        state.value = CallState(phase = CallPhase.Connecting)
        runCurrent()
        assertEquals("stop", tone.events.last())
        job.cancel()
    }

    @Test
    fun fallsBackToTheToneWhenRingingNeverArrives() = runTest {
        val tone = Recorder()
        val state = MutableStateFlow(outgoing())
        val job = launch { state.driveRingback(tone, fallbackMs = 4_000) }
        advanceTimeBy(3_999)
        assertEquals(emptyList(), tone.events.filter { it == "start" })
        advanceTimeBy(2)
        assertEquals(1, tone.events.count { it == "start" })
        job.cancel()
    }

    @Test
    fun endedBeforeTheFallbackStaysSilent() = runTest {
        val tone = Recorder()
        val state = MutableStateFlow(outgoing())
        val job = launch { state.driveRingback(tone, fallbackMs = 4_000) }
        runCurrent()
        state.value = CallState(phase = CallPhase.Ended(CallEndReason.Declined))
        advanceUntilIdle()
        assertEquals(0, tone.events.count { it == "start" })
        job.cancel()
    }

    @Test
    fun cancelledWhileRingingStopsTheTone() = runTest {
        val tone = Recorder()
        val state = MutableStateFlow(outgoing(ringing = true))
        val job = launch { state.driveRingback(tone, fallbackMs = 4_000) }
        runCurrent()
        assertEquals(listOf("start"), tone.events)
        job.cancel()
        runCurrent()
        assertEquals(listOf("start", "stop"), tone.events)
    }

    @Test
    fun wavIsAWellFormedLoop() {
        val wav = ringbackWav()
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("data", wav.decodeToString(36, 40))
        assertEquals(44 + 8_000 * 6 * 2, wav.size)
    }
}

class JvmRingbackToneTest {
    private class FakeClip(val failOnLoop: Boolean) {
        @Volatile var closed = false
        val clip: javax.sound.sampled.Clip = java.lang.reflect.Proxy.newProxyInstance(
            javax.sound.sampled.Clip::class.java.classLoader,
            arrayOf(javax.sound.sampled.Clip::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "loop" -> if (failOnLoop) error("loop failed")
                "close" -> closed = true
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as javax.sound.sampled.Clip
    }

    @Test
    fun clipThatFailsToLoopIsClosed() {
        val fake = FakeClip(failOnLoop = true)
        JvmRingbackTone { fake.clip }.start()
        kotlin.test.assertTrue(fake.closed)
    }

    @Test
    fun concurrentStartsCreateOneClip() {
        val created = java.util.concurrent.atomic.AtomicInteger()
        val tone = JvmRingbackTone {
            created.incrementAndGet()
            Thread.sleep(20)
            FakeClip(failOnLoop = false).clip
        }
        val threads = List(8) { Thread { tone.start() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, created.get())
    }
}
