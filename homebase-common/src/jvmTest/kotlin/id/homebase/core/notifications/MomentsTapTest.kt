package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Locks down how a Moments push is told apart from a chat message — and a moment
 * post from a comment — while all ride on the chat appId (the backend rejects a
 * non-chat appId; this is the chat app).
 *
 * Both moment kinds set typeId == momentId (so the moment is recoverable from
 * typeId and notifications coalesce per moment). A post sets tagId == typeId; a
 * comment sets tagId == [MOMENT_COMMENT_TAG_SENTINEL] and opens with comments
 * expanded. A chat message carries a distinct conversationId/messageId, neither
 * equal nor the sentinel.
 */
class MomentsTapTest {

    private val moment = Uuid.random()

    @Test
    fun postTap_opensMoment_noComments() {
        val target = resolveMomentsTap(typeId = moment.toString(), tagId = moment.toString())
        assertEquals(MomentsTapTarget(moment.toString(), openComments = false), target)
    }

    @Test
    fun commentTap_opensMoment_withCommentsExpanded() {
        val target = resolveMomentsTap(
            typeId = moment.toString(),
            tagId = MOMENT_COMMENT_TAG_SENTINEL,
        )
        // The moment to open comes from typeId (the comment's tagId is the sentinel).
        assertEquals(MomentsTapTarget(moment.toString(), openComments = true), target)
    }

    @Test
    fun chatMessage_isNotMoments() {
        // typeId = conversationId, tagId = messageId — always distinct, never the sentinel.
        assertNull(
            resolveMomentsTap(typeId = Uuid.random().toString(), tagId = Uuid.random().toString())
        )
    }

    @Test
    fun ambientChatTapWithBlankTagId_isNotMoments() {
        assertNull(resolveMomentsTap(typeId = moment.toString(), tagId = ""))
    }

    @Test
    fun blankTypeId_isNotMoments() {
        // Even a sentinel tagId can't rescue a blank typeId — there'd be no moment to open.
        assertNull(resolveMomentsTap(typeId = "", tagId = MOMENT_COMMENT_TAG_SENTINEL))
        assertNull(resolveMomentsTap(typeId = "   ", tagId = "   "))
    }

    @Test
    fun isMomentsTap_matchesResolver() {
        assertEquals(true, isMomentsTap(moment.toString(), moment.toString()))
        assertEquals(true, isMomentsTap(moment.toString(), MOMENT_COMMENT_TAG_SENTINEL))
        assertEquals(false, isMomentsTap(Uuid.random().toString(), Uuid.random().toString()))
    }
}
