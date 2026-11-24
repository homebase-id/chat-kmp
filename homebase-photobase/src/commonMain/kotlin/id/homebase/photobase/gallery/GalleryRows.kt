package id.homebase.photobase.gallery

import kotlinx.datetime.LocalDate

/** A month of the grid, for the scrubber's label and year marks: where its first day's header is. */
data class GalleryMonth(val year: Int, val month: Int, val top: Int, val headerItem: Int)

/**
 * The grid's height in pixels, row by row, from the layout and what the grid measured: each day is its header and
 * then ceil(photos / columns) rows of tiles. Lets a scrubber turn a position on its track into a place in the grid and
 * back without the grid having laid out the rows in between.
 */
class GalleryRows(
    private val layout: GalleryLayout,
    private val columns: Int,
    private val headerPx: Int,
    private val rowPx: Int,
) {
    private val top = IntArray(layout.days.size)

    /** The whole grid, top to bottom. */
    val height: Int

    /** In the grid's order; a month is where its first day in that order is. */
    val months: List<GalleryMonth>

    init {
        var y = 0
        val months = mutableListOf<GalleryMonth>()
        for (position in layout.days.indices) {
            top[position] = y
            val date = LocalDate.fromEpochDays(layout.days[position])
            val last = months.lastOrNull()
            if (last == null || last.year != date.year || last.month != date.month.ordinal + 1) {
                months += GalleryMonth(date.year, date.month.ordinal + 1, y, layout.headerItemOf(position))
            }
            y += headerPx + rowsOf(position) * rowPx
        }
        height = y
        this.months = months
    }

    private fun rowsOf(position: Int) = (layout.slotsOf(position) + columns - 1) / columns

    /** How far down the grid the top of [item] is, plus [offset] into it. */
    fun topOf(item: Int, offset: Int): Int {
        if (top.isEmpty()) return 0
        val position = layout.positionOfItem(item)
        val header = layout.headerItemOf(position)
        if (item == header) return top[position] + offset
        return top[position] + headerPx + (item - header - 1) / columns * rowPx + offset
    }

    /** The item [y] down the grid falls in, and how far into it: what to scroll to. */
    fun itemAt(y: Int): Pair<Int, Int> {
        if (top.isEmpty()) return 0 to 0
        val at = y.coerceIn(0, height)
        val position = lastAtOrBefore(at)
        val within = at - top[position]
        val header = layout.headerItemOf(position)
        if (within < headerPx) return header to within.coerceAtLeast(0)
        val row = ((within - headerPx) / rowPx).coerceAtMost((rowsOf(position) - 1).coerceAtLeast(0))
        return header + 1 + row * columns to (within - headerPx - row * rowPx)
    }

    /** The month [y] down the grid is in. */
    fun monthAt(y: Int): GalleryMonth? {
        val found = months.binarySearch { it.top.compareTo(y) }
        val at = if (found >= 0) found else -found - 2
        return months.getOrNull(at.coerceAtLeast(0))
    }

    // Every day has a header, so the tops rise strictly.
    private fun lastAtOrBefore(y: Int): Int {
        val found = top.asList().binarySearch(y)
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }
}
