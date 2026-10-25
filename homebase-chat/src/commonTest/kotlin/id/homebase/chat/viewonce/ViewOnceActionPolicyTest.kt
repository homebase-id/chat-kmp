package id.homebase.chat.viewonce

import id.homebase.chat.services.content.MessageContent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewOnceActionPolicyTest {

    private val policy = MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE)).actions

    @Test
    fun theRecipientCanReplyAndReactFromTheBubble() {
        assertTrue(policy.allowReply)
        assertTrue(policy.allowInlineReactions)
    }

    @Test
    fun nothingThatCouldCarryTheMediaOutOfTheViewerIsOffered() {
        assertFalse(policy.allowForward)
        assertFalse(policy.allowShare)
        assertFalse(policy.allowCopy)
        assertFalse(policy.allowEdit)
    }

    @Test
    fun aTombstoneWithNoDescriptorKeepsTheSamePolicy() {
        assertTrue(MessageContent.ViewOnce(null).actions == policy)
    }
}
