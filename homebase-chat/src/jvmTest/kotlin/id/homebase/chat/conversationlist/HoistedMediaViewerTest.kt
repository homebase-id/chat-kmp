package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant
import kotlin.uuid.Uuid

class HoistedMediaViewerTest {

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

    private fun attachmentEditor() = FullScreenOverlay.AttachmentData(
        selected = Uuid.random(),
        conversationTitle = "Alice",
        conversationId = Uuid.random(),
        attachments = emptyList(),
    )

    @Test
    fun `two-pane layout hoists the viewer out of the pane`() {
        val viewer = imageViewer()
        val state = MessageListUiState(fullScreenOverlay = viewer)
        assertSame(viewer, state.hoistedMediaViewer(isExpandedLayout = true))
    }

    @Test
    fun `single-pane layout keeps the viewer in the pane`() {
        val state = MessageListUiState(fullScreenOverlay = imageViewer())
        assertNull(state.hoistedMediaViewer(isExpandedLayout = false))
    }

    @Test
    fun `composer attachment editor is never hoisted`() {
        val state = MessageListUiState(fullScreenOverlay = attachmentEditor())
        assertNull(state.hoistedMediaViewer(isExpandedLayout = true))
    }

    @Test
    fun `nothing to hoist when no overlay is open`() {
        assertNull(MessageListUiState().hoistedMediaViewer(isExpandedLayout = true))
    }
}
