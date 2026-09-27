package id.homebase.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivenessProbeLoopTest {

    private class FakeMainThread(private val blockedDuring: List<LongRange>, private val stopAtMs: Long) {
        var nowMs = 0L
        private val queue = ArrayDeque<() -> Unit>()
        val reports = mutableListOf<Pair<Long, Long>>()

        private fun drainIfFree() {
            if (blockedDuring.none { nowMs in it }) while (queue.isNotEmpty()) queue.removeFirst()()
        }

        fun run() = runLivenessProbeLoop(
            thresholdMs = 4_000,
            pollIntervalMs = 1_000,
            isRunning = { nowMs < stopAtMs },
            nowMs = { nowMs },
            sleepMs = { nowMs += it; drainIfFree() },
            postToMainThread = { queue.addLast(it); drainIfFree() },
            onStalled = { stalledMs -> reports += nowMs to stalledMs },
        )
    }

    @Test
    fun stallThatNeverRecovers_isReportedAtTheThresholdWhileStillOngoing_once() {
        val main = FakeMainThread(blockedDuring = listOf(10_000L..Long.MAX_VALUE), stopAtMs = 3_600_000)
        main.run()

        assertEquals(1, main.reports.size, "reports: ${main.reports}")
        val (reportedAtMs, stalledMs) = main.reports.single()
        assertTrue(stalledMs in 4_000L..4_100L, "stalledMs=$stalledMs")
        assertTrue(reportedAtMs < 16_000, "reported only at $reportedAtMs")
    }

    @Test
    fun stallThatRecovers_isReportedAtTheThresholdAndAgainWithTheTotal_thenProbingResumes() {
        val main = FakeMainThread(
            blockedDuring = listOf(10_000L..40_000L, 100_000L..106_000L),
            stopAtMs = 200_000,
        )
        main.run()

        val stalls = main.reports.map { it.second }
        assertEquals(4, stalls.size, "reports: ${main.reports}")
        assertTrue(stalls[0] in 4_000L..4_100L, "first stall threshold report: ${stalls[0]}")
        assertTrue(stalls[1] in 29_000L..31_000L, "first stall recovery total: ${stalls[1]}")
        assertTrue(stalls[2] in 4_000L..4_100L, "second stall threshold report: ${stalls[2]}")
        assertTrue(stalls[3] in 5_000L..7_000L, "second stall recovery total: ${stalls[3]}")
    }

    @Test
    fun blipShorterThanTheThreshold_isNotReported() {
        val main = FakeMainThread(blockedDuring = listOf(10_000L..12_000L), stopAtMs = 60_000)
        main.run()

        assertEquals(emptyList(), main.reports)
    }
}
