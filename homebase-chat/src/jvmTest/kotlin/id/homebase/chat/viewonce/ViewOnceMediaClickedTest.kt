package id.homebase.chat.viewonce

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.chat.conversationlist.ConversationListUiState
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.conversationlist.MediaDownloadHandler
import id.homebase.chat.conversationlist.MessageListContentModel
import id.homebase.chat.conversationlist.MessageListUiState
import kotlinx.collections.immutable.persistentListOf
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.NoopFileOperationsProvider
import id.homebase.chat.services.mapToMessageData
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock

/** A 216 tap through the real handler: it opens the view-once viewer or nothing, never a generic viewer. */
class ViewOnceMediaClickedTest {

    private val now = Clock.System.now().toEpochMilliseconds()

    private class Harness(
        val handler: MediaDownloadHandler,
        val messages: MutableStateFlow<MessageListUiState>,
        val server: ViewOnceFakeServer,
    )

    private suspend fun harness(
        scope: TestScope,
        fixture: ChatMessageActionServiceTestFixture,
        mobile: Boolean? = null,
    ): Harness {
        val server = ViewOnceFakeServer().start()
        val service = fixture.build(scope = scope)
        val messages = MutableStateFlow(MessageListUiState())
        val ui = MutableStateFlow(
            ConversationListUiState(
                ownerSession = OwnerSession(
                    odinId = OdinId(VO_OWNER),
                    displayName = null,
                    firstName = null,
                    surName = null,
                    profileImageFileId = null,
                    profileImageFileKey = null,
                    profileImagePreviewThumbnail = null,
                    status = null,
                ),
            ),
        )
        val actions = ViewOnceActions(service, fixture.credentialsManager)
        val handler = MediaDownloadHandler(
            scope = scope.backgroundScope,
            uiState = ui,
            messagesUiState = messages,
            driveFileProvider = server.provider,
            fileOperationsProvider = NoopFileOperationsProvider(),
            chatMessageActionService = service,
            viewOnceActions = actions,
            chatMessageStream = unusedStream(),
            localVideoContextStore = LocalAttachmentContextStore(fixture.eventBus, scope.backgroundScope),
            sendEvent = {},
            dispatch = {},
            onMobile = mobile?.let { m -> { m } } ?: { id.homebase.core.util.isMobile() },
        )
        return Harness(handler, messages, server)
    }

    // The 216 branch returns before it reads the stream, and building a real one needs the whole sync stack.
    private fun unusedStream(): ChatMessageStream {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(ChatMessageStream::class.java) as ChatMessageStream
    }

    private suspend fun incoming(
        createdMs: Long = now - DAY_MS,
        localReactions: List<String> = emptyList(),
        author: String = VO_SENDER,
    ): MessageUiModel = mapToMessageData(
        viewOnceHeader(
            createdMs = createdMs,
            author = author,
            localReactions = localReactions.map { """{"emoji":"$it"}""" },
        ),
        ownerCredentials(),
    )!!

    private suspend fun TestScope.tap(h: Harness, message: MessageUiModel): FullScreenOverlay? {
        h.handler.handleMediaClicked(ConversationListUiAction.MediaClicked(message, VIEW_ONCE_PAYLOAD_KEY))
        runCurrent()
        return h.messages.value.fullScreenOverlay
    }

    @Test
    fun aCopyThatCannotBeOpenedLeavesTheOverlayNullInsteadOfFallingToAGenericViewer() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val phone = harness(this, fixture, mobile = true)
            assertNull(tap(phone, incoming(author = VO_OWNER)), "outgoing")
            assertNull(tap(phone, incoming(localReactions = listOf("_vo"))), "opened")
            assertNull(tap(phone, incoming(createdMs = now - ViewOnceRules.MAX_LIFESPAN_MS - 1)), "expired")

            val desktop = harness(this, fixture, mobile = false)
            assertNull(tap(desktop, incoming()), "non-mobile")
        }
    }

    @Test
    fun anUnopenedIncomingCopyOnAPhoneOpensOnlyTheViewOnceViewer() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val h = harness(this, fixture, mobile = true)
            val message = incoming()

            val overlay = tap(h, message)

            val viewer = assertIs<FullScreenOverlay.ViewOnceViewer>(overlay)
            assertEquals(message.id, viewer.messageId)
            assertEquals(0, h.server.requests, "opening the overlay reads nothing; the viewer does")
        }
    }

    @Test
    fun theViewerClosingClearsItsOverlaySoARemountCannotReopenTheSpentItem() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val h = harness(this, fixture, mobile = true)
            val message = incoming()
            val viewer = assertIs<FullScreenOverlay.ViewOnceViewer>(tap(h, message))

            h.handler.handleViewOnceViewerClosed(
                ConversationListUiAction.ViewOnceViewerClosed(viewer.conversationId, viewer.messageId),
            )

            assertNull(h.messages.value.fullScreenOverlay)
        }
    }

    @Test
    fun onTheRealDesktopPlatformAnUnopenedIncomingCopyNeverOpensAnOverlayOrTouchesThePayload() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            assertEquals(false, id.homebase.core.util.isMobile(), "this test is only meaningful where isMobile() is false")
            val h = harness(this, fixture)

            assertNull(tap(h, incoming()))

            assertEquals(0, h.server.requests)
            assertEquals(emptyList(), h.server.requestedPaths)
        }
    }

    @Test
    fun theLoaderRefusesToReadPayloadBytesWhereViewingIsBlocked() = runTest {
        val server = ViewOnceFakeServer().start()
        val blocked = ViewOncePayloadLoader(server.provider, server.fileOps, tempDir = { server.viewOnceTempDir }, canView = { false }) { _, _ -> }

        assertFailsWith<ViewOnceNotViewableHereException> { blocked.begin(server.fileId) }
        assertFailsWith<ViewOnceNotViewableHereException> {
            blocked.loadToTempFile(server.chatDriveId, server.fileId, VIEW_ONCE_PAYLOAD_KEY, server.keyHeader)
        }
        assertEquals(0, server.requests)
    }

    @Test
    fun replyingFromTheViewerQuotesTheUnopenedItemWithoutClosingOrReadingAnything() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val h = harness(this, fixture, mobile = true)
            val message = incoming()
            h.messages.value = MessageListUiState(
                messages = persistentListOf(MessageListContentModel.Message(message)),
            )
            val viewer = assertIs<FullScreenOverlay.ViewOnceViewer>(tap(h, message))

            h.handler.handleViewOnceReply(ConversationListUiAction.ViewOnceReply(viewer.messageId))

            assertEquals(message, h.messages.value.replyToMessage)
            assertEquals(viewer, h.messages.value.fullScreenOverlay, "the viewer's own single close path ends it, not the reply")
            assertEquals(0, h.server.requests)
        }
    }

    @Test
    fun aReplyForAMessageThatIsNotViewOnceOrNotLoadedQuotesNothing() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val h = harness(this, fixture, mobile = true)
            val plain = mapToMessageData(
                viewOnceHeader(createdMs = now - DAY_MS, dataType = 100, content = "hello"),
                ownerCredentials(),
            )!!
            h.messages.value = MessageListUiState(messages = persistentListOf(MessageListContentModel.Message(plain)))

            h.handler.handleViewOnceReply(ConversationListUiAction.ViewOnceReply(plain.id))
            h.handler.handleViewOnceReply(ConversationListUiAction.ViewOnceReply(kotlin.uuid.Uuid.random()))

            assertNull(h.messages.value.replyToMessage)
        }
    }
}
