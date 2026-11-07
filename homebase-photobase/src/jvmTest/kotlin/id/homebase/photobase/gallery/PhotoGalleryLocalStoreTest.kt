package id.homebase.photobase.gallery

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.photobase.backup.PhotoContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Through the real upsert and the real local query: the tiny preview has to survive both. */
class PhotoGalleryLocalStoreTest {
    private val db = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
    private val identity = Uuid.random()
    private val backend = DrivePhotoGalleryBackend(
        db,
        { identity },
        PhotoGalleryRepository(DriveQueryProvider(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }), CredentialsManager())),
    )

    @AfterTest
    fun close() = db.close()

    @Test
    fun aTinyPreviewSurvivesTheLocalIndex() = runTest {
        val tiny = byteArrayOf(1, 2, 3, 4)
        val content = OdinSystemSerializer.serialize(PhotoContent(name = "IMG", tiny = Base64Bytes(tiny), tinyWidth = 20, tinyHeight = 15, tinyType = "image/webp"))

        backend.save(listOf(galleryTestFile(500, content)))

        val item = backend.localPage(null, null, 10).files.single().toGalleryItem()!!
        assertContentEquals(tiny, item.tinyPreview?.content?.bytes)
        assertEquals(20, item.tinyPreview?.pixelWidth)
        assertEquals("IMG", item.name)
    }

    @Test
    fun localPagesGoNewestFirstByCaptureDateAndRespectTheFloor() = runTest {
        backend.save(listOf(galleryTestFile(100), galleryTestFile(300), galleryTestFile(200)))

        assertEquals(listOf(300L, 200L, 100L), backend.localPage(null, null, 10).files.map { it.sqlUserDateMs() })
        assertEquals(listOf(300L, 200L), backend.localPage(null, 200, 10).files.map { it.sqlUserDateMs() })
    }

    @Test
    fun localPagingContinuesFromItsCursor() = runTest {
        backend.save(listOf(galleryTestFile(100), galleryTestFile(300), galleryTestFile(200)))

        val first = backend.localPage(null, null, 2)
        val second = backend.localPage(first.cursor, null, 2)

        assertEquals(true, first.hasMore)
        assertEquals(listOf(100L), second.files.map { it.sqlUserDateMs() })
    }

    @Test
    fun coverageSurvivesARead() = runTest {
        val coverage = GalleryCoverage(floorMs = 42, serverCursor = "abc", reachedTheEnd = true)
        backend.saveCoverage(coverage)
        assertEquals(coverage, backend.coverage())
    }

    @Test
    fun aQuietFirstSyncStillLeavesACursorSoTheNextDoesntSkipAhead() = runTest {
        val drive = Uuid.random()
        val monday = UnixTimeUtc(1_000_000_000_000)

        seedPhotoSyncCursor(db, drive, monday)
        val seeded = CursorStorage(db, drive).loadCursor()

        seedPhotoSyncCursor(db, drive, UnixTimeUtc(2_000_000_000_000))
        assertEquals(seeded?.toJson(), CursorStorage(db, drive).loadCursor()?.toJson(), "Friday mustn't move Monday's position")
        assertEquals(QueryBatchCursor.fromStartPoint(UnixTimeUtc(monday.milliseconds - 86_400_000)).toJson(), seeded?.toJson())
    }
}
