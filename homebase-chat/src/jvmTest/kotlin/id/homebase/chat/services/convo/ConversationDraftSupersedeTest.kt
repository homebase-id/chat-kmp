package id.homebase.chat.services.convo

import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OutboxSerializer
import id.homebase.api.sync.database.Outbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

class ConversationDraftSupersedeTest {

    private val draft = "the message I just sent"

    private fun Outbox.content(): String =
        OutboxSerializer.decode<UpdateLocalAppdataContentOutboxRequest>(this).content.orEmpty()

    private fun inFixture(body: suspend (ConversationServiceTestFixture, ConversationService, Uuid) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                ConversationServiceTestFixture().use { fixture ->
                    val service = fixture.build(scope = scope)
                    body(fixture, service, fixture.seedOneOnOne(other = "alice.test"))
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun clearBehindAPendingDraftRowSendsTheClearLast() = inFixture { fixture, service, convoId ->
        service.updateLocalDraft(convoId, draft)
        service.clearLocalDraft(convoId)

        val sent = fixture.drainOutboxInDependencyOrder()
        assertFalse(sent.last().content().contains(draft), "last content sent still carries the draft")
    }

    @Test
    fun clearBehindAPendingLastReadRowSendsTheClearLast() = inFixture { fixture, service, convoId ->
        fixture.optimisticWriter.stampConversationDraft(fixture.chatDriveId, convoId, draft, UnixTimeUtc())
        val lastRead = fixture.optimisticWriter
            .stampConversationLastReadTime(fixture.chatDriveId, convoId, UnixTimeUtc())!!
        fixture.outboxSync.tryEnqueue(lastRead, sendNow = false)
        assertTrue(fixture.outboxRowCount() == 1L)

        service.clearLocalDraft(convoId)

        val sent = fixture.drainOutboxInDependencyOrder()
        assertFalse(sent.last().content().contains(draft), "last content sent still carries the draft")
    }

    @Test
    fun clearWhileTheDraftRowIsInFlightSendsBothInOrder() = inFixture { fixture, service, convoId ->
        service.updateLocalDraft(convoId, draft)
        val inFlight = fixture.dbm.outbox.checkout()!!

        service.clearLocalDraft(convoId)
        fixture.dbm.outbox.deleteByRowId(inFlight.rowId)

        val sent = listOf(inFlight) + fixture.drainOutboxInDependencyOrder()
        assertEquals(2, sent.size)
        assertTrue(sent.first().content().contains(draft))
        assertFalse(sent.last().content().contains(draft), "last content sent still carries the draft")
    }

    @Test
    fun aFailedClearIsRetriedByTheNextClear() = inFixture { fixture, service, convoId ->
        service.updateLocalDraft(convoId, draft)
        fixture.drainOutboxInDependencyOrder()
        fixture.failOutboxInserts()
        service.clearLocalDraft(convoId)
        assertEquals(0L, fixture.outboxRowCount())

        fixture.allowOutboxInserts()
        service.updateLocalDraft(convoId, null)

        val sent = fixture.drainOutboxInDependencyOrder()
        assertEquals(1, sent.size)
        assertFalse(sent.last().content().contains(draft), "retry never reached the outbox")
    }
}
