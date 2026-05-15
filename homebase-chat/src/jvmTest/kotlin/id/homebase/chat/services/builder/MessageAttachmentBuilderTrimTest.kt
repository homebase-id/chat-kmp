package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

/**
 * Pins the trim → PayloadFile threading. Without this, a refactor of
 * MessageAttachmentBuilder could silently drop trim metadata for video
 * attachments and the receiver would always get the full clip.
 *
 * Uses a tiny inline fake provider rather than the homebase-api
 * commonTest one — it's not visible across module test boundaries, and
 * the video/file builder branches don't actually touch the filesystem.
 */
class MessageAttachmentBuilderTrimTest {

    /** Throws on any call we don't expect to happen for video/file branches. */
    private val fakeFs = object : FileOperationsProvider {
        override fun getCacheDirectory(): String = "/tmp/test-cache"
        override fun openFileInput(path: String): InputProvider = fail("openFileInput")
        override suspend fun readFileBytes(path: String): ByteArray = fail("readFileBytes")
        override fun deleteTempFile(path: String): Boolean = false
        override fun getFileSize(path: String): Long = 0L
        override suspend fun writeBytesToTempFile(
            bytes: ByteArray, prefix: String, suffix: String,
        ): String = fail("writeBytesToTempFile")
        override suspend fun writeBytesToShareOutboundFile(
            bytes: ByteArray, suffix: String,
        ): String = fail("writeBytesToShareOutboundFile")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) =
            fail<Unit>("writeStream")
        private fun <T> fail(name: String): T =
            throw UnsupportedOperationException("$name should not be called from this test")
    }

    @Test
    fun videoAttachmentInput_propagatesTrim_toPayloadFile() = runTest {
        val input = AttachmentInput(
            filePath = "/tmp/some_clip.mp4",
            contentType = "video/mp4",
            displayName = "some_clip.mp4",
            trimStartMs = 9_337,
            trimEndMs = 13_319,
        )

        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = input,
            fileOperationsProvider = fakeFs,
            payloadKey = "chat_web0",
        )

        assertEquals(1, bundle.payloads.size)
        val payload = bundle.payloads.single()
        assertEquals(9_337L, payload.trimStartMs)
        assertEquals(13_319L, payload.trimEndMs)
        // Other fields are unaffected
        assertEquals("video/mp4", payload.contentType)
        assertEquals("/tmp/some_clip.mp4", payload.filePath)
    }

    @Test
    fun videoAttachmentInput_withoutTrim_leavesFieldsNull() = runTest {
        val input = AttachmentInput(
            filePath = "/tmp/some_clip.mp4",
            contentType = "video/mp4",
            displayName = "some_clip.mp4",
        )

        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = input,
            fileOperationsProvider = fakeFs,
            payloadKey = "chat_web0",
        )

        val payload = bundle.payloads.single()
        assertNull(payload.trimStartMs)
        assertNull(payload.trimEndMs)
    }

    @Test
    fun videoAttachmentInput_withOnlyStart_propagatesPartialTrim() = runTest {
        // The pipeline should faithfully forward whatever bounds the editor
        // dispatched. The compress step decides how to interpret a half-open trim.
        val input = AttachmentInput(
            filePath = "/tmp/some_clip.mp4",
            contentType = "video/mp4",
            trimStartMs = 5_000,
            trimEndMs = null,
        )

        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = input,
            fileOperationsProvider = fakeFs,
            payloadKey = "chat_web0",
        )

        val payload = bundle.payloads.single()
        assertEquals(5_000L, payload.trimStartMs)
        assertNull(payload.trimEndMs)
    }

    @Test
    fun fileAttachmentInput_doesNotInventTrim() = runTest {
        // Non-video, non-image, non-audio takes the same builder branch as video
        // (the `else ->` clause) but with content-type that signals "no trim".
        // We don't want trim fields to spontaneously appear on those PayloadFiles.
        val input = AttachmentInput(
            filePath = "/tmp/contract.pdf",
            contentType = "application/pdf",
            displayName = "contract.pdf",
        )

        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = input,
            fileOperationsProvider = fakeFs,
            payloadKey = "chat_web0",
        )

        val payload = bundle.payloads.single()
        assertNull(payload.trimStartMs)
        assertNull(payload.trimEndMs)
    }
}
