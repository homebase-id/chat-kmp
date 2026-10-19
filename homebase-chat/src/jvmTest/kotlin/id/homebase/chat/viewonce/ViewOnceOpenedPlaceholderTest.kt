package id.homebase.chat.viewonce

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.content.MessageContent
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

/** An opened view-once is an ordinary, undeleted message with no media; only a legacy copy is a deletion. */
class ViewOnceOpenedPlaceholderTest {

    private val now = Clock.System.now().toEpochMilliseconds()
    private val thumbsUp = "👍"

    private fun MessageUiModel.reactionCodes() =
        reactionPreview?.reactions?.values.orEmpty().mapNotNull { decodeReactionCode(it.reactionContent) }

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
                fileId = opened.fileId, uniqueId = opened.messageId, conversationId = opened.conversationId,
                createdMs = now - DAY_MS, content = """{\"schemaVersion\":1,\"kind\":\"image\",\"caption\":\"secret\"}""",
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

    // What the server sends back once the strip lands: the same live header, payloads and caption gone.
    private fun Opened.serverStrippedCopy() = viewOnceHeader(
        fileId = fileId, uniqueId = messageId, conversationId = conversationId,
        createdMs = now - DAY_MS, updatedMs = now + 60_000, payloadsJson = "null",
        reactionPreviewJson = """{"reactions":{""" +
            """"a":{"key":"a","count":1,"reactionContent":"{\"emoji\":\"$thumbsUp\"}"},""" +
            """"b":{"key":"b","count":1,"reactionContent":"{\"emoji\":\"${ViewOnceSignal.OPENED_CODE}\"}"}""" +
            """}}""",
        localReactions = listOf("""{"emoji":"$thumbsUp"}""", """{"emoji":"${ViewOnceSignal.OPENED_CODE}"}"""),
    )

    private suspend fun assertOpenedCopy(model: MessageUiModel) {
        assertFalse(model.isDeleted, "an opened copy is not a deletion")
        assertTrue(model.payloads.isNullOrEmpty(), "the media never comes back")
        val descriptor = assertNotNull((model.messageContent as MessageContent.ViewOnce).descriptor)
        assertEquals(ViewOnceDescriptor.KIND_IMAGE, descriptor.kind)
        assertNull(descriptor.caption, "the caption never outlives the viewing")
        assertTrue(thumbsUp in model.reactionCodes(), "the viewer emoji is kept: ${model.reactionCodes()}")
        assertTrue(thumbsUp in model.ownReactions, "and reads as this identity's own: ${model.ownReactions}")
        val me = ownerCredentials().requireActiveDomain()
        assertEquals(ViewOnceState.Opened, ViewOnceRules.stateOf(model, now, me))
        assertNull(viewOnceViewerFor(model, now, me, mobile = true), "an opened copy never reopens")
    }

    @Test
    fun closingTheViewerLeavesAnUndeletedCopyWithNoMediaThatKeepsTheViewerReaction() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            assertOpenedCopy(reactInViewerThenClose(fixture, this).model())
        }
    }

    @Test
    fun theServersStrippedCopyReadsTheSameAfterSync() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val opened = reactInViewerThenClose(fixture, this)
            opened.upsert(opened.serverStrippedCopy())
            assertOpenedCopy(opened.model())
        }
    }

    @Test
    fun aLegacyDeletedCopyIsAPlainDeletion() = runTest {
        val model = assertNotNull(
            mapToMessageData(serverTombstone(createdMs = now - 2 * DAY_MS, updatedMs = now - DAY_MS), ownerCredentials()),
        )
        assertTrue(model.isDeleted)
        assertNull(viewOnceViewerFor(model, now, ownerCredentials().requireActiveDomain(), mobile = true))
    }
}
