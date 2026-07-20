package id.homebase.chat.services

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.outbox.OptimisticWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Phase A data-layer tests for the per-message pin tag
 * ([ChatProtocol.MessagePinnedTag]) and the files-by-tag query that powers the
 * pinned-messages bar. Mirrors the OptimisticWriter*Test style: real in-memory
 * DB via [ChatMessageActionServiceTestFixture], no mocks.
 *
 * The pin/unpin lane mirrors ConversationService.updateConversationTags against a
 * MESSAGE file: an optimistic local tag write (read back from DriveLocalTagIndex)
 * plus an UpdateLocalMetadataTags outbox row carrying the FULL new tag list.
 */
class MessagePinTagTest {

    private suspend fun ChatMessageActionServiceTestFixture.localTags(messageId: Uuid): List<Uuid> {
        val file = dbm.driveMainIndex.selectHomebaseFileByUnique(
            testIdentityId, chatDriveId, messageId,
        ) ?: error("expected DriveMainIndex row for $messageId")
        return dbm.driveLocalTagIndex.selectByFile(testIdentityId, chatDriveId, file.fileId)
            .map { it.tagId }
    }

    private fun List<id.homebase.api.sync.database.Outbox>.singleTagsRequest():
            UpdateLocalMetadataTagsOutboxRequest =
        OdinSystemSerializer.deserialize(
            single { it.uploadType == DriveOutboxUploader.UpdateLocalMetadataTags }
                .json.decodeToString()
        )

    @Test
    fun pinMessage_addsTagLocally_andEnqueuesOutboxRowWithFullTagList() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val messageId = fixture.seedDeletableMessage(conversationId = convoId)

            service.pinMessage(messageId)

            assertEquals(
                listOf(ChatProtocol.MessagePinnedTag),
                fixture.localTags(messageId),
                "pin must write MessagePinnedTag to localAppData",
            )

            val request = fixture.drainOutbox().singleTagsRequest()
            assertEquals(
                listOf(ChatProtocol.MessagePinnedTag.toString()),
                request.tags,
                "outbox row must carry the FULL new tag list, not a delta",
            )
            assertEquals(
                messageId,
                request.uniqueId,
                "outbox row must carry the message uniqueId so the uploader can " +
                    "re-resolve the current fileId after a temp→server rekey (#887 own-send pin)",
            )
        }
    }

    @Test
    fun pinMessage_preservesPreExistingPendingSendTag() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            // isPendingSend seeds localAppData.tags = [isPendingSendTag] (mirrors a
            // just-sent optimistic message that hasn't confirmed yet).
            val messageId = fixture.seedDeletableMessage(
                conversationId = convoId,
                isPendingSend = true,
            )

            service.pinMessage(messageId)

            assertEquals(
                setOf(ChatProtocol.isPendingSendTag, ChatProtocol.MessagePinnedTag),
                fixture.localTags(messageId).toSet(),
                "pin must not clobber the pending-send tag",
            )

            val request = fixture.drainOutbox().singleTagsRequest()
            assertEquals(
                setOf(
                    ChatProtocol.isPendingSendTag.toString(),
                    ChatProtocol.MessagePinnedTag.toString(),
                ),
                request.tags?.toSet(),
                "outbox tag list must include the pre-existing pending-send tag",
            )
        }
    }

    @Test
    fun unpinMessage_removesOnlyMessagePinnedTag() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val messageId = fixture.seedDeletableMessage(
                conversationId = convoId,
                isPendingSend = true,
            )

            service.pinMessage(messageId)
            service.unpinMessage(messageId)

            assertEquals(
                listOf(ChatProtocol.isPendingSendTag),
                fixture.localTags(messageId),
                "unpin must remove ONLY MessagePinnedTag, leaving other local tags intact",
            )

            // The final (unpin) outbox row carries the remaining tag list.
            val rows = fixture.drainOutbox()
                .filter { it.uploadType == DriveOutboxUploader.UpdateLocalMetadataTags }
            val lastRequest: UpdateLocalMetadataTagsOutboxRequest =
                OdinSystemSerializer.deserialize(rows.last().json.decodeToString())
            assertEquals(
                listOf(ChatProtocol.isPendingSendTag.toString()),
                lastRequest.tags,
                "unpin outbox row must drop MessagePinnedTag but keep the pending-send tag",
            )
        }
    }

    @Test
    fun pinMessage_isIdempotent_secondPinDoesNotEnqueueOrDuplicate() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = Uuid.random()
            val messageId = fixture.seedDeletableMessage(conversationId = convoId)

            service.pinMessage(messageId)
            service.pinMessage(messageId) // already pinned — no-op

            assertEquals(
                listOf(ChatProtocol.MessagePinnedTag),
                fixture.localTags(messageId),
                "re-pinning must not duplicate the tag",
            )
            val tagRows = fixture.drainOutbox()
                .count { it.uploadType == DriveOutboxUploader.UpdateLocalMetadataTags }
            assertEquals(1, tagRows, "the idempotent second pin must not enqueue another row")
        }
    }

    @Test
    fun selectFilesByLocalTagInGroup_returnsOnlyActiveGroupMessages_newestFirst() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoA = Uuid.random()
            val convoB = Uuid.random()

            // convoA: three pinned (distinct userDates), one left unpinned.
            val older = fixture.seedDeletableMessage(conversationId = convoA, userDateMs = 100L)
            val newest = fixture.seedDeletableMessage(conversationId = convoA, userDateMs = 300L)
            val middle = fixture.seedDeletableMessage(conversationId = convoA, userDateMs = 200L)
            val unpinned = fixture.seedDeletableMessage(conversationId = convoA, userDateMs = 400L)
            // convoB: a pin in a different conversation must not leak into convoA.
            val otherGroup = fixture.seedDeletableMessage(conversationId = convoB, userDateMs = 500L)

            service.pinMessage(older)
            service.pinMessage(newest)
            service.pinMessage(middle)
            service.pinMessage(otherGroup)
            // `unpinned` is deliberately never pinned.

            suspend fun queryConvoA(): List<Uuid> = fixture.queryPinnedUids(convoA)

            assertEquals(
                listOf(newest, middle, older),
                queryConvoA(),
                "query must return only the pinned convoA messages, newest-first, " +
                        "excluding the unpinned one and the other conversation's pin",
            )
            assertFalse(queryConvoA().contains(unpinned))
            assertFalse(queryConvoA().contains(otherGroup))

            // Active-only: soft-delete the newest pinned message (flips fileState to
            // Deleted while keeping the pin tag) — it must drop out of the bar.
            OptimisticWriter(
                credentialsManager = fixture.credentialsManager,
                dbm = fixture.dbm,
                eventBus = fixture.eventBus,
                outboxSync = fixture.outboxSync,
            ).writeDelete(fixture.chatDriveId, newest)

            assertEquals(
                listOf(middle, older),
                queryConvoA(),
                "a soft-deleted (but still tagged) message must be excluded by the active filter",
            )
        }
    }
}

/** Run the files-by-tag query for [conversationId] and return uniqueIds, newest-first. */
private suspend fun ChatMessageActionServiceTestFixture.queryPinnedUids(
    conversationId: Uuid,
): List<Uuid> {
    val jsonHeaders = dbm.driveLocalTagIndex.selectJsonHeadersByLocalTagInGroup(
        identityId = testIdentityId,
        driveId = chatDriveId,
        tagId = ChatProtocol.MessagePinnedTag,
        groupId = conversationId,
    )
    return jsonHeaders.map {
        OdinSystemSerializer.deserialize<HomebaseFile>(it).fileMetadata.appData.uniqueId!!
    }
}
