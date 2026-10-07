package id.homebase.chat.conversationlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewOnceReplyTest {
    @Test
    fun viewOnceSendNeverConsumesThePendingReply() {
        assertNull(replyForSend(viewOnce = true, replyTo = "quoted"))
    }

    @Test
    fun regularSendKeepsTheReply() {
        assertEquals("quoted", replyForSend(viewOnce = false, replyTo = "quoted"))
    }
}
