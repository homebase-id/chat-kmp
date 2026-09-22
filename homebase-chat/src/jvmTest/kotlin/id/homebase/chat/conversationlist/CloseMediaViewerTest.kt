package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CloseMediaViewerTest {

    private fun imageViewer() = FullScreenOverlay.ViewMessageData(
        messageId = Uuid.random(),
        title = "Alice",
        userDate = Instant.fromEpochMilliseconds(0),
        content = "",
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        payloads = emptyList(),
        keyHeader = KeyHeader.empty(),
        selectedPayloadKey = "chat_web0",
    )

    private fun videoPlayer() = FullScreenOverlay.VideoPlayerData(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        payloadKey = "chat_web0",
        keyHeader = KeyHeader.empty(),
        payload = PayloadDescriptor(key = "chat_web0", contentType = "video/mp4"),
    )

    private fun pdfViewer() = FullScreenOverlay.PdfViewerData(
        messageId = Uuid.random(),
        fileId = Uuid.random(),
        payloadKey = "chat_web0",
        title = "spec.pdf",
        userDate = Instant.fromEpochMilliseconds(0),
    )

    private fun attachmentEditor() = FullScreenOverlay.AttachmentData(
        selected = Uuid.random(),
        conversationTitle = "Alice",
        conversationId = Uuid.random(),
        attachments = emptyList(),
    )

    @Test
    fun `image viewer is dropped`() {
        val state = MessageListUiState(fullScreenOverlay = imageViewer())
        assertNull(state.closeMediaViewer().fullScreenOverlay)
    }

    @Test
    fun `video player is dropped`() {
        val state = MessageListUiState(fullScreenOverlay = videoPlayer())
        assertNull(state.closeMediaViewer().fullScreenOverlay)
    }

    @Test
    fun `pdf viewer is dropped`() {
        val state = MessageListUiState(fullScreenOverlay = pdfViewer())
        assertNull(state.closeMediaViewer().fullScreenOverlay)
    }

    @Test
    fun `composer attachment editor is kept`() {
        val editor = attachmentEditor()
        val state = MessageListUiState(fullScreenOverlay = editor)
        assertEquals(editor, state.closeMediaViewer().fullScreenOverlay)
    }

    @Test
    fun `state is untouched when nothing is open`() {
        val state = MessageListUiState()
        assertSame(state, state.closeMediaViewer())
    }
}
