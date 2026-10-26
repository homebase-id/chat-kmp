package id.homebase.core.ui.screens.card

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestCoroutineScheduler

// Never wait on Dispatchers.Default here: under an UnconfinedTestDispatcher the test resumes on that worker, and its next
// ViewModel call then runs beside the ViewModel's own coroutines, which production Main never allows. The wire answers inline,
// so once the scheduler is idle every continuation has run and a condition still false is a real failure.
internal suspend fun settle(condition: () -> Boolean) {
    currentCoroutineContext()[TestCoroutineScheduler]!!.advanceUntilIdle()
    check(condition()) { "still false once the test scheduler went idle" }
}

internal suspend fun <T> settled(deferred: Deferred<T>): T {
    settle { deferred.isCompleted }
    return deferred.await()
}
