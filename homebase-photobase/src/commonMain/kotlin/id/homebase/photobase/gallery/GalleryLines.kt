package id.homebase.photobase.gallery

import kotlin.uuid.Uuid

/**
 * The gallery as lines [columns] photos wide: each day is its header and then a row per [columns] of its slots.
 * A list of rows, not a lazy grid: under a shared-transition lookahead a lazy grid composes screens past a far jump back.
 */
class GalleryLines(val layout: GalleryLayout, val columns: Int) {
    private val firstItem = IntArray(layout.days.size)
    val itemCount: Int

    init {
        var item = 0
        for (position in layout.days.indices) {
            firstItem[position] = item
            item += 1 + rowsOf(position)
        }
        itemCount = item
    }

    fun rowsOf(position: Int): Int = (layout.slotsOf(position) + columns - 1) / columns

    fun headerItemOf(position: Int): Int = firstItem[position]

    // Every day has a header, so the first items rise strictly.
    fun positionOfItem(item: Int): Int = firstItem.lastAtOrBefore(item)

    /** The row [item] is of its day, or -1 for the day's header. */
    fun rowOfItem(item: Int): Int = item - firstItem[positionOfItem(item)] - 1

    fun dayOfItem(item: Int): Int = layout.days[positionOfItem(item)]

    fun keyOf(item: Int): String {
        val day = dayOfItem(item)
        val row = rowOfItem(item)
        return if (row < 0) "day-$day" else "row-$day-$row"
    }

    /** The row a day's [slot] is on, held to the day's last; null once the day has none. */
    fun itemOf(day: Int, slot: Int): Int? =
        layout.positionOf(day)?.let { firstItem[it] + 1 + slot.coerceAtMost(layout.slotsOf(it) - 1) / columns }

    fun itemOf(fileId: Uuid, loaded: Map<Int, List<PhotoGalleryItem>>): Int? =
        layout.placeOf(fileId, loaded)?.let { (position, slot) -> firstItem[position] + 1 + slot / columns }

    /** The item here showing what [item] shows in [other]: its day's header, or the row with its first photo. */
    fun itemFor(other: GalleryLines, item: Int): Int? {
        val day = other.dayOfItem(item)
        val row = other.rowOfItem(item)
        return if (row < 0) headerOnOrAfter(day) else itemOf(day, row * other.columns)
    }

    // The day's header, or once the day is gone the next day's. Only on a column change, so a plain walk.
    private fun headerOnOrAfter(day: Int): Int? {
        val position = layout.days.indexOfFirst { if (layout.newestFirst) it <= day else it >= day }
        return if (position < 0) null else firstItem[position]
    }
}
