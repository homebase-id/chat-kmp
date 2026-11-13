package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

class MessageAttachmentBuilderNameTest {

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
    fun theSentNameIsMadeSafe() = runTest {
        suspend fun sentName(displayName: String?) = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = "/tmp/x.bin", contentType = "application/zip", displayName = displayName),
            fileOperationsProvider = fakeFs,
            payloadKey = "chat_web0",
        ).payloads.single().descriptorContent

        assertEquals("hyperfile.sys", sentName("../../whatever/hyperfile.sys"))
        assertEquals("_con.zip", sentName("con.zip"))
        assertEquals("Q3_ Budget_.zip", sentName("Q3: Budget?.zip"))
        assertNull(sentName(null))
    }
}
