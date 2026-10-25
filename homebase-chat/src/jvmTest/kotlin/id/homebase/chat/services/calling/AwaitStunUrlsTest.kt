@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AwaitStunUrlsTest {
    private val stun = listOf("stun:frodo.baggins.demo.rocks:3478")

    @Test
    fun knownUrlsAreUsedAtOnce() = runTest {
        assertEquals(stun, MutableStateFlow(stun).awaitStunUrls())
    }

    @Test
    fun urlsThatArriveWithinTheWaitAreUsed() = runTest {
        val urls = MutableStateFlow(emptyList<String>())
        val awaited = async { urls.awaitStunUrls(timeoutMs = 3_000) }
        advanceTimeBy(1_000)
        urls.value = stun
        runCurrent()
        assertEquals(stun, awaited.await())
    }

    @Test
    fun aServerThatSendsNoneCostsOnlyTheWait() = runTest {
        val awaited = async { MutableStateFlow(emptyList<String>()).awaitStunUrls(timeoutMs = 3_000) }
        advanceTimeBy(3_001)
        assertEquals(emptyList(), awaited.await())
    }
}
