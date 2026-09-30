package id.homebase.agent

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.crypto.AesCbc
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.codePointCount
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.event.EventDescriptor
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

class TypedToolsTest {
    private val owner = OdinId("owner.example.com")
    private val other = OdinId("other.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId
    private val group = Uuid.random()
    private val absent = Ffmpeg("/nonexistent-dir-for-l20b")

    private inner class Fake(
        override val allowlist: Allowlist = Allowlist.default(owner).copy(self, false),
        override val filesDir: File? = null,
        override val ffmpeg: Ffmpeg = absent,
        val msgs: List<ChatMsg> = emptyList(),
        val delegate: Boolean = false,
    ) : AgentBackend {
        val typed = CopyOnWriteArrayList<MessageContent>()
        val votes = CopyOnWriteArrayList<Pair<Int, PollDescriptor>>()
        val videos = CopyOnWriteArrayList<Pair<OutVideo, String>>()
        val voices = CopyOnWriteArrayList<Pair<OutVoice, String>>()
        val files = CopyOnWriteArrayList<Pair<OutFile, String>>()
        override val sendPrefix: String get() = if (delegate) BOT_PREFIX else ""
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = msgs
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?) = Uuid.random()
        override fun disclose(conversationId: Uuid, text: String) = allowlist.disclosure(conversationId, text, owner)
        override suspend fun sendTyped(conversationId: Uuid, content: MessageContent): Uuid { typed += content; return Uuid.random() }
        override suspend fun vote(conversationId: Uuid, message: ChatMsg, option: Int, poll: PollDescriptor): Boolean { votes += option to poll; return true }
        override suspend fun sendFile(conversationId: Uuid, file: OutFile, caption: String): Uuid { files += file to caption; return Uuid.random() }
        override suspend fun sendVideo(conversationId: Uuid, video: OutVideo, caption: String): Uuid { videos += video to caption; return Uuid.random() }
        override suspend fun sendVoice(conversationId: Uuid, voice: OutVoice, caption: String): Uuid { voices += voice to caption; return Uuid.random() }
    }

    private fun delegateFake(filesDir: File? = null): Fake {
        val allowlist = Allowlist(setOf(self, group), setOf(owner), Kind.DELEGATE).copy(group, false)
        allowlist.learnDerived(ConversationInfo(group, "the group", listOf(owner, other)))
        return Fake(allowlist, filesDir, delegate = true)
    }

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
                is Boolean -> put(k, v)
                is Number -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }

    private fun roundTrip(content: MessageContent): MessageContent? =
        MessageContentParser.parse(MessageContentParser.dataTypeFor(content), MessageContentParser.serialize(content))

    private fun scope(b: Fake, a: JsonObject, id: Uuid) = scopedArguments(a, id)

    private suspend fun ok(reply: ToolReply): ToolReply { assertFalse(reply.isError, reply.text); return reply }
    private fun refused(reply: ToolReply, part: String? = null) {
        assertTrue(reply.isError, "expected a refusal, got: ${reply.text}")
        part?.let { assertTrue(reply.text.contains(it, ignoreCase = true), reply.text) }
    }

    private fun mp4(dir: File, name: String = "clip.mp4", seconds: Int = 1): File {
        val out = File(dir, name)
        val p = ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-t", "$seconds", "-pix_fmt", "yuv420p", out.absolutePath)
            .redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        assertEquals(0, p.waitFor())
        return out
    }

    @Test
    fun pollDescriptorParsesBackThroughTheAppParser() = runBlocking<Unit> {
        val b = Fake()
        ok(toolSendPoll(b, scope(b, args("question" to "Lunch?", "options" to listOf("Pizza", "Sushi", "Tacos"), "allowMultiple" to true), self)))
        val sent = b.typed.single() as MessageContent.Poll
        assertEquals(PollDescriptor("Lunch?", listOf("Pizza", "Sushi", "Tacos"), allowMultiple = true), sent.descriptor)
        assertEquals(sent, roundTrip(sent))
        assertEquals(ChatProtocol.ChatPollMessageDataType, MessageContentParser.dataTypeFor(sent))
    }

    @Test
    fun pollValidationRefusals() = runBlocking<Unit> {
        val b = Fake()
        fun poll(question: String?, options: Any?) = runBlocking { toolSendPoll(b, scope(b, args("question" to question, "options" to options), self)) }
        refused(poll("q", listOf("only one")), "options")
        refused(poll("q", (1..11).map { "o$it" }), "options")
        refused(poll("q", listOf("a", "  ")), "empty")
        refused(poll("", listOf("a", "b")), "question")
        refused(poll("q", "not a list"))
        assertTrue(b.typed.isEmpty())
        ok(poll("q", (1..10).map { "o$it" }))
    }

    @Test
    fun longPollTextIsCappedToTheAppLimits() = runBlocking<Unit> {
        val b = Fake()
        ok(toolSendPoll(b, scope(b, args("question" to "q".repeat(400), "options" to listOf("x".repeat(300), "b")), self)))
        val poll = (b.typed.single() as MessageContent.Poll).descriptor
        assertNotNull(poll)
        assertEquals(PollDescriptor.MAX_QUESTION_CP, poll.question.codePointCount())
        assertEquals(PollDescriptor.MAX_OPTION_CP, poll.options[0].codePointCount())
        assertTrue(poll.isValid())
    }

    private fun pollMessage(closed: Boolean = false, multi: Boolean = false): ChatMsg {
        val descriptor = PollDescriptor("Lunch?", listOf("Pizza", "Sushi"), allowMultiple = multi, closed = closed)
        return ChatMsg(Uuid.random(), self, other, "Lunch?", 1L, dataType = ChatProtocol.ChatPollMessageDataType, rawContent = OdinSystemSerializer.serialize(descriptor), fileId = Uuid.random())
    }

    @Test
    fun voteResolvesNumberOrTextAndRefusesBadTargets() = runBlocking<Unit> {
        val poll = pollMessage()
        val b = Fake(msgs = listOf(poll, ChatMsg(Uuid.random(), self, other, "plain", 2L, fileId = Uuid.random())))
        fun vote(id: Uuid, option: String) = runBlocking { toolVotePoll(b, scope(b, args("messageId" to id.toString().take(8), "option" to option), self)) }
        ok(vote(poll.id, "2"))
        ok(vote(poll.id, "pizza"))
        assertEquals(listOf(1, 0), b.votes.map { it.first })
        refused(vote(poll.id, "0"), "option")
        refused(vote(poll.id, "3"), "option")
        refused(vote(poll.id, "Ramen"), "option")
        refused(vote(b.msgs[1].id, "1"), "not a poll")
        val closed = pollMessage(closed = true)
        refused(toolVotePoll(Fake(msgs = listOf(closed)), scope(b, args("messageId" to closed.id.toString().take(8), "option" to "1"), self)), "closed")
        assertEquals(2, b.votes.size)
    }

    @Test
    fun eventDescriptorParsesBackAndUsesTheZone() = runBlocking<Unit> {
        val b = Fake()
        ok(toolSendEvent(b, scope(b, args("title" to "Standup", "start" to "2026-10-01T18:00", "timezone" to "Europe/Berlin", "place" to "Room 4", "description" to "Bring notes"), self)))
        val event = b.typed.single() as MessageContent.Event
        val d = event.descriptor
        assertNotNull(d)
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 1, 18, 0, 0, 0, java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(), d.startUtcMs)
        assertEquals(d.startUtcMs + 3_600_000L, d.endUtcMs)
        assertEquals(EventDescriptor("Standup", "Bring notes", d.startUtcMs, d.endUtcMs, "Europe/Berlin", "Room 4"), d)
        assertEquals(event, roundTrip(event))
        ok(toolSendEvent(b, scope(b, args("title" to "Z", "start" to "2026-10-01T18:00:00Z", "end" to "2026-10-01T19:30:00+00:00"), self)))
        assertEquals(90 * 60_000L, (b.typed.last() as MessageContent.Event).descriptor!!.let { it.endUtcMs!! - it.startUtcMs })
    }

    @Test
    fun eventValidationRefusalsAndCaps() = runBlocking<Unit> {
        val b = Fake()
        fun event(vararg p: Pair<String, Any?>) = runBlocking { toolSendEvent(b, scope(b, args(*p), self)) }
        refused(event("title" to " ", "start" to "2026-10-01T18:00"), "title")
        refused(event("title" to "x", "start" to "next tuesday"), "ISO")
        refused(event("title" to "x", "start" to "2026-10-01T18:00", "end" to "2026-10-01T17:00"), "after")
        refused(event("title" to "x", "start" to "2026-10-01T18:00", "timezone" to "Mars/Base"), "timezone")
        assertTrue(b.typed.isEmpty())
        ok(event("title" to "t".repeat(300), "start" to "2026-10-01T18:00", "description" to "d".repeat(900), "place" to "p".repeat(500)))
        val d = (b.typed.single() as MessageContent.Event).descriptor!!
        assertEquals(EVENT_TITLE_CODEPOINTS, d.title.codePointCount())
        assertEquals(EVENT_DESCRIPTION_CODEPOINTS, d.description.codePointCount())
        assertEquals(EVENT_PLACE_CODEPOINTS, d.locationText!!.codePointCount())
    }

    @Test
    fun locationParsesBackIsStaticAndRangeChecked() = runBlocking<Unit> {
        val b = Fake()
        ok(toolSendLocation(b, scope(b, args("lat" to 52.52, "lon" to 13.405, "label" to "Berlin"), self)))
        val location = b.typed.single() as MessageContent.Location
        assertEquals(LocationPreviewDescriptor(52.52, 13.405, "Berlin", false, null, null, null, "Berlin"), location.descriptor)
        assertNull(location.descriptor!!.liveShareUntilMs)
        assertEquals(location, roundTrip(location))
        fun loc(lat: Any?, lon: Any?) = runBlocking { toolSendLocation(b, scope(b, args("lat" to lat, "lon" to lon), self)) }
        refused(loc(91, 0), "lat")
        refused(loc(-90.5, 0), "lat")
        refused(loc(0, 180.1), "lon")
        refused(loc(0, -181), "lon")
        refused(loc("north", 0), "lat")
        refused(loc("NaN", 0), "lat")
        refused(loc(null, 0), "lat")
        refused(loc(0, null), "lon")
        ok(loc(-90, 180))
        assertEquals(2, b.typed.size)
    }

    @Test
    fun contactParsesBackAndValidatesPhonesAndEmails() = runBlocking<Unit> {
        val b = Fake()
        ok(toolSendContact(b, scope(b, args("name" to "Ada Lovelace", "phones" to listOf("+1 (415) 555-0123", "+442071838750"), "emails" to listOf("ada@example.com"), "organization" to "Analytical"), self)))
        val card = b.typed.single() as MessageContent.ContactCard
        assertEquals(ContactCardDescriptor("Ada Lovelace", organization = "Analytical", phones = listOf("+14155550123", "+442071838750"), emails = listOf("ada@example.com")), card.descriptor)
        assertEquals(card, roundTrip(card))
        fun contact(vararg p: Pair<String, Any?>) = runBlocking { toolSendContact(b, scope(b, args(*p), self)) }
        refused(contact("name" to "A", "phones" to listOf("4155550123")), "E.164")
        refused(contact("name" to "A", "phones" to listOf("+0123456")), "E.164")
        refused(contact("name" to "A", "phones" to listOf("+1234567890123456")), "E.164")
        refused(contact("name" to "A", "emails" to listOf("no-at-sign")), "email")
        refused(contact("name" to "A", "emails" to listOf("a@b")), "email")
        refused(contact("name" to "  "), "name")
        refused(contact("name" to "A", "phones" to (1..11).map { "+1415555${1000 + it}" }), "at most")
        assertEquals(1, b.typed.size)
    }

    @Test
    fun delegateDisclosureRidesInsideEveryTypedKindWithinItsCap() = runBlocking<Unit> {
        val b = delegateFake()
        val prefix = "$BOT_PREFIX owner.example.com's AI assistant:"
        ok(toolSendPoll(b, scope(b, args("question" to "Lunch?", "options" to listOf("a", "b")), group)))
        ok(toolSendPoll(b, scope(b, args("question" to "q".repeat(400), "options" to listOf("a", "b")), group)))
        ok(toolSendEvent(b, scope(b, args("title" to "Standup", "start" to "2026-10-01T18:00"), group)))
        ok(toolSendEvent(b, scope(b, args("title" to "t".repeat(400), "start" to "2026-10-01T18:00"), group)))
        ok(toolSendLocation(b, scope(b, args("lat" to 1, "lon" to 2, "label" to "Cafe"), group)))
        ok(toolSendLocation(b, scope(b, args("lat" to 1, "lon" to 2), group)))
        ok(toolSendContact(b, scope(b, args("name" to "Ada", "organization" to "Acme"), group)))
        ok(toolSendContact(b, scope(b, args("name" to "Ada"), group)))
        val fields = b.typed.map {
            when (it) {
                is MessageContent.Poll -> it.descriptor!!.question to PollDescriptor.MAX_QUESTION_CP
                is MessageContent.Event -> it.descriptor!!.title to EVENT_TITLE_CODEPOINTS
                is MessageContent.Location -> it.descriptor!!.caption.orEmpty() to LOCATION_LABEL_CODEPOINTS
                is MessageContent.ContactCard -> it.descriptor!!.organization to CONTACT_NAME_CODEPOINTS
                else -> error("unexpected $it")
            }
        }
        assertEquals(8, fields.size)
        fields.forEach { (text, cap) ->
            assertTrue(text.startsWith(prefix), text)
            assertTrue(text.codePointCount() <= cap, "$text over $cap")
        }
        assertTrue(b.typed.all { roundTrip(it) == it })
    }

    @Test
    fun noDisclosureInNoteToSelfOrForAPlainProfile() = runBlocking<Unit> {
        val mine = Allowlist(setOf(self), setOf(owner), Kind.DELEGATE).copy(self, false)
        val b = Fake(mine, delegate = true)
        ok(toolSendPoll(b, scope(b, args("question" to "Lunch?", "options" to listOf("a", "b")), self)))
        assertEquals("Lunch?", (b.typed.single() as MessageContent.Poll).descriptor!!.question)
    }

    @Test
    fun sendsToOtherConversationsAreRefused() = runBlocking<Unit> {
        val b = Fake()
        refused(toolSendPoll(b, scope(b, args("question" to "q", "options" to listOf("a", "b")), Uuid.random())))
        refused(toolSendContact(b, scope(b, args("name" to "x"), Uuid.random())))
        assertTrue(b.typed.isEmpty())
    }

    @Test
    fun videoAndVoiceStayInsideTheFilesDirectory() = runBlocking<Unit> {
        val root = Files.createTempDirectory("l20b-root").toFile()
        val outside = Files.createTempDirectory("l20b-outside").toFile()
        File(outside, "secret.mp4").writeBytes(ByteArray(64) { 1 })
        File(outside, "secret.m4a").writeBytes(ByteArray(64) { 1 })
        Files.createSymbolicLink(File(root, "link.mp4").toPath(), File(outside, "secret.mp4").toPath())
        Files.createSymbolicLink(File(root, "link.m4a").toPath(), File(outside, "secret.m4a").toPath())
        val b = Fake(filesDir = root)
        for (path in listOf("../${outside.name}/secret.mp4", File(outside, "secret.mp4").absolutePath, "link.mp4", "missing.mp4")) refused(toolSendVideo(b, scope(b, args("path" to path), self)), null)
        for (path in listOf("../${outside.name}/secret.m4a", File(outside, "secret.m4a").absolutePath, "link.m4a", "missing.m4a")) refused(toolSendVoice(b, scope(b, args("path" to path), self)), null)
        refused(toolSendVideo(Fake(), scope(b, args("path" to "x.mp4"), self)), "mcpFilesDir")
        assertTrue(b.videos.isEmpty() && b.voices.isEmpty() && b.files.isEmpty())
    }

    @Test
    fun videoWithoutFfmpegFallsBackToAFileAndSaysSo() = runBlocking<Unit> {
        val root = Files.createTempDirectory("l20b-root").toFile()
        File(root, "clip.mp4").writeBytes(ByteArray(2048) { 3 })
        val b = Fake(filesDir = root)
        val reply = ok(toolSendVideo(b, scope(b, args("path" to "clip.mp4", "caption" to "hi"), self)))
        assertTrue(reply.text.contains("as a file") && reply.text.contains("ffmpeg"), reply.text)
        assertEquals(1, b.files.size)
        assertEquals("clip.mp4", b.files.single().first.name)
        assertTrue(b.videos.isEmpty())
    }

    @Test
    fun voiceWithoutFfprobeSendsWithUnknownDurationAndRejectsNonAudio() = runBlocking<Unit> {
        val root = Files.createTempDirectory("l20b-root").toFile()
        File(root, "note.m4a").writeBytes(ByteArray(2048) { 3 })
        File(root, "doc.pdf").writeBytes(ByteArray(64))
        val b = Fake(filesDir = root)
        val reply = ok(toolSendVoice(b, scope(b, args("path" to "note.m4a"), self)))
        assertTrue(reply.text.contains("duration unknown"), reply.text)
        assertEquals(0, b.voices.single().first.seconds)
        assertEquals("audio/mp4", b.voices.single().first.contentType)
        refused(toolSendVoice(b, scope(b, args("path" to "doc.pdf"), self)), "audio")
    }

    @Test
    fun videoSendsRealDurationThumbnailAndOnlyAnMp4Under5Mb() = runBlocking<Unit> {
        assumeTrue(Ffmpeg().available)
        val root = Files.createTempDirectory("l20b-root").toFile()
        mp4(root)
        File(root, "clip.mov").writeBytes(File(root, "clip.mp4").readBytes())
        File(root, "big.mp4").writeBytes(ByteArray((VIDEO_MAX_BYTES + 1).toInt()))
        File(root, "junk.mp4").writeBytes(ByteArray(2048) { 7 })
        val b = Fake(filesDir = root, ffmpeg = Ffmpeg())
        ok(toolSendVideo(b, scope(b, args("path" to "clip.mp4"), self)))
        val video = b.videos.single().first
        assertTrue(video.durationMs in 800..1300, "${video.durationMs}")
        assertEquals(160 to 120, video.width to video.height)
        assertNotNull(decodeImage(video.poster))
        refused(toolSendVideo(b, scope(b, args("path" to "clip.mov"), self)), ".mp4")
        refused(toolSendVideo(b, scope(b, args("path" to "junk.mp4"), self)), "not a playable")
        val big = ok(toolSendVideo(b, scope(b, args("path" to "big.mp4"), self)))
        assertTrue(big.text.contains("as a file"), big.text)
        assertEquals(1, b.videos.size)
    }

    @Test
    fun voiceReadsItsDurationFromFfprobe() = runBlocking<Unit> {
        assumeTrue(Ffmpeg().available)
        val root = Files.createTempDirectory("l20b-root").toFile()
        val wav = File(root, "note.wav")
        ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", wav.absolutePath).redirectErrorStream(true).start().also { it.inputStream.readAllBytes(); it.waitFor() }
        val b = Fake(filesDir = root, ffmpeg = Ffmpeg())
        ok(toolSendVoice(b, scope(b, args("path" to "note.wav"), self)))
        val voice = b.voices.single().first
        assertEquals(2, voice.seconds)
        val bundle = stageVoice(voice).bundle
        val payload = bundle.payloads.single()
        assertEquals("audio/wav", payload.contentType)
        val descriptor = OdinSystemSerializer.deserialize<DescriptorContent.AudioFile>(payload.descriptorContent!!)
        assertEquals(2, descriptor.lengthSeconds)
    }

    @Test
    fun stagedVideoIsAnEncryptedMp4WithAThumbnailAndTruthfulMetadata() = runBlocking<Unit> {
        assumeTrue(Ffmpeg().available)
        val root = Files.createTempDirectory("l20b-root").toFile()
        val file = mp4(root)
        val b = Fake(filesDir = root, ffmpeg = Ffmpeg())
        toolSendVideo(b, scope(b, args("path" to "clip.mp4"), self))
        val video = b.videos.single().first
        val key = KeyHeader.newRandom16()
        val staged = stageVideo(video, key, JvmFileOperationsProvider())
        try {
            assertTrue(staged.preEncrypted)
            val payload = staged.bundle.payloads.single()
            assertEquals("video/mp4", payload.contentType)
            assertTrue(payload.isPreEncrypted)
            val meta = OdinSystemSerializer.deserialize<VideoMetadata>(payload.descriptorContent!!)
            assertEquals(160 to 120, meta.widthPx to meta.heightPx)
            assertFalse(meta.isSegmented)
            assertTrue(meta.duration in 800f..1300f)
            assertNotNull(payload.previewThumbnail)
            assertTrue(staged.bundle.thumbnails.isNotEmpty() && staged.bundle.thumbnails.all { it.key == payload.key })
            val plain = AesCbc.decrypt(File(payload.filePath).readBytes(), key.aesKey, payload.iv!!)
            assertTrue(plain.contentEquals(file.readBytes()))
            val thumb = staged.bundle.thumbnails.first()
            assertNotNull(decodeImage(AesCbc.decrypt(thumb.thumbnailBytes, key.aesKey, payload.iv!!)))
        } finally {
            staged.bundle.payloads.forEach { File(it.filePath).delete() }
            staged.cleanup()
        }
    }

    @Test
    fun keyframesAreBoundedAndDecodable() = runBlocking<Unit> {
        assumeTrue(Ffmpeg().available)
        val root = Files.createTempDirectory("l20b-root").toFile()
        val frames = Ffmpeg().keyframes(mp4(root, seconds = 3), 3_000, 9)
        assertEquals(KEYFRAMES_MAX, frames.size)
        assertTrue(frames.all { decodeImage(it) != null && it.size < IMAGE_MAX_BYTES })
    }

    @Test
    fun aHungFfmpegIsKilledAtTheTimeout() = runBlocking<Unit> {
        val bin = Files.createTempDirectory("l20b-bin").toFile()
        val marker = File(bin, "started")
        for (name in listOf("ffmpeg", "ffprobe")) File(bin, name).apply { writeText("#!/bin/sh\n: > '${marker.absolutePath}'\nwhile :; do :; done\n"); setExecutable(true) }
        val hung = Ffmpeg(bin.absolutePath, timeoutMs = 500)
        assertTrue(hung.available)
        val input = File(bin, "in.mp4").apply { writeBytes(ByteArray(8)) }
        val started = System.nanoTime()
        repeat(5) { if (!marker.exists()) assertNull(hung.probe(input)) }
        assertNull(hung.poster(input, 1000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 8_000)
        assertTrue(marker.exists())
        Thread.sleep(300)
        val leftover = ProcessHandle.allProcesses().filter { it.info().commandLine().orElse("").contains(bin.absolutePath) }.count()
        assertEquals(0L, leftover)
    }

    @Test
    fun ffmpegNeedsBothBinariesOnPath() {
        val bin = Files.createTempDirectory("l20b-bin").toFile()
        File(bin, "ffmpeg").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertFalse(Ffmpeg(bin.absolutePath).available)
        assertFalse(Ffmpeg(null).available)
    }

    @Test
    fun ffmpegInputIsAlwaysAnAbsolutePathBehindDashI() = runBlocking<Unit> {
        val bin = Files.createTempDirectory("l20b-bin").toFile()
        val log = File(bin, "args")
        for (name in listOf("ffmpeg", "ffprobe")) File(bin, name).apply { writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" >> '${log.absolutePath}'\nexit 1\n"); setExecutable(true) }
        val tool = Ffmpeg(bin.absolutePath)
        val input = File(bin, "-rf.mp4").apply { writeBytes(ByteArray(8)) }
        tool.probe(input)
        tool.probe(input, VIDEO_FORMAT)
        tool.poster(input, 1000)
        val lines = log.readLines()
        assertEquals(3, lines.count { it == "-i" })
        lines.forEachIndexed { i, line ->
            if (line == "-i") {
                assertTrue(lines[i + 1].startsWith("/"), lines[i + 1])
                assertEquals("file,pipe", lines[lines.subList(0, i).lastIndexOf("-protocol_whitelist") + 1])
            }
        }
        assertEquals(3, lines.count { it == "-protocol_whitelist" })
        assertEquals(1, lines.count { it == "-nostdin" })
        assertEquals(listOf("mp4", "mp4", "image2pipe"), lines.indices.filter { lines[it] == "-f" }.map { lines[it + 1] })
    }
}
