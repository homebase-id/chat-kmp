package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.SendContents
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SendTest {
    private val allowlist = Allowlist.default(OdinId("owner.example.com"))

    @Test
    fun selfConversationMetadataIsEncryptedLocalMarkdown() = runBlocking {
        val kh = KeyHeader.newRandom16()
        val m = buildSelfMessageMetadata(allowlist, ChatProtocol.ConversationWithYourselfId, Uuid.random(), "hi", 1L, kh)
        val plain = buildMessageContent("hi")
        assertNotEquals(plain, m.appData.content)
        assertEquals(plain, kh.decrypt(Base64.decode(m.appData.content!!)).decodeToString())
        assertTrue(m.isEncrypted)
        assertFalse(m.allowDistribution)
        assertEquals(ChatProtocol.MessageFileType, m.appData.fileType)
        assertEquals(ChatProtocol.ConversationWithYourselfId, m.appData.groupId)
        assertTrue(plain.contains("\"version\":1"))
    }

    @Test
    fun otherConversationRefused() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            buildSelfMessageMetadata(allowlist, Uuid.random(), Uuid.random(), "hi", 1L, KeyHeader.newRandom16())
        }
    }

    @Test
    fun oversizedTextRejectedNotTruncated() {
        val e = assertFailsWith<IllegalArgumentException> { buildMessageContent("x".repeat(8000)) }
        assertTrue(e.message!!.contains("too large"))
    }

    @Test
    fun blankTextRejected() {
        assertFailsWith<IllegalArgumentException> { buildMessageContent("  ") }
    }

    @Test
    fun transitMatchesAppSelfSend() {
        val t = selfTransitOptions(ChatProtocol.ConversationWithYourselfId, Uuid.random())
        assertEquals(emptyList(), t.recipients)
        assertEquals(SendContents.All, t.sendContents)
        assertEquals(false, t.useAppNotification)
    }
}
