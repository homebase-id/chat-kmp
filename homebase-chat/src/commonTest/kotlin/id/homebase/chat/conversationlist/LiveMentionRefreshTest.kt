package id.homebase.chat.conversationlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class LiveMentionRefreshTest {
    private val pending = NextMention(Uuid.random(), userDateMs = 1, pendingCount = 1)

    @Test
    fun `a mention arriving while another is pending re-reads the count`() {
        assertTrue(shouldRefreshLiveMention(pending, isGroup = true, unreadMentionCount = 2, countAtLastRefresh = 1))
    }

    @Test
    fun `an unchanged mention count does not re-read`() {
        assertFalse(shouldRefreshLiveMention(pending, isGroup = true, unreadMentionCount = 1, countAtLastRefresh = 1))
    }

    @Test
    fun `a first live mention is picked up`() {
        assertTrue(shouldRefreshLiveMention(null, isGroup = true, unreadMentionCount = 1, countAtLastRefresh = null))
    }

    @Test
    fun `one-to-one chats never refresh`() {
        assertFalse(shouldRefreshLiveMention(null, isGroup = false, unreadMentionCount = 1, countAtLastRefresh = null))
    }

    @Test
    fun `a live seed starts at the boundary frozen on open, not the advanced lastRead`() {
        val frozen = Instant.fromEpochMilliseconds(1_000)
        val advanced = Instant.fromEpochMilliseconds(5_000)
        assertEquals(1_000L, mentionCursorSeed(frozen, advanced))
        assertEquals(5_000L, mentionCursorSeed(null, advanced))
    }
}
