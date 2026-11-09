package id.homebase.chat.services

import id.homebase.api.HomebaseProtocol
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessageHeaderFitTest {

    private class TempFileOps : FileOperationsProvider by NoopFileOperationsProvider() {
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String) = "/fake/$prefix$suffix"
    }

    private fun data(length: Int) = MessageAppData(message = JsonPrimitive("x".repeat(length)))

    private fun header(length: Int) =
        OdinSystemSerializer.serialize(data(length).copy(version = ChatProtocol.MessageVersionNumberOne))

    private val longestFitting: Int =
        generateSequence(0) { it + 1 }.first { !HomebaseProtocol.fitsHeaderContent(header(it + 1)) }

    @Test
    fun `the longest message whose real header fits stays in the header`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(fileOps = TempFileOps())

            val built = service.buildMessageContentAndBundle(data(longestFitting), null, TempFileOps())

            assertEquals(header(longestFitting), built.headerContent)
            assertNull(built.payloadBundle)
        }
    }

    @Test
    fun `one byte past the real header goes to a payload`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(fileOps = TempFileOps())

            val built = service.buildMessageContentAndBundle(data(longestFitting + 1), null, TempFileOps())

            assertNotEquals(header(longestFitting + 1), built.headerContent)
            assertTrue(built.payloadBundle!!.payloads.any { it.key == ChatProtocol.DefaultPayloadKey })
            assertTrue(HomebaseProtocol.fitsHeaderContent(built.headerContent))
        }
    }
}
