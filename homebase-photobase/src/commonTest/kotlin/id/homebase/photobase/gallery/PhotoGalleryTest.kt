package id.homebase.photobase.gallery

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.LocalAppMetadata
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotoGalleryTest {
    private fun photo(status: ArchivalStatus, vararg tags: Uuid): HomebaseFile = galleryTestFile(0).let {
        it.copy(fileMetadata = it.fileMetadata.copy(appData = it.fileMetadata.appData.copy(archivalStatus = status), localAppData = LocalAppMetadata(tags = tags.toList())))
    }

    @Test
    fun favoritesHoldsHeartedPhotosOfTheLibraryAndTheArchiveButNotTheBin() {
        val heart = PhotobaseProtocol.FavoriteTag
        assertTrue(PhotoGallery.Favorites.holds(photo(ArchivalStatus.None, heart)))
        assertTrue(PhotoGallery.Favorites.holds(photo(ArchivalStatus.Archived, Uuid.random(), heart)))
        assertFalse(PhotoGallery.Favorites.holds(photo(ArchivalStatus.Removed, heart)), "a binned favourite drops out")
        assertFalse(PhotoGallery.Favorites.holds(photo(ArchivalStatus.None, Uuid.random())))
        assertFalse(PhotoGallery.Favorites.holds(photo(ArchivalStatus.None, heart).copy(fileState = FileState.Deleted)))
    }

    @Test
    fun aShelfHoldsOnlyItsOwnStatusWhateverTheTags() {
        assertTrue(PhotoGallery.Archive.holds(photo(ArchivalStatus.Archived, PhotobaseProtocol.FavoriteTag)))
        assertFalse(PhotoGallery.Library.holds(photo(ArchivalStatus.Archived)))
    }

    @Test
    fun everyGalleryTheLibraryOpensIsFoundByItsKey() {
        for (gallery in PhotoGallery.away + PhotoGallery.Library) assertEquals(gallery, PhotoGallery.ofKey(gallery.key))
        assertEquals(null, PhotoGallery.ofKey("nothing"))
    }
}
