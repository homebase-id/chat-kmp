package id.homebase.chat.services.convo

import id.homebase.chat.data.ConversationState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Locks the cold-path half of auto-unarchive (#1145). The live WS path was
 * already covered; the bug was that DriveSync — FCM cold-wake, reconnect
 * catch-up — writes message rows silently, so nothing re-evaluated the archive.
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
