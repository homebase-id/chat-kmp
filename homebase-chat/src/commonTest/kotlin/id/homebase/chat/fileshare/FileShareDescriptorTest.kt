package id.homebase.chat.fileshare

import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FileShareDescriptorTest {
    private val photos = FileShareDescriptor(
        circleId = Uuid.random().toString(), title = "Photos", titleIsFolder = true, files = 78, bytes = 133_000_000,
        access = FileShareDescriptor.ACCESS_VIEW, group = "Hiking", note = "From the trip", expiresAt = 1_900_000_000_000,
    )

    @Test
    fun dataTypeIs218() = assertEquals(218, ChatProtocol.ChatFileShareMessageDataType)

    @Test
    fun aShareRoundTripsThroughTheHeader() {
        val content = MessageContentParser.serialize(MessageContent.FileShare(photos))

        assertEquals(MessageContent.FileShare(photos), MessageContentParser.parse(ChatProtocol.ChatFileShareMessageDataType, content))
        assertEquals(ChatProtocol.ChatFileShareMessageDataType, MessageContentParser.dataTypeFor(MessageContent.FileShare(photos)))
    }

    @Test
    fun anInvalidShareRendersTheChipNotNothing() {
        val parsed = MessageContentParser.parse(ChatProtocol.ChatFileShareMessageDataType, """{"circleId":"not-a-uuid","title":"x","files":1,"bytes":1,"access":"view"}""")
        assertEquals(MessageContent.FileShare(null), parsed)
        assertEquals(MessageContent.UNPARSEABLE_FILE_SHARE_LABEL, parsed!!.displayLabel)
        assertEquals(MessageContent.FileShare(null), MessageContentParser.parse(ChatProtocol.ChatFileShareMessageDataType, "{garbage"))
    }

    @Test
    fun validityBoundsEveryField() {
        assertTrue(photos.isValid())
        assertFalse(photos.copy(access = "own").isValid())
        assertFalse(photos.copy(title = " ").isValid())
        assertFalse(photos.copy(files = -1).isValid())
        assertFalse(photos.copy(note = "x".repeat(FileShareDescriptor.MAX_NOTE_CODEPOINTS + 1)).isValid())
        assertFalse(photos.copy(group = "x".repeat(FileShareDescriptor.MAX_GROUP_CODEPOINTS + 1)).isValid())
    }

    @Test
    fun theSummaryNamesTheShareAndItsSizeButThePushDoesnt() {
        assertEquals("Photos · 78 files", MessageContent.FileShare(photos).displayLabel)
        assertEquals("plan.pdf", photos.copy(title = "plan.pdf", titleIsFolder = false, files = 1).summaryLine())
        assertEquals(MessageContent.UNPARSEABLE_FILE_SHARE_LABEL, MessageContent.FileShare(photos).notificationLabel)
    }
}
