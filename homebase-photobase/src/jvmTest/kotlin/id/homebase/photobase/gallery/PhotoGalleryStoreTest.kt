package id.homebase.photobase.gallery

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** Through the real upsert and the real local query: the tiny preview has to survive both. */
class PhotoGalleryStoreTest {
    private val db = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
    private val identity = Uuid.random()
    private val store = PhotoGalleryStore(db, { identity })

    @AfterTest
    fun close() = db.close()

    // As the drive sync writes them.
    private suspend fun synced(vararg files: HomebaseFile, driveId: Uuid = PhotobaseProtocol.DriveAlias) {
        MainIndexMetaHelpers.HomebaseFileProcessor(db).baseUpsertEntryZapZap(identity, driveId, files.toList(), cursor = null)
    }

    // Noon UTC, so every zone puts it on the same calendar date the day number names... give or take one, which the
    // day counts settle.
    private fun on(day: Int, minute: Int = 0) = day * 86_400_000L + 12 * 3_600_000L + minute * 60_000L

    private suspend fun dayOf(ms: Long) = assertNotNull(store.dayOf(ms))

    @Test
    fun theTinyPreviewAndTheNameSurviveTheLocalIndex() = runTest {
        val tiny = byteArrayOf(1, 2, 3, 4)
        val payload = PayloadDescriptor(
            key = PhotobaseProtocol.ImagePayloadKey,
            contentType = "image/jpeg",
            descriptorContent = DescriptorContentV1(kind = DescriptorContentV1.Kind.IMAGE, name = "IMG.jpg", original = 1).encode(),
            previewThumbnail = ThumbnailDescriptor(pixelWidth = 20, pixelHeight = 15, contentType = "image/webp", content = Base64Bytes(tiny)),
        )
        synced(galleryTestFile(on(20_000), payload = payload))
        val day = dayOf(on(20_000))

        val item = store.photosOnDays(day, day).getValue(day).single()
        assertContentEquals(tiny, item.payload.previewThumbnail?.content?.bytes)
        assertEquals(20, item.payload.previewThumbnail?.pixelWidth)
        assertEquals("IMG.jpg", item.payload.filename())
    }

    @Test
    fun photosComeByTheirDayNewestFirstAndOnlyForTheDaysAsked() = runTest {
        synced(galleryTestFile(on(20_000, 1)), galleryTestFile(on(20_000, 3)), galleryTestFile(on(20_000, 2)))
        synced(galleryTestFile(on(20_002)), galleryTestFile(on(20_004)))
        val first = dayOf(on(20_000))
        val middle = dayOf(on(20_002))

        val read = store.photosOnDays(first, middle)

        assertEquals(setOf(first, middle), read.keys, "the day after, inside the query's spare range, isn't returned")
        assertEquals(listOf(on(20_000, 3), on(20_000, 2), on(20_000, 1)), read.getValue(first).map { it.capturedAtMs })
        assertEquals(store.dayCounts().filter { it.day in first..middle }.associate { it.day to it.photos }, read.mapValues { it.value.size })
    }

    @Test
    fun forgettingTheDriveTakesItsRecordsAndSyncPositionAndNothingElse() = runTest {
        val otherDrive = Uuid.random()
        synced(galleryTestFile(on(20_000)), galleryTestFile(on(20_001)))
        synced(galleryTestFile(on(20_000)), driveId = otherDrive)
        val position = QueryBatchCursor.fromStartPoint(UnixTimeUtc(1_000_000_000_000))
        CursorStorage(db, PhotobaseProtocol.DriveAlias).saveCursor(position)
        CursorStorage(db, otherDrive).saveCursor(position)

        store.forget()

        assertEquals(emptyList(), store.dayCounts())
        assertNull(CursorStorage(db, PhotobaseProtocol.DriveAlias).loadCursor())
        assertEquals(1, PhotoGalleryStore(db, { identity }, otherDrive).dayCounts().single().photos)
        assertNotNull(CursorStorage(db, otherDrive).loadCursor())
    }
}
