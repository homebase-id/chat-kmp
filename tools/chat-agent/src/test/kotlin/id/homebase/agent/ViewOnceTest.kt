package id.homebase.agent

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.viewonce.VIEW_ONCE_PAYLOAD_KEY
import id.homebase.chat.viewonce.ViewOnceDescriptor
import id.homebase.chat.viewonce.ViewOnceRules
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

class ViewOnceTest {
    private val owner = OdinId("owner.example.com")
    private val other = OdinId("other.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId
    private val group = Uuid.random()
    private val now = 1_800_000_000_000L
    private val raw = """{"kind":"image","schemaVersion":1,"caption":"secret caption"}"""

    private fun payload(key: String = VIEW_ONCE_PAYLOAD_KEY, type: String = "image/png", size: Long = 100) =
        PayloadDescriptor(key = key, contentType = type, bytesWritten = size)

    private fun reactions(vararg codes: String) = ReactionSummary(reactions = codes.withIndex().associate { (i, c) -> "k$i" to ReactionEntry("k$i", 1, """{"emoji":"$c"}""") })

    private fun label(payloads: List<PayloadDescriptor>?, own: Boolean = false, ageMs: Long = 1000, reactions: ReactionSummary? = null, content: String = raw) =
        messageDisplay("", ChatProtocol.ChatViewOnceMessageDataType, content, payloads, reactions, ViewOnceContext(own, now - ageMs, now))

    @Test
    fun labelsFollowTheMessageState() {
        assertEquals("[view-once photo, sent]", label(listOf(payload()), own = true))
        assertEquals("[view-once photo, sent; opened]", label(listOf(payload()), own = true, reactions = reactions("_vo")))
        assertEquals("[view-once video, sent]", label(listOf(payload()), own = true, content = """{"kind":"video"}"""))
        assertEquals("[view-once photo, unopened]", label(listOf(payload())))
        assertEquals("[view-once video, unopened]", label(listOf(payload()), content = """{"kind":"video","schemaVersion":1}"""))
        assertEquals("[view-once media, already opened]", label(emptyList(), content = """{"kind":"image","schemaVersion":1}"""))
        assertEquals("[view-once media, expired]", label(listOf(payload()), ageMs = ViewOnceRules.MAX_LIFESPAN_MS + 1))
    }

    @Test
    fun theCaptionNeverShowsBeforeTheOpen() {
        assertFalse(label(listOf(payload())).contains("secret"))
        assertFalse(label(listOf(payload()), own = true).contains("secret"))
    }

    private class Recorder {
        val calls = CopyOnWriteArrayList<String>()
        var failSignal = false
        var failStrip = false
        var fetches = 0
    }

    private fun loader(r: Recorder, ffmpeg: Ffmpeg? = null): AttachmentLoader {
        val fetcher = PayloadFetcher { _, key, _ ->
            r.fetches++
            assertEquals(VIEW_ONCE_PAYLOAD_KEY, key)
            byteArrayOf(1, 2, 3)
        }
        val hooks = ViewOnceHooks(
            self = owner.toString(),
            signalOpened = { r.calls += "signal"; if (r.failSignal) error("offline") },
            stripOwnCopy = { r.calls += "strip"; if (r.failStrip) error("rejected") },
            now = { now },
        )
        return AttachmentLoader(fetcher, null, {}, ffmpeg, hooks)
    }

    private fun incoming(author: OdinId = other, payloads: List<PayloadDescriptor>? = listOf(payload()), ageMs: Long = 1000) =
        ChatMsg(Uuid.random(), self, author, "", now - ageMs, payloads = payloads, dataType = ChatProtocol.ChatViewOnceMessageDataType, rawContent = raw, fileId = Uuid.random(), createdMs = now - ageMs)

    @Test
    fun anIncomingViewOnceOpensExactlyOnceSignalThenStrip() = runBlocking<Unit> {
        val r = Recorder()
        val l = loader(r)
        val m = incoming()
        val first = l.load(listOf(m to false), viewOnceInMemory = true)
        val a = first.single()
        assertTrue(a.ephemeral)
        assertTrue(a.viewableImage)
        assertEquals("secret caption", a.text)
        assertEquals(listOf("signal", "strip"), r.calls.toList())
        assertEquals(1, r.fetches)

        val second = l.load(listOf(m to false), viewOnceInMemory = true).single()
        assertNull(second.bytes)
        assertEquals("view-once media, already opened", second.note)
        assertEquals(1, r.fetches)
        assertEquals(listOf("signal", "strip"), r.calls.toList())

        assertTrue(l.load(listOf(incoming(payloads = emptyList()) to false), viewOnceInMemory = true).isEmpty())
        assertEquals(1, r.fetches)
    }

    @Test
    fun aFailedSignalMeansNoStripAndNoBytes() = runBlocking<Unit> {
        val r = Recorder().apply { failSignal = true }
        val out = loader(r).load(listOf(incoming() to false), viewOnceInMemory = true)
        assertTrue(out.isEmpty())
        assertEquals(listOf("signal"), r.calls.toList())
    }

    @Test
    fun aFailedStripStillCountsAsOpened() = runBlocking<Unit> {
        val r = Recorder().apply { failStrip = true }
        val l = loader(r)
        val m = incoming()
        assertEquals(1, l.load(listOf(m to false), viewOnceInMemory = true).size)
        assertNull(l.load(listOf(m to false), viewOnceInMemory = true).single().bytes)
        assertEquals(1, r.fetches)
    }

    @Test
    fun expiredOwnParentAndNonStreamingRunsAreNeverFetched() = runBlocking<Unit> {
        val r = Recorder()
        val l = loader(r)
        assertTrue(l.load(listOf(incoming(ageMs = ViewOnceRules.MAX_LIFESPAN_MS + 1) to false), viewOnceInMemory = true).isEmpty())
        assertTrue(l.load(listOf(incoming(author = owner) to false), viewOnceInMemory = true).isEmpty())
        assertTrue(l.load(listOf(incoming() to true), viewOnceInMemory = true).isEmpty())
        assertTrue(l.load(listOf(incoming() to false), viewOnceInMemory = false).isEmpty())
        assertEquals(0, r.fetches)
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun overTheCapIsLeftUnopened() = runBlocking<Unit> {
        val r = Recorder()
        assertTrue(loader(r).load(listOf(incoming(payloads = listOf(payload(size = IMAGE_MAX_BYTES + 1))) to false), viewOnceInMemory = true).isEmpty())
        assertEquals(0, r.fetches)
    }

    @Test
    fun anEphemeralAttachmentIsNeverWrittenAndTheClaudeTranscriptIsOff() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("quag-vo").toFile()
        val script = File(dir, "brain.sh").apply {
            writeText("if [ -z \"\$CHAT_AGENT_ATTACHMENTS\" ]; then a=none; else a=files; fi\ncat >/dev/null\nprintf '{\"type\":\"result\",\"result\":\"%s %s\"}\\n' \"\$a\" \"\$*\"\n")
        }
        val bytes = byteArrayOf(1, 2, 3)
        val shown = Attachment(Uuid.random(), false, "view-once.png", "image/png", 3, bytes = bytes, ephemeral = true)
        val out = runBrain(Brain("sh ${script.absolutePath}", streamJson = true), "look", attachments = listOf(shown)) as BrainOutcome.Output
        assertTrue(out.stdout.startsWith("none"), out.stdout)
        assertTrue(out.stdout.contains(NO_PERSISTENCE_FLAG), out.stdout)

        val plain = Attachment(Uuid.random(), false, "a.png", "image/png", 3, bytes = bytes)
        val control = runBrain(Brain("sh ${script.absolutePath}", streamJson = true), "look", attachments = listOf(plain)) as BrainOutcome.Output
        assertTrue(control.stdout.startsWith("files"), control.stdout)
        assertFalse(control.stdout.contains(NO_PERSISTENCE_FLAG), control.stdout)
        assertTrue(writeAttachments(dir, listOf(shown)).isEmpty())
        shown.wipe()
        assertTrue(bytes.all { it == 0.toByte() })
    }

    private inner class Fake(
        override val allowlist: Allowlist = Allowlist.default(owner).copy(self, false),
        override val filesDir: File? = null,
        override val ffmpeg: Ffmpeg = Ffmpeg("/nonexistent-dir-for-quag"),
        val delegate: Boolean = false,
    ) : AgentBackend {
        val sent = CopyOnWriteArrayList<Pair<ViewOnceDescriptor, suspend (KeyHeader, JvmFileOperationsProvider) -> StagedBundle>>()
        val files = CopyOnWriteArrayList<OutFile>()
        override val sendPrefix: String get() = if (delegate) BOT_PREFIX else ""
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = emptyList<ChatMsg>()
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?) = Uuid.random()
        override fun disclose(conversationId: Uuid, text: String) = allowlist.disclosure(conversationId, text, owner)
        override suspend fun sendFile(conversationId: Uuid, file: OutFile, caption: String): Uuid { files += file; return Uuid.random() }
        override suspend fun sendViewOnce(conversationId: Uuid, staged: suspend (KeyHeader, JvmFileOperationsProvider) -> StagedBundle, descriptor: ViewOnceDescriptor): Uuid {
            sent += descriptor to staged
            return Uuid.random()
        }
    }

    private fun root() = Files.createTempDirectory("quag-vo-send").toFile().apply {
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }
        File(this, "pic.png").writeBytes(png.toByteArray())
        File(this, "doc.pdf").writeBytes("%PDF-1.4\n".toByteArray())
        File(this, "clip.mp4").writeBytes(ByteArray(64))
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    private fun call(b: Fake, conversation: Uuid, vararg pairs: Pair<String, String>) = runBlocking { toolSendViewOnce(b, scopedArguments(args(*pairs), conversation)) }

    @Test
    fun sendBuildsAViewOnceImageWithNoThumbnailsAndCaption() = runBlocking<Unit> {
        val b = Fake(filesDir = root())
        val reply = call(b, self, "path" to "pic.png", "caption" to "hello")
        assertFalse(reply.isError, reply.text)
        val (descriptor, staged) = b.sent.single()
        assertEquals(ViewOnceDescriptor(kind = "image", caption = "hello"), descriptor)
        val bundle = staged(KeyHeader.newRandom16(), JvmFileOperationsProvider())
        try {
            assertEquals(1, bundle.bundle.payloads.size)
            assertEquals(VIEW_ONCE_PAYLOAD_KEY, bundle.bundle.payloads.single().key)
            assertEquals(payloadKeyFor(0), bundle.bundle.payloads.single().key)
            assertNull(bundle.bundle.payloads.single().previewThumbnail)
            assertTrue(bundle.bundle.thumbnails.isEmpty())
            assertTrue(bundle.bundle.previewThumbs.isEmpty())
        } finally {
            bundle.cleanup()
        }
    }

    @Test
    fun delegateDisclosureRidesInTheCaptionEvenWithoutOne() = runBlocking<Unit> {
        val allowlist = Allowlist(setOf(self, group), setOf(owner), Kind.DELEGATE).copy(group, false)
        allowlist.learnDerived(ConversationInfo(group, "the group", listOf(owner, other)))
        val b = Fake(allowlist, root(), delegate = true)
        assertFalse(call(b, group, "path" to "pic.png").isError)
        assertFalse(call(b, group, "path" to "pic.png", "caption" to "x".repeat(2000)).isError)
        val (bare, long) = b.sent.map { it.first }
        assertTrue(bare.caption.orEmpty().contains("owner.example.com's AI assistant"), bare.caption.toString())
        assertTrue(long.caption.orEmpty().contains("owner.example.com's AI assistant"))
        assertTrue(long.caption!!.codePointCount(0, long.caption!!.length) <= ViewOnceDescriptor.MAX_CAPTION_CODEPOINTS)
    }

    @Test
    fun sendRefusesAnythingButOneImageOrMp4() = runBlocking<Unit> {
        val b = Fake(filesDir = root())
        val pdf = call(b, self, "path" to "doc.pdf")
        assertTrue(pdf.isError)
        assertTrue(pdf.text.contains("image or an .mp4 video"), pdf.text)
        assertTrue(call(b, self, "path" to "pic.png", "replyTo" to "abcdefgh").isError)
        assertTrue(call(b, self, "path" to "clip.mp4").text.contains("ffmpeg"))
        assertTrue(b.sent.isEmpty())
        assertTrue(b.files.isEmpty())
    }

    @Test
    fun aVideoIsStagedPreEncryptedWithoutThumbnails() = runBlocking<Unit> {
        assumeTrue(Ffmpeg().available)
        val dir = root()
        val out = File(dir, "real.mp4")
        val p = ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-t", "1", "-pix_fmt", "yuv420p", out.absolutePath).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        assertEquals(0, p.waitFor())
        val b = Fake(filesDir = dir, ffmpeg = Ffmpeg())
        assertFalse(call(b, self, "path" to "real.mp4").isError)
        val (descriptor, staged) = b.sent.single()
        assertEquals("video", descriptor.kind)
        val bundle = staged(KeyHeader.newRandom16(), JvmFileOperationsProvider())
        try {
            assertTrue(bundle.preEncrypted)
            assertNotNull(bundle.bundle.payloads.single().iv)
            assertNull(bundle.bundle.payloads.single().previewThumbnail)
            assertTrue(bundle.bundle.thumbnails.isEmpty())
            assertTrue(bundle.bundle.previewThumbs.isEmpty())
        } finally {
            bundle.cleanup()
        }
    }

    @Test
    fun sendViewOnceIsOperatorOnly() {
        assertTrue("send_view_once" in chatToolNames(Tier.OPERATOR))
        assertFalse("send_view_once" in chatToolNames(Tier.LOCKED))
        assertTrue("send_view_once" in MEDIA_SEND_TOOLS)
    }
}
