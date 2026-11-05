package id.homebase.photobase.gallery

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class PhotoGalleryDay(val date: LocalDate, val items: List<PhotoGalleryItem>)

/** Newest day first; a file on two pages because it changed between them shows once. */
fun groupByCaptureDay(items: List<PhotoGalleryItem>, zone: TimeZone = TimeZone.currentSystemDefault()): List<PhotoGalleryDay> =
    items.distinctBy { it.fileId }
        .groupBy { Instant.fromEpochMilliseconds(it.capturedAtMs).toLocalDateTime(zone).date }
        .map { (date, dayItems) -> PhotoGalleryDay(date, dayItems.sortedByDescending { it.capturedAtMs }) }
        .sortedByDescending { it.date }
