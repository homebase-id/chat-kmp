package id.homebase.core.feed

import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.ui.screens.feed.toPendingMedia
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PostComposePickedMediaTest {
    private val file = PlatformFile(File("picked"))

    @Test
    fun aVideoPickOrCaptureBecomesAVideoAttachment() {
        assertIs<AttachmentPendingFile.FileVideo>(file.toPendingMedia("video/mp4"))
        assertIs<AttachmentPendingFile.FileVideo>(file.toPendingMedia("video/quicktime"))
    }

    @Test
    fun aPhotoStaysAnImageAttachmentWithItsContentType() {
        val image = assertIs<AttachmentPendingFile.FileImage>(file.toPendingMedia("image/heic"))
        assertEquals("image/heic", image.sourceContentType)
    }
}
