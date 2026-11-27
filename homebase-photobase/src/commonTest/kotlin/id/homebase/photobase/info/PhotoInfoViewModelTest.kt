package id.homebase.photobase.info

import id.homebase.api.image.ImageMetadata
import id.homebase.api.media.MediaLocation
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.galleryTestFile
import id.homebase.photobase.gallery.toGalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhotoInfoViewModelTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Reader(val camera: suspend () -> ImageMetadata?, val place: () -> String?, override val mapsOnline: Boolean = true) : PhotoInfoReader {
        var placeReads = 0
        override suspend fun camera(item: PhotoGalleryItem) = camera()
        override suspend fun place(latitude: Double, longitude: Double): String? = place().also { placeReads++ }
        override suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray? = null
    }

    private fun item(content: String?) = galleryTestFile(1).let { it.copy(fileMetadata = it.fileMetadata.copy(appData = it.fileMetadata.appData.copy(content = content))) }.toGalleryItem()!!

    private val located = """{"v":1,"latitude":37.7749,"longitude":-122.4194}"""

    @Test
    fun theCameraFailingLeavesThePlace() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = PhotoInfoViewModel(Reader(camera = { error("offline") }, place = { "San Francisco" }))

        viewModel.show(item(located))

        assertEquals(InfoPart.Failed, viewModel.uiState.value.camera)
        assertEquals(InfoPart.Read("San Francisco"), viewModel.uiState.value.place)
        assertEquals(MediaLocation(37.7749, -122.4194), viewModel.uiState.value.location)
    }

    @Test
    fun thePlaceFailingLeavesTheCamera() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val exif = ImageMetadata(cameraModel = "Pixel 9", iso = 100)
        val viewModel = PhotoInfoViewModel(Reader(camera = { exif }, place = { error("rate limited") }))

        viewModel.show(item(located))

        assertEquals(InfoPart.Read(exif), viewModel.uiState.value.camera)
        assertEquals(InfoPart.Failed, viewModel.uiState.value.place)
    }

    @Test
    fun aPhotoWithNoLocationLooksNoPlaceUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val reader = Reader(camera = { null }, place = { "anywhere" })
        val viewModel = PhotoInfoViewModel(reader)

        viewModel.show(item(null))

        assertNull(viewModel.uiState.value.location)
        assertEquals(0, reader.placeReads)
    }

    @Test
    fun mapsOfflineLooksNoPlaceUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val reader = Reader(camera = { null }, place = { "San Francisco" }, mapsOnline = false)
        val viewModel = PhotoInfoViewModel(reader)

        viewModel.show(item(located))

        assertEquals(InfoPart.Read(null), viewModel.uiState.value.place)
        assertEquals(0, reader.placeReads)
        assertEquals(MediaLocation(37.7749, -122.4194), viewModel.uiState.value.location, "its coordinates still show")
    }

    @Test
    fun closingStopsTheReadAndAnotherPhotoDropsThePreviousOnes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val never = CompletableDeferred<ImageMetadata?>()
        var cancelled = 0
        val viewModel = PhotoInfoViewModel(Reader(camera = {
            try {
                never.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }, place = { null }))
        val first = item(null)

        viewModel.show(first)
        val second = item(null)
        viewModel.show(second)
        assertEquals(second.fileId, viewModel.uiState.value.fileId)
        viewModel.close()

        assertNull(viewModel.uiState.value.fileId)
        assertEquals(2, cancelled, "the first photo's read when the second opened, the second's when the sheet closed")
    }
}
