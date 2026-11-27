package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.Base64Bytes
import id.homebase.chat.services.ChatMessageActionServiceTestFixture
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.NoopFileOperationsProvider
import id.homebase.chat.viewonce.ViewOnceActions
import id.homebase.chat.viewonce.ViewOnceFakeServer
import id.homebase.chat.viewonce.VO_OWNER
import id.homebase.chat.widget.canCopyImage
import id.homebase.resources.MR
import id.homebase.resources.chat_error_copy_image
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CopyImageActionTest {

    private fun payload(key: String, contentType: String, uploaded: Boolean = true) = PayloadDescriptor(
        key = key,
        contentType = contentType,
        iv = if (uploaded) Base64Bytes(ByteArray(16)) else null,
    )

    private fun viewer(vararg payloads: PayloadDescriptor) = FullScreenOverlay.ViewMessageData(
        messageId = Uuid.random(),
        title = "Alice",
        userDate = Instant.fromEpochMilliseconds(0),
        content = "",
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        payloads = payloads.toList(),
        keyHeader = KeyHeader.empty(),
        selectedPayloadKey = payloads.first().key,
    )

    @Test
    fun theViewerOffersCopyFromItsOwnPayloadsOnlyForAnUploadedImage() {
        assertTrue(viewer(payload("chat_img0", "image/jpeg")).canCopyImage())
        assertTrue(viewer(payload("chat_vid0", "video/mp4"), payload("chat_img1", "image/png")).canCopyImage())
        assertFalse(viewer(payload("chat_vid0", "video/mp4")).canCopyImage())
        assertFalse(viewer(payload("chat_img0", "image/jpeg", uploaded = false)).canCopyImage())
    }

    @Test
    fun aCopyForAMessageThatIsNeitherLoadedNorInTheViewerShowsTheCopyError() = runTest {
        val events = mutableListOf<ConversationListUiEvent>()
        ChatMessageActionServiceTestFixture().use { fixture ->
            val handler = handler(this, fixture, MutableStateFlow(MessageListUiState()), events)
            handler.handleCopyImage(ConversationListUiAction.CopyImage(Uuid.random(), "chat_img0"))
            runCurrent()
        }
        assertEquals(listOf<ConversationListUiEvent>(ConversationListUiEvent.ShowErrorMessage(MR.string.chat_error_copy_image)), events)
    }

    @Test
    fun aCopyOfANonImagePayloadInTheViewerShowsTheCopyError() = runTest {
        val events = mutableListOf<ConversationListUiEvent>()
        val overlay = viewer(payload("chat_vid0", "video/mp4"))
        ChatMessageActionServiceTestFixture().use { fixture ->
            val handler = handler(this, fixture, MutableStateFlow(MessageListUiState(fullScreenOverlay = overlay)), events)
            handler.handleCopyImage(ConversationListUiAction.CopyImage(overlay.messageId, "chat_vid0"))
            runCurrent()
        }
        assertEquals(listOf<ConversationListUiEvent>(ConversationListUiEvent.ShowErrorMessage(MR.string.chat_error_copy_image)), events)
    }

    private suspend fun handler(
        scope: TestScope,
        fixture: ChatMessageActionServiceTestFixture,
        messages: MutableStateFlow<MessageListUiState>,
        events: MutableList<ConversationListUiEvent>,
    ): MediaDownloadHandler {
        val server = ViewOnceFakeServer().start()
        val service = fixture.build(scope = scope)
        val owner = OwnerSession(
            odinId = OdinId(VO_OWNER), displayName = null, firstName = null, surName = null,
            profileImageFileId = null, profileImageFileKey = null, profileImagePreviewThumbnail = null, status = null,
        )
        return MediaDownloadHandler(
            scope = scope.backgroundScope,
            uiState = MutableStateFlow(ConversationListUiState(ownerSession = owner)),
            messagesUiState = messages,
            driveFileProvider = server.provider,
            payloadReader = server.payloadReader,
            fileOperationsProvider = NoopFileOperationsProvider(),
            chatMessageActionService = service,
            viewOnceActions = ViewOnceActions(service, fixture.credentialsManager),
            chatMessageStream = unusedStream(),
            localVideoContextStore = LocalAttachmentContextStore(fixture.eventBus, scope.backgroundScope),
            albumSaver = object : id.homebase.core.util.AlbumSaver {
                override suspend fun target(name: String, asked: Boolean) = error("not used")
            },
            sendEvent = { events += it },
            dispatch = {},
        )
    }

    private fun unusedStream(): ChatMessageStream {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (field.get(null) as sun.misc.Unsafe).allocateInstance(ChatMessageStream::class.java) as ChatMessageStream
    }
}
