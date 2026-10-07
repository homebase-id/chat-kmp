package id.homebase.chat.viewonce

import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewOnceDescriptorTest {

    @Test
    fun dataTypeIs216() = assertEquals(216, ChatProtocol.ChatViewOnceMessageDataType)

    @Test
    fun isValid_acceptsImageAndVideo() {
        assertTrue(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE).isValid())
        assertTrue(ViewOnceDescriptor(ViewOnceDescriptor.KIND_VIDEO).isValid())
    }

    @Test
    fun isValid_rejectsUnknownAndEmptyKind() {
        assertFalse(ViewOnceDescriptor("audio").isValid())
        assertFalse(ViewOnceDescriptor("").isValid())
    }

    @Test
    fun summaryLine_namesTheKind() {
        assertEquals("Photo", ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE).summaryLine())
        assertEquals("Video", ViewOnceDescriptor(ViewOnceDescriptor.KIND_VIDEO).summaryLine())
    }

    @Test
    fun roundTrip_serializeThenParse_yieldsViewOnce() {
        val descriptor = ViewOnceDescriptor(ViewOnceDescriptor.KIND_VIDEO)
        val content = MessageContent.ViewOnce(descriptor)

        val json = MessageContentParser.serialize(content)
        assertTrue(json.isNotBlank())
        assertEquals(216, MessageContentParser.dataTypeFor(content))

        val parsed = assertIs<MessageContent.ViewOnce>(MessageContentParser.parse(216, json))
        assertEquals(descriptor, parsed.descriptor)
        assertTrue(MessageContentParser.usesRawHeaderContent(parsed))
    }

    @Test
    fun wireShape_isExactlySchemaVersionAndKind() {
        val json = MessageContentParser.serialize(
            MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE))
        )
        assertTrue("\"kind\":\"image\"" in json.replace(" ", ""))
        assertTrue("\"schemaVersion\":1" in json.replace(" ", ""))
    }

    @Test
    fun caption_roundTripsButNeverReachesTheLabel() {
        val descriptor = ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE, caption = "meet at 9")
        val content = MessageContent.ViewOnce(descriptor)

        val parsed = assertIs<MessageContent.ViewOnce>(
            MessageContentParser.parse(216, MessageContentParser.serialize(content))
        )
        assertEquals("meet at 9", parsed.descriptor?.caption)
        assertEquals("Photo", parsed.displayLabel)
        assertEquals("Photo", parsed.notificationLabel)
        assertEquals("Photo", descriptor.summaryLine())
    }

    @Test
    fun captionlessDescriptor_keepsTheOriginalWireShape() {
        val json = MessageContentParser.serialize(
            MessageContent.ViewOnce(ViewOnceDescriptor(ViewOnceDescriptor.KIND_VIDEO))
        )
        assertTrue("caption" !in json)
    }

    @Test
    fun malformedJson_yieldsViewOnceWithNullDescriptor_notNull() {
        val parsed = MessageContentParser.parse(216, "{not json")
        val viewOnce = assertIs<MessageContent.ViewOnce>(parsed)
        assertNull(viewOnce.descriptor)
        assertEquals(MessageContent.UNPARSEABLE_VIEW_ONCE_LABEL, viewOnce.displayLabel)
    }

    @Test
    fun invalidKind_yieldsViewOnceWithNullDescriptor() {
        val parsed = MessageContentParser.parse(216, """{"schemaVersion":1,"kind":"gif"}""")
        assertNull(assertIs<MessageContent.ViewOnce>(parsed).descriptor)
    }

    @Test
    fun unrecognizedHigherKind_isUnknown() {
        assertIs<MessageContent.Unknown>(MessageContentParser.parse(217, """{"kind":"image"}"""))
    }

    @Test
    fun actionsLockDownEveryCopyingSurface() {
        val actions = MessageContent.ViewOnce(ViewOnceDescriptor("image")).actions
        assertFalse(actions.allowReply)
        assertFalse(actions.allowForward)
        assertFalse(actions.allowShare)
        assertFalse(actions.allowEdit)
        assertFalse(actions.allowInlineReactions)
        assertNotNull(actions)
    }

    @Test
    fun serialize_refusesNullDescriptor() {
        var threw = false
        try {
            MessageContentParser.serialize(MessageContent.ViewOnce(null))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
