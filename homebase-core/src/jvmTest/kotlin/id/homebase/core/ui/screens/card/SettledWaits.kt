package id.homebase.core.ui.screens.card

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestCoroutineScheduler

// Never wait on Dispatchers.Default here: the test resumes on that worker and races the ViewModel's own coroutines.
internal suspend fun settle(condition: () -> Boolean) {
    currentCoroutineContext()[TestCoroutineScheduler]!!.advanceUntilIdle()
    check(condition()) { "still false once the test scheduler went idle" }
}

internal suspend fun <T> settled(deferred: Deferred<T>): T {
    settle { deferred.isCompleted }
    return deferred.await()
}
