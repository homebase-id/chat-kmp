package id.homebase.chat.services.convo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the unread-enrichment pileup: a sync storm used to fire one full-DB
 * enrichment per WebSocket batch / DriveSync-Stopped with no coalescing, spawning N concurrent
 * passes (47–131s in homebase.log). [CoalescingTrigger] must collapse a burst into ~one run and
 * survive a failing action.
 *
 * The collector is launched on a child scope sharing the test dispatcher (so advanceUntilIdle
 * drives it) but with its own [Job] we cancel at the end, so its infinite drain loop doesn't
 * trip runTest's "uncompleted coroutines" check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingTriggerTest {

    @Test
    fun rapidBurst_collapsesToASingleRun() = runTest {
        val collectorJob = Job()
        val scope = CoroutineScope(coroutineContext + collectorJob)
        var runs = 0
        val trigger = CoalescingTrigger<String>(scope, debounceMs = 100) { runs++ }

        // 20 requests with no suspension between them: the CONFLATED channel keeps only the
        // latest, so the collector should run exactly once.
        repeat(20) { trigger.request("t$it") }
        advanceUntilIdle()
        collectorJob.cancel()

        assertEquals(1, runs, "a rapid burst of 20 requests should coalesce to a single run")
    }

    @Test
    fun requestDuringRun_triggersASingleRerun() = runTest {
        val collectorJob = Job()
        val scope = CoroutineScope(coroutineContext + collectorJob)
        var runs = 0
        lateinit var trigger: CoalescingTrigger<Int>
        trigger = CoalescingTrigger(scope, debounceMs = 0) { value ->
            runs++
            // While the first run executes, enqueue several more — they must coalesce into
            // exactly one rerun, not one-per-request.
            if (value == 0) repeat(5) { trigger.request(it + 1) }
        }

        trigger.request(0)
        advanceUntilIdle()
        collectorJob.cancel()

        assertEquals(2, runs, "requests made during a run should coalesce to a single rerun")
    }

    @Test
    fun failingAction_doesNotKillTheCollector() = runTest {
        val collectorJob = Job()
        val scope = CoroutineScope(coroutineContext + collectorJob)
        var runs = 0
        val trigger = CoalescingTrigger<Int>(scope, debounceMs = 0) { value ->
            runs++
            if (value == 1) error("boom")
        }

        trigger.request(1)
        advanceUntilIdle()
        trigger.request(2)
        advanceUntilIdle()
        collectorJob.cancel()

        assertEquals(2, runs, "the collector must survive a failing action and process later requests")
    }
}
