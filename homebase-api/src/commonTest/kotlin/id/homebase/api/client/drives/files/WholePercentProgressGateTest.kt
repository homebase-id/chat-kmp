package id.homebase.api.client.drives.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression test for the upload-progress de-duplication that backs the fix for the
 * EventBus "ItemProgress BUFFER FULL — suspending" flood: a chunked upload must not emit
 * more than one progress event per whole integer percent.
 */
class WholePercentProgressGateTest {

    @Test
    fun emitsAtMostOncePerWholePercentAcrossAChunkedUpload() {
        val gate = WholePercentProgressGate()
        val admitted = mutableListOf<Float>()

        // Simulate the HTTP client firing onProgress per chunk: 0.0%..100.0% in 0.1% steps
        // (10 ticks per percent — i.e. ~1000 callbacks, like a multi-MB upload).
        for (i in 0..1000) {
            gate.admit(i / 10f)?.let { admitted.add(it) }
        }

        // 0..100 inclusive = 101 distinct whole percents, never more — no per-chunk duplicates.
        assertEquals(101, admitted.size)
        val wholes = admitted.map { it.toInt() }
        assertEquals((0..100).toList(), wholes)
        assertEquals(wholes, wholes.distinct(), "no two admitted ticks share a whole percent")
    }

    @Test
    fun dropsConsecutiveTicksWithinTheSameWholePercent() {
        val gate = WholePercentProgressGate()

        assertNotNull(gate.admit(0.0f))   // first tick — admitted even at 0
        assertNull(gate.admit(0.4f))      // still 0% — dropped
        assertNull(gate.admit(0.9f))      // still 0% — dropped
        assertNotNull(gate.admit(1.0f))   // 1% — admitted
        assertNull(gate.admit(1.7f))      // still 1% — dropped
        assertNotNull(gate.admit(2.2f))   // 2% — admitted
        assertNotNull(gate.admit(100.0f)) // jump to 100% — admitted
        assertNull(gate.admit(100.0f))    // 100% again — dropped
    }

    @Test
    fun preservesTheExactFractionItAdmits() {
        val gate = WholePercentProgressGate()
        // admit returns the original fraction (not the truncated whole) so the emitted
        // progress keeps full precision for the percent it represents.
        assertEquals(0.0f, gate.admit(0.0f))
        assertEquals(1.6f, gate.admit(1.6f))
    }
}
