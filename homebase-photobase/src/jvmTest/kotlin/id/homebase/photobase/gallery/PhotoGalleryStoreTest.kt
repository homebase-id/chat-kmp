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

    @Test
    fun theTinyPreviewAndTheNameSurviveTheLocalIndex() = runTest {
        val tiny = byteArrayOf(1, 2, 3, 4)
        val payload = PayloadDescriptor(
            key = PhotobaseProtocol.ImagePayloadKey,
            contentType = "image/jpeg",
            descriptorContent = DescriptorContentV1(kind = DescriptorContentV1.Kind.IMAGE, name = "IMG.jpg", original = 1).encode(),
            previewThumbnail = ThumbnailDescriptor(pixelWidth = 20, pixelHeight = 15, contentType = "image/webp", content = Base64Bytes(tiny)),
        )
        synced(galleryTestFile(500, payload = payload))

        val item = store.page(null, newestFirst = true).items.single()
        assertContentEquals(tiny, item.payload.previewThumbnail?.content?.bytes)
        assertEquals(20, item.payload.previewThumbnail?.pixelWidth)
        assertEquals("IMG.jpg", item.payload.filename())
    }

    @Test
    fun pagesGoByCaptureDateEitherWay() = runTest {
        synced(galleryTestFile(100), galleryTestFile(300), galleryTestFile(200))

        assertEquals(listOf(300L, 200L, 100L), store.page(null, newestFirst = true).items.map { it.capturedAtMs })
        assertEquals(listOf(100L, 200L, 300L), store.page(null, newestFirst = false).items.map { it.capturedAtMs })
    }

    @Test
    fun pagingContinuesFromItsCursorToTheEnd() = runTest {
        synced(*(1..45L).map { galleryTestFile(it) }.toTypedArray())

        val first = store.page(null, newestFirst = true)
        val second = store.page(assertNotNull(first.nextCursor), newestFirst = true)

        assertEquals(galleryPageSize(null), first.items.size)
        assertEquals((5L downTo 1L).toList(), second.items.map { it.capturedAtMs })
        assertNull(second.nextCursor)
    }

    @Test
    fun forgettingTheDriveTakesItsRecordsAndSyncPositionAndNothingElse() = runTest {
        val otherDrive = Uuid.random()
        synced(galleryTestFile(100), galleryTestFile(200))
        synced(galleryTestFile(300), driveId = otherDrive)
        val position = QueryBatchCursor.fromStartPoint(UnixTimeUtc(1_000_000_000_000))
        CursorStorage(db, PhotobaseProtocol.DriveAlias).saveCursor(position)
        CursorStorage(db, otherDrive).saveCursor(position)

        store.forget()

        assertEquals(emptyList(), store.page(null, newestFirst = true).items)
        assertNull(CursorStorage(db, PhotobaseProtocol.DriveAlias).loadCursor())
        assertEquals(1, PhotoGalleryStore(db, { identity }, otherDrive).page(null, newestFirst = true).items.size)
        assertNotNull(CursorStorage(db, otherDrive).loadCursor())
    }
}
