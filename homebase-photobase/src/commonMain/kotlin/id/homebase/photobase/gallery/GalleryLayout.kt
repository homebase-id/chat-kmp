package id.homebase.photobase.gallery

import kotlin.uuid.Uuid

/**
 * Where every day and photo of the library sits, in the grid's order: each day is a header item and then a slot per
 * photo. A day has as many slots as the index counts, so reading it fills them without moving anything; [shown] gives
 * the days that, once read, show another number (a photo the gallery can't show, a count not caught up yet).
 */
class GalleryLayout(library: LibraryDays, val newestFirst: Boolean, shown: Map<Int, Int> = emptyMap()) {
    /** The days, in the grid's order. */
    val days: IntArray
    private val slots: IntArray
    private val firstItem: IntArray
    private val firstPhoto: IntArray
    val photoCount: Int

    init {
        val n = library.days.size
        days = IntArray(n) { library.days[if (newestFirst) it else n - 1 - it] }
        slots = IntArray(n) { shown[days[it]] ?: library.counts[if (newestFirst) it else n - 1 - it] }
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
        photoCount = photo
    }

    fun slotsOf(position: Int): Int = slots[position]

    /** The day an item of the grid, header or photo, belongs to. */
    fun dayOfItem(item: Int): Int = days[lastAtOrBefore(firstItem, item)]

    fun dayOfPhoto(photo: Int): Int = days[lastAtOrBefore(firstPhoto, photo)]

    /** The grid item of the [photo]th photo. */
    fun itemOfPhoto(photo: Int): Int {
        val position = lastAtOrBefore(firstPhoto, photo)
        return firstItem[position] + 1 + photo - firstPhoto[position]
    }

    /** The photo in a day's [slot], or null while its headers aren't read. */
    fun photoAt(position: Int, slot: Int, loaded: Map<Int, List<PhotoGalleryItem>>): PhotoGalleryItem? {
        val items = loaded[days[position]] ?: return null
        return items.getOrNull(if (newestFirst) slot else items.size - 1 - slot)
    }

    fun photoAt(photo: Int, loaded: Map<Int, List<PhotoGalleryItem>>): PhotoGalleryItem? {
        val position = lastAtOrBefore(firstPhoto, photo)
        return photoAt(position, photo - firstPhoto[position], loaded)
    }

    fun itemOf(fileId: Uuid, loaded: Map<Int, List<PhotoGalleryItem>>): Int? =
        find(fileId, loaded) { position, slot -> firstItem[position] + 1 + slot }

    fun photoOf(fileId: Uuid, loaded: Map<Int, List<PhotoGalleryItem>>): Int? =
        find(fileId, loaded) { position, slot -> firstPhoto[position] + slot }

    private inline fun find(fileId: Uuid, loaded: Map<Int, List<PhotoGalleryItem>>, index: (Int, Int) -> Int): Int? {
        for ((day, items) in loaded) {
            val at = items.indexOfFirst { it.fileId == fileId }
            if (at < 0) continue
            val position = days.asList().binarySearch { if (newestFirst) day.compareTo(it) else it.compareTo(day) }
            if (position < 0) return null
            val slot = if (newestFirst) at else items.size - 1 - at
            return if (slot < slots[position]) index(position, slot) else null
        }
        return null
    }

    // A day without photos shares its first photo with the next day, which is the one a photo index means.
    private fun lastAtOrBefore(starts: IntArray, index: Int): Int {
        val found = starts.asList().binarySearch(index)
        if (found < 0) return (-found - 2).coerceAtLeast(0)
        var position = found
        while (position + 1 < starts.size && starts[position + 1] == index) position++
        return position
    }
}
