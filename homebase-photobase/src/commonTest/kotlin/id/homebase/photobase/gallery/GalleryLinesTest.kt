package id.homebase.photobase.gallery

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class GalleryLinesTest {
    private fun item() = PhotoGalleryItem(Uuid.random(), Uuid.random(), 0, KeyHeader.newRandom16(), PayloadDescriptor(key = "photo_main", contentType = "image/jpeg"))

    // Days 30, 20, 10 with 2, 3 and 1 photos.
    private val library = LibraryDays(intArrayOf(30, 20, 10), intArrayOf(2, 3, 1))

    @Test
    fun eachDayIsAHeaderThenARowPerColumnsOfPhotos() {
        val lines = GalleryLines(GalleryLayout(library, newestFirst = true), columns = 2)

        // Items: 30, [a a], 20, [b b], [b], 10, [c]
        assertEquals(7, lines.itemCount)
        assertEquals(listOf(30, 30, 20, 20, 20, 10, 10), (0 until 7).map { lines.dayOfItem(it) })
        assertEquals(listOf(1, 2, 1), (0 until 3).map { lines.rowsOf(it) })
        assertEquals(listOf(0, 2, 5), (0 until 3).map { lines.headerItemOf(it) })
    }

    @Test
    fun aSlotIsOnItsDaysRowAndAPhotoOnItsSlotsRow() {
        val newer = item()
        val older = item()
        val layout = GalleryLayout(library, newestFirst = true)
        val loaded = mapOf(20 to listOf(item(), item(), older), 30 to listOf(newer))
        val lines = GalleryLines(layout, columns = 2)

        assertEquals(3, lines.itemOf(20, 0))
        assertEquals(4, lines.itemOf(20, 2))
        assertEquals(4, lines.itemOf(20, 9), "a slot past the day's last is held to its last")
        assertEquals(4, lines.itemOf(older.fileId, loaded))
        assertEquals(1, lines.itemOf(newer.fileId, loaded))
        assertNull(lines.itemOf(item().fileId, loaded))
    }

    @Test
    fun oneColumnIsAnItemPerPhoto() {
        val lines = GalleryLines(GalleryLayout(library, newestFirst = false), columns = 1)

        // Items: 10, c, 20, b, b, b, 30, a, a
        assertEquals(listOf(10, 10, 20, 20, 20, 20, 30, 30, 30), (0 until 9).map { lines.dayOfItem(it) })
        assertEquals(7, lines.itemOf(30, 0))
    }

    @Test
    fun aDayShowingNoPhotosIsItsHeaderAlone() {
        val lines = GalleryLines(GalleryLayout(library, newestFirst = true, shown = mapOf(20 to 0)), columns = 2)

        assertEquals(listOf(30, 30, 20, 10, 10), (0 until 5).map { lines.dayOfItem(it) })
        assertNull(lines.itemOf(20, 0))
    }
}
