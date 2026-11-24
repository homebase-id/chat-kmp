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

    /** The day and slot of the [photo]th photo: where it stays when photos arrive before it. */
    fun locate(photo: Int): Pair<Int, Int> {
        val position = lastAtOrBefore(firstPhoto, photo)
        return days[position] to photo - firstPhoto[position]
    }

    /** The photo index of a day's slot, held to the day's last; null once the day has none. */
    fun photoOf(day: Int, slot: Int): Int? = positionOf(day)?.let { firstPhoto[it] + slot.coerceAtMost(slots[it] - 1) }

    fun itemOf(day: Int, slot: Int): Int? = positionOf(day)?.let { firstItem[it] + 1 + slot.coerceAtMost(slots[it] - 1) }

    private fun positionOf(day: Int): Int? {
        val at = days.asList().binarySearch { if (newestFirst) day.compareTo(it) else it.compareTo(day) }
        return at.takeIf { it >= 0 && slots[it] > 0 }
    }

    /** The photo in a day's [slot], or null while its headers aren't read. */
    fun photoAt(position: Int, slot: Int, loaded: Map<Int, List<PhotoGalleryItem>>): PhotoGalleryItem? {
        val items = loaded[days[position]] ?: return null
        return items.getOrNull(if (newestFirst) slot else items.size - 1 - slot)
    }

    fun photoAt(photo: Int, loaded: Map<Int, List<PhotoGalleryItem>>): PhotoGalleryItem? {
        // Read while composing, so also for a page the layout no longer has, before the viewer closes.
        if (photo !in 0 until photoCount) return null
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
            val position = positionOf(day) ?: return null
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
