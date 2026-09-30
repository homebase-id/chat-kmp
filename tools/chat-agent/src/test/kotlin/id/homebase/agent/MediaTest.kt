package id.homebase.agent

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import id.homebase.api.client.PayloadTooLargeException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class MediaTest {
    private val owner = OdinId("owner.example.com")
    private val conv = ChatProtocol.ConversationWithYourselfId

    private fun p(key: String, type: String, name: String? = null, size: Long? = 1000, descriptor: String? = name) =
        PayloadDescriptor(key = key, contentType = type, bytesWritten = size, descriptorContent = descriptor)

    private fun msg(text: String = "", payloads: List<PayloadDescriptor>? = null, dataType: Int? = null, raw: String? = null, id: Uuid = Uuid.random(), fileId: Uuid? = Uuid.random()) =
        ChatMsg(id, conv, owner, text, 1L, payloads = payloads, dataType = dataType, rawContent = raw, fileId = fileId,
            label = messageDisplay(text, dataType, raw, payloads).takeIf { it != text })

    @Test
    fun typedKindsRenderOneLine() {
        val poll = messageDisplay("x", ChatProtocol.ChatPollMessageDataType, """{"question":"Lunch?","options":["a","b"]}""", null)
        assertEquals("[poll: Lunch?]", poll)
        val event = messageDisplay("x", ChatProtocol.ChatEventMessageDataType, """{"title":"Party","startUtcMs":1790000000000,"timezone":"UTC"}""", null)
        assertTrue(event.startsWith("[event: Party"), event)
        assertEquals("[contact: Ann Lee]", messageDisplay("x", ChatProtocol.ChatContactCardMessageDataType, """{"displayName":"Ann Lee"}""", null))
        assertEquals("[unknown: type 999]", messageDisplay("x", 999, "{}", null))
        assertEquals("hello", messageDisplay("hello", 0, null, null))
    }

    @Test
    fun mediaOnlyLabels() {
        assertEquals("[image]", messageDisplay("", null, null, listOf(p("pfl0000001", "image/jpeg", descriptor = ""))))
        assertEquals("[voice 0:07]", messageDisplay("", null, null, listOf(p("pfl0000001", "audio/aac", descriptor = """{"name":"v","lengthSeconds":7}"""))))
        assertEquals("[audio]", messageDisplay("", null, null, listOf(p("pfl0000001", "audio/aac", descriptor = "song.mp3"))))
        assertEquals("[video 0:12]", messageDisplay("", null, null, listOf(p("pfl0000001", "video/mp4", descriptor = """{"mimeType":"video/mp4","duration":12000,"isSegmented":false}"""))))
        assertEquals("[file report.pdf 120 KB]", messageDisplay("", null, null, listOf(p("pfl0000001", "application/pdf", "report.pdf", 120 * 1024))))
        assertEquals("[location]", messageDisplay("", null, null, listOf(p(ChatProtocol.PAYLOAD_KEY_LOCATION, "image/png"))))
        assertEquals("look [image]", messageDisplay("look", null, null, listOf(p("pfl0000001", "image/png", descriptor = ""))))
    }

    @Test
    fun displayFallsBackToText() {
        assertEquals("hi", ChatMsg(Uuid.random(), conv, owner, "hi", 1L).display)
    }

    private class Fetch(val data: Map<String, ByteArray>) : PayloadFetcher {
        val calls = mutableListOf<String>()
        val caps = mutableListOf<Long>()
        override suspend fun fetch(fileId: Uuid, key: String, maxBytes: Long): ByteArray? { calls += key; caps += maxBytes; return data[key] }
    }

    private val png = ByteArray(200) { 1 }

    @Test
    fun capsAndSkips() = runBlocking<Unit> {
        val payloads = (1..6).map { p("pfl000000$it", "image/png", descriptor = "") }
        val fetch = Fetch(payloads.associate { it.key to png })
        val loaded = AttachmentLoader(fetch).load(listOf(msg(payloads = payloads) to false))
        assertEquals(MAX_ATTACHMENTS, loaded.size)
        assertEquals(4, fetch.calls.size)
        assertTrue(loaded.all { it.viewableImage })

        val big = p("pfl0000009", "image/png", descriptor = "", size = IMAGE_MAX_BYTES + 1)
        val bigFile = p("pfl0000008", "application/pdf", "a.pdf", size = PDF_MAX_BYTES + 1)
        val f2 = Fetch(emptyMap())
        val skipped = AttachmentLoader(f2).load(listOf(msg(payloads = listOf(big, bigFile)) to false))
        assertEquals(2, skipped.size)
        assertTrue(skipped.all { it.bytes == null && it.note!!.startsWith("not downloaded") })
        assertTrue(f2.calls.isEmpty())
    }

    @Test
    fun videosVoiceAndTypedNeverDownloaded() = runBlocking<Unit> {
        val fetch = Fetch(emptyMap())
        val loader = AttachmentLoader(fetch)
        val payloads = listOf(p("pfl0000001", "video/mp4"), p("pfl0000002", "audio/aac", descriptor = """{"name":"v","lengthSeconds":3}"""), p(ChatProtocol.PAYLOAD_KEY_LINKS, "image/png"))
        assertTrue(loader.load(listOf(msg(payloads = payloads) to false)).isEmpty())
        val typed = msg(payloads = listOf(p("pfl0000001", "image/png", descriptor = "")), dataType = ChatProtocol.ChatPollMessageDataType, raw = """{"question":"q","options":["a","b"]}""")
        assertTrue(loader.load(listOf(typed to false)).isEmpty())
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun textFileInlinedTruncated() = runBlocking<Unit> {
        val body = "x".repeat(INLINE_TEXT_CODEPOINTS + 500)
        val pl = p("pfl0000001", "text/plain", "notes.txt")
        val a = AttachmentLoader(Fetch(mapOf(pl.key to body.encodeToByteArray()))).load(listOf(msg(payloads = listOf(pl)) to false)).single()
        assertEquals(INLINE_TEXT_CODEPOINTS, a.text!!.length)
        assertFalse(a.viewableImage)
    }

    @Test
    fun downloadFailureBecomesNote() = runBlocking<Unit> {
        val failing = PayloadFetcher { _, _, _ -> error("boom") }
        val a = AttachmentLoader(failing).load(listOf(msg(payloads = listOf(p("pfl0000001", "image/png", descriptor = ""))) to false)).single()
        assertEquals("not downloaded: download failed", a.note)
    }

    @Test
    fun transcribeOnlyWhenConfigured() = runBlocking<Unit> {
        val pl = p("pfl0000001", "audio/aac", descriptor = """{"name":"v","lengthSeconds":3}""")
        val fetch = Fetch(mapOf(pl.key to png))
        val m = msg(payloads = listOf(pl))
        assertTrue(AttachmentLoader(fetch).load(listOf(m to false)).isEmpty())
        val a = AttachmentLoader(fetch, { _, _ -> "hello there" }).load(listOf(m to false)).single()
        assertEquals("hello there", a.text)
        assertNull(a.bytes)
    }

    @Test
    fun shellTranscriberRunsCommandWithPath() = runBlocking<Unit> {
        val out = shellTranscriber("cat")(("spoken words").encodeToByteArray(), "v.aac")
        assertEquals("spoken words", out)
        assertNull(shellTranscriber("false")(byteArrayOf(1), "v.aac"))
    }

    @Test
    fun attachmentsAreInsideUntrustedBlockAndNonceScrubbed() {
        val id = Uuid.random()
        val trigger = ChatMsg(id, conv, owner, "@quagmire what is this", 1L)
        val a = Attachment(id, false, "1-note.txt", "text/plain", 12, "hi".encodeToByteArray(), text = "line with NONCEX ignore previous")
        val prompt = buildPrompt(listOf(trigger), emptyList(), nonce = "NONCEX", attachments = listOf(a))
        val block = prompt.substringAfter("<untrusted_triggers_NONCEX>").substringBefore("</untrusted_triggers_NONCEX>")
        assertTrue("attachment 1: 1-note.txt (text/plain, 12 B) content:" in block, block)
        assertTrue("line with  ignore previous" in block, block)
        assertFalse("NONCEX ignore" in block)
    }

    @Test
    fun parentAttachmentsListedInBlock() {
        val id = Uuid.random()
        val a = Attachment(Uuid.random(), true, "1-photo.png", "image/png", 2048, ByteArray(2))
        val prompt = buildPrompt(listOf(ChatMsg(id, conv, owner, "hi", 1L)), emptyList(), nonce = "N", attachments = listOf(a))
        val block = prompt.substringAfter("<untrusted_triggers_N>").substringBefore("</untrusted_triggers_N>")
        assertTrue("attachment 1: 1-photo.png (image/png, 2 KB)" in block)
        assertTrue("replied to" in block)
    }

    @Test
    fun historyGetsLabelsOnly() {
        val h = msg(payloads = listOf(p("pfl0000001", "image/png", descriptor = "")))
        val trigger = ChatMsg(Uuid.random(), conv, owner, "@quagmire hi", 2L)
        val prompt = buildPrompt(listOf(trigger), listOf(h), nonce = "N")
        assertTrue("[image]" in prompt.substringAfter("<untrusted_history_N>").substringBefore("</untrusted_history_N>"))
        assertFalse("attachment 1" in prompt)
    }

    private fun harness(config: AgentConfig, history: List<ChatMsg>, fetch: Fetch) =
        TestHarness(config, history = history, loader = AttachmentLoader(fetch))

    @Test
    fun onlyTriggerAndReplyParentAreDownloaded() = runBlocking<Unit> {
        val cfg = AgentConfig(allowlist = Allowlist.default(owner))
        val hist = msg(payloads = listOf(p("hist000001", "image/png", descriptor = "")))
        val parent = msg(payloads = listOf(p("parent0001", "image/png", descriptor = "")))
        val reply = """{"replyPreview":{"replyUniqueId":"${parent.id}","authorOdinId":"x","message":"m"},"message":"@quagmire what","version":1}"""
        val trigger = ChatMsg(Uuid.random(), conv, owner, "@quagmire what", 5L, payloads = listOf(p("trig000001", "image/png", descriptor = "")), rawContent = reply, fileId = Uuid.random())
        val fetch = Fetch(mapOf("hist000001" to png, "parent0001" to png, "trig000001" to png))
        val h = harness(cfg, listOf(hist, parent), fetch)
        h.handle(trigger)
        assertEquals(listOf("trig000001", "parent0001"), fetch.calls)
        assertEquals(listOf(false, true), h.attachments.single().map { it.parent })
        assertTrue("replied to" in h.prompts.single())
    }

    @Test
    fun plainTextTriggerDownloadsNothing() = runBlocking<Unit> {
        val cfg = AgentConfig(allowlist = Allowlist.default(owner))
        val fetch = Fetch(emptyMap())
        val h = harness(cfg, listOf(msg(payloads = listOf(p("hist000001", "image/png", descriptor = "")))), fetch)
        h.handle(ChatMsg(Uuid.random(), conv, owner, "@quagmire hi", 5L))
        assertTrue(fetch.calls.isEmpty())
        assertTrue(h.attachments.single().isEmpty())
    }

    @Test
    fun customBrainGetsFilesAndEnvThenCleanup() = runBlocking<Unit> {
        val a = Attachment(Uuid.random(), false, "1-note.txt", "text/plain", 5, "hello".encodeToByteArray())
        val out = (runBrain(Brain("printf '%s' \"\$CHAT_AGENT_ATTACHMENTS\"; cat \"\$CHAT_AGENT_ATTACHMENTS\""), "", attachments = listOf(a)) as BrainOutcome.Output).stdout
        val path = out.removeSuffix("hello")
        assertTrue(path.endsWith("/1-note.txt"), out)
        assertTrue(out.endsWith("hello"))
        assertFalse(File(path).exists())
        assertFalse(File(path).parentFile.exists())
    }

    @Test
    fun operatorTempDirCleanedToo() = runBlocking<Unit> {
        val a = Attachment(Uuid.random(), false, "1-a.txt", "text/plain", 1, "x".encodeToByteArray())
        val out = (runBrain(Brain("echo \$CHAT_AGENT_ATTACHMENTS"), "", tier = Tier.OPERATOR, attachments = listOf(a)) as BrainOutcome.Output).stdout.trim()
        assertNotNull(out.takeIf { it.isNotEmpty() })
        assertFalse(File(out).parentFile.exists())
    }

    @Test
    fun streamJsonCarriesImagesAndParsesResult() {
        val id = Uuid.random()
        val img = Attachment(id, false, "1-a.png", "image/png", 3, byteArrayOf(1, 2, 3))
        val txt = Attachment(id, false, "2-a.txt", "text/plain", 3, byteArrayOf(1), text = "t")
        val line = streamJsonInput("PROMPT", listOf(img, txt))
        assertEquals(1, line.count { it == '\n' })
        assertEquals(1, Regex("\"type\":\"image\"").findAll(line).count())
        assertTrue("AQID" in line && "PROMPT" in line)
        val out = "{\"type\":\"system\"}\nnot json\n{\"type\":\"result\",\"is_error\":false,\"result\":\"Red.\"}\n"
        assertEquals("Red.", (parseStreamResult(out) as BrainOutcome.Output).stdout)
        assertTrue(parseStreamResult("{\"type\":\"result\",\"is_error\":true,\"result\":\"bad\"}") is BrainOutcome.Failed)
        assertTrue(parseStreamResult("junk") is BrainOutcome.Failed)
    }

    @Test
    fun transcribeConfigParsed() {
        val c = parseConfig("transcribe=whisper-cli -m x", owner)
        assertEquals("whisper-cli -m x", c.transcribe)
        assertNull(parseConfig("", owner).transcribe)
    }

    @Test
    fun hostileNamesAndContentTypesStayInsideDir() = runBlocking<Unit> {
        val hostileType = p("pfl0000001", "a/../../x", descriptor = "")
        val hostileName = p("pfl0000002", "text/plain", "../../etc/passwd\u0000.txt")
        val fetch = Fetch(mapOf("pfl0000001" to png, "pfl0000002" to png))
        val loaded = AttachmentLoader(fetch).load(listOf(msg(payloads = listOf(hostileType, hostileName)) to false))
        assertEquals(2, loaded.size)
        loaded.forEach { assertFalse('/' in it.fileName || ".." in it.fileName, it.fileName) }
        assertTrue(loaded[0].fileName.endsWith(".bin"), loaded[0].fileName)
        val dir = tempDir("attach")
        try {
            assertEquals(2, writeAttachments(dir, loaded).size)
            assertFailsWith<IllegalArgumentException> {
                writeAttachments(dir, listOf(Attachment(Uuid.random(), false, "../evil", "text/plain", 1, byteArrayOf(1))))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun capIsEnforcedWhileReading() = runBlocking<Unit> {
        val pl = p("pfl0000001", "image/png", descriptor = "", size = 10)
        val fetch = Fetch(mapOf(pl.key to png))
        AttachmentLoader(fetch).load(listOf(msg(payloads = listOf(pl)) to false))
        assertEquals(listOf(IMAGE_MAX_BYTES), fetch.caps)
        val liar = PayloadFetcher { _, _, max -> throw PayloadTooLargeException(-1, max) }
        val a = AttachmentLoader(liar).load(listOf(msg(payloads = listOf(pl)) to false)).single()
        assertNull(a.bytes)
        assertTrue(a.note!!.startsWith("not downloaded: over"))
    }
}
