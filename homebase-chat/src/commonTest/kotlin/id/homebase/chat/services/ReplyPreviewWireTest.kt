package id.homebase.chat.services

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

class ReplyPreviewWireTest {

    private val id = Uuid.random()

    @Serializable
    private data class OldReplyPreview(val replyUniqueId: Uuid, val authorOdinId: String, val message: String)

    @Test
    fun aKeyRoundTrips() {
        val preview = ReplyPreview(replyUniqueId = id, authorOdinId = "a.test", message = "m", payloadKey = "chat_web2")
        val back = OdinSystemSerializer.deserialize<ReplyPreview>(OdinSystemSerializer.serialize(preview))
        assertEquals("chat_web2", back.payloadKey)
    }

    @Test
    fun aWholeMessageQuoteWritesNoKey() {
        val json = OdinSystemSerializer.serialize(ReplyPreview(replyUniqueId = id, authorOdinId = "a.test", message = "m"))
        assertFalse(json.contains("payloadKey"), json)
    }

    @Test
    fun anOldSenderDecodesToAWholeMessageQuote() {
        val json = """{"replyUniqueId":"$id","authorOdinId":"a.test","message":"m"}"""
        assertNull(OdinSystemSerializer.deserialize<ReplyPreview>(json).payloadKey)
    }

    @Test
    fun anOldReceiverIgnoresTheKey() {
        val json = OdinSystemSerializer.serialize(ReplyPreview(replyUniqueId = id, authorOdinId = "a.test", message = "m", payloadKey = "chat_web2"))
        assertTrue(json.contains("chat_web2"))
        assertEquals("m", OdinSystemSerializer.deserialize<OldReplyPreview>(json).message)
    }
}
