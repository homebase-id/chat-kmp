package id.homebase.chat.services

import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pins the explicit local-only rule in `ChatMessageSenderService.resolveRecipients`
 * (#934). Local-only must be a deliberate classification, never an inference from
 * an empty participant lookup:
 *  - recipientOverride != null        -> caller forced the set (heal's emptyList())
 *  - id == ConversationWithYourselfId -> note-to-self
 *  - participants non-empty, all self -> legacy self-1:1
 *  - anything else with zero resolved recipients -> throw; nothing enqueued.
 *
 * The regression this guards: an existing archived 1:1 whose in-memory row had
 * empty participants was misclassified as note-to-self and uploaded with
 * allowDistribution=false — "sent" locally, never delivered.
 */
class ChatMessageSenderServiceRecipientRuleTest {

    /** Deserialize an outbox row as [UploadFileRequest], or null if it's a different type. */
    private fun Outbox.asUploadFileRequestOrNull(): UploadFileRequest? {
        if (this.uploadType != DriveOutboxUploader.UploadNewFile) return null
        return try {
            OdinSystemSerializer.deserialize<UploadFileRequest>(this.json.decodeToString())
        } catch (_: Throwable) {
            null
        }
    }

    /** Register a conversation whose in-memory row carries NO participants —
     *  the corrupt/placeholder shape that used to be mistaken for note-to-self. */
    private fun ChatMessageSenderServiceTestFixture.seedParticipantlessConversation(
        conversationId: Uuid = Uuid.random(),
    ): Uuid {
        conversationLookup.register(
            ConversationUiModel(
                id = conversationId,
                name = "broken",
                lastMessage = "",
                latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
                admins = setOf(OdinId(testDomain)),
                unreadCount = 0,
                avatarTiny = null,
                avatarInitials = "",
                avatarUrl = "",
                participants = emptyList(),
                lastRead = Instant.fromEpochMilliseconds(0),
                avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
                isGroup = false,
                conversationState = ConversationState.Active,
            )
        )
        return conversationId
    }

    @Test
    fun participantlessConversation_sendThrows_andNothingIsEnqueued() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedParticipantlessConversation()

            assertFailsWith<IllegalStateException> {
                service.sendNewMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = conversationId,
                    messageText = "hi",
                    previousMessageUniqueId = null,
                    payloadBundle = null,
                )
            }
            assertEquals(
                0, fixture.outboxRowCount(),
                "a refused send must not leave anything in the outbox"
            )
        }
    }

    @Test
    fun legacySelfOneToOne_participantsAllSelf_isLocalOnly() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            // participants = [testDomain] only — a legacy chat-with-your-own-identity.
            val conversationId = fixture.seedConversation(others = emptyList())

            val messageId = Uuid.random()
            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "to myself",
                previousMessageUniqueId = null,
                payloadBundle = null,
            )

            val request = fixture.drainOutboxInDependencyOrder()
                .single { it.uniqueId == messageId }
                .asUploadFileRequestOrNull()
            assertNotNull(request)
            assertFalse(request.metadata.allowDistribution, "self conversation must stay local-only")
            assertTrue(request.transitOptions?.recipients.isNullOrEmpty())
        }
    }

    @Test
    fun noteToSelf_wellKnownId_isLocalOnly() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(
                conversationId = ChatProtocol.ConversationWithYourselfId,
                others = emptyList(),
            )

            val messageId = Uuid.random()
            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "note",
                previousMessageUniqueId = null,
                payloadBundle = null,
            )

            val request = fixture.drainOutboxInDependencyOrder()
                .single { it.uniqueId == messageId }
                .asUploadFileRequestOrNull()
            assertNotNull(request)
            assertFalse(request.metadata.allowDistribution)
            assertTrue(request.transitOptions?.recipients.isNullOrEmpty())
        }
    }

    @Test
    fun explicitEmptyRecipientOverride_healContract_isLocalOnlyAndDoesNotThrow() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            // Conversation HAS a peer — the override must still win (GroupHealService
            // forces a local-only status write with recipientOverride = emptyList()).
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))

            val messageId = Uuid.random()
            service.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(StatusMessage.GroupHealLocalCleanup),
                previousMessageUniqueId = null,
                payloadBundle = null,
                additionalRecipients = emptyList(),
                recipientOverride = emptyList(),
            )

            val request = fixture.drainOutboxInDependencyOrder()
                .single { it.uniqueId == messageId }
                .asUploadFileRequestOrNull()
            assertNotNull(request)
            assertFalse(request.metadata.allowDistribution, "explicit empty override = intentional local-only")
            assertTrue(request.transitOptions?.recipients.isNullOrEmpty())
        }
    }

    @Test
    fun participantlessConversation_updateMessageThrows() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val messageId = Uuid.random()
            val versionTag = Uuid.random()
            val conversationId = Uuid.random()
            val keyHeader = id.homebase.api.client.KeyHeader.newRandom16()

            val seededMessage = id.homebase.chat.data.MessageUiModel(
                id = messageId,
                globalTransitId = null,
                fileId = Uuid.random(),
                conversationId = conversationId,
                content = "original",
                userDate = Instant.fromEpochMilliseconds(1_000L),
                modified = null,
                created = Instant.fromEpochMilliseconds(1_000L),
                originalAuthor = OdinId(fixture.testDomain),
                sender = OdinId(fixture.testDomain),
                displayName = fixture.testDomain,
                isDeleted = false,
                isPendingSend = false,
                versionTag = versionTag,
                messageAppData = MessageAppData(),
                reactionPreview = null,
                previewThumbnail = null,
                payloads = kotlinx.collections.immutable.persistentListOf(),
                keyHeader = keyHeader,
                hasMore = false,
            )
            val service = fixture.build(
                scope = this,
                messageLookup = { _ ->
                    object : MessageLookup by FakeMessageLookup() {
                        override suspend fun getMessage(messageId: Uuid) =
                            seededMessage.takeIf { it.id == messageId }
                    }
                },
            )
            fixture.seedParticipantlessConversation(conversationId)

            assertFailsWith<IllegalStateException> {
                service.updateMessage(
                    messageId = messageId,
                    versionTag = versionTag,
                    content = "edited",
                )
            }
            assertEquals(0, fixture.outboxRowCount(), "a refused edit must not enqueue anything")
        }
    }

    @Test
    fun normalOneToOne_distributesToPeer() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))

            val messageId = Uuid.random()
            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "hello",
                previousMessageUniqueId = null,
                payloadBundle = null,
            )

            val request = fixture.drainOutboxInDependencyOrder()
                .single { it.uniqueId == messageId }
                .asUploadFileRequestOrNull()
            assertNotNull(request)
            assertTrue(request.metadata.allowDistribution)
            assertEquals(listOf(OdinId("alice.test")), request.transitOptions?.recipients)
            assertEquals(true, request.transitOptions?.useAppNotification)
        }
    }
}
