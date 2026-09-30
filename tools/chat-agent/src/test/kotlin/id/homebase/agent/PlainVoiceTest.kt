package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.XorIdUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class PlainVoiceTest {
    private val bot = OdinId("bot.example.com")
    private val alice = OdinId("alice.example.com")
    private val owner = OdinId("owner.example.com")
    private val dm = XorIdUtil.getNewXorId(bot.domainName, alice.domainName)
    private val self = ChatProtocol.ConversationWithYourselfId

    private fun botConfig(extra: String = "") = parseConfig("bot=true\n$extra", bot).also {
        it.allowlist.learnDerived(ConversationInfo(dm, alice.toString(), listOf(bot, alice)))
    }

    private fun H(config: AgentConfig, identity: String) = TestHarness(config, identity = identity)

    private fun m(conv: Uuid, author: OdinId, t: Long, text: String = "hi") =
        ChatMsg(Uuid.random(), conv, author, text, t, sender = author)

    @Test
    fun botRepliesAndFailuresArePlain() = runBlocking {
        val h = H(botConfig(), bot.toString())
        assertEquals("replied", h.handle(m(dm, alice, 1L)))
        assertEquals(listOf("pong"), h.replies)
        assertEquals("failed: boom", brainReply(BrainOutcome.Failed("boom"), botConfig().replyPrefix))
        assertEquals("pong", brainReply(BrainOutcome.Output("🤖 pong"), ""))
        assertEquals("hi", brainReply(BrainOutcome.Output("🤖 x.example.com's AI assistant: hi"), ""))
    }

    @Test
    fun botJobTextsArePlain() {
        assertEquals("job 1 failed: boom", jobText(1, BrainOutcome.Failed("boom"), ""))
        assertEquals("job 1 done (no output)", jobText(1, BrainOutcome.Output(" "), ""))
        assertEquals("all done", jobText(1, BrainOutcome.Output("🤖 all done"), ""))
        val runner = JobRunner(CoroutineScope(Dispatchers.Default), RunLimiter(null, 1, 1), prefix = "")
        assertEquals("no jobs", runner.status())
        assertEquals("no jobs", runner.cancel(null))
    }

    @Test
    fun meKeepsRobotPrefixInNoteToSelfAndDisclosureInGroups() = runBlocking {
        val cfg = parseConfig("", owner, "me")
        assertEquals(BOT_PREFIX, cfg.replyPrefix)
        val h = H(cfg, owner.toString())
        assertEquals("replied", h.handle(ChatMsg(Uuid.random(), self, owner, "@quagmire hi", 1L)))
        assertEquals(listOf("🤖 pong"), h.replies)
        val group = Uuid.random()
        val me = parseConfig("allowConversations=$group", owner, "me").allowlist
        assertEquals("🤖 ${owner.domainName}'s AI assistant: pong", me.disclosure(group, "🤖 pong", owner))
    }

    @Test
    fun meIgnoresBotFlagForVoiceAndReceipts() {
        val cfg = parseConfig("bot=true\nallowConversations=self\nreadReceipts=true", owner, "me")
        assertEquals(BOT_PREFIX, cfg.replyPrefix)
        assertFalse(cfg.sendsReceipts)
    }

    @Test
    fun robotPrefixedIncomingAndOwnMessagesNeverTrigger() = runBlocking {
        val h = H(botConfig(), bot.toString())
        assertEquals("skip: no trigger", h.handle(m(dm, alice, 1L, "🤖 pong")))
        assertEquals("skip: own message", h.handle(m(dm, bot, 2L, "pong")))
        assertEquals(0, h.brainRuns)
    }

    @Test
    fun directSenderStopsGettingRepliesAtHourlyCap() = runBlocking {
        val h = H(botConfig("maxRunsPerHour=2"), bot.toString())
        assertEquals("replied", h.handle(m(dm, alice, 1L)))
        assertEquals("replied", h.handle(m(dm, alice, 2L)))
        assertEquals("skip: rate limited", h.handle(m(dm, alice, 3L)))
        assertEquals(2, h.replies.size)
    }

    private class Sink {
        val sent = mutableListOf<List<Uuid>>()
        val logs = mutableListOf<String>()
        var fail = 0
    }

    private fun receipts(cfg: AgentConfig, sink: Sink, store: ProcessedStore = ProcessedStore(null)) = ReadReceipts(
        enabled = cfg.sendsReceipts,
        self = bot,
        allowlist = cfg.allowlist,
        store = store,
        send = { ids -> if (sink.fail > 0) { sink.fail--; error("boom") }; sink.sent += ids },
        log = { sink.logs += it },
    )

    private fun file(msg: ChatMsg, fileId: Uuid = Uuid.random()) =
        ChatMsg(msg.id, msg.conversationId, msg.author, msg.text, msg.userDate, sender = msg.sender, fileId = fileId)

    @Test
    fun receiptsSentOnceForPeerMessagesInAllowedConversationsOnly() = runBlocking {
        val sink = Sink()
        val r = receipts(botConfig(), sink)
        val peer = file(m(dm, alice, 1L))
        val peer2 = file(m(dm, alice, 2L))
        val own = file(m(dm, bot, 3L))
        val elsewhere = file(m(Uuid.random(), alice, 4L))
        val noFile = m(dm, alice, 5L)
        r.mark(listOf(peer, peer2, own, elsewhere, noFile))
        assertEquals(listOf(listOf(peer.fileId!!, peer2.fileId!!)), sink.sent)
        r.mark(listOf(peer, peer2, own))
        assertEquals(1, sink.sent.size)
    }

    @Test
    fun receiptsNeverForMeProfile() = runBlocking {
        val cfg = parseConfig("readReceipts=true", owner, "me")
        val sink = Sink()
        receipts(cfg, sink).mark(listOf(file(ChatMsg(Uuid.random(), self, alice, "hi", 1L, sender = alice))))
        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun readReceiptsConfigFlag() {
        assertTrue(botConfig().sendsReceipts)
        assertFalse(botConfig("readReceipts=false").sendsReceipts)
        assertFalse(parseConfig("", owner, "me").sendsReceipts)
    }

    @Test
    fun receiptFailureIsLoggedRetriedAndNeverThrows() = runBlocking {
        val sink = Sink().apply { fail = 1 }
        val r = receipts(botConfig(), sink)
        val peer = file(m(dm, alice, 1L))
        r.mark(listOf(peer))
        assertTrue(sink.sent.isEmpty())
        assertTrue(sink.logs.any { it.startsWith("read receipt error") })
        r.mark(emptyList())
        assertEquals(listOf(listOf(peer.fileId!!)), sink.sent)
        r.mark(emptyList())
        assertEquals(1, sink.sent.size)
    }

    @Test
    fun receiptStoreIsBounded() = runBlocking {
        val store = ProcessedStore(null, cap = 3)
        receipts(botConfig(), Sink(), store).mark(List(10) { file(m(dm, alice, it.toLong())) })
        assertEquals(3, store.size)
    }
}
