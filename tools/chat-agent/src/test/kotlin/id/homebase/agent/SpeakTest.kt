package id.homebase.agent

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.XorIdUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SpeakTest {
    private val dir: File = Files.createTempDirectory("speak-test").toFile()
    private val noFfmpeg = Ffmpeg("/nonexistent-dir-for-speak")
    private val logs = CopyOnWriteArrayList<String>()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun script(body: String): String {
        val f = File(dir, "tts-${Uuid.random()}.sh")
        f.writeText("#!/bin/sh\n$body\n")
        return "sh ${f.absolutePath}"
    }

    private fun speaker(command: String, timeoutMs: Long = 10_000, env: Map<String, String> = mapOf("TTS_VOICE" to "Marcus"), maxChars: Int = 400) =
        shellSpeaker(command, env, timeoutMs, { logs += it }, maxChars, noFfmpeg)

    @Test
    fun speakerPassesSanitizedTextAndEnvAndReturnsTheAudio() = runBlocking {
        val cmd = script("""[ "${'$'}(cat "${'$'}1")" = "hello there link" ] || exit 3
[ "${'$'}TTS_VOICE" = "Marcus" ] || exit 4
printf FAKEAUDIO > "${'$'}2"""")
        val voice = assertNotNull(speaker(cmd)("hello **there** https://example.com/x\n```kotlin\nval x = 1\n```\nATTACH: /tmp/a.png"))
        assertEquals("FAKEAUDIO", voice.bytes.decodeToString())
        assertEquals("audio/mp4", voice.contentType)
        assertEquals("reply.m4a", voice.name)
        assertEquals(0, voice.seconds)
    }

    @Test
    fun envIsNotInterpolatedIntoTheShellString() = runBlocking {
        val marker = File(dir, "pwned")
        val cmd = script("""printf '%s' "${'$'}TTS_VOICE" > "${'$'}2"""")
        val voice = assertNotNull(speaker(cmd, env = mapOf("TTS_VOICE" to "x; touch ${marker.absolutePath}"))("hi"))
        assertEquals("x; touch ${marker.absolutePath}", voice.bytes.decodeToString())
        assertFalse(marker.exists())
    }

    @Test
    fun timeoutKillsTheCommand() = runBlocking {
        val start = System.nanoTime()
        assertNull(speaker("sleep 5 #", timeoutMs = 200)("hi"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4_000)
        assertTrue(logs.any { "timed out" in it })
    }

    @Test
    fun nonZeroExitLogsTheStderrTail() = runBlocking {
        assertNull(speaker(script("echo boom >&2; exit 7"))("hi"))
        assertTrue(logs.any { "exit 7" in it && "boom" in it })
    }

    @Test
    fun emptyOutputFails() = runBlocking {
        assertNull(speaker(script(": > \"\$2\""))("hi"))
        assertTrue(logs.any { "empty output" in it })
        logs.clear()
        assertNull(speaker(script("true"))("hi"))
        assertTrue(logs.any { "empty output" in it })
    }

    @Test
    fun oversizedOutputFails() = runBlocking {
        assertNull(speaker(script("dd if=/dev/zero of=\"\$2\" bs=1000000 count=11 2>/dev/null"))("hi"))
        assertTrue(logs.any { "cap" in it })
    }

    @Test
    fun nothingSpeakableNeverRunsTheCommand() = runBlocking {
        val ran = File(dir, "ran")
        assertNull(speaker(script("touch ${ran.absolutePath}; printf x > \"\$2\""))("```\ncode only\n```"))
        assertFalse(ran.exists())
    }

    @Test
    fun speakableTextStripsCodeLinksAndMarkdown() {
        assertEquals("see link and the docs", speakableText("see https://a.b/c and [the docs](https://x.y)", 400))
        assertEquals("hi", speakableText("hi\n```\nsecret()\n```", 400))
        assertEquals("hi", speakableText("hi\n```\nunclosed", 400))
        assertEquals("Title one two", speakableText("# Title\n- one\n> two", 400))
        assertEquals("done", speakableText("done\nATTACH: /files/a.png", 400))
        assertEquals("a b", speakableText("a   \n\n  b", 400))
    }

    @Test
    fun speakableTextCutsByCodePointsNotUtf16() {
        val cut = speakableText("😀".repeat(10), 5)
        assertEquals("😀".repeat(5), cut)
        assertFalse(cut.any { Character.isSurrogate(it) && !Character.isHighSurrogate(it) && cut.indexOf(it) % 2 == 0 })
        assertEquals(5, cut.codePointCount(0, cut.length))
    }

    @Test
    fun voiceLimiterCountsARollingDayAndPersists() {
        var now = 0L
        val file = File(dir, "voice.txt")
        val limiter = VoiceLimiter(file, 2) { now }
        assertTrue(limiter.allows())
        limiter.record(); now += 1_000; limiter.record()
        assertFalse(limiter.allows())
        assertFalse(VoiceLimiter(file, 2) { now }.allows())
        now += 86_400_000L
        assertTrue(VoiceLimiter(file, 2) { now }.allows())
    }

    private val owner = OdinId("owner.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId

    private class VoiceBackend(override val allowlist: Allowlist, val voice: VoiceOut?, override val sendPrefix: String = "") : AgentBackend {
        val voices = CopyOnWriteArrayList<Pair<OutVoice, String>>()
        val replyTargets = CopyOnWriteArrayList<id.homebase.chat.services.ReplyPreview?>()
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = emptyList<ChatMsg>()
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?) = Uuid.random()
        override suspend fun speak(text: String): OutVoice = voice?.speak(text) ?: super.speak(text)
        override suspend fun sendVoice(conversationId: Uuid, voice: OutVoice, caption: String, replyTo: id.homebase.chat.services.ReplyPreview?): Uuid {
            voices += voice to caption
            replyTargets += replyTo
            return Uuid.random()
        }
    }

    private fun voiceOut(perDay: Int = 5, result: OutVoice? = OutVoice("reply.m4a", byteArrayOf(1, 2), "audio/mp4", 3), spoken: MutableList<String>? = null) =
        VoiceOut({ text -> spoken?.add(text); result }, VoiceLimiter(null, perDay))

    private fun backend(voice: VoiceOut?, prefix: String = "") = VoiceBackend(Allowlist.default(owner).copy(self, false), voice, prefix)

    private val args = buildJsonObject { put("conversationId", self.toString()); put("text", "hello world") }

    @Test
    fun sendVoiceReplySpeaksSendsAndCarriesTheTextAsCaption() = runBlocking {
        val spoken = mutableListOf<String>()
        val b = backend(voiceOut(spoken = spoken), prefix = BOT_PREFIX)
        val reply = toolSendVoiceReply(b, args)
        assertFalse(reply.isError, reply.text)
        assertEquals(listOf("hello world"), spoken)
        assertEquals("$BOT_PREFIX hello world", b.voices.single().second)
        assertEquals("audio/mp4", b.voices.single().first.contentType)
    }

    @Test
    fun sendVoiceReplyDailyLimitIsEnforcedAndSharedWithTheSpeaker() = runBlocking {
        val out = voiceOut(perDay = 2)
        val b = backend(out)
        assertFalse(toolSendVoiceReply(b, args).isError)
        out.speak("mirror reply")
        val third = toolSendVoiceReply(b, args)
        assertTrue(third.isError)
        assertTrue("limit" in third.text)
        assertEquals(1, b.voices.size)
    }

    @Test
    fun sendVoiceReplyErrorsWhenSynthesisFailsOrVoiceIsNotConfigured() = runBlocking {
        val failing = backend(voiceOut(result = null))
        val r = toolSendVoiceReply(failing, args)
        assertTrue(r.isError)
        assertTrue(failing.voices.isEmpty())
        val unconfigured = toolSendVoiceReply(backend(null), args)
        assertTrue(unconfigured.isError)
        assertTrue(toolSendVoiceReply(backend(voiceOut()), buildJsonObject { put("conversationId", self.toString()); put("text", " ") }).isError)
    }

    @Test
    fun toolIsListedOnlyWhenTtsIsConfigured() {
        for (tier in Tier.entries) {
            assertFalse(VOICE_REPLY_TOOL in chatToolNames(tier))
            assertTrue(VOICE_REPLY_TOOL in chatToolNames(tier, voice = true))
            assertTrue(scopedTools(chatToolNames(tier), null).none { it.name == VOICE_REPLY_TOOL })
            assertTrue(scopedTools(chatToolNames(tier, voice = true), null).any { it.name == VOICE_REPLY_TOOL })
        }
        assertFalse("mcp__chat__$VOICE_REPLY_TOOL" in lockedBrainCommand(setOf("chat")))
        assertTrue("mcp__chat__$VOICE_REPLY_TOOL" in lockedBrainCommand(setOf("chat"), voice = true))
        assertTrue("mcp__chat__$VOICE_REPLY_TOOL" in parseConfig("lockedTools=chat\ntts=/x/speak.sh", owner).brain.command)
        assertFalse("mcp__chat__$VOICE_REPLY_TOOL" in parseConfig("lockedTools=chat", owner).brain.command)
    }

    private val bot = OdinId("bot.example.com")
    private val alice = OdinId("alice.example.com")
    private val dm = XorIdUtil.getNewXorId(bot.domainName, alice.domainName)

    private fun botConfig(extra: String) = parseConfig("bot=true\n$extra", bot).also {
        it.allowlist.learnDerived(ConversationInfo(dm, alice.toString(), listOf(bot, alice)))
    }

    private fun note(text: String, at: Long) = ChatMsg(
        Uuid.random(), dm, alice, text, at, sender = alice,
        payloads = listOf(PayloadDescriptor(key = "pfl0000001", contentType = "audio/aac", bytesWritten = 100, descriptorContent = """{"name":"v","lengthSeconds":3}""")),
    )

    private fun text(text: String, at: Long) = ChatMsg(Uuid.random(), dm, alice, text, at, sender = alice)

    @Test
    fun mirrorAnswersAVoiceNoteWithAVoiceNote() = runBlocking {
        val spoken = mutableListOf<String>()
        val h = TestHarness(botConfig("tts=/x/speak.sh\nvoiceReplies=mirror"), identity = bot.toString(), voice = voiceOut(spoken = spoken))
        assertEquals("replied", h.handle(note("", 1L)))
        assertEquals(listOf("pong"), spoken)
        assertEquals("pong", h.voices.single().third)
        assertTrue(h.sends.isEmpty())
    }

    @Test
    fun mirrorLeavesTextTriggersAndOffModeAlone() = runBlocking {
        val h = TestHarness(botConfig("tts=/x/speak.sh\nvoiceReplies=mirror"), identity = bot.toString(), voice = voiceOut())
        assertEquals("replied", h.handle(text("hi", 1L)))
        assertTrue(h.voices.isEmpty())
        assertEquals(listOf("pong"), h.replies)
        val off = TestHarness(botConfig("tts=/x/speak.sh"), identity = bot.toString(), voice = voiceOut())
        assertEquals("replied", off.handle(note("", 2L)))
        assertTrue(off.voices.isEmpty())
        assertEquals(listOf("pong"), off.replies)
    }

    @Test
    fun mirrorFallsBackToTextOnTtsFailureOrLimit() = runBlocking {
        val failing = TestHarness(botConfig("tts=/x/speak.sh\nvoiceReplies=mirror"), identity = bot.toString(), voice = voiceOut(result = null))
        assertEquals("replied", failing.handle(note("", 1L)))
        assertEquals(listOf("pong"), failing.replies)
        assertTrue(failing.voices.isEmpty())
        assertTrue(failing.logs.any { "fell back to text" in it })
        val limited = TestHarness(botConfig("tts=/x/speak.sh\nvoiceReplies=mirror"), identity = bot.toString(), voice = voiceOut(perDay = 0))
        assertEquals("replied", limited.handle(note("", 2L)))
        assertEquals(listOf("pong"), limited.replies)
        assertTrue(limited.logs.any { "limit" in it })
    }

    @Test
    fun mirrorFallsBackWhenSendingTheVoiceNoteFails() = runBlocking {
        val h = TestHarness(botConfig("tts=/x/speak.sh\nvoiceReplies=mirror"), identity = bot.toString(), voice = VoiceOut({ error("boom") }, VoiceLimiter(null, 5)))
        assertEquals("replied", h.handle(note("", 1L)))
        assertEquals(listOf("pong"), h.replies)
    }

    @Test
    fun promptTellsTheBrainRepliesMayBeSpoken() = runBlocking {
        val h = TestHarness(botConfig("tts=/x/speak.sh\nvoiceReplies=mirror"), identity = bot.toString(), voice = voiceOut())
        h.handle(note("", 1L))
        assertTrue(VOICE_PROMPT_LINE in h.prompts.single())
        val off = TestHarness(botConfig("tts=/x/speak.sh"), identity = bot.toString(), voice = voiceOut())
        off.handle(note("", 2L))
        assertFalse(VOICE_PROMPT_LINE in off.prompts.single())
    }
}
