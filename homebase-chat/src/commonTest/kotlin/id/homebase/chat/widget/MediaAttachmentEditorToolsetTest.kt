package id.homebase.chat.widget

import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.gallery.GalleryImage
import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MediaAttachmentEditorToolsetTest {

    private fun fileImage(): AttachmentPendingFile.FileImage {
        val id = Uuid.random()
        return AttachmentPendingFile.FileImage(id = id, file = PlatformFile("/tmp/i-$id.png"))
    }

    private fun gallery(): AttachmentPendingFile.Gallery {
        val id = Uuid.random()
        return AttachmentPendingFile.Gallery(
            id = id,
            image = GalleryImage(
                id = id.toString(),
                file = PlatformFile("/tmp/g-$id.png"),
                dateAdded = 0L,
                mimeType = "image/png",
                fileName = "g.png",
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
}
