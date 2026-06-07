package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.audio.AudioFileInfo
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.gallery.GalleryImage
import id.homebase.imageeditor.ui.CropResultBus
import id.homebase.imageeditor.ui.DrawResultBus
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit-tests [AttachmentHandler.handleToggleStickerAttachment] — the reducer that
 * flips the per-image `forceSticker` flag on the open attachment editor overlay.
 *
 * The reducer is purely synchronous over `messagesUiState` (mirrors the video-trim
 * reducer), so every other [AttachmentHandler] collaborator is a no-op fake that is
 * never touched on this path. Pairs with PR #664's
 * `MessageAttachmentBuilderStickerTest`, which proves a `forceSticker=true`
 * AttachmentInput produces the `{"isSticker":true}` payload descriptor.
 */
class AttachmentToggleStickerTest {

    private fun handlerWith(
        messagesUiState: MutableStateFlow<MessageListUiState>,
    ): AttachmentHandler = AttachmentHandler(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        uiState = MutableStateFlow(ConversationListUiState()),
        messagesUiState = messagesUiState,
        fileOperationsProvider = NoopFileOps(),
        cropResultBus = CropResultBus(),
        drawResultBus = DrawResultBus(),
        audioRecorder = NoopAudioRecorder(),
        audioWaveFormGenerator = NoopWaveformGenerator(),
        sendEvent = {},
        dispatch = {},
        addMessageWithFiles = { _, _, _ -> },
    )

    private fun overlayWith(vararg attachments: AttachmentPendingFile) =
        FullScreenOverlay.AttachmentData(
            selected = attachments.first().attachmentId,
            conversationTitle = "Test",
            conversationId = Uuid.random(),
            attachments = attachments.toList(),
        )

    private fun fileImage(forceSticker: Boolean = false): AttachmentPendingFile.FileImage {
        val id = Uuid.random()
        return AttachmentPendingFile.FileImage(
            id = id,
            file = PlatformFile("/tmp/test-$id.png"),
            forceSticker = forceSticker,
        )
    }

    private fun gallery(forceSticker: Boolean = false): AttachmentPendingFile.Gallery {
        val id = Uuid.random()
        return AttachmentPendingFile.Gallery(
            id = id,
            image = GalleryImage(
                id = id.toString(),
                file = PlatformFile("/tmp/gallery-$id.png"),
                dateAdded = 0L,
                mimeType = "image/png",
                fileName = "g.png",
                galleryName = "Camera",
            ),
            forceSticker = forceSticker,
        )
    }

    private fun MessageListUiState.fileImageById(id: Uuid): AttachmentPendingFile.FileImage {
        val overlay = fullScreenOverlay as FullScreenOverlay.AttachmentData
        return overlay.attachments.first { it.attachmentId == id } as AttachmentPendingFile.FileImage
    }

    private fun MessageListUiState.galleryById(id: Uuid): AttachmentPendingFile.Gallery {
        val overlay = fullScreenOverlay as FullScreenOverlay.AttachmentData
        return overlay.attachments.first { it.attachmentId == id } as AttachmentPendingFile.Gallery
    }

    @Test
    fun toggle_flipsFileImage_falseToTrueToFalse() = runTest {
        val image = fileImage(forceSticker = false)
        val state = MutableStateFlow(MessageListUiState(fullScreenOverlay = overlayWith(image)))
        val handler = handlerWith(state)

        handler.handleToggleStickerAttachment(
            ConversationListUiAction.ToggleStickerAttachment(Uuid.random(), image.attachmentId)
        )
        assertTrue(state.value.fileImageById(image.attachmentId).forceSticker, "first toggle -> true")

        handler.handleToggleStickerAttachment(
            ConversationListUiAction.ToggleStickerAttachment(Uuid.random(), image.attachmentId)
        )
        assertFalse(state.value.fileImageById(image.attachmentId).forceSticker, "second toggle -> false")
    }

    @Test
    fun toggle_flipsGallery() = runTest {
        val g = gallery(forceSticker = false)
        val state = MutableStateFlow(MessageListUiState(fullScreenOverlay = overlayWith(g)))
        val handler = handlerWith(state)

        handler.handleToggleStickerAttachment(
            ConversationListUiAction.ToggleStickerAttachment(Uuid.random(), g.attachmentId)
        )
        assertTrue(state.value.galleryById(g.attachmentId).forceSticker, "gallery toggle -> true")
    }

    @Test
    fun toggle_onlyAffectsMatchingAttachment() = runTest {
        val a = fileImage(forceSticker = false)
        val b = fileImage(forceSticker = false)
        val state = MutableStateFlow(MessageListUiState(fullScreenOverlay = overlayWith(a, b)))
        val handler = handlerWith(state)

        handler.handleToggleStickerAttachment(
            ConversationListUiAction.ToggleStickerAttachment(Uuid.random(), a.attachmentId)
        )

        assertTrue(state.value.fileImageById(a.attachmentId).forceSticker, "target image toggled")
        assertFalse(state.value.fileImageById(b.attachmentId).forceSticker, "sibling untouched")
    }

    @Test
    fun toggle_unknownAttachmentId_isNoOp() = runTest {
        val image = fileImage(forceSticker = false)
        val state = MutableStateFlow(MessageListUiState(fullScreenOverlay = overlayWith(image)))
        val handler = handlerWith(state)
        val before = state.value

        handler.handleToggleStickerAttachment(
            ConversationListUiAction.ToggleStickerAttachment(Uuid.random(), Uuid.random())
        )

        assertEquals(before, state.value, "no matching attachment -> state unchanged")
        assertFalse(state.value.fileImageById(image.attachmentId).forceSticker)
    }

    @Test
    fun toggle_withoutAttachmentOverlay_isNoOp() = runTest {
        val state = MutableStateFlow(MessageListUiState(fullScreenOverlay = null))
        val handler = handlerWith(state)
        val before = state.value

        handler.handleToggleStickerAttachment(
            ConversationListUiAction.ToggleStickerAttachment(Uuid.random(), Uuid.random())
        )

        assertEquals(before, state.value, "no attachment overlay -> state unchanged")
    }
}

/** Minimal no-op [FileOperationsProvider]; the toggle reducer never touches it. */
private class NoopFileOps : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider = throw NotImplementedError()
    override suspend fun readFileBytes(path: String): ByteArray = throw NotImplementedError()
    override fun deleteTempFile(path: String): Boolean = false
    override fun getCacheDirectory(): String = "/tmp"
    override fun getFileSize(path: String): Long = 0L
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = throw NotImplementedError()
}

private class NoopAudioRecorder : AudioRecorder {
    override fun getAudioFileExtension(): String = "m4a"
    override fun startRecording(fileName: String) {}
    override fun stopRecording(): String? = null
}

private class NoopWaveformGenerator : AudioWaveFormGenerator {
    override fun generateWaveForm(file: PlatformFile): AudioFileInfo = throw NotImplementedError()
    override fun saveWaveformToPng(amplitudes: FloatArray, width: Int, height: Int): ByteArray =
        throw NotImplementedError()
}
