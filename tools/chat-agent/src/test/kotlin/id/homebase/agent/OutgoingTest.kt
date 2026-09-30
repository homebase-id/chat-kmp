package id.homebase.agent

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.video.VideoPayloadProcessor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.upload.PayloadBundleEncryptionService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OutgoingTest {
    private val owner = OdinId("owner.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId

    init {
        System.setProperty("homebase.data.dir", Files.createTempDirectory("chat-agent-data").toString())
        System.setProperty("java.awt.headless", "true")
    }

    private fun tempDir() = Files.createTempDirectory("chat-agent-test").toFile().canonicalFile

    private fun png(w: Int = 200, h: Int = 100): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (x * 255 / w shl 16) or (y * 255 / h shl 8) or 0x40)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun attachLinesAreStrippedFromTheEnd() {
        val (text, paths) = parseAttachLines("here you go\n\nATTACH: a.png\nATTACH:  sub/b.txt\n")
        assertEquals("here you go", text)
        assertEquals(listOf("a.png", "sub/b.txt"), paths)
        val (mid, none) = parseAttachLines("ATTACH: a.png\nthen more text")
        assertEquals("ATTACH: a.png\nthen more text", mid)
        assertTrue(none.isEmpty())
        val (only, onlyPaths) = parseAttachLines("ATTACH: a.png")
        assertEquals("", only)
        assertEquals(listOf("a.png"), onlyPaths)
    }

    @Test
    fun resolveRefusesTraversalAbsoluteOutsideAndSymlinkEscape() {
        val root = tempDir()
        val outside = tempDir()
        File(outside, "secret.txt").writeText("secret")
        File(root, "ok.txt").writeText("ok")
        File(root, "sub").mkdir()
        File(root, "sub/inner.txt").writeText("inner")
        Files.createSymbolicLink(File(root, "link.txt").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(root, "linkdir").toPath(), outside.toPath())
        assertEquals("ok.txt", resolveInside(root, "ok.txt").name)
        assertEquals("inner.txt", resolveInside(root, "sub/inner.txt").name)
        assertFailsWith<IllegalArgumentException> { resolveInside(root, "../${outside.name}/secret.txt") }
        assertFailsWith<IllegalArgumentException> { resolveInside(root, File(outside, "secret.txt").absolutePath) }
        assertFailsWith<IllegalArgumentException> { resolveInside(root, "link.txt") }
        assertFailsWith<IllegalArgumentException> { resolveInside(root, "linkdir/secret.txt") }
        assertFailsWith<IllegalArgumentException> { resolveInside(root, "sub") }
        assertFailsWith<IllegalArgumentException> { resolveInside(root, "missing.txt") }
        assertFailsWith<IllegalArgumentException> { resolveInside(root, "sub/../../${outside.name}/secret.txt") }
    }

    @Test
    fun collectEnforcesCapsAndSkipsBadFiles() {
        val root = tempDir()
        repeat(6) { File(root, "f$it.txt").writeText("data $it") }
        File(root, "big.bin").writeBytes(ByteArray((MAX_OUT_BYTES + 1).toInt()))
        File(root, "empty.txt").writeText("")
        val reply = "done\nATTACH: ../nope\nATTACH: big.bin\nATTACH: empty.txt\n" + (0..5).joinToString("\n") { "ATTACH: f$it.txt" }
        val attached = collectAttachments(reply, root)
        assertEquals("done", attached.text)
        assertEquals(listOf("f0.txt", "f1.txt", "f2.txt", "f3.txt"), attached.files.map { it.name })
        assertEquals(5, attached.skipped.size)
        assertTrue(attached.skipped.any { it.startsWith("../nope") })
        assertTrue(attached.skipped.any { it.startsWith("big.bin") && it.contains("limit") })
        assertTrue(attached.skipped.any { it.startsWith("f5.txt") && it.contains("more than") })
        val exactly = File(root, "ten.bin").also { it.writeBytes(ByteArray(MAX_OUT_BYTES.toInt())) }
        assertEquals(MAX_OUT_BYTES.toInt(), loadOutFile(exactly).bytes.size)
    }

    @Test
    fun noDirectoryMeansNoAttachmentsButLinesAreStillStripped() {
        val attached = collectAttachments("hi\nATTACH: a.png", null)
        assertEquals("hi", attached.text)
        assertTrue(attached.files.isEmpty())
        assertEquals(1, attached.skipped.size)
    }

    @Test
    fun brainOutputCarriesFilesAndStripsLines() {
        val root = tempDir()
        File(root, "a.txt").writeText("hello")
        val out = withAttachments(BrainOutcome.Output("caption\nATTACH: a.txt"), root)
        assertEquals("caption", out.stdout)
        assertEquals("a.txt", out.files.single().name)
        val escape = withAttachments(BrainOutcome.Output("caption\nATTACH: ../etc/passwd"), root)
        assertEquals("caption", escape.stdout)
        assertTrue(escape.files.isEmpty())
    }

    @Test
    fun runBrainAttachesFilesWrittenToItsScratchDirAndRemovesTheDir() = runBlocking<Unit> {
        val outcome = runBrain("printf 'note' > out.txt; printf 'look\\nATTACH: out.txt\\nATTACH: ../../../etc/hosts'", "prompt")
        val out = outcome as BrainOutcome.Output
        assertEquals("look", out.stdout)
        assertEquals("out.txt", out.files.single().name)
        assertEquals("note", out.files.single().bytes.decodeToString())
    }

    @Test
    fun fileBundleMatchesTheAppsBuilderShape() = runBlocking<Unit> {
        val file = OutFile("report.txt", "hello report".encodeToByteArray())
        val staged = buildOutgoingBundle(listOf(file), JvmFileOperationsProvider())
        val ours = staged.bundle
        val staging = File(ours.payloads.single().filePath)
        val app = MessageAttachmentBuilder.build(
            listOf(AttachmentInput(filePath = staging.absolutePath, contentType = "text/plain", displayName = "report.txt")),
            JvmFileOperationsProvider(),
        ) { _, _ -> "chat_web0" }
        val mine = ours.payloads.single()
        val theirs = app.payloads.single()
        assertEquals(theirs.key, mine.key)
        assertEquals(theirs.contentType, mine.contentType)
        assertEquals(theirs.descriptorContent, mine.descriptorContent)
        assertTrue(ours.thumbnails.isEmpty() && ours.previewThumbs.isEmpty())
        assertTrue(Regex("^[a-z0-9_]{8,10}$").matches(mine.key))
        staged.cleanup()
        assertFalse(staging.exists())
    }

    @Test
    fun imageBundleHasThumbsPreviewAndEncryptsBackToTheOriginal() = runBlocking<Unit> {
        val bytes = png(1200, 800)
        val txt = OutFile("n.txt", "note".encodeToByteArray())
        val staged = buildOutgoingBundle(listOf(OutFile("pic.png", bytes), txt), JvmFileOperationsProvider())
        val bundle = staged.bundle
        assertEquals(listOf("chat_web0", "chat_web1"), bundle.payloads.map { it.key })
        assertTrue(bundle.payloads.all { Regex("^[a-z0-9_]{8,10}$").matches(it.key) })
        val image = bundle.payloads[0]
        assertEquals("image/png", image.contentType)
        assertEquals("", image.descriptorContent)
        val preview = assertNotNull(image.previewThumbnail)
        assertTrue(Base64.decode(preview.content!!).size <= HomebaseProtocol.MaxEmbeddedThumbBytes)
        assertTrue(maxOf(preview.pixelWidth, preview.pixelHeight) <= 20)
        assertEquals(listOf(preview), bundle.previewThumbs)
        assertTrue(bundle.thumbnails.isNotEmpty() && bundle.thumbnails.all { it.key == "chat_web0" })
        assertTrue(bundle.thumbnails.all { ImageIO.read(it.thumbnailBytes.inputStream()) != null })
        assertEquals("text/plain", bundle.payloads[1].contentType)
        assertEquals("n.txt", bundle.payloads[1].descriptorContent)

        val fileOps = JvmFileOperationsProvider()
        val keyHeader = KeyHeader.newRandom16()
        val encrypted = PayloadBundleEncryptionService(fileOps, VideoPayloadProcessor(fileOps), EventBus())
            .encryptBundle(Uuid.random(), bundle, keyHeader.aesKey, CoroutineScope(Dispatchers.Default))
        assertTrue(encrypted.payloads.all { it.isPreEncrypted && it.iv != null })
        assertFalse(encrypted.payloads[0].iv!!.contentEquals(encrypted.payloads[1].iv!!))
        val expected = listOf(bytes, txt.bytes)
        encrypted.payloads.forEachIndexed { i, p ->
            val cipher = File(p.filePath).readBytes()
            assertFalse(cipher.contentEquals(expected[i]))
            val plain = KeyHeader(iv = p.iv!!, aesKey = keyHeader.aesKey).decrypt(cipher)
            assertContentEquals(expected[i], plain)
        }
        val thumb = encrypted.thumbnails.first()
        val decrypted = KeyHeader(iv = encrypted.payloads[0].iv!!, aesKey = keyHeader.aesKey).decrypt(thumb.thumbnailBytes)
        assertContentEquals(bundle.thumbnails.first().thumbnailBytes, decrypted)
        encrypted.payloads.forEach { File(it.filePath).delete() }
        staged.cleanup()
    }

    @Test
    fun unreadableImageFallsBackToAPlainFile() = runBlocking<Unit> {
        val staged = buildOutgoingBundle(listOf(OutFile("fake.png", "not really a png".encodeToByteArray())), JvmFileOperationsProvider())
        val p = staged.bundle.payloads.single()
        assertEquals("application/octet-stream", p.contentType)
        assertEquals("fake.png", p.descriptorContent)
        staged.cleanup()
    }

    @Test
    fun metadataCarriesPreviewThumbnailAndAllowsBlankCaptionForMedia() = runBlocking<Unit> {
        val kh = KeyHeader.newRandom16()
        val thumb = id.homebase.api.client.drives.upload.EmbeddedThumb(20, 10, "image/jpeg", "AAAA")
        val m = buildMessageMetadata(self, Uuid.random(), "", 1L, kh, distribute = false, previewThumbnail = thumb, allowBlank = true)
        assertEquals(thumb, m.appData.previewThumbnail)
        assertEquals(0, m.appData.dataType)
        assertEquals(buildMessageContent("", allowBlank = true), kh.decrypt(Base64.decode(m.appData.content!!)).decodeToString())
        assertFailsWith<IllegalArgumentException> { buildMessageContent("") }
    }

    private class FilesBackend(override val allowlist: Allowlist, override val filesDir: File?) : AgentBackend {
        val sent = mutableListOf<Pair<OutFile, String>>()
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = emptyList<ChatMsg>()
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?) = Uuid.random()
        override suspend fun sendFile(conversationId: Uuid, file: OutFile, caption: String): Uuid {
            sent += file to caption
            return Uuid.random()
        }
    }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun mcpSendFileScopeReadOnlyAndDirectory() = runBlocking<Unit> {
        val root = tempDir()
        File(root, "a.txt").writeText("hi")
        val call = args("conversationId" to self.toString(), "path" to "a.txt", "caption" to "cap")

        val disabled = FilesBackend(Allowlist.default(owner), null)
        assertTrue(toolSendFile(disabled, call).let { it.isError && it.text.contains("mcpFilesDir") })
        assertTrue(disabled.sent.isEmpty())

        val ok = FilesBackend(Allowlist.default(owner), root)
        assertFalse(toolSendFile(ok, call).isError)
        assertEquals("a.txt" to "cap", ok.sent.single().let { it.first.name to it.second })

        val traversal = FilesBackend(Allowlist.default(owner), root)
        assertTrue(toolSendFile(traversal, args("conversationId" to self.toString(), "path" to "../x")).isError)
        assertTrue(traversal.sent.isEmpty())

        val readOnly = FilesBackend(Allowlist.default(owner).also { it.readOnly = true }, root)
        assertTrue(toolSendFile(readOnly, call).isError)
        assertTrue(readOnly.sent.isEmpty())

        val other = Uuid.random()
        val scoped = FilesBackend(Allowlist(setOf(self, other), setOf(owner)).also { it.scope = other }, root)
        assertTrue(toolSendFile(scoped, call).isError)
        assertTrue(scoped.sent.isEmpty())

        val delegate = FilesBackend(Allowlist(setOf(self), setOf(owner), delegate = true), root)
        toolSendFile(delegate, args("conversationId" to self.toString(), "path" to "a.txt"))
        assertEquals(BOT_PREFIX, delegate.sent.single().second)
    }

    private val page = id.homebase.api.client.link.LinkPreview("GitHub", "https://github.com/", "Where software is built", null, null, null)

    @Test
    fun linkPreviewUsesTheAppsPayloadShape() = runBlocking<Unit> {
        val staged = assertNotNull(outgoingBundle("look https://github.com/ ok", emptyList()) { page })
        val p = staged.bundle.payloads.single()
        assertEquals(ChatProtocol.PAYLOAD_KEY_LINKS, p.key)
        val label = payloadLabels(listOf(id.homebase.api.client.drives.files.PayloadDescriptor(p.key, p.contentType, descriptorContent = p.descriptorContent)))
        assertEquals(listOf("[link: https://github.com/ - GitHub]"), label)
        assertTrue(staged.bundle.previewThumbs.isEmpty())
        staged.bundle.payloads.forEach { File(it.filePath).delete() }
        staged.cleanup()
    }

    @Test
    fun linkPreviewSkippedWhenDisabledMissingOrFailing() = runBlocking<Unit> {
        assertNull(outgoingBundle("https://github.com/", emptyList(), null))
        assertNull(outgoingBundle("no link", emptyList()) { error("must not be called") })
        assertNull(outgoingBundle("https://github.com/", emptyList()) { null })
        assertNull(outgoingBundle("https://github.com/", emptyList()) { throw java.io.IOException("server down") })
    }

    @Test
    fun firstUrlPicksTheFirstAndTrimsPunctuation() {
        assertEquals("https://a.test/x?y=1", firstUrl("see (https://a.test/x?y=1). also http://b.test"))
        assertNull(firstUrl("no links here, only ftp://a.test"))
    }

    @Test
    fun processorSendsFilesReturnedByTheBrain() = runBlocking<Unit> {
        val sentFiles = mutableListOf<Triple<Uuid, String, List<OutFile>>>()
        val plain = mutableListOf<String>()
        val file = OutFile("a.txt", "x".encodeToByteArray())
        val processor = WatchProcessor(
            AgentConfig(allowlist = Allowlist.default(owner)), "owner.example.com", ProcessedStore(null),
            history = { emptyList() },
            brain = { _, _, _ -> BrainOutcome.Output("here", listOf(file)) },
            reply = { _, t -> plain += t },
            log = {},
            replyFiles = { c, t, f -> sentFiles += Triple(c, t, f) },
        )
        assertEquals("replied", processor.handle(ChatMsg(Uuid.random(), self, owner, "@quagmire send it", 1L)))
        assertTrue(plain.isEmpty())
        assertEquals("🤖 here", sentFiles.single().second)
        assertEquals("a.txt", sentFiles.single().third.single().name)
        val silentFiles = mutableListOf<String>()
        val p2 = WatchProcessor(
            AgentConfig(allowlist = Allowlist.default(owner)), "owner.example.com", ProcessedStore(null),
            history = { emptyList() },
            brain = { _, _, _ -> BrainOutcome.Output("", listOf(file)) },
            reply = { _, t -> plain += t },
            log = {},
            replyFiles = { _, t, _ -> silentFiles += t },
        )
        assertEquals("replied", p2.handle(ChatMsg(Uuid.random(), self, owner, "@quagmire send it", 1L)))
        assertEquals(listOf(BOT_PREFIX), silentFiles)
    }

    @Test
    fun linkPreviewsAndMcpDirAreConfigurable() {
        val c = parseConfig("linkPreviews=true\nmcpFilesDir=/tmp/x", owner)
        assertTrue(c.linkPreviews)
        assertEquals(File("/tmp/x"), c.mcpFilesDir)
        val d = parseConfig("", owner)
        assertFalse(d.linkPreviews)
        assertTrue(parseConfig("bot=true", owner).linkPreviews)
        assertFalse(parseConfig("bot=true\nlinkPreviews=false", owner).linkPreviews)
        assertNull(d.mcpFilesDir)
    }
}
