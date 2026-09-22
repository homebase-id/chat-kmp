package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * [ConversationService.updateLocalDraft] dedups against the last draft it stored
 * so a burst of edits (or the restore echo) makes no outbox traffic. This pins
 * the ordering that dedup depends on: the guard must only advance once the write
 * has actually landed.
 *
 * If it advanced up-front, a write that never happened would still mark the draft
 * as stored — and since the retry carries the *same* text, dedup would swallow it
 * forever. That retry is the leaving-the-thread save, so the failure mode is a
 * silently lost draft. #1122.
 */
class ConversationServiceDraftGuardTest {

    @Test
    fun aWriteThatDidNotLandDoesNotPoisonTheDedupGuard() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = scope)

                // No conv file in the local index yet: stampConversationDraft finds
                // nothing to stamp, so nothing is stored.
                val convoId = Uuid.random()
                service.updateLocalDraft(convoId, "half a thought")
                assertEquals(null, service.readDraft(convoId))

                // The file arrives (sync landed it). The same draft text must still
                // be written — the earlier no-op must not have claimed it.
                fixture.seedOneOnOne(other = "alice.test", conversationId = convoId)
                service.updateLocalDraft(convoId, "half a thought")

                assertEquals("half a thought", service.readDraft(convoId))
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Composing straight through and sending never trips the idle debounce, so the
     * overwhelming majority of sends have no draft stored. Clearing one that was
     * never written must cost nothing — otherwise every message sent bills a stamp
     * and an outbox push to erase something that doesn't exist.
     */
    @Test
    fun clearingADraftThatWasNeverWrittenCostsNothing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = scope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                // Opening the conversation seeds the guard — no draft stored.
                assertEquals(null, service.readDraft(convoId))

                val outboxBefore = fixture.outboxRowCount()
                val updatedBefore = fixture.getConversationFile(convoId)!!.fileMetadata.updated

                service.clearLocalDraft(convoId)

                assertEquals(outboxBefore, fixture.outboxRowCount(), "no outbox push")
                assertEquals(
                    updatedBefore,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated,
                    "conv file untouched",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * The send-time clear used to skip the write whenever its in-memory guard read
     * "cleared" — but the guard only tracks what THIS composer wrote. A draft can be
     * persisted behind its back: a peer device's draft merged in by the drive sync, or
     * this device's own idle flush still inside its read-modify-write when the send
     * finishes. Both left the stale draft in `localAppData`, to be restored into the
     * composer on the next entry — the sent-message-reappears bug. #1492.
     */
    @Test
    fun theSendClearsADraftTheGuardNeverSaw() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = scope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                // Opening the conversation seeds the guard with "no draft stored".
                assertEquals(null, service.readDraft(convoId))

                // Persist a draft WITHOUT going through updateLocalDraft, so the guard
                // still reads "cleared". Not via readDraft — that would reseed the guard
                // and hide the very thing under test.
                fixture.optimisticWriter.stampConversationDraft(
                    fixture.chatDriveId,
                    convoId,
                    "the long message I just sent",
                    UnixTimeUtc(),
                )
                assertTrue(
                    fixture.getConversationFile(convoId)!!.fileMetadata.localAppData?.content
                        ?.contains("the long message I just sent") == true,
                    "seed failed: no draft was persisted",
                )

                service.clearLocalDraft(convoId)

                assertEquals(null, service.readDraft(convoId), "the send left a stale draft behind")
            }
        } finally {
            scope.cancel()
        }
    }
}
