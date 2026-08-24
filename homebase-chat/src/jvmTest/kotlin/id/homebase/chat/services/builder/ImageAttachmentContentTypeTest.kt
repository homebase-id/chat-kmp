package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.conversationlist.sandboxCopyName
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for #1149: photo-picker images sent as `application/octet-stream` and rendered
 * as generic file chips.
 *
 * Android's system photo picker vends display names with NO extension
 * (`photopicker-1000022602`), and the pick-time sandbox copy is a plain file — so the only handle
 * that knows the type is the picked `content://` URI (`ContentResolver.getType()` == image/jpeg).
 * Resolving from the copy lands on octet-stream, which skips thumbnail generation
 * (`MessageAttachmentBuilder` gates on `contentType.startsWith("image/")`) and makes every renderer
 * draw a file chip.
 */
class ImageAttachmentContentTypeTest {

    @Test
    fun dotlessPickerName_keepsContentUriMime() = runTest {
        val copy = PlatformFile("/tmp/chat_attach_0f9c_photopicker-1000022602")

        val input = copy.toImageAttachmentInput(NoopFileOps(), sourceContentType = "image/jpeg")

        assertEquals("image/jpeg", input.contentType)
    }

    @Test
    fun dotlessPickerName_withoutCarriedMime_fallsBackToTheNameAndIsUseless() = runTest {
        // Documents WHY the carry-through is needed: the copy alone resolves to octet-stream.
        val copy = PlatformFile("/tmp/chat_attach_0f9c_photopicker-1000022602")

        assertEquals("application/octet-stream", copy.toImageAttachmentInput(NoopFileOps()).contentType)
    }

    @Test
    fun sandboxCopyName_appendsExtensionWhenPickerNameHasNone() {
        val name = sandboxCopyName("photopicker-1000022602", "image/jpeg")

        assertTrue(name.endsWith("_photopicker-1000022602.jpg"), "expected .jpg suffix, got: $name")
    }

    @Test
    fun sandboxCopyName_keepsAnExistingExtensionAndSurvivesAnUnmappedMime() {
        assertTrue(sandboxCopyName("IMG_2026.jpeg", "image/jpeg").endsWith("_IMG_2026.jpeg"))
        assertTrue(sandboxCopyName("raw-shot", "image/x-not-a-real-mime").endsWith("_raw-shot"))
        assertTrue(sandboxCopyName("raw-shot", null).endsWith("_raw-shot"))
    }
}

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
