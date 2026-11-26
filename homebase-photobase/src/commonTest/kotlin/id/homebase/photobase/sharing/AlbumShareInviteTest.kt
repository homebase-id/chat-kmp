package id.homebase.photobase.sharing

import id.homebase.chat.albumshare.AlbumShareDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AlbumShareInviteTest {
    @Test
    fun theInviteNamesTheAlbumAndWhatTheyMayDo() {
        val albumId = Uuid.random()

        val invite = albumShareInvite(albumId, "Hiking trip", PhotoAlbumAccess.Add).descriptor!!

        assertEquals(albumId, invite.album())
        assertEquals("Hiking trip", invite.title)
        assertTrue(invite.canAdd)
        assertEquals(AlbumShareDescriptor.ACCESS_VIEW, albumShareInvite(albumId, "Hiking trip", PhotoAlbumAccess.View).descriptor!!.access)
        assertTrue(invite.isValid())
    }

    @Test
    fun aLongNameIsCutToFitWithoutSplittingAnEmoji() {
        val invite = albumShareInvite(Uuid.random(), "🏔".repeat(AlbumShareDescriptor.MAX_TITLE_CODEPOINTS + 5), PhotoAlbumAccess.View).descriptor!!

        assertTrue(invite.isValid())
        assertEquals("🏔".repeat(AlbumShareDescriptor.MAX_TITLE_CODEPOINTS), invite.title)
    }
}
