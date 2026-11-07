package id.homebase.photobase.gallery

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotoGalleryStoreTest {

    /** Local records, newest first, and server pages by cursor. */
    private class Backend(
        var local: List<HomebaseFile> = emptyList(),
        val server: Map<String?, ServerPhotoPage> = emptyMap(),
        var coverage: GalleryCoverage = GalleryCoverage(),
    ) : PhotoGalleryBackend {
        val askedServer = mutableListOf<String?>()
        var serverFailure: Exception? = null

        override suspend fun coverage() = coverage

        override suspend fun saveCoverage(coverage: GalleryCoverage) {
            this.coverage = coverage
        }

        override suspend fun localPage(cursor: String?, floorMs: Long?, limit: Int): LocalPhotoPage {
            val below = cursor?.toLong() ?: Long.MAX_VALUE
            val matching = local.sortedByDescending { it.sqlUserDateMs() }
                .filter { it.sqlUserDateMs() < below && (floorMs == null || it.sqlUserDateMs() >= floorMs) }
            val page = matching.take(limit)
            return LocalPhotoPage(page, matching.size > limit, (page.lastOrNull()?.sqlUserDateMs() ?: below).toString())
        }

        override suspend fun serverPage(cursor: String?, limit: Int): ServerPhotoPage {
            serverFailure?.let { throw it }
            askedServer += cursor
            return server.getValue(cursor)
        }

        override suspend fun save(files: List<HomebaseFile>) {
            local = local + files
        }
    }

    private val noOldestFirst = PhotoGalleryPageSource { _, _ -> error("newest first must stay on the device") }

    private fun store(backend: Backend, oldestFirst: PhotoGalleryPageSource = noOldestFirst) = PhotoGalleryStore(backend, oldestFirst)

    private fun List<PhotoGalleryItem>.dates() = map { it.capturedAtMs }

    @Test
    fun theFirstOpenFetchesFromTheServerAndKeepsIt() = runTest {
        val backend = Backend(server = mapOf(null to ServerPhotoPage(listOf(photo(300), photo(200)), hasMore = true, cursor = "c1")))

        val page = store(backend).page(null, newestFirst = true)

        assertEquals(listOf(300L, 200L), page.items.dates())
        assertEquals(2, backend.local.size)
        assertEquals(GalleryCoverage(floorMs = 200, serverCursor = "c1"), backend.coverage)
    }

    @Test
    fun whatTheDeviceHoldsNeedsNoServer() = runTest {
        val backend = Backend(local = listOf(photo(300), photo(200)), coverage = GalleryCoverage(floorMs = 200, serverCursor = "c1"))

        val page = store(backend).page(null, newestFirst = true)

        assertEquals(listOf(300L, 200L), page.items.dates())
        assertTrue(backend.askedServer.isEmpty())
    }

    @Test
    fun scrollingPastWhatsHeldFetchesTheNextServerPage() = runTest {
        val backend = Backend(
            local = listOf(photo(300)),
            coverage = GalleryCoverage(floorMs = 300, serverCursor = "c1"),
            server = mapOf("c1" to ServerPhotoPage(listOf(photo(200), photo(100)), hasMore = true, cursor = "c2")),
        )
        val store = store(backend)

        val first = store.page(null, newestFirst = true)
        val second = store.page(first.nextCursor, newestFirst = true)

        assertEquals(listOf<String?>("c1"), backend.askedServer)
        assertEquals(listOf(200L, 100L), second.items.dates())
        assertEquals(100L, backend.coverage.floorMs)
        assertEquals("c2", backend.coverage.serverCursor)
    }

    /** The reason for coverage: a just-backed-up 2015 photo is local, and the years between aren't. */
    @Test
    fun anOldPhotoThatSyncedDownWaitsItsTurnInsteadOfSkippingTheYearsBetween() = runTest {
        val backend = Backend(
            local = listOf(photo(300), photo(10)),
            coverage = GalleryCoverage(floorMs = 300, serverCursor = "c1"),
            server = mapOf("c1" to ServerPhotoPage(listOf(photo(200), photo(10)), hasMore = false, cursor = null)),
        )
        val store = store(backend)

        val first = store.page(null, newestFirst = true)
        assertEquals(listOf(300L), first.items.dates())

        val second = store.page(first.nextCursor, newestFirst = true)
        assertEquals(200L, second.items.dates().first())
    }

    @Test
    fun theEndOfTheLibraryStopsAskingAndShowsEverythingHeld() = runTest {
        val backend = Backend(
            local = listOf(photo(300), photo(10)),
            coverage = GalleryCoverage(floorMs = 300, serverCursor = "c1", reachedTheEnd = true),
        )
        val store = store(backend)

        val page = store.page(null, newestFirst = true)

        assertEquals(listOf(300L, 10L), page.items.dates())
        assertNull(page.nextCursor)
        assertTrue(backend.askedServer.isEmpty())
    }

    @Test
    fun anEmptyLibraryEnds() = runTest {
        val backend = Backend(server = mapOf(null to ServerPhotoPage(emptyList(), hasMore = false, cursor = null)))

        val page = store(backend).page(null, newestFirst = true)

        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun beingOfflineFailsThePageButLeavesCoverageAlone() = runTest {
        val before = GalleryCoverage(floorMs = 300, serverCursor = "c1")
        val backend = Backend(coverage = before).also { it.serverFailure = IllegalStateException("offline") }

        assertFailsWith<IllegalStateException> { store(backend).page("100", newestFirst = true) }

        assertEquals(before, backend.coverage)
    }

    @Test
    fun oldestFirstGoesToTheServer() = runTest {
        val serverPage = PhotoGalleryPage(emptyList(), "n")
        val store = store(Backend(), PhotoGalleryPageSource { cursor, newestFirst ->
            assertEquals("x", cursor)
            assertEquals(false, newestFirst)
            serverPage
        })

        assertEquals(serverPage, store.page("x", newestFirst = false))
    }

    private fun photo(capturedAtMs: Long) = galleryTestFile(capturedAtMs)
}

internal fun galleryTestFile(capturedAtMs: Long, content: String? = null, fileId: Uuid = Uuid.random()) = HomebaseFile(
    fileId = fileId,
    driveId = PhotobaseProtocol.DriveAlias,
    fileState = FileState.Active,
    fileSystemType = FileSystemType.Standard,
    keyHeader = KeyHeader.newRandom16(),
    fileMetadata = FileMetadata(
        appData = AppFileMetaData(
            fileType = PhotobaseProtocol.PhotoFileType,
            dataType = PhotobaseProtocol.ImageDataType,
            userDate = capturedAtMs,
            content = content,
        ),
        payloads = listOf(PayloadDescriptor(key = PhotobaseProtocol.ImagePayloadKey, contentType = "image/jpeg")),
    ),
    serverMetadata = ServerMetadata(),
)
