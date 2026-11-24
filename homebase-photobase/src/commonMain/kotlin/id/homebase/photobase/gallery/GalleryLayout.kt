package id.homebase.photobase.gallery

import kotlin.uuid.Uuid

/**
 * Where every day and photo of the library sits, in the grid's order: each day is a header item and then a slot per
 * photo. A day read from the database has a slot per photo it showed, one not read yet as many as the index counts.
 */
class GalleryLayout(library: LibraryDays, val newestFirst: Boolean, private val loaded: Map<Int, List<PhotoGalleryItem>>) {
    /** The days, in the grid's order. */
    val days: IntArray
    private val slots: IntArray
    private val firstItem: IntArray
    private val firstPhoto: IntArray
    val itemCount: Int
    val photoCount: Int

    init {
        val n = library.days.size
        val at = { position: Int -> if (newestFirst) position else n - 1 - position }
        days = IntArray(n) { library.days[at(it)] }
        slots = IntArray(n) { loaded[days[it]]?.size ?: library.counts[at(it)] }
        firstItem = IntArray(n)
        firstPhoto = IntArray(n)
        var item = 0
        var photo = 0
        for (position in 0 until n) {
            firstItem[position] = item
            firstPhoto[position] = photo
            item += 1 + slots[position]
            photo += slots[position]
        }
        itemCount = item
        photoCount = photo
    }

    fun slotsOf(position: Int): Int = slots[position]

    /** The day's photos in the grid's order, or null while its headers aren't read. */
    fun itemsOf(position: Int): List<PhotoGalleryItem>? =
        loaded[days[position]]?.let { if (newestFirst) it else it.asReversed() }

    /** The day an item of the grid, header or photo, belongs to. */
    fun positionOfItem(item: Int): Int = lastAtOrBefore(firstItem, item)

    /** The day the [photo]th photo of the library falls on, and its slot there. */
    fun slotOfPhoto(photo: Int): Pair<Int, Int> {
        val position = lastAtOrBefore(firstPhoto, photo)
        return position to photo - firstPhoto[position]
    }

    fun itemOf(fileId: Uuid): Int? = find(fileId) { position, slot -> firstItem[position] + 1 + slot }

    fun photoOf(fileId: Uuid): Int? = find(fileId) { position, slot -> firstPhoto[position] + slot }

    private inline fun find(fileId: Uuid, index: (Int, Int) -> Int): Int? {
        for (position in days.indices) {
            val slot = itemsOf(position)?.indexOfFirst { it.fileId == fileId } ?: continue
            if (slot >= 0) return index(position, slot)
        }
        return null
    }

    // A day without slots shares its first photo with the next day, which is the one a photo index means.
    private fun lastAtOrBefore(starts: IntArray, index: Int): Int {
        val found = starts.asList().binarySearch(index)
        if (found < 0) return (-found - 2).coerceAtLeast(0)
        var position = found
        while (position + 1 < starts.size && starts[position + 1] == index) position++
        return position
    }
}
