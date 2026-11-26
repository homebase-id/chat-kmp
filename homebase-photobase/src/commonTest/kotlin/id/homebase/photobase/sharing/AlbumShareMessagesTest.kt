package id.homebase.photobase.sharing

import id.homebase.chat.albumshare.AlbumShareDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AlbumShareMessagesTest {
    @Test
    fun theInviteNamesTheAlbumAndWhatTheyMayDo() {
        val albumId = Uuid.random()

        val invite = albumShareDescriptor(albumId, "Hiking trip", PhotoAlbumAccess.Add)

        assertEquals(albumId, invite.album())
        assertEquals("Hiking trip", invite.title)
        assertTrue(invite.canAdd)
        assertEquals(AlbumShareDescriptor.ACCESS_VIEW, albumShareDescriptor(albumId, "Hiking trip", PhotoAlbumAccess.View).access)
        assertTrue(invite.isValid())
    }

    @Test
    fun aLongNameIsCutToFitWithoutSplittingAnEmoji() {
        val invite = albumShareDescriptor(Uuid.random(), "🏔".repeat(AlbumShareDescriptor.MAX_TITLE_CODEPOINTS + 5), PhotoAlbumAccess.View)

        assertTrue(invite.isValid())
        assertEquals("🏔".repeat(AlbumShareDescriptor.MAX_TITLE_CODEPOINTS), invite.title)
    }
}
