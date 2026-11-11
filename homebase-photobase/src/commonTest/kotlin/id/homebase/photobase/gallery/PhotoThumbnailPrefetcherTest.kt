package id.homebase.photobase.gallery

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PhotoThumbnailPrefetcherTest {
    private fun item(thumbnails: List<ThumbnailDescriptor>? = listOf(ThumbnailDescriptor(pixelWidth = 427, pixelHeight = 240), ThumbnailDescriptor(pixelWidth = 854, pixelHeight = 480))) = PhotoGalleryItem(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        capturedAtMs = 1,
        keyHeader = KeyHeader.newRandom16(),
        payload = PayloadDescriptor(key = "k", contentType = "image/jpeg", thumbnails = thumbnails),
        tinyPreview = null,
        name = null,
    )

    /** Pages of 100, forever. */
    private class EndlessPages : PhotoGalleryPageSource {
        val asked = mutableListOf<String?>()
        var next = 0

        override suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage {
            asked += cursor
            return PhotoGalleryPage(List(100) { pageItem() }, "c${next++}")
        }

        var pageItem: () -> PhotoGalleryItem = { error("set me") }
    }

    @Test
    fun itWalksPastTheFirstPageAndStopsAtTheNewest500() = runTest {
        val pages = EndlessPages().also { it.pageItem = { item() } }
        var fetched = 0
        val sizes = mutableSetOf<Int>()

        PhotoThumbnailPrefetcher(pages, { true }) { _, size ->
            fetched++
            sizes += size.pixelWidth
        }.prefetch()

        assertEquals(500, fetched)
        assertEquals(5, pages.asked.size)
        assertEquals(setOf(427), sizes, "Only the grid's size")
    }

    @Test
    fun itDoesNothingWhenItMayNotDownload() = runTest {
        val pages = EndlessPages()

        PhotoThumbnailPrefetcher(pages, { false }) { _, _ -> error("no downloads") }.prefetch()

        assertEquals(0, pages.asked.size)
    }

    @Test
    fun oneFailedThumbnailDoesntStopTheRest() = runTest {
        val pages = EndlessPages().also { it.pageItem = { item() } }
        var attempts = 0

        PhotoThumbnailPrefetcher(pages, { true }) { _, _ ->
            attempts++
            if (attempts == 1) error("bad")
        }.prefetch()

        assertEquals(500, attempts)
    }

    @Test
    fun leavingWifiPartWayStopsTheWalk() = runTest {
        val pages = EndlessPages().also { it.pageItem = { item() } }
        var checks = 0

        PhotoThumbnailPrefetcher(pages, { ++checks <= 2 }) { _, _ -> }.prefetch()

        assertEquals(2, pages.asked.size)
    }

    @Test
    fun itWaitsWhileTheGridScrollsAndCarriesOnAfter() = runTest {
        val pages = EndlessPages().also { it.pageItem = { item() } }
        var fetched = 0
        val prefetcher = PhotoThumbnailPrefetcher(pages, { true }) { _, _ -> fetched++ }

        prefetcher.pause(true)
        val walk = async(Dispatchers.Default) { prefetcher.prefetch() }
        withContext(Dispatchers.Default) { delay(100) }
        assertEquals(0, fetched)

        prefetcher.pause(false)
        walk.await()
        assertEquals(500, fetched)
    }

    @Test
    fun afterAFinishedWalkItDoesntWalkAgainUntilReset() = runTest {
        val pages = EndlessPages().also { it.pageItem = { item() } }
        val prefetcher = PhotoThumbnailPrefetcher(pages, { true }) { _, _ -> }

        prefetcher.prefetch()
        val first = pages.asked.size
        prefetcher.prefetch()
        assertEquals(first, pages.asked.size)

        prefetcher.reset()
        prefetcher.prefetch()
        assertEquals(first * 2, pages.asked.size)
    }
}
