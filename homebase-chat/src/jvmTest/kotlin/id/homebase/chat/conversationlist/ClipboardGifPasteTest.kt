package id.homebase.chat.conversationlist

import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.widget.editorToolsetFor
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.imageeditor.ui.CropResultBus
import id.homebase.imageeditor.ui.DrawResultBus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ClipboardGifPasteTest {

    private val conversationId = Uuid.random()

    // 2x1, two frames.
    private val animatedGif = Base64.getDecoder().decode(
        "R0lGODlhAgABAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgABAAAIBQABAAgIACH5" +
            "BAAKAAAALAAAAAACAAEAgQAA/wAAAAAAAAAAAAgFAAEACAgAOw=="
    )

    // 1x1, one frame.
    private val stillGif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==")

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    private val state = MutableStateFlow(MessageListUiState())
    private val fileOps = RecordingFileOps()
    private val stickerSends = mutableListOf<Triple<Uuid, ByteArray, String>>()

    private val handler = AttachmentHandler(
        scope = CoroutineScope(Dispatchers.Unconfined),
        uiState = MutableStateFlow(ConversationListUiState(activeConversations = persistentListOf(conversation()))),
        messagesUiState = state,
        fileOperationsProvider = fileOps,
        cropResultBus = CropResultBus(),
        drawResultBus = DrawResultBus(),
        audioRecorder = NoopAudioRecorder(),
        audioWaveFormGenerator = NoopWaveformGenerator(),
        sendEvent = {},
        dispatch = {},
        addMessageWithFiles = { _, _, _ -> },
        saveAndSendSticker = { cid, bytes, contentType -> stickerSends += Triple(cid, bytes, contentType) },
    )

    private fun conversation() = EnrichedConversationUiModel(
        conversation = ConversationUiModel(
            id = conversationId,
            name = "convo",
            lastMessage = "",
            latestMessageTimestamp = Instant.fromEpochMilliseconds(0),
            avatarInitials = "",
            avatarTiny = null,
            lastRead = Instant.fromEpochMilliseconds(0),
            avatarModel = ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback),
            admins = emptySet(),
            participants = listOf(OdinId("frodo.demo.rocks")),
        ),
        participants = emptyList(),
        missingConnections = emptyList(),
    )

    private fun paste(bytes: ByteArray) =
        handler.handleAttachClipboardImage(ConversationListUiAction.AttachClipboardImage(conversationId, bytes))

    private fun stagedImage(): AttachmentPendingFile.FileImage {
        val overlay = assertNotNull(state.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData)
        return overlay.attachments.single() as AttachmentPendingFile.FileImage
    }

    private fun assertNothingStaged() {
        assertNull(state.value.fullScreenOverlay)
        assertTrue(fileOps.writes.isEmpty())
        assertTrue(stickerSends.isEmpty())
    }

    @Test
    fun animatedGif_opensTheSheet_andStagesNothing() {
        paste(animatedGif)

        val pending = assertNotNull(state.value.pendingGifPaste)
        assertEquals(conversationId, pending.conversationId)
        assertSame(animatedGif, pending.bytes)
        assertNothingStaged()
    }

    @Test
    fun stillImages_stageAsBefore_withoutTheSheet() {
        for ((bytes, suffix) in listOf(png to ".png", jpeg to ".jpg", stillGif to ".gif")) {
            state.value = MessageListUiState()
            fileOps.writes.clear()

            paste(bytes)

            assertNull(state.value.pendingGifPaste, suffix)
            val write = fileOps.writes.single()
            assertSame(bytes, write.bytes, suffix)
            assertEquals(suffix, write.suffix)
            assertEquals(write.path, stagedImage().file.toString(), suffix)
        }
    }

    @Test
    fun dismiss_stagesNothing() {
        paste(animatedGif)

        handler.handleDismissPastedGif()

        assertNull(state.value.pendingGifPaste)
        assertNothingStaged()
    }

    @Test
    fun sendAsGif_stagesTheAnimatedGifInTheEditor() {
        paste(animatedGif)

        handler.handleSendPastedGifAsGif()

        assertNull(state.value.pendingGifPaste)
        val write = fileOps.writes.single()
        assertSame(animatedGif, write.bytes)
        assertEquals(".gif", write.suffix)
        assertFalse(editorToolsetFor(stagedImage(), canCrop = true, canDraw = true, canSave = true).showCrop)
        assertTrue(stickerSends.isEmpty())
    }

    @Test
    fun sendAsSticker_savesAndSendsTheOriginalGifOnce() {
        paste(animatedGif)

        handler.handleSendPastedGifAsSticker()

        assertNull(state.value.pendingGifPaste)
        val (cid, bytes, contentType) = stickerSends.single()
        assertEquals(conversationId, cid)
        assertSame(animatedGif, bytes)
        assertEquals("image/gif", contentType)
        assertNull(state.value.fullScreenOverlay)
        assertTrue(fileOps.writes.isEmpty())
    }

    @Test
    fun sheetActions_withoutAPendingPaste_doNothing() {
        handler.handleSendPastedGifAsSticker()
        handler.handleSendPastedGifAsGif()

        assertNothingStaged()
    }
}

private class RecordingFileOps : FileOperationsProvider by NoopFileOps() {
    class Write(val bytes: ByteArray, val suffix: String, val path: String)

    val writes = mutableListOf<Write>()

    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String {
        val path = "/tmp/$prefix-${writes.size}$suffix"
        writes += Write(bytes, suffix, path)
        return path
    }
}
