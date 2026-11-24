package id.homebase.photobase.gallery

import kotlin.uuid.Uuid

/**
 * Where every day and photo of the library sits, in the grid's order: each day has a slot per photo. A day has as many
 * slots as the index counts, so reading it fills them without moving anything; [shown] gives the days that, once read,
 * show another number (a photo the gallery can't show, a count not caught up yet). [GalleryLines] lays them out.
 */
class GalleryLayout(library: LibraryDays, val newestFirst: Boolean, shown: Map<Int, Int> = emptyMap()) {
    /** The days, in the grid's order. */
    val days: IntArray
    private val slots: IntArray
    private val firstPhoto: IntArray
    val photoCount: Int

    init {
        val n = library.days.size
        days = IntArray(n) { library.days[if (newestFirst) it else n - 1 - it] }
        slots = IntArray(n) { shown[days[it]] ?: library.counts[if (newestFirst) it else n - 1 - it] }
        firstPhoto = IntArray(n)
        var photo = 0
        for (position in 0 until n) {
            firstPhoto[position] = photo
            photo += slots[position]
        }
        photoCount = photo
    }

    fun slotsOf(position: Int): Int = slots[position]

    /** The day and slot of the [photo]th photo: where it stays when photos arrive before it. */
    fun locate(photo: Int): Pair<Int, Int> {
        val position = firstPhoto.lastAtOrBefore(photo)
        return days[position] to photo - firstPhoto[position]
    }

    /** The photo index of a day's slot, held to the day's last; null once the day has none. */
    fun photoOf(day: Int, slot: Int): Int? = positionOf(day)?.let { firstPhoto[it] + slot.coerceAtMost(slots[it] - 1) }

    /** Where [day] is among the days, or null once it has no slots. */
    fun positionOf(day: Int): Int? {
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
        val position = firstPhoto.lastAtOrBefore(photo)
        return photoAt(position, photo - firstPhoto[position], loaded)
    }

    fun photoOf(fileId: Uuid, loaded: Map<Int, List<PhotoGalleryItem>>): Int? =
        placeOf(fileId, loaded)?.let { (position, slot) -> firstPhoto[position] + slot }

    /** The position of the day showing [fileId] and its slot there. */
    fun placeOf(fileId: Uuid, loaded: Map<Int, List<PhotoGalleryItem>>): Pair<Int, Int>? {
        for ((day, items) in loaded) {
            val at = items.indexOfFirst { it.fileId == fileId }
            if (at < 0) continue
            val position = positionOf(day) ?: return null
            val slot = if (newestFirst) at else items.size - 1 - at
            return if (slot < slots[position]) position to slot else null
        }
        return null
    }
}

/**
 * The last index whose value is at most [value], in an array rising or level, or 0 if none is. On a level run, its
 * last: a day without photos shares its first photo with the next day, which is the one a photo index means.
 */
internal fun IntArray.lastAtOrBefore(value: Int): Int {
    var low = 0
    var high = size - 1
    var found = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid] <= value) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}
