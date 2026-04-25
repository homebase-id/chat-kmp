package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Locks down the BOTH-ids-required gate for chat notification taps:
 * only when typeId AND tagId both parse as Uuid do we set the pending
 * tap and auto-navigate. A payload with only typeId is ambient
 * "new activity" — we should land on ChatList without jumping into
 * a speculative conversation.
 */
class ChatTapGatingTest {

    private val convo = Uuid.random()
    private val msg = Uuid.random()

    @Test
    fun bothValidUuids_returnsPair() {
        val result = extractChatTapIds(
            typeId = convo.toString(),
            tagId = msg.toString(),
        )
        assertEquals(convo to msg, result)
    }

    @Test
    fun emptyTagId_returnsNull() {
        assertNull(
            extractChatTapIds(typeId = convo.toString(), tagId = "")
        )
    }

    @Test
    fun blankTagId_returnsNull() {
        assertNull(
            extractChatTapIds(typeId = convo.toString(), tagId = "   ")
        )
    }

    @Test
    fun malformedTypeId_returnsNull() {
        assertNull(
            extractChatTapIds(typeId = "not-a-uuid", tagId = msg.toString())
        )
    }

    @Test
    fun malformedTagId_returnsNull() {
        assertNull(
            extractChatTapIds(typeId = convo.toString(), tagId = "not-a-uuid")
        )
    }

    @Test
    fun bothMalformed_returnsNull() {
        assertNull(extractChatTapIds(typeId = "", tagId = ""))
    }
}
