package id.homebase.imageeditor.ui

import id.homebase.api.image.ImageResult
import id.homebase.api.image.ImageSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The crop/draw result buses are single-consumer, single-result. The bug they
 * regression-guard: `resultsFor(id).collect {}` used to suspend forever because
 * `postResult` never closed the channel and the nav callers never `cancel()`ed —
 * leaking one coroutine + Channel per crop/draw for the ViewModel's lifetime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CropDrawResultBusTest {

    private fun result(b: Byte = 1) =
        ImageResult(byteArrayOf(b), ImageSize(10, 10), ImageSize(10, 10))

    @Test
    fun postResult_isSingleShot_soCollectorCompletes() = runTest {
        val bus = CropResultBus()
        val id = Uuid.random()
        val received = mutableListOf<ImageResult>()
        val job = launch { bus.resultsFor(id).collect { received.add(it) } }
        advanceUntilIdle() // collector subscribes and suspends, waiting for the result

        bus.postResult(id, result())
        advanceUntilIdle()

        // Without the close() in postResult this collector would still be suspended
        // (and runTest itself would fail on the leaked coroutine).
        assertTrue(job.isCompleted, "collector must complete after the single result")
        assertEquals(1, received.size)
    }

    @Test
    fun cancel_completesCollector_onTheAbortPath() = runTest {
        val bus = CropResultBus()
        val id = Uuid.random()
        val received = mutableListOf<ImageResult>()
        val job = launch { bus.resultsFor(id).collect { received.add(it) } }
        advanceUntilIdle()

        bus.cancel(id) // user backed out of the cropper without confirming
        advanceUntilIdle()

        assertTrue(job.isCompleted, "collector must complete when the request is cancelled")
        assertEquals(0, received.size)
    }

    @Test
    fun takeSource_returnsBytesThenClears() {
        val bus = CropResultBus()
        val id = Uuid.random()
        bus.postSource(id, byteArrayOf(9))
        assertEquals(9.toByte(), bus.takeSource(id)?.single())
        assertNull(bus.takeSource(id), "source must be consumed exactly once")
    }

    @Test
    fun drawBus_postResult_isAlsoSingleShot() = runTest {
        val bus = DrawResultBus()
        val id = Uuid.random()
        val job = launch { bus.resultsFor(id).collect { } }
        advanceUntilIdle()

        bus.postResult(id, result())
        advanceUntilIdle()

        assertTrue(job.isCompleted, "DrawResultBus must be single-shot too")
    }
}
