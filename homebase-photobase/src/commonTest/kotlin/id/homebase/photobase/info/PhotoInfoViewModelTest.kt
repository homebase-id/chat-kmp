package id.homebase.photobase.info

import id.homebase.api.common.OdinId
import id.homebase.api.image.ImageMetadata
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.galleryTestFile
import id.homebase.photobase.gallery.toGalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoInfoViewModelTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Reader(val camera: () -> ImageMetadata?, val place: () -> String?) : PhotoInfoReader {
        var placeReads = 0
        override suspend fun camera(item: PhotoGalleryItem, owner: OdinId?) = camera()
        override suspend fun place(latitude: Double, longitude: Double): String? = place().also { placeReads++ }
        override suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray? = null
    }

    private fun item(content: String?) = galleryTestFile(1).let { it.copy(fileMetadata = it.fileMetadata.copy(appData = it.fileMetadata.appData.copy(content = content))) }.toGalleryItem()!!

    private val located = """{"v":1,"latitude":37.7749,"longitude":-122.4194}"""

    @Test
    fun theCameraFailingLeavesThePlace() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = PhotoInfoViewModel(item(located), owner = null, Reader(camera = { error("offline") }, place = { "San Francisco" }))

        assertEquals(InfoPart.Failed, viewModel.uiState.value.camera)
        assertEquals(InfoPart.Read("San Francisco"), viewModel.uiState.value.place)
    }

    @Test
    fun thePlaceFailingLeavesTheCamera() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val exif = ImageMetadata(cameraModel = "Pixel 9", iso = 100)
        val viewModel = PhotoInfoViewModel(item(located), owner = null, Reader(camera = { exif }, place = { error("rate limited") }))

        assertEquals(InfoPart.Read(exif), viewModel.uiState.value.camera)
        assertEquals(InfoPart.Failed, viewModel.uiState.value.place)
    }

    @Test
    fun aPhotoWithNoLocationLooksNoPlaceUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val reader = Reader(camera = { null }, place = { "anywhere" })
        val viewModel = PhotoInfoViewModel(item(null), owner = null, reader)

        assertEquals(InfoPart.Read(null), viewModel.uiState.value.place)
        assertEquals(0, reader.placeReads)
    }
}
