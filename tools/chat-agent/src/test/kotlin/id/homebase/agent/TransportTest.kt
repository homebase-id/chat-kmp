package id.homebase.agent

import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TransportTest {
    private fun TestScope.clockedWaker() = PollWaker(now = { testScheduler.currentTime })

    @Test
    fun backoffDoublesToCap() {
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), (0..7).map(::backoffMs))
    }

    @Test
    fun ringWakesIdleWaitAfterDebounce() = runTest {
        val waker = clockedWaker()
        waker.setConnected(true)
        waker.await()
        val start = currentTime
        launch { waker.ring() }
        waker.await()
        assertEquals(MIN_POLL_SPACING_MS, currentTime - start)
    }

    @Test
    fun burstCoalescesIntoOnePoll() = runTest {
        val waker = clockedWaker()
        waker.setConnected(true)
        waker.await()
        repeat(5) { waker.ring() }
        waker.await()
        val start = currentTime
        waker.await()
        assertEquals(IDLE_CONNECTED_MS, currentTime - start)
    }

    @Test
    fun idleIntervalFollowsConnectionState() = runTest {
        val waker = clockedWaker()
        var t = currentTime
        waker.await()
        assertEquals(IDLE_DISCONNECTED_MS, currentTime - t)
        waker.setConnected(true)
        waker.await()
        t = currentTime
        waker.await()
        assertEquals(IDLE_CONNECTED_MS, currentTime - t)
        launch { waker.setConnected(false) }
        t = currentTime
        waker.await()
        assertEquals(IDLE_DISCONNECTED_MS, currentTime - t)
    }

    @Test
    fun flappingConnectionKeepsBackingOff() = runTest {
        val attempts = ArrayList<Long>()
        val job = launch {
            runDoorbell({ up, _ -> attempts += currentTime; up(); error("boom") }, clockedWaker(), {}, { currentTime })
        }
        testScheduler.advanceTimeBy(140_000)
        job.cancel()
        assertEquals(listOf(0L, 1_000, 3_000, 7_000, 15_000, 31_000, 63_000, 123_000), attempts)
    }

    @Test
    fun stableConnectionResetsBackoff() = runTest {
        val attempts = ArrayList<Long>()
        var n = 0
        val job = launch {
            runDoorbell({ up, _ ->
                attempts += currentTime
                if (++n == 3) { up(); delay(STABLE_CONNECTION_MS) }
                error("boom")
            }, clockedWaker(), {}, { currentTime })
        }
        testScheduler.advanceTimeBy(40_000)
        job.cancel()
        assertEquals(listOf(0L, 1_000, 3_000, 34_000), attempts.take(4))
        assertEquals(35_000L, attempts.drop(3).firstOrNull()?.plus(1_000))
    }

    @Test
    fun floodIsSpacedToOnePollPerTwoSeconds() = runTest {
        val waker = clockedWaker()
        waker.setConnected(true)
        waker.await()
        val stamps = ArrayList<Long>()
        val flood = launch { repeat(100) { waker.ring(); delay(50) } }
        repeat(3) { waker.await(); stamps += currentTime }
        flood.cancel()
        stamps.zipWithNext { a, b -> assertTrue(b - a >= MIN_POLL_SPACING_MS) }
    }

    @Test
    fun notificationWakesPollThroughFakeSocket() = runTest {
        val waker = clockedWaker()
        val ring = CompletableDeferred<() -> Unit>()
        val job = launch { runDoorbell({ up, r -> up(); ring.complete(r); kotlinx.coroutines.awaitCancellation() }, waker, {}) }
        waker.await()
        val start = currentTime
        ring.await().invoke()
        waker.await()
        assertEquals(MIN_POLL_SPACING_MS, currentTime - start)
        job.cancel()
    }

    @Test
    fun pollTransportNeverConnects() = runTest {
        var connects = 0
        val connector: Connector = { _, _ -> connects++ }
        assertNull(startDoorbell(this, Transport.POLL, connector, clockedWaker(), {}))
        testScheduler.advanceTimeBy(100_000)
        assertEquals(0, connects)
        val job = startDoorbell(this, Transport.AUTO, connector, clockedWaker(), {})!!
        testScheduler.advanceTimeBy(1)
        assertEquals(1, connects)
        job.cancel()
    }

    @Test
    fun transportConfig() {
        val owner = OdinId("o.example.com")
        assertEquals(Transport.AUTO, parseConfig("", owner).transport)
        assertEquals(Transport.POLL, parseConfig("transport=poll", owner).transport)
        assertNull(parseTransport("nope"))
        assertEquals(1, parseConfig("transport=nope", owner).warnings.size)
    }
}
