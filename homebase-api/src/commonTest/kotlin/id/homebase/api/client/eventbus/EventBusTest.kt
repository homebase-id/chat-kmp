package id.homebase.api.client.eventbus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusTest {

    private val eventCount = 1_000

    private fun event(i: Int) = BackendEvent.DriveEvent.Progress(Uuid.NIL, totalCount = i)

    @Test
    fun stalledSubscriberDoesNotSuspendEmittersOnOtherCoroutines() = runTest {
        val bus = EventBus()
        val neverResumes = CompletableDeferred<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { neverResumes.await() }
        }
        val received = mutableListOf<BackendEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.drop(bus.events.replayCache.size).collect { received += it }
        }

        val emitter = launch { repeat(eventCount) { bus.emit(event(it)) } }
        advanceUntilIdle()

        assertTrue(emitter.isCompleted, "emit suspended behind a subscriber that never resumes")
        assertEquals<List<BackendEvent>>((0 until eventCount).map(::event), received)
    }

    @Test
    fun tryEmitDoesNotDropBehindStalledSubscriber() = runTest {
        val bus = EventBus()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { CompletableDeferred<Unit>().await() }
        }

        repeat(eventCount) { assertTrue(bus.tryEmit(event(it)), "tryEmit dropped event $it") }
    }

    @Test
    fun progressFloodBehindStalledSubscriberStaysBoundedWhileCriticalEventsAllArrive() = runTest {
        val bus = EventBus()
        val gate = CompletableDeferred<Unit>()
        val stalledProgress = mutableListOf<BackendEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.progress.collect { stalledProgress += it; gate.await() }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.collect { gate.await() }
        }
        val critical = mutableListOf<BackendEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.events.drop(bus.events.replayCache.size).collect { critical += it }
        }

        val progressCount = 10_000
        val emitter = launch {
            repeat(progressCount) { i ->
                bus.emit(BackendEvent.OutboxEvent.ItemProgress(Uuid.NIL, Uuid.NIL, progress = i.toFloat()))
                if (i % 100 == 99) bus.emit(BackendEvent.OutboxEvent.ItemCompleted(Uuid.NIL, Uuid.NIL))
            }
        }
        advanceUntilIdle()
        assertTrue(emitter.isCompleted, "emit suspended behind a stalled subscriber")
        assertEquals(progressCount / 100, critical.size)
        assertTrue(critical.all { it is BackendEvent.OutboxEvent.ItemCompleted })

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(
            stalledProgress.size <= 1 + EventBus.PROGRESS_BUFFER_CAPACITY,
            "progress buffer grew to ${stalledProgress.size} behind a stalled subscriber",
        )
        assertEquals(
            (progressCount - 1).toFloat(),
            (stalledProgress.last() as BackendEvent.OutboxEvent.ItemProgress).progress,
            "the newest progress tick must survive the overflow",
        )
    }
}
