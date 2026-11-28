package id.homebase.core.privatedrive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SignInCompletionTest {

    @Test
    fun aSignInFinishedAfterTheAskingScreenIsGoneStillCompletes() = runTest {
        val finished = mutableListOf<String>()
        val completion = SignInCompletion(backgroundScope) { finished += it }
        val screen = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val lost = mutableListOf<String>()

        // What the view models did: launch in their own scope, which leaving the screen cancels.
        screen.launch { lost += "homebase://callback?code=1" }
        completion.complete("homebase://callback?code=1")
        screen.coroutineContext[Job]!!.cancel()
        testScheduler.runCurrent()

        assertEquals(emptyList(), lost, "the screen's own launch never runs")
        assertEquals(listOf("homebase://callback?code=1"), finished)
    }
}
