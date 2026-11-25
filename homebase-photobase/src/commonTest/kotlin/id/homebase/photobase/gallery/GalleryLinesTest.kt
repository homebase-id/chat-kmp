package id.homebase.photobase.gallery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GalleryLinesTest {
    private fun item() = galleryItem()

    private val library = threeDays

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
    fun eachItemHasAKeyOfItsDayAndRow() {
        val lines = GalleryLines(GalleryLayout(library, newestFirst = true), columns = 2)

        assertEquals(listOf("day-30", "row-30-0", "day-20", "row-20-0", "row-20-1", "day-10", "row-10-0"), (0 until 7).map { lines.keyOf(it) })
        assertEquals(listOf(-1, 0, -1, 0, 1, -1, 0), (0 until 7).map { lines.rowOfItem(it) })
    }

    @Test
    fun aNewColumnCountKeepsTheSameDayAndPhotoAtTheTop() {
        val layout = GalleryLayout(LibraryDays(intArrayOf(30, 20), intArrayOf(2, 9)), newestFirst = true)
        val three = GalleryLines(layout, columns = 3)
        val two = GalleryLines(layout, columns = 2)

        // Three wide: 30, [a a], 20, [b b b], [b b b], [b b b]. Two wide: 30, [a a], 20, [b b], [b b], [b b], [b b], [b].
        assertEquals(2, two.itemFor(three, 2), "a header stays its header")
        assertEquals(4, two.itemFor(three, 4), "the row starting at day 20's fourth photo is two wide's row with it")
        assertEquals(5, three.itemFor(two, 6), "and back: the row with day 20's sixth photo")
    }

    @Test
    fun aHeaderWhoseDayIsGoneGoesToTheNextDay() {
        val before = GalleryLines(GalleryLayout(library, newestFirst = true), columns = 2)
        val after = GalleryLines(GalleryLayout(LibraryDays(intArrayOf(30, 10), intArrayOf(2, 1)), newestFirst = true), columns = 3)

        assertEquals(2, after.itemFor(before, 2), "day 20's header becomes day 10's")
    }

    @Test
    fun oldestFirstAPhotoIsOnTheRowOfItsReversedSlot() {
        val newer = item()
        val older = item()
        val lines = GalleryLines(GalleryLayout(library, newestFirst = false), columns = 2)

        // Items: 10, [c], 20, [b b], [b], 30, [a a]; day 30's older photo is its first slot.
        assertEquals(6, lines.itemOf(older.fileId, mapOf(30 to listOf(newer, older))))
        assertEquals(6, lines.itemOf(newer.fileId, mapOf(30 to listOf(newer, older))))
    }

    @Test
    fun aDayShowingFewerThanCountedTakesFewerRows() {
        val lines = GalleryLines(GalleryLayout(library, newestFirst = true, shown = mapOf(30 to 1)), columns = 2)

        // Items: 30, [a], 20, [b b], [b], 10, [c]
        assertEquals(3, lines.itemOf(20, 0), "day 30's header, its one row, day 20's header, then this")
        assertEquals(4, lines.itemOf(20, 2))
    }

    @Test
    fun aRowWhoseDayEmptiedGoesToTheNextDay() {
        val before = GalleryLines(GalleryLayout(library, newestFirst = true), columns = 2)
        val after = GalleryLines(GalleryLayout(library, newestFirst = true, shown = mapOf(20 to 0)), columns = 3)

        // After: 30, [a a], 20, 10, [c]: day 20's row 1 has no photos to land on.
        assertEquals(2, after.itemFor(before, 4))
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
