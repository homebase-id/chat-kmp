package id.homebase.photobase.gallery

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class PhotoGalleryDay(val date: LocalDate, val items: List<PhotoGalleryItem>)

/** In [newestFirst]'s order, days and the photos within them; a file on two pages because it changed between them shows once. */
fun groupByCaptureDay(
    items: List<PhotoGalleryItem>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    newestFirst: Boolean = true,
): List<PhotoGalleryDay> {
    val days = items.distinctBy { it.fileId }
        .groupBy { Instant.fromEpochMilliseconds(it.capturedAtMs).toLocalDateTime(zone).date }
        .map { (date, dayItems) ->
            PhotoGalleryDay(date, if (newestFirst) dayItems.sortedByDescending { it.capturedAtMs } else dayItems.sortedBy { it.capturedAtMs })
        }
    return if (newestFirst) days.sortedByDescending { it.date } else days.sortedBy { it.date }
}
