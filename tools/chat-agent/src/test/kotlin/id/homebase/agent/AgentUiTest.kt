package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.agentui.AgentUiActionDescriptor
import id.homebase.chat.agentui.AgentUiDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentUiTest {
    private val bot = OdinId("bot.example.com")
    private val op = OdinId("op.example.com")
    private val rando = OdinId("rando.example.com")
    private val grp = Uuid.random()
    private var n = 0L

    private val tree = """{"root":"card","nodes":[
        {"id":"card","type":"card","children":["head","tone","caption","approve"]},
        {"id":"head","type":"text","style":"title","markdown":"Draft post"},
        {"id":"tone","type":"select","label":"Tone","options":[{"id":"casual","label":"Casual"},{"id":"formal","label":"Formal"}]},
        {"id":"caption","type":"textInput","label":"Caption","value":""},
        {"id":"approve","type":"button","label":"Approve","action":{"id":"approve","include":["tone","caption"]}}
    ]}"""

    private inner class Fake(val msgs: List<ChatMsg> = emptyList()) : AgentBackend {
        override val allowlist = Allowlist(setOf(grp), null, Kind.BOT).also { it.learn(listOf(ConversationInfo(grp, "room", listOf(bot, op)))) }
        override val self: OdinId get() = bot
        val sent = CopyOnWriteArrayList<Pair<AgentUiDescriptor, ByteArray>>()
        val edits = CopyOnWriteArrayList<Pair<ChatMsg, AgentUiDescriptor>>()
        override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) = msgs
        override suspend fun send(conversationId: Uuid, text: String, replyTo: id.homebase.chat.services.ReplyPreview?) = Uuid.random()
        override suspend fun sendAgentUi(conversationId: Uuid, descriptor: AgentUiDescriptor, tree: ByteArray, images: List<OutFile>): Uuid { sent += descriptor to tree; return Uuid.random() }
        override suspend fun editAgentUi(conversationId: Uuid, message: ChatMsg, descriptor: AgentUiDescriptor, tree: ByteArray) { edits += message to descriptor }
    }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(mapOf("conversationId" to JsonPrimitive(grp.toString())) + pairs.associate { it.first to JsonPrimitive(it.second) })

    private fun card(rev: Int, author: OdinId = bot, sender: OdinId? = null, title: String? = "Draft post") = ChatMsg(
        Uuid.random(), grp, author, "", ++n, dataType = ChatProtocol.ChatAgentUiMessageDataType, sender = sender,
        rawContent = MessageContentParser.serialize(MessageContent.AgentUi(AgentUiDescriptor(fallback = "Draft post for X", title = title, rev = rev))),
    )

    private fun action(target: Uuid, rev: Int, who: OdinId = op, values: Map<String, String> = mapOf("tone" to "casual", "caption" to "Spring is \"here\"")) = ChatMsg(
        Uuid.random(), grp, who, "", ++n, dataType = ChatProtocol.ChatAgentUiActionMessageDataType, sender = who,
        rawContent = MessageContentParser.serialize(MessageContent.AgentUiAction(AgentUiActionDescriptor(target = target, rev = rev, actionId = "approve", label = "Approve", values = values))),
    )

    @Test
    fun sendUiRefusesAnInvalidTreeWithTheDecodeReason() = runBlocking {
        val fake = Fake()
        val dup = toolSendUi(fake, args("tree" to """{"root":"a","nodes":[{"id":"a","type":"text"},{"id":"a","type":"text"}]}""", "fallback" to "x"))
        assertTrue(dup.isError)
        assertEquals("refused: tree is invalid: duplicate id", dup.text)
        val noRoot = toolSendUi(fake, args("tree" to """{"root":"zz","nodes":[]}""", "fallback" to "x"))
        assertEquals("refused: tree is invalid: root missing", noRoot.text)
        assertTrue(toolSendUi(fake, args("tree" to tree)).isError)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun sendUiSendsAValidTreeAtRevZeroAndAcceptsAnObjectTree() = runBlocking {
        val fake = Fake()
        val asObject = JsonObject(args("fallback" to "Draft post for X", "title" to "Draft post") + ("tree" to kotlinx.serialization.json.Json.parseToJsonElement(tree)))
        val reply = toolSendUi(fake, asObject)
        assertFalse(reply.isError, reply.text)
        val (descriptor, bytes) = fake.sent.single()
        assertEquals(AgentUiDescriptor(fallback = "Draft post for X", title = "Draft post", rev = 0), descriptor)
        assertTrue(bytes.decodeToString().contains("\"approve\""))
    }

    @Test
    fun editUiBumpsRevAndKeepsFallbackAndTitle() = runBlocking {
        val c = card(rev = 2)
        val fake = Fake(listOf(c))
        val reply = toolEditUi(fake, args("messageId" to c.id.toString(), "tree" to tree))
        assertEquals("edited agent card ${c.id.toString().take(8)}, now rev 3", reply.text)
        assertEquals(AgentUiDescriptor(fallback = "Draft post for X", title = "Draft post", rev = 3), fake.edits.single().second)
    }

    @Test
    fun editUiRefusesOtherCardsAndPlainMessages() = runBlocking {
        val theirs = card(rev = 0, author = rando, sender = rando)
        val plain = ChatMsg(Uuid.random(), grp, bot, "hi", ++n)
        val fake = Fake(listOf(theirs, plain))
        assertEquals("refused: you can only edit your own cards", toolEditUi(fake, args("messageId" to theirs.id.toString(), "tree" to tree)).text)
        assertTrue(toolEditUi(fake, args("messageId" to plain.id.toString(), "tree" to tree)).text.contains("is not an agent card"))
        assertTrue(fake.edits.isEmpty())
    }

    private fun cfg(): AgentConfig {
        val allow = Allowlist(setOf(grp), null, Kind.BOT)
        allow.learn(listOf(ConversationInfo(grp, "room", listOf(bot, op, rando))))
        return AgentConfig(allowlist = allow, operators = setOf(op), operatorBrain = "full")
    }

    @Test
    fun anOperatorActionIsAnAddressedOperatorTriggerNamingTheCard() = runBlocking<Unit> {
        val c = card(rev = 1)
        val h = TestHarness(cfg(), identity = bot.toString(), history = listOf(c))
        val a = action(c.id, rev = 1)
        assertEquals("replied", h.handle(a))
        assertEquals(listOf(Tier.OPERATOR), h.tiers)
        val p = h.prompts.single()
        assertTrue("card action: op.example.com pressed action approve on your card ${c.id} \"Draft post\" (rev 1)" in p, p)
        assertTrue("<untrusted_chat_" in p)
        assertTrue("""{"label":"Approve","values":{"tone":"casual","caption":"Spring is \"here\""}}""" in p, p)
    }

    @Test
    fun aNonOperatorActionGetsTheLockedTier() = runBlocking<Unit> {
        val c = card(rev = 0)
        val h = TestHarness(cfg(), identity = bot.toString(), history = listOf(c))
        assertEquals("replied", h.handle(action(c.id, rev = 0, who = rando)))
        assertEquals(listOf(Tier.LOCKED), h.tiers)
    }

    @Test
    fun staleOwnForeignAndRepeatedActionsAreRejected() = runBlocking<Unit> {
        val c = card(rev = 2)
        val foreign = card(rev = 0, author = rando, sender = rando)
        val first = action(c.id, rev = 2)
        val h = TestHarness(cfg(), identity = bot.toString(), history = listOf(c, foreign, first))
        assertEquals("skip: stale card action (rev 1, card is at rev 2)", h.handle(action(c.id, rev = 1)))
        assertEquals("skip: own card action", h.handle(action(c.id, rev = 2, who = bot)))
        assertEquals("skip: card action target ${foreign.id} is not this bot's card", h.handle(action(foreign.id, rev = 0)))
        assertEquals("skip: duplicate card action", h.handle(action(c.id, rev = 2)))
        assertEquals("replied", h.handle(action(c.id, rev = 2, values = mapOf("tone" to "formal"))))
        assertEquals(1, h.brainRuns)
    }
}
