package id.homebase.photobase.gallery

import kotlin.uuid.Uuid

/**
 * The gallery as a list of lines, [columns] photos wide: each day is its header and then a row per [columns] of its
 * slots. A row per item, not a photo per item, keeps the lazy list a third the size, and a list, unlike a lazy grid,
 * doesn't compose screens past a far jump back under a shared-transition lookahead.
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

    fun dayOfItem(item: Int): Int = layout.days[positionOfItem(item)]

    /** The row a day's [slot] is on, held to the day's last; null once the day has none. */
    fun itemOf(day: Int, slot: Int): Int? =
        layout.positionOf(day)?.let { firstItem[it] + 1 + slot.coerceAtMost(layout.slotsOf(it) - 1) / columns }

    fun itemOf(fileId: Uuid, loaded: Map<Int, List<PhotoGalleryItem>>): Int? =
        layout.photoOf(fileId, loaded)?.let { layout.locate(it) }?.let { (day, slot) -> itemOf(day, slot) }
}
