package id.homebase.chat.albumshare

import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AlbumShareDescriptorTest {
    private val trip = AlbumShareDescriptor(albumId = Uuid.random().toString(), title = "Hiking trip", access = AlbumShareDescriptor.ACCESS_ADD)

    @Test
    fun dataTypeIs221() = assertEquals(221, ChatProtocol.ChatAlbumShareMessageDataType)

    @Test
    fun aShareRoundTripsThroughTheHeader() {
        val content = MessageContentParser.serialize(MessageContent.AlbumShare(trip))

        assertEquals(MessageContent.AlbumShare(trip), MessageContentParser.parse(ChatProtocol.ChatAlbumShareMessageDataType, content))
        assertEquals(ChatProtocol.ChatAlbumShareMessageDataType, MessageContentParser.dataTypeFor(MessageContent.AlbumShare(trip)))
        assertTrue(MessageContentParser.usesRawHeaderContent(MessageContent.AlbumShare(trip)))
    }

    @Test
    fun anInvalidShareRendersTheChipNotNothing() {
        val parsed = MessageContentParser.parse(ChatProtocol.ChatAlbumShareMessageDataType, """{"albumId":"not-a-uuid","title":"x","access":"view"}""")
        assertEquals(MessageContent.AlbumShare(null), parsed)
        assertEquals(MessageContent.UNPARSEABLE_ALBUM_SHARE_LABEL, parsed!!.displayLabel)
        assertEquals(MessageContent.AlbumShare(null), MessageContentParser.parse(ChatProtocol.ChatAlbumShareMessageDataType, "{garbage"))
    }

    @Test
    fun validityBoundsEveryField() {
        assertTrue(trip.isValid())
        assertTrue(trip.canAdd)
        assertFalse(trip.copy(access = AlbumShareDescriptor.ACCESS_VIEW).canAdd)
        assertFalse(trip.copy(access = "own").isValid())
        assertFalse(trip.copy(title = " ").isValid())
        assertFalse(trip.copy(title = "x".repeat(AlbumShareDescriptor.MAX_TITLE_CODEPOINTS + 1)).isValid())
    }

    @Test
    fun theListNamesTheAlbumButThePushDoesnt() {
        assertEquals("Hiking trip", MessageContent.AlbumShare(trip).displayLabel)
        assertEquals(MessageContent.UNPARSEABLE_ALBUM_SHARE_LABEL, MessageContent.AlbumShare(trip).notificationLabel)
    }
}
