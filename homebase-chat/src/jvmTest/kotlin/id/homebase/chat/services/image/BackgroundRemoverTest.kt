package id.homebase.chat.services.image

import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.gallery.GalleryImage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Pins the background-remover contract on the JVM (Desktop) leg and the shared
 * `forceSticker` carrier wiring.
 *
 * Desktop/Web are deferred in v1: the actual must return null and report
 * unsupported so the editor hides the tool — common code still compiles.
 * The Android (ML Kit) and iOS (Vision) success paths are device-only and are
 * covered by instrumented / XCTest device tests, not here.
 */
class BackgroundRemoverTest {

    @Test
    fun jvm_removeBackground_returnsNull_deferredPlatform() = runTest {
        // Even with plausible PNG-ish bytes, the Desktop actual is a deferred no-op.
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertNull(removeBackground(bytes), "Desktop background removal must be a soft no-op (null)")
    }

    @Test
    fun jvm_isBackgroundRemovalSupported_isFalse() {
        assertFalse(
            isBackgroundRemovalSupported(),
            "Desktop has no on-device segmenter; the editor must hide the tool",
        )
    }

    @Test
    fun fileImage_forceSticker_defaultsFalse_andCarries() {
        val id = Uuid.random()
        val file = PlatformFile(File("/tmp/x.png"))
        assertFalse(AttachmentPendingFile.FileImage(id = id, file = file).forceSticker)
        assertTrue(
            AttachmentPendingFile.FileImage(id = id, file = file, forceSticker = true).forceSticker,
        )
    }

    @Test
    fun gallery_forceSticker_defaultsFalse_andCarries() {
        val id = Uuid.random()
        val image = GalleryImage(
            id = "g1",
            file = PlatformFile(File("/tmp/x.png")),
            dateAdded = 0L,
            mimeType = "image/png",
            fileName = "x.png",
            galleryName = "Camera",
        )
        assertFalse(AttachmentPendingFile.Gallery(id = id, image = image).forceSticker)
        assertEquals(
            true,
            AttachmentPendingFile.Gallery(id = id, image = image, forceSticker = true).forceSticker,
        )
    }
}
