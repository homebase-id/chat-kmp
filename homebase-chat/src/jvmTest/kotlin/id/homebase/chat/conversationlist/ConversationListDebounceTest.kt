@file:OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package id.homebase.chat.conversationlist

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the cold-start "list blocked until the connection goes green" bug.
 *
 * The conversation-list combine builds a complete, ready list within a few ms of vmInit
 * (cached conversations + a credentials-synthesized session), but its sources
 * (ownerSession, connectionStatusFlow) keep re-emitting all through the auth/connect
 * lifecycle. A flat `debounce(50)` never saw 50ms of quiet during that storm, so it
 * withheld the already-ready list for ~half a second until the connection settled.
 *
 * The fix is a leading-edge debounce ([conversationListDebounceMillis]): 0ms for the first
 * ready snapshot, 50ms thereafter. These tests pin both the selector and the end-to-end
 * flow behavior so a revert to a flat debounce is caught.
 */
class ConversationListDebounceTest {

    @Test
    fun selector_firstReadySnapshotIsImmediate() {
        // The first ready snapshot must pass through with no debounce.
        assertEquals(0L, conversationListDebounceMillis(dataReady = true, readyAlreadyEmitted = false))
    }

    @Test
    fun selector_subsequentReadySnapshotsAreCoalesced() {
        // After the first render, updates are coalesced so the connect-time storm doesn't
        // re-sort the list on every micro-change.
        assertEquals(50L, conversationListDebounceMillis(dataReady = true, readyAlreadyEmitted = true))
    }

    @Test
    fun selector_notReadySnapshotsAreCoalesced() {
        assertEquals(50L, conversationListDebounceMillis(dataReady = false, readyAlreadyEmitted = false))
        assertEquals(50L, conversationListDebounceMillis(dataReady = true.not(), readyAlreadyEmitted = true))
    }

    @Test
    fun leadingEdge_deliversFirstReadyImmediately_despiteSourceStorm() = runTest {
        // A ready snapshot at t=0, then a storm of updates every 30ms (faster than the 50ms
        // trailing debounce) — exactly the connect-lifecycle source storm.
        var firstReadyEmitted = false
        val collected = mutableListOf<Pair<Long, Boolean>>()

        flow {
            emit(true to "ready")                 // dataReady at t=0
            repeat(10) { i ->
                delay(30)
                emit(false to "storm$i")          // storm faster than the 50ms debounce
            }
        }.debounce { (dataReady, _) ->
            // Mirror the ViewModel wiring: the selector flips the flag when it grants the
            // leading edge (0ms), so the flag is confined to the selector's coroutine.
            val timeout = conversationListDebounceMillis(dataReady = dataReady, readyAlreadyEmitted = firstReadyEmitted)
            if (timeout == 0L) firstReadyEmitted = true
            timeout
        }.collect { (dataReady, _) ->
            collected += testScheduler.currentTime to dataReady
        }

        // The ready snapshot must be delivered, and at virtual time ~0 — not held behind
        // the storm. With a flat debounce(50) the ready value would be superseded by the
        // storm before any 50ms of quiet and never delivered at all, so this fails on revert.
        val firstReady = collected.firstOrNull { it.second }
        assertTrue(firstReady != null, "the ready list snapshot must be delivered")
        assertTrue(
            firstReady.first < 30L,
            "ready list must render immediately (delivered at t=${firstReady.first}ms — gated behind the storm)",
        )
    }
}
