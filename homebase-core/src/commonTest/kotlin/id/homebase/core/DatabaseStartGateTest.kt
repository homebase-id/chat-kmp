package id.homebase.core

import id.homebase.api.sync.database.DatabaseUnavailableException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseStartGateTest {

    @Test
    fun unavailableDatabaseRunsNothingUntilARetryOpensIt() = runTest {
        var readable = false
        var opens = 0
        val gate = DatabaseStartGate()
        val open: suspend () -> Unit = {
            opens++
            if (!readable) throw DatabaseUnavailableException("before first unlock")
        }
        val ran = mutableListOf<String>()
        gate.whenOpen { ran += "after" }

        assertFalse(gate.tryOpen(open))
        assertFalse(gate.isOpen)
        assertEquals(emptyList(), ran)

        readable = true
        assertTrue(gate.tryOpen(open))
        assertTrue(gate.tryOpen(open))
        assertEquals(listOf("after"), ran)
        assertEquals(2, opens)
    }

    @Test
    fun workQueuedOnceOpenRunsImmediately() = runTest {
        val gate = DatabaseStartGate()
        gate.tryOpen {}
        var ran = false
        gate.whenOpen { ran = true }
        assertTrue(ran)
    }

    @Test
    fun otherOpenFailuresStillPropagate() = runTest {
        val gate = DatabaseStartGate()
        assertFailsWith<IllegalStateException> { gate.tryOpen { error("schema broken") } }
        assertFalse(gate.isOpen)
    }

    @Test
    fun queuedWorkFailingAfterTheOpenIsNotTakenForAnUnreadableDatabase() = runTest {
        val gate = DatabaseStartGate()
        gate.whenOpen { throw DatabaseUnavailableException("a key store the work reads") }

        assertFailsWith<DatabaseUnavailableException> { gate.tryOpen {} }
        assertTrue(gate.isOpen)
    }

    @Test
    fun aPlatformThatCantWaitGetsTheUnavailableDatabaseAsIs() = runTest {
        val gate = DatabaseStartGate()
        assertFailsWith<DatabaseUnavailableException> { gate.open { throw DatabaseUnavailableException("key store locked") } }
        assertFalse(gate.isOpen)
    }
}
