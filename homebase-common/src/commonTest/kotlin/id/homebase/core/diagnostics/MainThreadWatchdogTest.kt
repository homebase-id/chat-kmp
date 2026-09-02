package id.homebase.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pure logic behind #941's fix — [renderStallMessage], [detectWatchdogStarvation],
 * and [StallReporter]'s throttle — following the same convention as [GpuTextDiagnosticsTest]:
 * only deterministic, platform-free logic is unit-tested here. Platform capture
 * (`captureMainThreadStackTrace`, the dedicated-thread liveness probes, memory snapshots) is
 * exercised manually on-device, same as before this fix.
 *
 * A genuine `Dispatchers.Default` pool-exhaustion scenario (the actual whole-process stall from
 * the production incidents) isn't reproduced here — that would be flaky/environment-dependent and
 * wouldn't exercise this fix's logic, only JVM thread-pool behavior. [detectWatchdogStarvation]'s
 * arithmetic and [StallReporter]'s throttle are what decide "was there a gap" and "do we log it
 * exactly once," and both are covered directly below with synthetic inputs.
 */
class MainThreadWatchdogTest {

    // --- renderStallMessage ---------------------------------------------------------------

    @Test
    fun mainThreadBlock_withStack_rendersPreservedText() {
        val message = renderStallMessage(
            StallEvent(StallKind.MainThreadBlock, StallSource.CoroutineLoop, observedMs = 5000),
            stack = "    at Foo.bar(Foo.kt:1)\n",
        )
        assertEquals(
            "Main/UI thread stalled >5000ms — likely a recomposition loop or blocking I/O on the UI dispatcher.\n" +
                "UI thread stack at sample:\n" +
                "    at Foo.bar(Foo.kt:1)\n",
            message,
        )
    }

    @Test
    fun mainThreadBlock_withoutStack_rendersPreservedText() {
        val message = renderStallMessage(
            StallEvent(StallKind.MainThreadBlock, StallSource.CoroutineLoop, observedMs = 5000),
            stack = null,
        )
        assertEquals(
            "Main/UI thread stalled >5000ms — likely a recomposition loop or blocking I/O on the UI dispatcher.\n" +
                "(UI thread stack capture not supported on this platform)\n",
            message,
        )
    }

    @Test
    fun watchdogStarved_rendersDistinctTextFromMainThreadBlock() {
        val blockMessage = renderStallMessage(
            StallEvent(StallKind.MainThreadBlock, StallSource.CoroutineLoop, observedMs = 8000),
            stack = null,
        )
        val starvedMessage = renderStallMessage(
            StallEvent(StallKind.WatchdogStarved, StallSource.CoroutineLoop, observedMs = 8000),
            stack = null,
        )
        assertNotEquals(blockMessage, starvedMessage)
        assertTrue(starvedMessage.contains("watchdog starved"))
    }

    @Test
    fun dedicatedThreadSource_prefixesTheMessage() {
        val message = renderStallMessage(
            StallEvent(StallKind.MainThreadBlock, StallSource.DedicatedThread, observedMs = 4200),
            stack = null,
        )
        assertTrue(message.startsWith("[dedicated-thread probe] Main/UI thread stalled >4200ms"))
    }

    @Test
    fun memorySnapshot_appendedWhenPresent_omittedWhenNull() {
        val withMemory = renderStallMessage(
            StallEvent(
                kind = StallKind.MainThreadBlock,
                source = StallSource.CoroutineLoop,
                observedMs = 100,
                memory = MemoryDiagnostics.Snapshot(freeMemoryMb = 12, totalMemoryMb = 256),
            ),
            stack = null,
        )
        assertTrue(withMemory.contains("Memory at stall: freeMb=12 totalMb=256"))

        val withoutMemory = renderStallMessage(
            StallEvent(StallKind.MainThreadBlock, StallSource.CoroutineLoop, observedMs = 100),
            stack = null,
        )
        assertTrue(!withoutMemory.contains("Memory at stall"))
    }

    @Test
    fun watchdogStarved_withNoCpuBurnedAcrossTheGap_readsOsSuspended() {
        val message = renderStallMessage(
            StallEvent(
                kind = StallKind.WatchdogStarved,
                source = StallSource.CoroutineLoop,
                observedMs = 28961,
                processDelta = ProcessTimes(cpuMs = 3, continuousMs = 28961),
            ),
            stack = null,
        )
        assertTrue(
            message.startsWith(
                "Process/dispatchers stalled ~28961ms (watchdog starved, OS-SUSPENDED: cpu +3ms over 28961ms wall)"
            ),
            message,
        )
    }

    @Test
    fun watchdogStarved_withCpuBurnedInStep_readsSelfStalled() {
        val message = renderStallMessage(
            StallEvent(
                kind = StallKind.WatchdogStarved,
                source = StallSource.CoroutineLoop,
                observedMs = 28961,
                processDelta = ProcessTimes(cpuMs = 28400, continuousMs = 28961),
            ),
            stack = null,
        )
        assertTrue(message.contains("SELF-STALLED: cpu +28400ms over 28961ms wall"), message)
    }

    // --- classifyStallCause ------------------------------------------------------------------

    @Test
    fun classifyStallCause_splitsOnTheCpuShareThreshold() {
        assertEquals("OS-SUSPENDED", classifyStallCause(ProcessTimes(cpuMs = 200, continuousMs = 10_000)))
        assertEquals("SELF-STALLED", classifyStallCause(ProcessTimes(cpuMs = 9_500, continuousMs = 10_000)))
    }

    @Test
    fun processTimesDelta_isNullUnlessBothSamplesExist() {
        val before = ProcessTimes(cpuMs = 100, continuousMs = 1_000)
        val after = ProcessTimes(cpuMs = 103, continuousMs = 30_000)
        assertEquals(ProcessTimes(cpuMs = 3, continuousMs = 29_000), processTimesDelta(before, after))
        assertNull(processTimesDelta(null, after))
        assertNull(processTimesDelta(before, null))
    }

    // --- detectWatchdogStarvation ----------------------------------------------------------

    @Test
    fun noGap_returnsNull() {
        assertNull(detectWatchdogStarvation(expectedGapMs = 1000, actualGapMs = 1050))
    }

    @Test
    fun gapWithinSlack_returnsNull() {
        assertNull(detectWatchdogStarvation(expectedGapMs = 1000, actualGapMs = 2900, slackFactor = 3.0))
    }

    @Test
    fun largeGap_returnsObservedStallDuration() {
        assertEquals(9000L, detectWatchdogStarvation(expectedGapMs = 1000, actualGapMs = 9000, slackFactor = 3.0))
    }

    // --- StallReporter -----------------------------------------------------------------------

    @Test
    fun firstReportAlwaysLogs() {
        val logged = mutableListOf<String>()
        val reporter = StallReporter(throttleMs = 30_000, nowMs = { 0L }, log = { logged += it })
        reporter.reportIfDue { "first" }
        assertEquals(listOf("first"), logged)
    }

    @Test
    fun throttleSuppressesReportsWithinWindow_andAllowsAfterItElapses() {
        var fakeNow = 0L
        val logged = mutableListOf<String>()
        val reporter = StallReporter(throttleMs = 30_000, nowMs = { fakeNow }, log = { logged += it })

        reporter.reportIfDue { "a" }
        fakeNow += 10_000
        reporter.reportIfDue { "b" } // still inside the 30s window: suppressed
        fakeNow += 25_000
        reporter.reportIfDue { "c" } // 35s since "a": window elapsed, logs again

        assertEquals(listOf("a", "c"), logged)
    }
}
