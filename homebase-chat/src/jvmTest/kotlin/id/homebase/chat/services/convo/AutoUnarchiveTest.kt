package id.homebase.chat.services.convo

import id.homebase.chat.data.ConversationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Locks the cold-path half of auto-unarchive. The live WS path was already
 * covered; the bug was that DriveSync — FCM cold-wake, reconnect catch-up —
 * writes message rows silently, so nothing re-evaluated the archive.
 */
class AutoUnarchiveTest {

    private val archivedAt = Instant.fromEpochMilliseconds(1_000)

    private fun decide(
        state: ConversationState = ConversationState.Archived,
        archivedAt: Instant? = this.archivedAt,
        lastMessageMs: Long = 2_000,
        fromActiveUser: Boolean = false,
    ) = shouldAutoUnarchive(
        state = state,
        archivedAt = archivedAt,
        lastMessageUserDate = Instant.fromEpochMilliseconds(lastMessageMs),
        lastMessageIsFromActiveUser = fromActiveUser,
    )

    @Test
    fun messageAfterTheArchive_unarchives() = assertTrue(decide())

    @Test
    fun messageFromBeforeTheArchive_staysArchived() =
        assertFalse(decide(lastMessageMs = 999), "archiving a thread must stick")

    @Test
    fun messageAtExactlyTheArchiveInstant_staysArchived() =
        assertFalse(decide(lastMessageMs = 1_000))

    @Test
    fun ourOwnMessage_doesNotUnarchive() =
        assertFalse(decide(fromActiveUser = true), "the incoming path is a separate trigger from sending")

    @Test
    fun noBaseline_staysArchived() =
        assertFalse(decide(archivedAt = null), "pre-stamp threads have no baseline to compare against")

    @Test
    fun nonArchivedStates_areNeverTouched() {
        for (state in ConversationState.entries) {
            if (state == ConversationState.Archived) continue
            assertFalse(decide(state = state), "$state must not be flipped to Active")
        }
    }
}

/**
 * The cold half runs on every sync `Stopped` and on every cold start, re-reading
 * conversation state from the DB each time. If the unarchive write never lands,
 * the row still reads `Archived` on the next pass — ungated, that re-enqueues an
 * outbox row every pass, indefinitely.
 */
class AutoUnarchiveGateTest {

    private val convoId = Uuid.random()
    private val archivedAt = Instant.fromEpochMilliseconds(1_000)

    /** 1 = the pass reached `onUnarchiveConversation`, which is what enqueues. */
    private fun AutoUnarchiveGate.coldPass(at: Instant = archivedAt): Int =
        if (markFired(convoId, at)) 1 else 0

    @Test
    fun coldPassRepeatedWhileTheWriteNeverLands_enqueuesOnce() {
        val gate = AutoUnarchiveGate()
        val enqueued = (1..5).sumOf { gate.coldPass() }
        assertEquals(1, enqueued, "a stuck unarchive must not re-enqueue on every sync")
    }

    @Test
    fun reArchivingStampsANewBaseline_soTheGateReopens() {
        val gate = AutoUnarchiveGate()
        gate.coldPass()

        val reArchivedAt = Instant.fromEpochMilliseconds(5_000)
        assertEquals(1, gate.coldPass(reArchivedAt), "a thread archived again must still auto-unarchive")
        assertEquals(0, gate.coldPass(reArchivedAt))
    }

    @Test
    fun conversationsAreGatedIndependently() {
        val gate = AutoUnarchiveGate()
        assertTrue(gate.markFired(convoId, archivedAt))
        assertTrue(gate.markFired(Uuid.random(), archivedAt))
    }
}
