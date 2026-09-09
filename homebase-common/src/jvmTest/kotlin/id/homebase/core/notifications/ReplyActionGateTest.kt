package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The direct-reply action must never be offered on a redacted or placeholder push — there is
 * nothing on screen to reply to, and the user would be answering blind.
 */
class ReplyActionGateTest {

    private fun data(hasContent: Boolean, showsRealContent: Boolean) = RichNotificationData(
        notificationId = 1,
        channelId = "messages",
        conversationId = "conversation",
        title = "Sender",
        body = "body",
        senderName = "Sender",
        senderId = "sender.example.com",
        senderImageBytes = null,
        timestamp = 0L,
        payloadData = emptyMap(),
        hasContent = hasContent,
        showsRealContent = showsRealContent,
    )

    @Test
    fun allowsReply_onlyWhenRealContentIsShown() {
        assertTrue(data(hasContent = true, showsRealContent = true).allowsReplyAction)
    }

    @Test
    fun blocksReply_onPlaceholderBody() {
        assertFalse(data(hasContent = false, showsRealContent = true).allowsReplyAction)
    }

    @Test
    fun blocksReply_onRedactedContentLevel() {
        assertFalse(data(hasContent = true, showsRealContent = false).allowsReplyAction)
    }

    @Test
    fun blocksReply_byDefault() {
        assertFalse(data(hasContent = false, showsRealContent = false).allowsReplyAction)
    }
}
