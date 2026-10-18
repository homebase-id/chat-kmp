package id.homebase.chat.viewonce

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.decodeReactionCode
import id.homebase.chat.services.mapToMessageData
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** An opened view-once is a placeholder that still takes replies and reactions; a real deletion is not. */
class ViewOnceOpenedPlaceholderTest {

    private val now = Clock.System.now().toEpochMilliseconds()
    private val thumbsUp = "👍"
    private val heart = "❤️"

    private fun MessageUiModel.reactionCodes() =
        reactionPreview?.reactions?.values.orEmpty().map { decodeReactionCode(it.reactionContent) }

    private class Opened(
        val fixture: ChatMessageActionServiceTestFixture,
        val conversationId: Uuid,
        val fileId: Uuid,
        val messageId: Uuid,
    ) {
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm)

        suspend fun upsert(header: HomebaseFile) =
            processor.baseUpsertEntryZapZap(fixture.testIdentityId, fixture.chatDriveId, header, null)

        suspend fun model(): MessageUiModel {
            val stored = assertNotNull(
                fixture.dbm.driveMainIndex.selectByIdentityAndDriveAndFile(fixture.testIdentityId, fixture.chatDriveId, fileId),
            )
            return assertNotNull(mapToMessageData(processor.convertDriveMainIndexRecordToFileHeader(stored), fixture.credentialsManager))
        }
    }

    private suspend fun reactInViewerThenClose(fixture: ChatMessageActionServiceTestFixture, scope: TestScope): Opened {
        val service = fixture.build(scope = scope)
        val opened = Opened(fixture, fixture.seedOneOnOneConversation(other = VO_SENDER), Uuid.random(), Uuid.random())
        opened.upsert(
            viewOnceHeader(
                fileId = opened.fileId, uniqueId = opened.messageId, conversationId = opened.conversationId, createdMs = now - DAY_MS,
            ),
        )
        fixture.seedMessage(
            conversationId = opened.conversationId, senderDomain = VO_SENDER, userDateMs = now - DAY_MS,
            id = opened.messageId, fileId = opened.fileId,
        )
        val actions = ViewOnceActions(service, fixture.credentialsManager)
        actions.onReact(opened.conversationId, opened.messageId, thumbsUp)
        actions.onViewerClosed(opened.conversationId, opened.messageId)
        return opened
    }

    // What WriteDeletedFileHeader sends back for the recipient's copy: no uniqueId, content, preview or local app data.
    private fun Opened.serverTombstone(updatedMs: Long, reactionPreviewJson: String = "null", localReactions: List<String> = emptyList()) =
        viewOnceHeader(
            fileId = fileId, uniqueId = null, conversationId = conversationId, fileState = "deleted",
            createdMs = now - DAY_MS, updatedMs = updatedMs, content = "", payloadsJson = "null",
            reactionPreviewJson = reactionPreviewJson, localReactions = localReactions,
        )

    @Test
    fun theInViewerReactionStaysOnTheOpenedPlaceholderAfterTheServerTombstone() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val opened = reactInViewerThenClose(fixture, this)
            opened.upsert(opened.serverTombstone(updatedMs = now + 60_000))

            val model = opened.model()
            assertTrue(model.isDeleted)
            assertNull(model.payloads, "the media never comes back")
            assertTrue(thumbsUp in model.reactionCodes(), "the viewer emoji survives the server tombstone: ${model.reactionCodes()}")
            assertTrue(thumbsUp in model.ownReactions, "and still reads as this identity's own: ${model.ownReactions}")
            assertTrue(model.acceptsActions, "the opened placeholder still takes replies and reactions")
        }
    }

    @Test
    fun aReactionOnThePlaceholderAfterTheDeleteDoesNotDropTheViewerOne() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val opened = reactInViewerThenClose(fixture, this)
            opened.upsert(opened.serverTombstone(updatedMs = now + 60_000))
            val heartJson = """{\"emoji\":\"$heart\"}"""
            opened.upsert(
                opened.serverTombstone(
                    updatedMs = now + 120_000,
                    reactionPreviewJson = """{"reactions":{"h":{"key":"h","count":1,"reactionContent":"$heartJson"}}}""",
                    localReactions = listOf("""{"emoji":"$heart"}"""),
                ),
            )

            val model = opened.model()
            assertEquals(setOf(thumbsUp, heart), model.reactionCodes().filterNotNull().filterNot { it.startsWith("_") }.toSet())
            assertTrue(thumbsUp in model.ownReactions && heart in model.ownReactions, "own: ${model.ownReactions}")
        }
    }

    @Test
    fun aReceivedOpenedTombstoneAcceptsActions() = runTest {
        val model = assertNotNull(
            mapToMessageData(serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS), ownerCredentials()),
        )
        assertEquals(ViewOnceState.Opened, ViewOnceRules.stateOf(model, now, ownerCredentials().requireActiveDomain()))
        assertTrue(model.isOpenedViewOnce)
        assertTrue(model.acceptsActions)
    }

    @Test
    fun theSendersOwnDeletedViewOnceIsARealDeletion() = runTest {
        val model = assertNotNull(
            mapToMessageData(serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS, author = VO_OWNER), ownerCredentials()),
        )
        assertFalse(model.acceptsActions)
    }

    @Test
    fun anExpiredUnopenedViewOnceDoesNotAcceptActions() = runTest {
        val created = now - 40 * DAY_MS
        val model = assertNotNull(
            mapToMessageData(serverTombstone(createdMs = created, updatedMs = created + 31 * DAY_MS), ownerCredentials()),
        )
        assertFalse(model.acceptsActions)
    }

    @Test
    fun anOrdinaryDeletedMessageDoesNotAcceptActions() = runTest {
        val model = assertNotNull(
            mapToMessageData(serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS, dataType = 0), ownerCredentials()),
        )
        assertTrue(model.isDeleted)
        assertFalse(model.acceptsActions)
    }
}
