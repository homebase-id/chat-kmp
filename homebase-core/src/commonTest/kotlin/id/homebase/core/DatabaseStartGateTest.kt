package id.homebase.core

import id.homebase.api.sync.database.DatabaseUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseStartGateTest {

    @Test
    fun unavailableDatabaseRunsNothingUntilARetryOpensIt() {
        var readable = false
        var opens = 0
        val gate = DatabaseStartGate {
            opens++
            if (!readable) throw DatabaseUnavailableException("before first unlock")
        }
        val ran = mutableListOf<String>()
        gate.whenOpen { ran += "after" }

        assertFalse(gate.tryOpen())
        assertFalse(gate.isOpen)
        assertEquals(emptyList(), ran)

        readable = true
        assertTrue(gate.tryOpen())
        assertTrue(gate.tryOpen())
        assertEquals(listOf("after"), ran)
        assertEquals(2, opens)
    }

    @Test
    fun workQueuedOnceOpenRunsImmediately() {
        val gate = DatabaseStartGate {}
        gate.tryOpen()
        var ran = false
        gate.whenOpen { ran = true }
        assertTrue(ran)
    }

    @Test
    fun otherOpenFailuresStillPropagate() {
        val gate = DatabaseStartGate { error("schema broken") }
        assertFailsWith<IllegalStateException> { gate.tryOpen() }
        assertFalse(gate.isOpen)
    }
}
