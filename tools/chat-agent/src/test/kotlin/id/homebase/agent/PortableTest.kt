package id.homebase.agent

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.common.OdinId
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.chat.services.ChatProtocol
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PortableTest {
    private val self = OdinId("bot.example.com")
    private val owner = OdinId("owner.example.com")
    private val member = OdinId("member.example.com")
    private val stranger = OdinId("stranger.example.com")
    private val room = Uuid.random()
    private val other = Uuid.random()
    private val dm = Uuid.random()

    @Test
    fun dataDirPerOs() {
        val home = "/home/u"
        assertEquals(File("/Users/u/Library/Application Support/HomebaseChatAgent/me"), Profile.dataDir("me", "Mac OS X", "/Users/u", emptyMap()))
        assertEquals(File("$home/.local/share/homebase-chat-agent/bot"), Profile.dataDir("bot", "Linux", home, emptyMap()))
        assertEquals(File("/x/share/homebase-chat-agent/bot"), Profile.dataDir("bot", "Linux", home, mapOf("XDG_DATA_HOME" to "/x/share")))
        assertEquals(File("/srv/agent/bot"), Profile.dataDir("bot", "Linux", home, mapOf("XDG_DATA_HOME" to "/x", "CHAT_AGENT_HOME" to "/srv/agent")))
        assertEquals(File("/srv/agent/me"), Profile.dataDir("me", "Mac OS X", "/Users/u", mapOf("CHAT_AGENT_HOME" to "/srv/agent")))
        assertEquals(File("C:\\Roaming/HomebaseChatAgent/me"), Profile.dataDir("me", "Windows 11", "C:\\u", mapOf("APPDATA" to "C:\\Roaming")))
        assertEquals(File("$home/.local/share/homebase-chat-agent/x"), Profile.dataDir("x", "Linux", home, mapOf("XDG_DATA_HOME" to " ", "CHAT_AGENT_HOME" to "")))
    }

    @Test
    fun versionLineShowsShaAndDate() {
        val p = Properties().apply { setProperty("sha", "abc1234"); setProperty("date", "2026-09-30") }
        assertEquals("chat-agent abc1234 built 2026-09-30", versionLine(p))
        assertEquals("chat-agent unknown built unknown", versionLine(Properties()))
        assertTrue(versionLine().startsWith("chat-agent "))
    }

    private fun roomConfig(rooms: Set<Uuid> = setOf(room), operators: Set<OdinId> = setOf(owner), brain: String? = "full"): AgentConfig {
        val allow = Allowlist(setOf(ChatProtocol.ConversationWithYourselfId, room, other, dm), emptySet(), authorsAnyMember = true, groupSend = true)
        allow.learn(
            listOf(
                ConversationInfo(room, "team", listOf(self, owner, member)),
                ConversationInfo(other, "other", listOf(self, owner, member, stranger)),
                ConversationInfo(dm, "dm", listOf(self, owner)),
            ),
        )
        return AgentConfig(bot = true, allowlist = allow, operators = operators, operatorBrain = brain, operatorRooms = rooms)
    }

    private fun tier(config: AgentConfig, conversation: Uuid, vararg senders: OdinId) =
        decideTier(config, self, config.allowlist.info(conversation)?.members, false, senders.toSet(), conversation)

    @Test
    fun everyMemberOfAnOperatorRoomIsOperator() {
        val c = roomConfig()
        assertEquals(Tier.OPERATOR, tier(c, room, member))
        assertEquals(Tier.OPERATOR, tier(c, room, owner, member))
        assertEquals(Tier.LOCKED, tier(c, room, stranger))
        assertEquals(Tier.LOCKED, tier(c, room, member, stranger))
    }

    @Test
    fun sameMemberElsewhereIsLocked() {
        val c = roomConfig()
        assertEquals(Tier.LOCKED, tier(c, other, member))
        assertEquals(Tier.OPERATOR, tier(c, dm, owner))
        assertEquals(Tier.LOCKED, tier(c, room, member).takeIf { false } ?: tier(roomConfig(rooms = emptySet()), room, member))
    }

    @Test
    fun operatorRoomNeedsAnOperatorBrainAndKnownMembers() {
        assertEquals(Tier.LOCKED, tier(roomConfig(brain = null), room, member))
        val c = roomConfig()
        assertEquals(Tier.LOCKED, decideTier(c, self, null, false, setOf(member), room))
    }

    @Test
    fun removedMemberLosesAccessAfterRediscovery() {
        val c = roomConfig()
        assertEquals(Tier.OPERATOR, tier(c, room, member))
        c.allowlist.learn(listOf(ConversationInfo(room, "team", listOf(self, owner))))
        assertEquals(Tier.LOCKED, tier(c, room, member))
    }

    @Test
    fun operatorRoomHistoryIsUnfilteredElsewhereFiltered() {
        val c = roomConfig()
        fun m(sender: OdinId, t: String) = ChatMsg(Uuid.random(), room, sender, t, 1L, sender = sender)
        val history = listOf(m(member, "a"), m(stranger, "b"), m(self, "c"))
        assertEquals(listOf("a", "b", "c"), operatorHistory(history, c, self, room).map { it.text })
        assertEquals(listOf("c"), operatorHistory(history, c, self, other).map { it.text })
        assertTrue(isOperatorSender(c, self, member, room, c.allowlist.info(room)?.members))
        assertFalse(isOperatorSender(c, self, member, other, c.allowlist.info(other)?.members))
    }

    @Test
    fun bannerListsRoomsWithMemberCountsAndWarnsAboutMachineAccess() {
        val lines = tierBanner(roomConfig(rooms = setOf(room, Uuid.random())))
        assertTrue(lines.any { it.startsWith("operator room $room (team): 3 members") }, lines.toString())
        assertTrue(lines.any { it.contains("not discovered") && it.contains("ignored") })
        assertTrue(lines.any { it.startsWith("WARNING: group membership grants machine access") })
    }

    @Test
    fun configParsesRoomsAndRejectsBadTimeout() {
        val c = parseConfig("operatorBrain=x\noperatorRooms=$room, $other", owner)
        assertEquals(setOf(room, other), c.operatorRooms)
        assertTrue(parseConfig("", owner).operatorRooms.isEmpty())
        val e = assertFailsWith<IllegalArgumentException> { parseConfig("operatorTimeout=soon", owner) }
        assertTrue(e.message!!.contains("operatorTimeout"))
        assertEquals(90_000L, parseConfig("operatorTimeout=90s", owner).operatorTimeoutMs)
    }

    @Test
    fun processorGivesRoomMembersTheOperatorBrainAndIgnoresUnlistedDms() = runBlocking<Unit> {
        val allow = Allowlist(setOf(ChatProtocol.ConversationWithYourselfId, room, other), emptySet(), authorsAnyMember = true, groupSend = true)
        allow.learn(
            listOf(
                ConversationInfo(room, "team", listOf(self, owner, member)),
                ConversationInfo(other, "other", listOf(self, owner, member, stranger)),
            ),
        )
        val config = AgentConfig(bot = true, allowlist = allow, operators = setOf(owner), operatorBrain = "full", operatorRooms = setOf(room))
        val tiers = mutableListOf<Tier>()
        val p = WatchProcessor(
            config, self.toString(), ProcessedStore(null),
            history = { emptyList() },
            brain = { _, t, _ -> tiers += t; BrainOutcome.Output("ok") },
            reply = { _, _ -> },
            log = {},
        )
        var n = 0L
        fun say(c: Uuid, who: OdinId) = ChatMsg(Uuid.random(), c, who, "@quagmire hi", ++n, sender = who)
        val unlistedDm = Uuid.random()
        val unlisted = say(unlistedDm, stranger)
        val results = p.handleAll(listOf(say(room, member), say(other, member), unlisted))
        assertEquals(listOf(Tier.OPERATOR, Tier.LOCKED), tiers)
        assertEquals("skip: conversation not allowed", results[unlisted.id])
    }

    @Test
    fun doubleForkedDaemonDiesWithTheBrainOnTimeout() = runBlocking<Unit> {
        val pidFile = File.createTempFile("pid", ".txt")
        val out = runBrain("(sleep 300 </dev/null >/dev/null 2>&1 & echo \$! > ${pidFile.absolutePath}); sleep 60", "", timeoutMs = 1000, tier = Tier.OPERATOR)
        assertTrue(out is BrainOutcome.Failed && out.reason.startsWith("timeout"))
        val pid = pidFile.readText().trim().toLong()
        untilTrue("daemon dead") { !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
        pidFile.delete()
    }

    private suspend fun untilTrue(what: String, cond: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!cond()) delay(20) }
        } catch (e: Exception) {
            error("timed out waiting for $what")
        }
    }

    private fun runner(journal: File? = null) = JobRunner(CoroutineScope(Dispatchers.Default), RunLimiter(null, Int.MAX_VALUE, 10), prefix = "", journal = journal)

    @Test
    fun jobFailureMessageIsSanitisedTruncatedOneLine() = runBlocking<Unit> {
        val delivered = CompletableDeferred<String>()
        runner().submit(room, setOf("a"), announce = {}, deliver = { delivered.complete(it) }, work = { error("🤖 " + "x".repeat(500) + "\nsecond line") })
        val text = withTimeout(5_000) { delivered.await() }
        assertTrue(text.startsWith("job 1 failed: xxx"), text)
        assertFalse('\n' in text)
        assertTrue(text.codePointCount(0, text.length) <= "job 1 failed: ".length + 120)
        assertEquals("job 2 failed: a b", jobText(2, BrainOutcome.Failed("a\n\n b"), ""))
    }

    @Test
    fun cancelIsScopedToTheStartingConversation() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val r = runner()
        r.submit(room, setOf("a"), announce = {}, deliver = {}, work = { gate.await(); BrainOutcome.Output("ok") })
        assertEquals("job 1 was started in another conversation", r.cancel(1) { it == other })
        assertTrue(r.status().startsWith("job 1 running"))
        assertEquals("cancelled job 1", r.cancel(null) { it == room })
        gate.complete(Unit)
    }

    @Test
    fun processorLetsAnotherRoomMemberCancelOnlyInTheSameRoomOrAsListedOperatorMember() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val allow = Allowlist(setOf(room, other), emptySet(), authorsAnyMember = true, groupSend = true)
        allow.learn(
            listOf(
                ConversationInfo(room, "team", listOf(self, owner, member)),
                ConversationInfo(other, "other", listOf(self, member, stranger)),
            ),
        )
        val config = AgentConfig(bot = true, allowlist = allow, operators = setOf(owner), operatorBrain = "full", operatorRooms = setOf(room, other))
        val replies = CopyOnWriteArrayList<Pair<Uuid, String>>()
        val p = WatchProcessor(
            config, self.toString(), ProcessedStore(null),
            history = { emptyList() },
            brain = { _, _, _ -> gate.await(); BrainOutcome.Output("ok") },
            reply = { c, t -> replies += c to t },
            log = {},
            jobs = runner(),
        )
        var n = 0L
        suspend fun say(c: Uuid, who: OdinId, text: String) = p.handleAll(listOf(ChatMsg(Uuid.random(), c, who, text, ++n, sender = who)))
        say(room, member, "@quagmire build it")
        say(other, stranger, "@quagmire cancel")
        assertTrue(replies.last().second.contains("started in another conversation"), replies.toString())
        say(room, member, "@quagmire cancel")
        assertEquals("cancelled job 1", replies.last().second)
        gate.complete(Unit)
    }

    @Test
    fun droppedQueueIsAnnouncedAfterRestart() = runBlocking<Unit> {
        val journal = File.createTempFile("jobs-pending", ".txt")
        journal.delete()
        val gate = CompletableDeferred<Unit>()
        val first = runner(journal)
        first.submit(room, setOf("a"), announce = {}, deliver = {}, work = { gate.await(); BrainOutcome.Output("ok") })
        first.submit(other, setOf("a"), announce = {}, deliver = {}, work = { gate.await(); BrainOutcome.Output("ok") })
        assertTrue(journal.exists())
        val notices = mutableListOf<Pair<Uuid, String>>()
        runner(journal).recoverDropped { c, t -> notices += c to t }
        assertEquals(listOf(room, other), notices.map { it.first })
        assertTrue(notices.all { it.second.startsWith("restarted: job") && it.second.contains("dropped") })
        assertFalse(journal.exists())
        gate.complete(Unit)
    }

    private fun tempDir() = Files.createTempDirectory("portable").toFile()

    @Test
    fun loadInsideReadsOnceAndRefusesSymlinks() {
        val root = tempDir()
        val outside = tempDir()
        File(outside, "s.txt").writeText("secret")
        File(root, "a.txt").writeText("hello")
        Files.createSymbolicLink(File(root, "l.txt").toPath(), File(outside, "s.txt").toPath())
        assertEquals("hello", loadInside(root, "a.txt").bytes.decodeToString())
        assertFailsWith<IllegalArgumentException> { loadInside(root, "l.txt") }
        File(root, "big.bin").writeBytes(ByteArray(20))
        val e = assertFailsWith<IllegalArgumentException> { loadInside(root, "big.bin", maxBytes = 10) }
        assertTrue(e.message!!.contains("limit"))
    }

    private fun jpegWithOrientation(orientation: Int): ByteArray {
        val img = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 8) for (x in 0 until 16) img.setRGB(x, y, if (x < 8 && y < 4) 0xFF0000 else 0x0000FF)
        val out = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return out.copyOfRange(0, 2) + app1 + out.copyOfRange(2, out.size)
    }

    @Test
    fun exifOrientationIsParsedAndBakedIn() {
        val jpeg = jpegWithOrientation(6)
        assertEquals(6, exifOrientation(jpeg))
        assertEquals(1, exifOrientation(jpegWithOrientation(1)))
        assertEquals(1, exifOrientation(byteArrayOf(1, 2, 3)))
        val prepared = prepareImage(jpeg, decodeImage(jpeg)!!)
        val img = ImageIO.read(ByteArrayInputStream(prepared.bytes))
        assertEquals(8, img.width)
        assertEquals(16, img.height)
        assertEquals(1, exifOrientation(prepared.bytes))
        val topRight = img.getRGB(6, 2)
        val topLeft = img.getRGB(1, 2)
        assertTrue((topRight shr 16 and 0xFF) > 200 && (topRight and 0xFF) < 80, "top right should be red")
        assertTrue((topLeft and 0xFF) > 200 && (topLeft shr 16 and 0xFF) < 80, "top left should be blue")
    }

    @Test
    fun uprightSmallImagesGoOutByteForByteAndBigOnesAreScaledTo1600() {
        val small = jpegWithOrientation(1)
        val same = prepareImage(small, decodeImage(small)!!)
        assertTrue(same.bytes === small)
        val big = BufferedImage(3200, 800, BufferedImage.TYPE_INT_RGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(big, "png", it) }.toByteArray()
        val scaled = prepareImage(png, decodeImage(png)!!)
        val img = ImageIO.read(ByteArrayInputStream(scaled.bytes))
        assertEquals(1600, img.width)
        assertEquals(400, img.height)
        assertEquals("image/png", scaled.decoded.contentType)
    }

    @Test
    fun linkPreviewOgImageThumbnailsWithoutSkia() = runBlocking<Unit> {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val preview = LinkPreview("Site", "https://a.test/", "desc", "data:image/png;base64," + Base64.encode(png), 60, 120)
        val bundle = buildLinkPreviewBundle(preview, JvmFileOperationsProvider())
        val p = bundle.payloads.single()
        assertEquals(ChatProtocol.PAYLOAD_KEY_LINKS, p.key)
        assertEquals("image/png", p.contentType)
        assertEquals(1, bundle.previewThumbs.size)
        assertTrue(p.descriptorContent!!.contains("\"hasImage\":true"), p.descriptorContent)
        val plain = buildLinkPreviewBundle(preview.copy(imageUrl = "data:image/webp;base64,AAAA"), JvmFileOperationsProvider())
        assertTrue(plain.previewThumbs.isEmpty())
        assertTrue(plain.payloads.single().descriptorContent!!.contains("\"hasImage\":false"))
        bundle.payloads.plus(plain.payloads).forEach { File(it.filePath).delete() }
    }

    private fun tinyPdf(pages: Int): ByteArray {
        val body = StringBuilder("%PDF-1.4\n")
        repeat(pages) { body.append("${it + 3} 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n") }
        body.append("2 0 obj\n<< /Type /Pages /Count $pages >>\nendobj\n")
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun pdfsBecomeDocumentBlocksWithinPageAndSizeLimits() = runBlocking<Unit> {
        assertEquals(3, pdfPageCount(tinyPdf(3)))
        val id = Uuid.random()
        val small = Attachment(id, false, "1-a.pdf", "application/pdf", 100, bytes = tinyPdf(2))
        assertTrue(small.viewablePdf)
        val json = streamJsonInput("hi", listOf(small))
        assertTrue(json.contains("\"type\":\"document\"") && json.contains("\"media_type\":\"application/pdf\""), json)
        assertFalse(Attachment(id, false, "2-b.pdf", "application/pdf", 100, bytes = tinyPdf(2), note = "not shown to the model: 30 pages, limit 20").viewablePdf)

        fun payload(key: String) = PayloadDescriptor(key = key, contentType = "application/pdf", bytesWritten = 100, descriptorContent = "doc.pdf")
        val conv = ChatProtocol.ConversationWithYourselfId
        fun msg(key: String) = ChatMsg(Uuid.random(), conv, owner, "", 1L, payloads = listOf(payload(key)), fileId = Uuid.random())
        val data = mapOf("pfl0000001" to tinyPdf(2), "pfl0000002" to tinyPdf(25), "pfl0000003" to "not a pdf".encodeToByteArray())
        val loader = AttachmentLoader(PayloadFetcher { _, key, _ -> data[key] })
        val loaded = loader.load(listOf(msg("pfl0000001") to false, msg("pfl0000002") to false, msg("pfl0000003") to false))
        assertEquals(listOf(true, false, false), loaded.map { it.viewablePdf })
        assertTrue(loaded[1].note!!.contains("25 pages"))
        assertEquals("not a valid PDF", loaded[2].note)
        assertNull(loaded[0].note)
    }
}
