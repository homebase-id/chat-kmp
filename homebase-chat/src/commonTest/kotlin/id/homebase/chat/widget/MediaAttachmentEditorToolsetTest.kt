package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.gallery.GalleryImage
import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MediaAttachmentEditorToolsetTest {

    private fun fileImage(name: String = "i.png", sourceContentType: String? = null): AttachmentPendingFile.FileImage {
        val id = Uuid.random()
        return AttachmentPendingFile.FileImage(
            id = id,
            file = PlatformFile("/tmp/$id-$name"),
            sourceContentType = sourceContentType,
        )
    }

    private fun gallery(mimeType: String = "image/png", fileName: String = "g.png"): AttachmentPendingFile.Gallery {
        val id = Uuid.random()
        return AttachmentPendingFile.Gallery(
            id = id,
            image = GalleryImage(
                id = id.toString(),
                file = PlatformFile("/tmp/g-$id.png"),
                dateAdded = 0L,
                mimeType = mimeType,
                fileName = fileName,
                galleryName = "Camera",
            ),
        )
    }

    private fun video(): AttachmentPendingFile.FileVideo {
        val id = Uuid.random()
        return AttachmentPendingFile.FileVideo(id = id, file = PlatformFile("/tmp/v-$id.mp4"))
    }

    private fun file(): AttachmentPendingFile.File {
        val id = Uuid.random()
        return AttachmentPendingFile.File(id = id, file = PlatformFile("/tmp/f-$id.pdf"))
    }

    @Test
    fun image_allCallbacks_showsAllTools() {
        assertEquals(
            EditorToolset(showCrop = true, showDraw = true, showSave = true),
            editorToolsetFor(fileImage(), canCrop = true, canDraw = true, canSave = true),
        )
    }

    @Test
    fun gif_hidesCropAndDraw_keepsSave() {
        val gifs = listOf(
            fileImage(name = "clipboard_image.gif"),
            fileImage(name = "photopicker-1000022602", sourceContentType = "image/gif"),
            gallery(mimeType = "image/gif", fileName = "g"),
            gallery(mimeType = "image/*", fileName = "IMG_0001.GIF"),
        )
        for (gif in gifs) {
            assertEquals(
                EditorToolset(showCrop = false, showDraw = false, showSave = true),
                editorToolsetFor(gif, canCrop = true, canDraw = true, canSave = true),
                gif.toString(),
            )
        }
    }

    @Test
    fun gallery_isEditableImage() {
        assertEquals(
            EditorToolset(showCrop = true, showDraw = true, showSave = true),
            editorToolsetFor(gallery(), canCrop = true, canDraw = true, canSave = true),
        )
    }

    @Test
    fun video_hidesCropAndDraw_keepsSave() {
        assertEquals(
            EditorToolset(showCrop = false, showDraw = false, showSave = true),
            editorToolsetFor(video(), canCrop = true, canDraw = true, canSave = true),
        )
    }

    @Test
    fun nonMediaFile_hidesCropAndDraw_keepsSave() {
        assertEquals(
            EditorToolset(showCrop = false, showDraw = false, showSave = true),
            editorToolsetFor(file(), canCrop = true, canDraw = true, canSave = true),
        )
    }

    @Test
    fun nullCurrent_hidesEverything() {
        assertEquals(
            EditorToolset(showCrop = false, showDraw = false, showSave = false),
            editorToolsetFor(null, canCrop = true, canDraw = true, canSave = true),
        )
    }

    @Test
    fun noCallbacks_hidesEverything_evenForImage() {
        assertEquals(
            EditorToolset(showCrop = false, showDraw = false, showSave = false),
            editorToolsetFor(fileImage(), canCrop = false, canDraw = false, canSave = false),
        )
    }

    @Test
    fun image_partialCallbacks_gateIndependently() {
        // Vault-style: crop + save available, draw not wired.
        assertEquals(
            EditorToolset(showCrop = true, showDraw = false, showSave = true),
            editorToolsetFor(fileImage(), canCrop = true, canDraw = false, canSave = true),
        )
    }

    @Test
    fun image_canSaveFalse_hidesSaveOnly() {
        assertEquals(
            EditorToolset(showCrop = true, showDraw = true, showSave = false),
            editorToolsetFor(fileImage(), canCrop = true, canDraw = true, canSave = false),
        )
    }

    @Test
    fun quality_showsForImagesAndVideo_hidesForDocuments() {
        fun showQuality(current: AttachmentPendingFile?) =
            editorToolsetFor(
                current,
                canCrop = true,
                canDraw = true,
                canSave = true,
                canSetQuality = true,
            ).showQuality

        assertTrue(showQuality(fileImage()))
        assertTrue(showQuality(gallery()))
        assertTrue(showQuality(video()))
        // A document ships byte-for-byte in both modes, so offering the toggle would mislead.
        assertFalse(showQuality(file()))
        assertFalse(showQuality(null))
    }

    @Test
    fun quality_hiddenWhenTheCallbackIsNotWired() {
        assertFalse(
            editorToolsetFor(fileImage(), canCrop = true, canDraw = true, canSave = true).showQuality,
        )
    }

    @Test
    fun viewOnceCandidate_isAnImageOrVideoButNotAStickerOrDocument() {
        assertTrue(isViewOnceCandidate(fileImage()))
        assertTrue(isViewOnceCandidate(gallery()))
        assertTrue(isViewOnceCandidate(video()))
        assertTrue(isViewOnceCandidate(fileImage(name = "anim.gif")))
        assertFalse(isViewOnceCandidate(file()))
        assertFalse(isViewOnceCandidate(fileImage().copy(forceSticker = true)))
        assertFalse(isViewOnceCandidate(null))
    }

    @Test
    fun viewOnceEligible_requiresExactlyOneCandidate() {
        assertTrue(isViewOnceEligible(listOf(fileImage())))
        assertFalse(isViewOnceEligible(emptyList()))
        assertFalse(isViewOnceEligible(listOf(fileImage(), video())))
        assertFalse(isViewOnceEligible(listOf(file())))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun viewOnceToggle_shownOnlyForOneImageOrVideoThroughTheRealWiring() {
        val cases = listOf(
            listOf(fileImage()) to 1,
            listOf(video()) to 1,
            emptyList<AttachmentPendingFile>() to 0,
            listOf(fileImage(), fileImage()) to 0,
            listOf(fileImage().copy(forceSticker = true)) to 0,
            listOf(file()) to 0,
        )
        for ((attachments, expected) in cases) {
            runComposeUiTest {
                setContent {
                    WithComposerPreferences {
                        MaterialTheme {
                            MessageTextFieldForAttachment(
                                state = rememberRichTextState(),
                                onSendMessage = {},
                                showFormattingToolbar = false,
                                viewOnceToggle = viewOnceToggleFor(attachments, requested = false) {},
                            )
                        }
                    }
                }
                assertEquals(expected, onAllNodesWithTag(VIEW_ONCE_TOGGLE_TAG).fetchSemanticsNodes().size)
            }
        }
    }
}
