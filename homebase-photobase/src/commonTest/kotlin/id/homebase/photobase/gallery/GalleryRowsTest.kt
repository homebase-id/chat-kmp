package id.homebase.photobase.gallery

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class GalleryRowsTest {
    private fun day(year: Int, month: Int, day: Int) = LocalDate(year, month, day).toEpochDays().toInt()

    // Three days, newest first: two photos on 10 March, three on 2 March, one on 20 February.
    private val layout = GalleryLayout(
        LibraryDays(intArrayOf(day(2024, 3, 10), day(2024, 3, 2), day(2024, 2, 20)), intArrayOf(2, 3, 1)),
        newestFirst = true,
    )

    // Two columns, a 10 px header and 20 px rows: 10 March is 0..30, 2 March 30..80, 20 February 80..110.
    private val rows = GalleryRows(layout, columns = 2, headerPx = 10, rowPx = 20)

    @Test
    fun eachDayIsItsHeaderAndARowPerColumnsOfPhotos() {
        assertEquals(110, rows.height)
        // Items: header, 2 photos; header, 3 photos; header, 1 photo.
        assertEquals(listOf(0, 10, 10, 30, 40, 40, 60, 80, 90), (0..8).map { rows.topOf(it, 0) })
        assertEquals(45, rows.topOf(4, 5))
    }

    @Test
    fun aPlaceDownTheGridIsAnItemAndHowFarIntoItThatScrollsBackToIt() {
        assertEquals(0 to 0, rows.itemAt(0))
        assertEquals(1 to 5, rows.itemAt(15))
        assertEquals(3 to 5, rows.itemAt(35))
        assertEquals(6 to 5, rows.itemAt(65))
        for (y in 0 until rows.height) {
            val (item, offset) = rows.itemAt(y)
            assertEquals(y, rows.topOf(item, offset), "at $y")
        }
    }

    @Test
    fun pastTheEndIsTheLastRow() {
        assertEquals(8 to 20, rows.itemAt(500))
    }

    @Test
    fun monthsStartAtTheirFirstDayInTheGridsOrder() {
        assertEquals(listOf(GalleryMonth(2024, 3, top = 0, headerItem = 0), GalleryMonth(2024, 2, top = 80, headerItem = 7)), rows.months)
        assertEquals(3, rows.monthAt(79)?.month)
        assertEquals(2, rows.monthAt(85)?.month)

        val oldestFirst = GalleryRows(GalleryLayout(LibraryDays(intArrayOf(day(2024, 3, 10), day(2024, 2, 20)), intArrayOf(1, 1)), newestFirst = false), 2, 10, 20)
        assertEquals(listOf(2, 3), oldestFirst.months.map { it.month })
    }
}
