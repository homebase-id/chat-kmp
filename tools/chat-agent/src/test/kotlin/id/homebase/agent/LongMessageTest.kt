package id.homebase.agent

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.chat.ChatMessageSizer
import id.homebase.upload.PayloadBundle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class LongMessageTest {
    private val owner = OdinId("owner.example.com")
    private val conv = ChatProtocol.ConversationWithYourselfId

    private fun sizerSaysHeader(text: String): Boolean {
        val data = MessageAppData(message = JsonPrimitive(text), deliveryStatus = ChatDeliveryStatus.Sent.value, version = 1)
        return ChatMessageSizer.shouldEmbedInHeader(OdinSystemSerializer.serialize(data))
    }

    private fun boundary(unit: String): Pair<String, String> {
        var lo = 1
        var hi = 20_000
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sizerSaysHeader(unit.repeat(mid))) lo = mid else hi = mid - 1
        }
        return unit.repeat(lo) to unit.repeat(lo + 1)
    }

    @Test
    fun decisionMatchesSizerAtTheBoundaryForAsciiAndMultiByteText() {
        for (unit in listOf("a", "é", "日", "😀")) {
            val (fits, over) = boundary(unit)
            assertNull(buildMessage(fits).payloadJson, "unit $unit should fit")
            assertNotNull(buildMessage(over).payloadJson, "unit $unit should overflow")
        }
    }

    @Test
    fun overflowHeaderCarriesPreviewAndPayloadCarriesFullText() {
        val text = "# Title\n\n" + "word ".repeat(2500)
        val built = buildMessage(text, ReplyPreview(Uuid.random(), "x", "quoted"))
        val header = OdinSystemSerializer.deserialize<MessageAppData>(built.header)
        assertEquals(ChatMessageSizer.preview(text), header.getMessage())
        assertEquals("quoted", header.replyPreview?.message)
        assertEquals(1, header.version)
        assertEquals(text, parseLongText(built.payloadJson!!))
        assertTrue(built.header.encodeToByteArray().size < 7000)
    }

    @Test
    fun bundleGetsTextPayloadAlongsideAttachments() = runBlocking<Unit> {
        val ops = JvmFileOperationsProvider()
        val payload = textPayload("{\"message\":\"x\"}".encodeToByteArray(), ops)
        try {
            assertEquals(ChatProtocol.DefaultPayloadKey, payload.key)
            assertEquals("application/json", payload.contentType)
            val alone = withTextPayload(null, payload)
            assertEquals(listOf(ChatProtocol.DefaultPayloadKey), alone.payloads.map { it.key })
            val existing = PayloadBundle(listOf(payload.copy(key = "chat_web0")), emptyList(), emptyList())
            assertEquals(listOf("chat_web0", ChatProtocol.DefaultPayloadKey), withTextPayload(existing, payload).payloads.map { it.key })
        } finally {
            File(payload.filePath).delete()
        }
    }

    @Test
    fun hardCapTruncatesTheTailOnACodePointBoundary() {
        val text = "😀".repeat(60_000)
        val capped = capTextBytes(text)
        assertTrue(capped.contains("truncated"))
        assertTrue(capped.encodeToByteArray().size < MAX_TEXT_BYTES + 100)
        assertFalse(capped.any { Character.isLowSurrogate(it) && false })
        assertEquals("short", capTextBytes("short"))
        assertTrue(brainReply(BrainOutcome.Output("y".repeat(100_000)), "", operator = true)!!.length >= 100_000)
        assertTrue(brainReply(BrainOutcome.Output("y".repeat(100_000)), "", operator = false)!!.length <= REPLY_CODEPOINTS + 3)
    }

    private fun longMsg(preview: String) = ChatMsg(
        Uuid.random(), conv, owner, preview, 1L, payloads = listOf(PayloadDescriptor(key = ChatProtocol.DefaultPayloadKey)), fileId = Uuid.random(),
    )

    @Test
    fun readReassemblesLongMessageFromThePayload() = runBlocking<Unit> {
        val full = "line\n".repeat(3000)
        val json = buildMessage(full).payloadJson!!
        val msg = longMsg("preview")
        val expanded = expandLongText(msg, PayloadFetcher { _, key, _ -> json.takeIf { key == ChatProtocol.DefaultPayloadKey } })
        assertEquals(full, expanded.text)
        assertTrue(expanded.expanded)
        assertEquals("preview", expandLongText(msg, PayloadFetcher { _, _, _ -> null }).text)
        assertEquals("preview", expandLongText(msg, PayloadFetcher { _, _, _ -> "not json".encodeToByteArray() }).text)
        val short = ChatMsg(Uuid.random(), conv, owner, "hi", 1L)
        assertEquals("hi", expandLongText(short, PayloadFetcher { _, _, _ -> error("no fetch") }).text)
    }

    @Test
    fun promptShowsExpandedTriggerInFull() {
        val full = "z".repeat(5000)
        val trigger = longMsg("p").withFullText(full)
        assertTrue(buildPrompt(listOf(trigger), emptyList(), nonce = "N").contains(full))
        assertFalse(buildPrompt(listOf(longMsg("p")), emptyList(), nonce = "N").contains("zzzz"))
    }
}
