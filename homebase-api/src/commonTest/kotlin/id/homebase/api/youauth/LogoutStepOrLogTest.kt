package id.homebase.api.youauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [stepOrLog] is what keeps logout from wedging: a teardown step that throws (corrupt DB key,
 * unreadable keychain) must not stop the caller from reaching the `_authState` flip that
 * actually signs the user out.
 */
class LogoutStepOrLogTest {

    @Test
    fun failingStepDoesNotPropagate() = runTest {
        stepOrLog("boom") { error("teardown blew up") }
    }

    @Test
    fun laterStepsStillRunAfterAnEarlierFailure() = runTest {
        val ran = mutableListOf<String>()

        stepOrLog("first") { ran += "first"; error("blew up") }
        stepOrLog("second") { ran += "second" }

        assertEquals(listOf("first", "second"), ran)
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        assertFailsWith<CancellationException> {
            stepOrLog("cancelled") { throw CancellationException("scope gone") }
        }
    }

    @Test
    fun successfulStepRuns() = runTest {
        var ran = false
        stepOrLog("ok") { ran = true }
        assertTrue(ran)
    }
}
