package id.homebase.photobase.gallery

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GalleryLayoutTest {
    private fun item() = PhotoGalleryItem(Uuid.random(), Uuid.random(), 0, KeyHeader.newRandom16(), PayloadDescriptor(key = "photo_main", contentType = "image/jpeg"))

    // Days 30, 20, 10 with 2, 3 and 1 photos.
    private val library = LibraryDays(intArrayOf(30, 20, 10), intArrayOf(2, 3, 1))

    @Test
    fun eachDayIsAHeaderThenASlotPerCountedPhoto() {
        val layout = GalleryLayout(library, newestFirst = true)

        assertEquals(6, layout.photoCount)
        // Items: 30, a, a, 20, b, b, b, 10, c
        assertEquals(listOf(30, 30, 30, 20, 20, 20, 20, 10, 10), (0 until 9).map { layout.dayOfItem(it) })
        assertEquals(listOf(30, 30, 20, 20, 20, 10), (0 until 6).map { layout.dayOfPhoto(it) })
    }

    @Test
    fun aDaysSlotsFillFromWhatsReadWithoutMovingAnything() {
        val newer = item()
        val older = item()
        val layout = GalleryLayout(library, newestFirst = true)
        val loaded = mapOf(30 to listOf(newer, older))

        assertEquals(newer, layout.photoAt(0, loaded))
        assertEquals(older, layout.photoAt(1, loaded))
        assertNull(layout.photoAt(2, loaded), "day 20 isn't read yet")
        assertEquals(2, layout.itemOf(older.fileId, loaded))
        assertEquals(1, layout.photoOf(older.fileId, loaded))
    }

    @Test
    fun oldestFirstRunsTheDaysAndTheirPhotosTheOtherWay() {
        val newer = item()
        val older = item()
        val layout = GalleryLayout(library, newestFirst = false)
        val loaded = mapOf(30 to listOf(newer, older))

        assertContentEquals(intArrayOf(10, 20, 30), layout.days)
        assertEquals(older, layout.photoAt(2, 0, loaded))
        assertEquals(newer, layout.photoAt(2, 1, loaded))
        assertEquals(1 + 1 + 1 + 3 + 1, layout.itemOf(older.fileId, loaded))
        assertEquals(1 + 3, layout.photoOf(older.fileId, loaded))
    }

    @Test
    fun aDayThatShowsAnotherNumberThanCountedHasASlotPerPhotoItShows() {
        val shown = item()
        // Two counted on day 30, but only one header made a photo to show.
        val layout = GalleryLayout(library, newestFirst = true, shown = mapOf(30 to 1))
        val loaded = mapOf(30 to listOf(shown))

        assertEquals(1, layout.slotsOf(0))
        assertEquals(5, layout.photoCount)
        assertEquals(shown, layout.photoAt(0, loaded))
        assertEquals(20, layout.dayOfPhoto(1))
        assertEquals(3, layout.itemOfPhoto(1), "day 30's header, its photo, day 20's header, then this")
    }
}
