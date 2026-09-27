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
}
