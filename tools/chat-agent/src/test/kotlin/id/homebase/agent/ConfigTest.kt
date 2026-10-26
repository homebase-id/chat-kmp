package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ConfigTest {
    private val owner = OdinId("owner.example.com")
    private val self = ChatProtocol.ConversationWithYourselfId

    private val me = OdinId("bot.example.com")
    private val alice = OdinId("alice.example.com")
    private val bob = OdinId("bob.example.com")

    @Test
    fun lockedHistoryDefaultsAndClamps() {
        assertEquals(10, parseConfig("", owner).lockedHistory)
        assertTrue(parseConfig("", owner).warnings.none { "lockedHistory" in it })
        assertEquals(25, parseConfig("lockedHistory=25", owner).lockedHistory)
        val high = parseConfig("lockedHistory=99", owner)
        assertEquals(30, high.lockedHistory)
        assertTrue(high.warnings.any { "lockedHistory=99" in it })
        assertEquals(1, parseConfig("lockedHistory=0", owner).lockedHistory)
        assertTrue(parseConfig("lockedHistory=abc", owner).warnings.any { "lockedHistory" in it })
    }

    @Test
    fun ttsDefaultsAndPersonaVoice() {
        val off = parseConfig("", owner)
        assertNull(off.tts)
        assertFalse(off.voiceReplies)
        assertTrue(off.warnings.none { "tts" in it.lowercase() })
        val plain = parseConfig("tts=/x/speak.sh", owner)
        assertEquals("Bruno", plain.ttsVoice)
        assertEquals("expressive", plain.ttsPreset)
        assertEquals(240_000L, plain.ttsTimeoutMs)
        assertEquals(400, plain.ttsMaxChars)
        assertEquals(20, plain.maxVoicePerDay)
        assertFalse(plain.voiceMirror)
        assertEquals("Marcus", parseConfig("tts=/x/speak.sh\npersona=Loud neighbour", owner).ttsVoice)
        assertEquals("Dante", parseConfig("tts=/x/speak.sh\npersona=x\nttsVoice=Dante", owner).ttsVoice)
    }

    @Test
    fun ttsKnobsParseAndReachTheCommandAsEnv() {
        val cfg = parseConfig("tts=/x/speak.sh\nttsVoice=Dante\nttsPreset=Stable\nttsTimeout=90s\nttsMaxChars=120\nvoiceReplies=mirror\nmaxVoicePerDay=3", owner)
        assertEquals(90_000L, cfg.ttsTimeoutMs)
        assertEquals(120, cfg.ttsMaxChars)
        assertEquals(3, cfg.maxVoicePerDay)
        assertTrue(cfg.voiceMirror)
        assertTrue(cfg.warnings.none { "tts" in it.lowercase() || "voice" in it.lowercase() })
        assertEquals(mapOf("TTS_VOICE" to "Dante", "TTS_PRESET" to "stable"), ttsEnvironment(cfg))
        assertFailsWith<IllegalArgumentException> { parseConfig("ttsTimeout=soon", owner) }
    }

    @Test
    fun ttsReferenceReplacesTheVoiceAndWarnsWhenBothAreSet() {
        val cfg = parseConfig("tts=/x/speak.sh\nttsReference=/refs/a.wav\nttsReferenceText=hello there\nttsVoice=Dante", owner)
        assertEquals(mapOf("TTS_REFERENCE" to "/refs/a.wav", "TTS_REFERENCE_TEXT" to "hello there", "TTS_PRESET" to "expressive"), ttsEnvironment(cfg))
        assertTrue(cfg.warnings.any { "ttsReference" in it && "ttsVoice" in it })
        assertTrue(parseConfig("tts=/x/speak.sh\nttsReference=/refs/a.wav", owner).warnings.none { "ttsVoice" in it })
        assertTrue(parseConfig("tts=/x/speak.sh\nttsReferenceText=hi", owner).warnings.any { "ttsReferenceText" in it })
    }

    @Test
    fun ttsBadValuesWarnAndFallBack() {
        val cfg = parseConfig("tts=/x/speak.sh\nttsPreset=loud\nvoiceReplies=always\nttsMaxChars=9000", owner)
        assertEquals("expressive", cfg.ttsPreset)
        assertFalse(cfg.voiceReplies)
        assertEquals(600, cfg.ttsMaxChars)
        assertTrue(cfg.warnings.any { "ttsPreset" in it })
        assertTrue(cfg.warnings.any { "voiceReplies" in it })
        assertTrue(cfg.warnings.any { "ttsMaxChars" in it })
        assertTrue(parseConfig("voiceReplies=mirror", owner).warnings.any { "needs tts=" in it })
        assertFalse(parseConfig("voiceReplies=mirror", owner).voiceMirror)
        assertTrue(parseConfig("ttsVoice=Dante", owner).warnings.any { "without tts=" in it })
    }

    @Test
    fun botDefaultsToMemberMode() {
        val cfg = parseConfig("bot=true", me)
        assertTrue(cfg.allowlist.memberMode)
        assertTrue(cfg.allowlist.authorsAnyMember)
        val owned = parseConfig("bot=true\nowner=alice.example.com", me)
        assertFalse(owned.allowlist.authorsAnyMember)
        assertEquals(setOf(alice), owned.allowlist.authors)
    }

    @Test
    fun meDefaultStaysNoteToSelfOwnerOnly() {
        val cfg = parseConfig("", me)
        assertFalse(cfg.allowlist.memberMode)
        assertFalse(cfg.allowlist.authorsAnyMember)
        assertEquals(setOf(ChatProtocol.ConversationWithYourselfId), cfg.allowlist.allowedConversationIds())
    }

    @Test
    fun memberModeAllowsOnlyDiscovered() {
        val group = Uuid.random()
        val allow = parseConfig("bot=true", me).allowlist
        assertFalse(allow.allowsConversation(group))
        assertFailsWith<IllegalArgumentException> { allow.requireConversation(group) }
        allow.learn(listOf(ConversationInfo(group, "Team", listOf(alice, me))))
        assertTrue(allow.allowsConversation(group))
        assertFalse(allow.allowsConversation(Uuid.random()))
        assertTrue(allow.allowsAuthor(alice, group))
        assertFalse(allow.allowsAuthor(bob, group))
        assertFalse(allow.allowsAuthor(null, group))
        assertEquals("Team", allow.title(group))
    }

    @Test
    fun configDefaultsAndOverrides() {
        val d = parseConfig("", owner)
        assertEquals("quagmire", d.nickname)
        assertEquals(DEFAULT_BRAIN, d.brain.command)
        assertFalse(d.bot)
        assertEquals(setOf(self), d.allowlist.conversationIds)
        assertEquals(setOf(owner), d.allowlist.authors)
        val other = Uuid.random()
        val c = parseConfig(
            "# c\nnickname=zed\nbrain=echo hi | cat\nbot=true\nallowConversations=self, $other\nallowAuthors=a.example.com",
            owner,
        )
        assertEquals("zed", c.nickname)
        assertEquals("echo hi | cat", c.brain.command)
        assertTrue(c.bot)
        assertEquals(setOf(self, other), c.allowlist.conversationIds)
        assertEquals(setOf(OdinId("a.example.com")), c.allowlist.authors)
    }
}
