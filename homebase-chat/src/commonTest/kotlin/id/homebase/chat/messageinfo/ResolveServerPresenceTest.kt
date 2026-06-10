package id.homebase.chat.messageinfo

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers [resolveServerPresence] — the exception-folding probe extracted from
 * MessageInfoViewModel. This is the one branch in the server check that can be
 * subtly wrong, so it's tested directly without constructing the ViewModel.
 */
class ResolveServerPresenceTest {

    @Test
    fun exists_isPresent() = runTest {
        assertEquals(ServerPresence.Present, resolveServerPresence(existsOnServer = { true }))
    }

    @Test
    fun missing_isAbsent() = runTest {
        assertEquals(ServerPresence.Absent, resolveServerPresence(existsOnServer = { false }))
    }

    @Test
    fun thrownError_becomesUnknown_andIsReported() = runTest {
        var reported: Throwable? = null
        val presence = resolveServerPresence(
            existsOnServer = { throw RuntimeException("offline: DNS hiccup") },
            onError = { reported = it },
        )
        assertEquals(ServerPresence.Unknown, presence, "a failed probe must not blow up the UI")
        assertNotNull(reported, "the error should be surfaced to the onError sink for logging")
    }

    @Test
    fun thrownError_withoutSink_stillBecomesUnknown() = runTest {
        // onError defaults to a no-op; the Unknown contract must hold regardless.
        assertEquals(
            ServerPresence.Unknown,
            resolveServerPresence(existsOnServer = { throw IllegalStateException("boom") }),
        )
    }

    @Test
    fun cancellation_isRethrown_notSwallowed() = runTest {
        // A navigated-away check must tear down cleanly — cancellation is a
        // control-flow signal, never an "Unknown" outcome.
        var reported: Throwable? = null
        assertFailsWith<CancellationException> {
            resolveServerPresence(
                existsOnServer = { throw CancellationException("scope cancelled") },
                onError = { reported = it },
            )
        }
        assertNull(reported, "cancellation must bypass the error sink entirely")
    }
}
