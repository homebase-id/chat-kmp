package id.homebase.chat.services

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.BatchResult
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * A contact card is the first typed kind with `allowForward = true`, and a typed kind stores its
 * descriptor VERBATIM in `appData.content`. `MessageAppData` has a default for every field and the
 * serializer ignores unknown keys, so re-wrapping the descriptor in that envelope succeeds silently
 * and lands a card with no `displayName` — which fails `ContactCardDescriptor`'s required-field
 * decode and paints the receiver's unsupported-format chip instead of the card.
 */
class ChatMessageSenderServiceForwardTest {

    private class DbBackedLookup : MessageLookup {
        var fileLookup: suspend (Uuid) -> HomebaseFile? = { null }

        override suspend fun getMessage(messageId: Uuid): MessageUiModel? = null

        override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? = fileLookup(messageId)

        override suspend fun getMessages(
            messageIds: List<Uuid>,
            conversationId: Uuid,
        ): BatchResult<MessageUiModel> =
            BatchResult(records = emptyList(), hasMoreRows = false, cursor = QueryBatchCursor())

        override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? = null

        override fun findCachedFileId(messageId: Uuid): Uuid? = null
    }

    private suspend fun ChatMessageSenderServiceTestFixture.headerContentOf(uniqueId: Uuid): String? =
        dbm.driveMainIndex
            .selectHomebaseFileByUnique(testIdentityId, chatDriveId, uniqueId)
            ?.fileMetadata?.appData?.content

    @Test
    fun `forwarding a contact card carries the descriptor itself, not a MessageAppData envelope`() = runTest {
        val lookup = DbBackedLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(messageLookup = { _: DatabaseManager -> lookup })
            lookup.fileLookup = { uid ->
                fixture.dbm.driveMainIndex
                    .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, uid)
            }

            val source = fixture.seedConversation(others = listOf("bob.test"))
            val target = fixture.seedConversation(others = listOf("carol.test"))
            val messageId = Uuid.random()
            val descriptor = ContactCardDescriptor(
                displayName = "Ada Vance",
                phones = listOf("+14155550123"),
                emails = listOf("ada@example.com"),
            )

            service.sendNewTypedMessage(
                messageUniqueId = messageId,
                conversationId = source,
                content = MessageContent.ContactCard(descriptor),
                previousMessageUniqueId = null,
            )

            val forwarded = service.forwardMessage(messageId, listOf(target))

            val storedContent = fixture.headerContentOf(forwarded.single().uniqueId)
            assertNotNull(storedContent, "the forwarded card must have header content")

            val reparsed = MessageContentParser.parse(
                ChatProtocol.ChatContactCardMessageDataType,
                storedContent,
            )
            assertEquals(
                descriptor,
                (reparsed as? MessageContent.ContactCard)?.descriptor,
                "a forwarded contact card must re-parse to the card that was forwarded",
            )
        }
    }

    /**
     * The other side of the branch: a text message's content IS a MessageAppData, and forwarding it
     * has to rebuild that envelope rather than copy it — a forward does not carry the quote of the
     * message it came from.
     */
    @Test
    fun `forwarding a text reply still rebuilds the envelope, dropping the quote`() =
        runTest {
            val lookup = DbBackedLookup()

            ChatMessageSenderServiceTestFixture().use { fixture ->
                val service = fixture.build(messageLookup = { _: DatabaseManager -> lookup })
                lookup.fileLookup = { uid ->
                    fixture.dbm.driveMainIndex
                        .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, uid)
                }

                val source = fixture.seedConversation(others = listOf("bob.test"))
                val target = fixture.seedConversation(others = listOf("carol.test"))
                val messageId = Uuid.random()

                service.replyToMessage(
                    messageUniqueId = messageId,
                    conversationId = source,
                    replyTo = ReplyPreview(
                        replyUniqueId = Uuid.random(),
                        authorOdinId = "bob.test",
                        message = "what is her number?",
                    ),
                    messageText = "here is the number",
                    previousMessageUniqueId = null,
                    payloadBundle = null,
                )

                val forwarded = service.forwardMessage(messageId, listOf(target))

                val storedContent = fixture.headerContentOf(forwarded.single().uniqueId)
                assertNotNull(storedContent, "the forwarded text must have header content")

                val envelope = OdinSystemSerializer.deserialize<MessageAppData>(storedContent)
                assertEquals("here is the number", envelope.getMessage())
                assertNull(envelope.replyPreview, "a forward is not a reply to the original's parent")
            }
        }
}
