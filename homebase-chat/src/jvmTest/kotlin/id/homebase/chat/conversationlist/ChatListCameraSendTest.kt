package id.homebase.chat.conversationlist

import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.data.ContactUiModel
import id.homebase.imageeditor.ui.CropResultBus
import id.homebase.imageeditor.ui.DrawResultBus
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ChatListCameraSendTest {

    private fun contact(name: String) =
        RecipientModel.Contact(ContactUiModel(id = Uuid.random(), odinId = OdinId("$name.test"), name = name, avatarInitials = ""))

    private fun picker() = SendToPicker(caption = "hi", viewOnce = false, recipients = persistentListOf())

    @Test
    fun togglingARecipientSelectsThenDeselectsIt() {
        val frodo = contact("frodo")
        val sam = contact("sam")

        val both = picker().toggle(frodo).toggle(sam)
        assertEquals(listOf<RecipientModel>(frodo, sam), both.selected)

        assertEquals(listOf<RecipientModel>(sam), both.toggle(frodo).selected)
    }

    @Test
    fun onlyASingleTargetOpensItsConversation() {
        val one = Uuid.random()
        assertEquals(one, conversationToOpenAfterSend(listOf(one)))
        assertNull(conversationToOpenAfterSend(listOf(one, Uuid.random())))
        assertNull(conversationToOpenAfterSend(emptyList()))
    }

    private val messages = MutableStateFlow(MessageListUiState())
    private val events = mutableListOf<ConversationListUiEvent>()

    private fun handler(fileOps: FileOperationsProvider = NoopFileOps()) = AttachmentHandler(
        scope = CoroutineScope(Dispatchers.Unconfined),
        uiState = MutableStateFlow(ConversationListUiState()),
        messagesUiState = messages,
        fileOperationsProvider = fileOps,
        cropResultBus = CropResultBus(),
        drawResultBus = DrawResultBus(),
        audioRecorder = NoopAudioRecorder(),
        audioWaveFormGenerator = NoopWaveformGenerator(),
        sendEvent = { events += it },
        dispatch = {},
        addMessageWithFiles = { _, _, _ -> },
        sendSticker = { _, _, _ -> },
    )

    private suspend fun awaitChatListEditor(): FullScreenOverlay.AttachmentData {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(10_000) { messages.first { it.fullScreenOverlay != null } }
        }
        val editor = messages.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData
        assertTrue(editor != null && editor.isChatListCapture, "editor opens with no chat behind it: $events")
        return editor
    }

    @Test
    fun capturedMediaOpensTheEditorBeforeAnyChatIsChosen() = runTest {
        val captured = kotlin.io.path.createTempFile("capture", ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }

        handler().handleAttachPlatformFile(
            ConversationListUiAction.AttachPlatformFile(NO_CONVERSATION, listOf(PlatformFile(captured)), isImage = true)
        )

        assertEquals(1, awaitChatListEditor().attachments.size)
    }

    @Test
    fun aGifPastedIntoTheCaptionJoinsTheEditorWithoutTheStickerPrompt() = runTest {
        val tempFileOps = object : FileOperationsProvider by NoopFileOps() {
            override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
                kotlin.io.path.createTempFile(prefix, suffix).toFile().apply { writeBytes(bytes); deleteOnExit() }.absolutePath
        }
        val gif = java.util.Base64.getDecoder().decode(
            "R0lGODlhAgABAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgABAAAIBQABAAgIACH5" +
                "BAAKAAAALAAAAAACAAEAgQAA/wAAAAAAAAAAAAgFAAEACAgAOw=="
        )

        handler(tempFileOps).handleAttachClipboardImage(ConversationListUiAction.AttachClipboardImage(NO_CONVERSATION, gif))

        assertNull(messages.value.pendingGifPaste)
        assertEquals(1, awaitChatListEditor().attachments.size)
    }
}
