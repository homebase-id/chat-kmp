package id.homebase.chat.viewonce

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.ConversationUiModel.Companion.updateWithLatestMessage
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.mapToMessageData
import id.homebase.chat.widget.ContentLabel
import id.homebase.chat.widget.messageContentLabel
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.api.common.OdinId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The spent item must keep reading Photo or Video after the server's own tombstone (which blanks the
 * content) replaces the local one, as it does after a restart or a sync on any device.
 */
@OptIn(ExperimentalTestApi::class)
class ViewOnceTombstoneKindTest {

    private val now = Clock.System.now().toEpochMilliseconds()

    private fun descriptor(kind: String) = """{\"schemaVersion\":1,\"kind\":\"$kind\",\"caption\":\"secret\"}"""

    private suspend fun openThenServerTombstone(fixture: ChatMessageActionServiceTestFixture, kind: String, scope: kotlinx.coroutines.test.TestScope): MessageUiModel {
        val service = fixture.build(scope = scope)
        val conversationId = fixture.seedOneOnOneConversation(other = VO_SENDER)
        val fileId = Uuid.random()
        val messageId = Uuid.random()
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm)
        processor.baseUpsertEntryZapZap(
            fixture.testIdentityId, fixture.chatDriveId,
            viewOnceHeader(
                fileId = fileId, uniqueId = messageId, conversationId = conversationId,
                createdMs = now - DAY_MS, content = descriptor(kind),
            ),
            null,
        )
        fixture.seedMessage(
            conversationId = conversationId, senderDomain = VO_SENDER, userDateMs = now - DAY_MS, id = messageId, fileId = fileId,
        )
        ViewOnceActions(service, fixture.credentialsManager).onViewerClosed(conversationId, messageId)

        processor.baseUpsertEntryZapZap(
            fixture.testIdentityId, fixture.chatDriveId,
            viewOnceHeader(
                fileId = fileId, uniqueId = null, conversationId = conversationId, fileState = "deleted",
                createdMs = now - DAY_MS, updatedMs = now + 60_000, content = "", payloadsJson = "null",
            ),
            null,
        )
        val stored = assertNotNull(
            fixture.dbm.driveMainIndex.selectByIdentityAndDriveAndFile(fixture.testIdentityId, fixture.chatDriveId, fileId),
        )
        val file = MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm).convertDriveMainIndexRecordToFileHeader(stored)
        return assertNotNull(mapToMessageData(file, fixture.credentialsManager))
    }

    private fun labelOf(message: MessageUiModel): ContentLabel? {
        val convo = ConversationUiModel(
            id = Uuid.random(),
            name = "convo",
            lastMessage = "",
            latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
            avatarInitials = "",
            avatarTiny = null,
            lastRead = Instant.fromEpochMilliseconds(0),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
            admins = emptySet(),
            participants = listOf(OdinId(VO_OWNER)),
        )
        var label: ContentLabel? = null
        runComposeUiTest {
            kotlinx.coroutines.runBlocking {
                val updated = convo.updateWithLatestMessage(message, activeUserDomain = OdinId(VO_OWNER))
                setContent {
                    label = messageContentLabel(
                        textContent = updated.lastMessage,
                        isDeleted = updated.lastMessageIsDeleted,
                        firstPayload = updated.lastMessageFirstPayload,
                        hasMultiplePayloads = updated.lastMessageHasMultiplePayloads,
                        messageContent = updated.lastMessageContent,
                    )
                }
            }
            waitForIdle()
        }
        return label
    }

    @Test
    fun anOpenedPhotoStillReadsPhotoAfterTheServerTombstoneReplacesTheLocalOne() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val model = openThenServerTombstone(fixture, "image", this)

            val kept = (model.messageContent as MessageContent.ViewOnce).descriptor
            assertEquals(ViewOnceDescriptor.KIND_IMAGE, kept?.kind)
            assertNull(kept?.caption, "the caption never outlives the viewing")
            assertNull(model.payloads)
            assertEquals("Photo", labelOf(model)?.text)
            assertEquals(ViewOnceOpenedIcon, labelOf(model)?.icon)
        }
    }

    @Test
    fun anOpenedVideoStillReadsVideoAfterTheServerTombstoneReplacesTheLocalOne() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val model = openThenServerTombstone(fixture, "video", this)

            assertEquals(ViewOnceDescriptor.KIND_VIDEO, (model.messageContent as MessageContent.ViewOnce).descriptor?.kind)
            assertEquals("Video", labelOf(model)?.text)
        }
    }

    private suspend fun serverTombstoneOnly(fixture: ChatMessageActionServiceTestFixture, kind: String): Pair<String, MessageUiModel> {
        val conversationId = fixture.seedOneOnOneConversation(other = VO_SENDER)
        val fileId = Uuid.random()
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm)
        processor.baseUpsertEntryZapZap(
            fixture.testIdentityId, fixture.chatDriveId,
            viewOnceHeader(fileId = fileId, conversationId = conversationId, createdMs = now - DAY_MS, content = descriptor(kind)),
            null,
        )
        processor.baseUpsertEntryZapZap(
            fixture.testIdentityId, fixture.chatDriveId,
            viewOnceHeader(
                fileId = fileId, uniqueId = null, conversationId = conversationId, fileState = "deleted",
                createdMs = now - DAY_MS, updatedMs = now + 60_000, content = "", payloadsJson = "null",
            ),
            null,
        )
        val stored = assertNotNull(
            fixture.dbm.driveMainIndex.selectByIdentityAndDriveAndFile(fixture.testIdentityId, fixture.chatDriveId, fileId),
        )
        val content = OdinSystemSerializer.deserialize<HomebaseFile>(stored.jsonHeader).fileMetadata.appData.content.orEmpty()
        return content to assertNotNull(mapToMessageData(processor.convertDriveMainIndexRecordToFileHeader(stored), fixture.credentialsManager))
    }

    @Test
    fun aTombstoneArrivingFromAnotherDeviceDropsTheCaptionButKeepsTheKind() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val (stored, model) = serverTombstoneOnly(fixture, "video")

            assertFalse("caption" in stored, "the stored header never carries the caption")
            assertEquals("video", OdinSystemSerializer.json.parseToJsonElement(stored).jsonObject["kind"]?.jsonPrimitive?.content)
            val kept = (model.messageContent as MessageContent.ViewOnce).descriptor
            assertEquals(ViewOnceDescriptor.KIND_VIDEO, kept?.kind)
            assertNull(kept?.caption)
            assertEquals("Video", labelOf(model)?.text)
        }
    }

    @Test
    fun aTombstoneOfAnotherKindIsStillBlankedByTheServerRow() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            fixture.build(scope = this)
            val fileId = Uuid.random()
            val conversationId = fixture.seedOneOnOneConversation(other = VO_SENDER)
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(fixture.dbm)
            processor.baseUpsertEntryZapZap(
                fixture.testIdentityId, fixture.chatDriveId,
                viewOnceHeader(fileId = fileId, conversationId = conversationId, createdMs = now - DAY_MS, dataType = 7, content = "hello"),
                null,
            )
            processor.baseUpsertEntryZapZap(
                fixture.testIdentityId, fixture.chatDriveId,
                viewOnceHeader(
                    fileId = fileId, uniqueId = null, conversationId = conversationId, fileState = "deleted",
                    createdMs = now - DAY_MS, updatedMs = now, dataType = 7, content = "", payloadsJson = "null",
                ),
                null,
            )
            val stored = assertNotNull(
                fixture.dbm.driveMainIndex.selectByIdentityAndDriveAndFile(fixture.testIdentityId, fixture.chatDriveId, fileId),
            )
            assertEquals("", processor.convertDriveMainIndexRecordToFileHeader(stored).fileMetadata.appData.content)
        }
    }
}
