package id.homebase.photobase.gallery

import kotlinx.datetime.LocalDate

/** A month of the grid: its first day, and how far down the grid its first day in the grid's order starts. */
data class GalleryMonth(val first: LocalDate, val top: Int)

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
    val height: Int

    /** In the grid's order. */
    val months: List<GalleryMonth>

    /** Each year's first month in the grid's order. */
    val years: List<GalleryMonth>

    private val monthTops: IntArray

    init {
        var y = 0
        val months = mutableListOf<GalleryMonth>()
        for (position in layout.days.indices) {
            top[position] = y
            val date = LocalDate.fromEpochDays(layout.days[position])
            val first = LocalDate(date.year, date.month, 1)
            if (months.lastOrNull()?.first != first) months += GalleryMonth(first, y)
            y += headerPx + rowsOf(position) * rowPx
        }
        height = y
        this.months = months
        years = months.distinctBy { it.first.year }
        monthTops = IntArray(months.size) { months[it].top }
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
        val position = top.lastAtOrBefore(at)
        val within = at - top[position]
        val header = layout.headerItemOf(position)
        if (within < headerPx) return header to within
        val row = ((within - headerPx) / rowPx).coerceAtMost((rowsOf(position) - 1).coerceAtLeast(0))
        return header + 1 + row * columns to (within - headerPx - row * rowPx)
    }

    fun monthAt(y: Int): GalleryMonth? = months.getOrNull(monthTops.lastAtOrBefore(y))
}
