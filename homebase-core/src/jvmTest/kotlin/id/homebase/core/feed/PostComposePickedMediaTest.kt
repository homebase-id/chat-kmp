package id.homebase.core.feed

import id.homebase.api.file.contentType
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.ui.screens.feed.toPendingMedia
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PostComposePickedMediaTest {
    private fun picked(name: String) = PlatformFile(File(name))

    @Test
    fun aVideoPickOrCaptureBecomesAVideoAttachment() {
        assertIs<AttachmentPendingFile.FileVideo>(picked("picked.mp4").toPendingMedia())
        assertIs<AttachmentPendingFile.FileVideo>(picked("picked.mov").toPendingMedia())
    }

    @Test
    fun aPhotoStaysAnImageAttachmentWithItsContentType() {
        val image = assertIs<AttachmentPendingFile.FileImage>(picked("picked.heic").toPendingMedia())
        assertEquals("image/heic", image.sourceContentType)
    }

    @Test
    fun aVideoThePlatformCannotOpenBecomesAFile() {
        val mkv = picked("picked.mkv")
        val file = assertIs<AttachmentPendingFile.File>(mkv.toPendingMedia { false })
        assertEquals(mkv.contentType(), file.sourceContentType)
    }

    @Test
    fun aPickWithNoKnownTypeStaysAnImage() {
        assertIs<AttachmentPendingFile.FileImage>(picked("picked").toPendingMedia())
    }
}
