package id.homebase.core.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// runTest uses a single-threaded TestDispatcher, so plain Int counters are safe here.
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedActionTest {

    @Test
    fun singleTriggerInvokesActionAfterDelay() = runTest {
        var invocations = 0
        val debouncer = DebouncedAction(backgroundScope, delayMs = 500L) {
            invocations++
        }

        debouncer.trigger()

        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, invocations, "action must not fire before the debounce window elapses")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, invocations, "action fires once the debounce window has passed")
    }

    @Test
    fun threeRapidTriggersCoalesceIntoOneInvocation() = runTest {
        var invocations = 0
        val debouncer = DebouncedAction(backgroundScope, delayMs = 500L) {
            invocations++
        }

        debouncer.trigger()
        debouncer.trigger()
        debouncer.trigger()

        advanceTimeBy(1000)
        runCurrent()

        assertEquals(
            1, invocations,
            "three rapid triggers within the debounce window must fire action exactly once",
        )
    }

    @Test
    fun triggerAfterPreviousInvocationFiresAgain() = runTest {
        var invocations = 0
        val debouncer = DebouncedAction(backgroundScope, delayMs = 500L) {
            invocations++
        }

        debouncer.trigger()
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, invocations)

        debouncer.trigger()
        advanceTimeBy(600)
        runCurrent()
        assertEquals(2, invocations, "a trigger after the previous action completed must fire again")
    }

    @Test
    fun cancelPreventsPendingInvocation() = runTest {
        var invocations = 0
        val debouncer = DebouncedAction(backgroundScope, delayMs = 500L) {
            invocations++
        }

        debouncer.trigger()
        advanceTimeBy(100)
        debouncer.cancel()
        advanceTimeBy(1000)
        runCurrent()

        assertEquals(0, invocations, "cancel() before the debounce fires must swallow the action")
    }
}
