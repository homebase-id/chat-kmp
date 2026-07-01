package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #909: a PDF attachment whose file can't be rendered (missing / not a real
 * PDF) must fall back to a plain, preview-less payload — contentType and filename
 * preserved, no crash — so the bubble shows the icon row instead of throwing. The
 * happy-path (a renderable PDF → embedded preview + thumbnail ladder) is verified
 * on-device; there's no lightweight PDF fixture to render in a JVM unit test.
 */
class MessageAttachmentBuilderPdfTest {

    // The PDF branch renders straight from the file path (JVM PDFBox actual) and never
    // touches FileOperationsProvider, so every op here should be unreachable.
    private fun failingFs() = object : FileOperationsProvider {
        override fun getCacheDirectory(): String = "/tmp/test-cache"
        override fun openFileInput(path: String): InputProvider = fail("openFileInput")
        override suspend fun readFileBytes(path: String): ByteArray = fail("readFileBytes")
        override fun deleteTempFile(path: String): Boolean = false
        override fun getFileSize(path: String): Long = 0L
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
            fail("writeBytesToTempFile")
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
            fail("writeBytesToShareOutboundFile")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = fail<Unit>("writeStream")
        private fun <T> fail(name: String): T =
            throw UnsupportedOperationException("$name should not be called from the PDF path")
    }

    @Test
    fun pdf_withUnrenderableFile_fallsBackToPreviewlessPayload() = runTest {
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(
                filePath = "/tmp/does-not-exist-909.pdf",
                contentType = "application/pdf",
                displayName = "report.pdf",
            ),
            fileOperationsProvider = failingFs(),
            payloadKey = "chat_web0",
        )

        val payload = bundle.payloads.single()
        assertEquals("application/pdf", payload.contentType)
        assertEquals("report.pdf", payload.descriptorContent)
        assertNull(payload.previewThumbnail, "an unrenderable PDF must not carry a preview thumb")
        assertTrue(bundle.thumbnails.isEmpty())
        assertTrue(bundle.previewThumbs.isEmpty())
    }
}
