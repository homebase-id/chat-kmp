package id.homebase.photobase.ui

import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoLibraryViewModelTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun eachGalleryShowsItsPhotosOnceCountedInTheLibrarysOrder() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val favorites = MutableStateFlow<LibraryDays?>(null)
        val bin = MutableStateFlow<LibraryDays?>(LibraryDays(intArrayOf(3, 1), intArrayOf(2, 5)))
        val viewModel = PhotoLibraryViewModel(listOf(PhotoGallery.Favorites to favorites.filterNotNull(), PhotoGallery.Bin to bin.filterNotNull()))
        val shown = launch { viewModel.counts.collect { } }
        advanceUntilIdle()

        assertEquals(listOf(PhotoGallery.Favorites to null, PhotoGallery.Bin to 7), viewModel.counts.value.toList(), "not counted yet, then the bin's")
        favorites.value = LibraryDays(intArrayOf(2), intArrayOf(1))
        advanceUntilIdle()
        assertEquals(mapOf(PhotoGallery.Favorites to 1, PhotoGallery.Bin to 7), viewModel.counts.value)
        shown.cancel()
    }
}
