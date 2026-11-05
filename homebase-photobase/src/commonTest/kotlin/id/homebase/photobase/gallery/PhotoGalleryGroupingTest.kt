package id.homebase.photobase.gallery

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PhotoGalleryGroupingTest {
    private fun item(capturedAtMs: Long, id: Uuid = Uuid.random()) = PhotoGalleryItem(
        fileId = id,
        driveId = Uuid.random(),
        capturedAtMs = capturedAtMs,
        keyHeader = KeyHeader.newRandom16(),
        payload = PayloadDescriptor(key = "photo_main"),
        tinyPreview = null,
        name = null,
    )

    private val day1 = 1_767_225_600_000 // 2026-01-01T00:00Z
    private val day2 = day1 + 86_400_000

    @Test
    fun newestDayFirstAndNewestPhotoFirstWithinIt() {
        val days = groupByCaptureDay(listOf(item(day1 + 1), item(day2 + 1), item(day1 + 2)), TimeZone.UTC)

        assertEquals(listOf(LocalDate(2026, 1, 2), LocalDate(2026, 1, 1)), days.map { it.date })
        assertEquals(listOf(day1 + 2, day1 + 1), days[1].items.map { it.capturedAtMs })
    }

    @Test
    fun aFileOnTwoPagesShowsOnce() {
        val id = Uuid.random()
        val days = groupByCaptureDay(listOf(item(day1, id), item(day1, id)), TimeZone.UTC)

        assertEquals(1, days.single().items.size)
    }
}
