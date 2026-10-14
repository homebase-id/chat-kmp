package id.homebase.chat.services.calling

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CallSenderParamsTest {
    @Test
    fun aLaterChangeKeepsTheFieldsAnEarlierOneSet() {
        val writes = mutableListOf<SenderState>()
        val params = SenderParams { writes += it }

        params.update { copy(maxBitrateBps = 2_000_000, maxFramerate = 30) }
        params.update { copy(scaleDownBy = 1.5) }
        params.update { copy(maxFramerate = 15) }

        assertEquals(SenderState(maxBitrateBps = 2_000_000, maxFramerate = 15, scaleDownBy = 1.5), writes.last())
    }

    @Test
    fun concurrentWritersNeverOverlapAndTheLastWriteHoldsEveryChange() {
        val inside = AtomicInteger()
        val overlapped = AtomicInteger()
        var last: SenderState? = null
        val params = SenderParams {
            if (inside.incrementAndGet() > 1) overlapped.incrementAndGet()
            Thread.sleep(1)
            last = it
            inside.decrementAndGet()
        }
        val start = CountDownLatch(1)
        val writers = listOf(
            thread { start.await(); repeat(50) { params.update { copy(maxFramerate = 15 + it % 2) } } },
            thread { start.await(); repeat(50) { params.update { copy(scaleDownBy = 1.0 + it % 3) } } },
            thread { start.await(); params.update { copy(maxBitrateBps = 2_000_000) } },
        )
        start.countDown()
        writers.forEach { it.join() }

        assertEquals(0, overlapped.get())
        val final = last!!
        assertEquals(2_000_000, final.maxBitrateBps)
        assertTrue(final.maxFramerate != null && final.scaleDownBy != null)
    }

    @Test
    fun aFailedWriteDoesNotStopLaterOnes() {
        var fail = true
        val writes = mutableListOf<SenderState>()
        val params = SenderParams { if (fail) error("rejected") else writes += it }

        assertFailsWith<IllegalStateException> { params.update { copy(maxFramerate = 15) } }
        fail = false
        params.update { copy(scaleDownBy = 2.0) }

        assertEquals(SenderState(maxFramerate = 15, scaleDownBy = 2.0), writes.single())
    }
}
