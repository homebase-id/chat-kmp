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
    fun eachDayIsAHeaderThenASlotPerPhoto() {
        val layout = GalleryLayout(library, newestFirst = true, loaded = emptyMap())

        assertEquals(9, layout.itemCount)
        assertEquals(6, layout.photoCount)
        // Items: 30, a, a, 20, b, b, b, 10, c
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 2, 2), (0 until 9).map { layout.positionOfItem(it) })
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 0), (0 until 6).map { layout.slotOfPhoto(it) })
    }

    @Test
    fun oldestFirstRunsTheDaysAndTheirPhotosTheOtherWay() {
        val newer = item()
        val older = item()
        val layout = GalleryLayout(library, newestFirst = false, loaded = mapOf(30 to listOf(newer, older)))

        assertContentEquals(intArrayOf(10, 20, 30), layout.days)
        assertEquals(listOf(older, newer), layout.itemsOf(2))
        assertEquals(1 + 1 + 1 + 3 + 1, layout.itemOf(older.fileId))
    }

    @Test
    fun aReadDayHasASlotPerPhotoItShowsAndOneNotReadAsManyAsCounted() {
        val shown = item()
        // Two counted on day 30, but only one header made a photo to show.
        val layout = GalleryLayout(library, newestFirst = true, loaded = mapOf(30 to listOf(shown)))

        assertEquals(1, layout.slotsOf(0))
        assertEquals(3, layout.slotsOf(1))
        assertNull(layout.itemsOf(1))
        assertEquals(0, layout.photoOf(shown.fileId))
        assertEquals(1 to 0, layout.slotOfPhoto(1))
    }

    @Test
    fun aDayWithNothingToShowIsSkippedByPhotoIndexes() {
        val layout = GalleryLayout(library, newestFirst = true, loaded = mapOf(20 to emptyList()))

        assertEquals(listOf(0 to 0, 0 to 1, 2 to 0), (0 until 3).map { layout.slotOfPhoto(it) })
        assertEquals(1, layout.positionOfItem(3))
    }
}
