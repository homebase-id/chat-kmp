package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.services.ChatMessageStreamTestFixture
import id.homebase.chat.services.ChatMessageStreamTestFixture.Companion.conversationId
import id.homebase.chat.services.ChatMessageStreamTestFixture.Companion.testDomain
import id.homebase.chat.services.ChatMessageStreamTestFixture.Companion.testIdentityId
import id.homebase.chat.services.uniqueId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnreadMentionIndexTest {
    private val peer = "peer.test"

    @Test
    fun refresh_countsUnreadMentionsOfMeFromColdDb() = runTest {
        val f = ChatMessageStreamTestFixture.build(this)
        try {
            f.commit(f.buildMessageFile(1000, text = "hi @$testDomain", author = peer))
            f.commit(f.buildMessageFile(2000, text = "hi @other.test", author = peer))
            f.commit(f.buildMessageFile(3000, text = "again @${testDomain.uppercase()} please", author = peer))
            f.commit(f.buildMessageFile(4000, text = "note to @$testDomain", author = testDomain))

            val counts = UnreadMentionIndex(f.dbm).refresh(testIdentityId, testDomain)

            assertEquals(mapOf(conversationId to 2), counts)
        } finally {
            f.close()
        }
    }

    @Test
    fun refresh_dropsMentionsOnceRead_andScansEachMessageOnce() = runTest {
        val f = ChatMessageStreamTestFixture.build(this)
        try {
            val first = f.buildMessageFile(1000, text = "@$testDomain one", author = peer)
            f.commit(first)
            val index = UnreadMentionIndex(f.dbm)
            assertEquals(1, index.refresh(testIdentityId, testDomain)[conversationId])
            assertTrue(f.dbm.chatMentionIndex.selectUnscannedUnread(testIdentityId, testDomain).isEmpty())

            f.dbm.chatReadCount.upsertLastReadTime(conversationId, UnixTimeUtc(1500))
            assertEquals(null, index.refresh(testIdentityId, testDomain)[conversationId])
            assertEquals(first.uniqueId(), f.dbm.chatMentionIndex.selectOldestMentionAfter(testIdentityId, conversationId, 0)?.uniqueId)
        } finally {
            f.close()
        }
    }

    @Test
    fun headerMentions_isFalseForUnreadableHeader() {
        assertFalse(headerMentions("not json", testDomain))
    }
}
