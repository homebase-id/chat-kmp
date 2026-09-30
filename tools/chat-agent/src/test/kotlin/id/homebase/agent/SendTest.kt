package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.SendContents
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SendTest {
    private val allowlist = Allowlist.default(OdinId("owner.example.com"))

    private val owner = OdinId("owner.example.com")
    private val bob = OdinId("bob.example.com")
    private val bot = OdinId("bot.example.com")
    private val group = Uuid.random()
    private val groupInfo = ConversationInfo(group, "Team", listOf(owner, bob, bot))

    private fun botAllowlist() =
        Allowlist(setOf(ChatProtocol.ConversationWithYourselfId), setOf(owner), Kind.BOT, memberMode = true)
            .also { it.learn(listOf(groupInfo)) }

    @Test
    fun selfConversationMetadataIsEncryptedLocalMarkdown() = runBlocking {
        val kh = KeyHeader.newRandom16()
        val m = buildMessageMetadata(ChatProtocol.ConversationWithYourselfId, Uuid.random(), "hi", 1L, kh, distribute = false)
        val plain = buildMessageContent("hi")
        assertNotEquals(plain, m.appData.content)
        assertEquals(plain, kh.decrypt(Base64.decode(m.appData.content!!)).decodeToString())
        assertTrue(m.isEncrypted)
        assertFalse(m.allowDistribution)
        assertEquals(ChatProtocol.MessageFileType, m.appData.fileType)
        assertEquals(ChatProtocol.ConversationWithYourselfId, m.appData.groupId)
        assertTrue(plain.contains("\"version\":1"))
    }

    @Test
    fun groupMetadataIsEncryptedDistributedAndGroupedByConversation() = runBlocking {
        val kh = KeyHeader.newRandom16()
        val id = Uuid.random()
        val m = buildMessageMetadata(group, id, "hi", 7L, kh, distribute = true)
        assertTrue(m.isEncrypted)
        assertTrue(m.allowDistribution)
        assertEquals(group, m.appData.groupId)
        assertEquals(id, m.appData.uniqueId)
        assertEquals(7L, m.appData.userDate)
        assertEquals(0, m.appData.dataType)
        assertEquals(buildMessageContent("hi"), kh.decrypt(Base64.decode(m.appData.content!!)).decodeToString())
    }

    @Test
    fun recipientsAreMembersMinusSelf() {
        assertEquals(listOf(owner, bob), conversationRecipients(groupInfo, bot))
        assertEquals(emptyList(), conversationRecipients(noteToSelf(owner), owner))
    }

    @Test
    fun groupTransitCarriesRecipientsAndNotification() {
        val t = conversationTransitOptions(group, Uuid.random(), listOf(owner, bob), "Team")
        assertEquals(listOf(owner, bob), t.recipients)
        assertEquals(SendContents.All, t.sendContents)
        assertEquals(true, t.useAppNotification)
        assertTrue(t.appNotificationOptions!!.unEncryptedMessage!!.endsWith("in Team"))
    }

    @Test
    fun delegateProfileNeverSendsToGroup() = runBlocking<Unit> {
        val me = Allowlist(setOf(ChatProtocol.ConversationWithYourselfId, group), setOf(owner), memberMode = true)
        me.learn(listOf(groupInfo))
        assertTrue(me.allowsConversation(group))
        assertFalse(me.allowsSend(group))
        assertFailsWith<IllegalArgumentException> { me.requireSend(group) }
        assertTrue(me.allowsSend(ChatProtocol.ConversationWithYourselfId))
        assertFalse(parseConfig("allowConversations=member", owner).allowlist.groupSend)
        assertTrue(parseConfig("bot=true", owner).allowlist.groupSend)
    }

    @Test
    fun meProfileCannotEnableGroupSendViaConfig() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { parseConfig("bot=true\nallowConversations=member", owner, "me") }
        val me = parseConfig("bot=true\nallowConversations=$group", owner, "me").allowlist
        assertFalse(me.groupSend)
        val ownerBot = parseConfig("bot=true\nowner=owner.example.com", owner, "bot").allowlist
        assertFalse(ownerBot.groupSend)
        assertTrue(parseConfig("bot=true\nowner=owner.example.com", bot, "bot").allowlist.groupSend)
    }

    @Test
    fun botRefusesUnlistedConversation() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { botAllowlist().requireSend(Uuid.random()) }
        botAllowlist().requireSend(group)
    }

    @Test
    fun otherConversationRefused() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { allowlist.requireSend(Uuid.random()) }
    }

    @Test
    fun oversizedTextRejectedNotTruncated() {
        val e = assertFailsWith<IllegalArgumentException> { buildMessageContent("x".repeat(8000)) }
        assertTrue(e.message!!.contains("too large"))
    }

    @Test
    fun blankTextRejected() {
        assertFailsWith<IllegalArgumentException> { buildMessageContent("  ") }
    }

    @Test
    fun selfTransitMatchesApp() {
        val t = conversationTransitOptions(ChatProtocol.ConversationWithYourselfId, Uuid.random(), emptyList(), null)
        assertEquals(emptyList(), t.recipients)
        assertEquals(SendContents.All, t.sendContents)
        assertEquals(false, t.useAppNotification)
    }
}
