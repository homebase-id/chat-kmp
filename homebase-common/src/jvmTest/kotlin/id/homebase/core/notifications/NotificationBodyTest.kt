package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [notificationBody]: at the content level that shows real text, the body is always the message
 * itself (the Android displayer stacks multiples via MessagingStyle); at redacted levels it
 * collapses to a "$N new messages" count once more than one has accumulated.
 */
class NotificationBodyTest {

    @Test
    fun realContent_keepsMessage_evenWhenMultiple() {
        assertEquals("Hey there", notificationBody("Hey there", messageCount = 3, showsRealContent = true))
    }

    @Test
    fun realContent_singleMessage_keepsMessage() {
        assertEquals("Hey there", notificationBody("Hey there", messageCount = 1, showsRealContent = true))
    }

    @Test
    fun redacted_multiple_collapsesToCount() {
        assertEquals("3 new messages", notificationBody("New notification", messageCount = 3, showsRealContent = false))
    }

    @Test
    fun redacted_single_keepsBody() {
        assertEquals("New notification", notificationBody("New notification", messageCount = 1, showsRealContent = false))
    }
}
