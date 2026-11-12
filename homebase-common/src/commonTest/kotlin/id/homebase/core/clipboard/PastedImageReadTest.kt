package id.homebase.core.clipboard

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PastedImageReadTest {

    private class RecordingDispatcher : CoroutineDispatcher() {
        var running = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            running = true
            try {
                block.run()
            } finally {
                running = false
            }
        }
    }

    @Test
    fun readsOnTheReadDispatcherAndDeliversOnTheCallerScope() = runTest {
        val io = RecordingDispatcher()
        var readOnIo = false
        var deliveredOnIo = true
        val bytes = byteArrayOf(1, 2, 3)
        var delivered: ByteArray? = null

        launchPastedImageRead(
            readDispatcher = io,
            read = {
                readOnIo = io.running
                bytes
            },
            onImagePasted = {
                deliveredOnIo = io.running
                delivered = it
            },
        ).join()

        assertTrue(readOnIo, "bytes must be read on the read dispatcher, not the caller's thread")
        assertFalse(deliveredOnIo, "callback must run back on the caller's context")
        assertContentEquals(bytes, delivered)
    }

    @Test
    fun doesNotReadBeforeTheCallerYields() {
        val scheduler = StandardTestDispatcher()
        val scope = TestScope(scheduler)
        val io = StandardTestDispatcher(scheduler.scheduler)
        var read = false

        scope.launchPastedImageRead(io, { read = true; byteArrayOf(1) }, {})

        assertFalse(read)
        scheduler.scheduler.advanceUntilIdle()
        assertTrue(read)
    }

    @Test
    fun unreadableImageIsNotDelivered() = runTest {
        var calls = 0
        launchPastedImageRead(RecordingDispatcher(), { null }, { calls++ }).join()
        assertEquals(0, calls)
    }
}
