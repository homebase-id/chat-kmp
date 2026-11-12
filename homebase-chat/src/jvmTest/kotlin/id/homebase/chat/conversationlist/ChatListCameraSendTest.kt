package id.homebase.chat.conversationlist

import id.homebase.api.common.OdinId
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

    @Test
    fun capturedMediaOpensTheEditorBeforeAnyChatIsChosen() = runTest {
        val messages = MutableStateFlow(MessageListUiState())
        val events = mutableListOf<ConversationListUiEvent>()
        val handler = AttachmentHandler(
            scope = CoroutineScope(Dispatchers.Unconfined),
            uiState = MutableStateFlow(ConversationListUiState()),
            messagesUiState = messages,
            fileOperationsProvider = NoopFileOps(),
            cropResultBus = CropResultBus(),
            drawResultBus = DrawResultBus(),
            audioRecorder = NoopAudioRecorder(),
            audioWaveFormGenerator = NoopWaveformGenerator(),
            sendEvent = { events += it },
            dispatch = {},
            addMessageWithFiles = { _, _, _ -> },
            sendSticker = { _, _, _ -> },
        )

        val captured = kotlin.io.path.createTempFile("capture", ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }

        handler.handleAttachPlatformFile(
            ConversationListUiAction.AttachPlatformFile(NO_CONVERSATION, listOf(PlatformFile(captured)), isImage = true)
        )

        withContext(Dispatchers.Default) {
            withTimeoutOrNull(10_000) { messages.first { it.fullScreenOverlay != null } }
        }

        val editor = messages.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData
        assertTrue(editor != null && editor.isChatListCapture, "editor opens with no chat behind it: $events")
        assertEquals(1, editor.attachments.size)
    }
}
