package id.homebase.photobase.ui

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.sync.DriveState
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.PhotoGallerySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PhotoGalleryViewModelTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun item(capturedAtMs: Long, contentType: String = "image/jpeg") = PhotoGalleryItem(
        fileId = Uuid.random(),
        driveId = Uuid.random(),
        capturedAtMs = capturedAtMs,
        keyHeader = KeyHeader.newRandom16(),
        payload = PayloadDescriptor(key = "photo_main", contentType = contentType, descriptorContent = DescriptorContentV1(kind = DescriptorContentV1.Kind.IMAGE, name = "IMG.jpg", original = 1).encode()),
    )

    /** A library of [days] (newest first) with [perDay] photos each, read the way the store reads it. */
    private inner class Library(days: IntRange, perDay: Int) {
        val photos = days.reversed().associateWith { day -> List(perDay) { item(day * DAY_MS + (perDay - it)) } }.toMutableMap()
        val asked = mutableListOf<IntRange>()
        var failure: Exception? = null
        val counts = MutableStateFlow<LibraryDays?>(null)

        fun publish() {
            val days = photos.keys.sortedDescending()
            counts.value = LibraryDays(days.toIntArray(), days.map { photos.getValue(it).size }.toIntArray())
        }

        val source = PhotoGallerySource { from, to ->
            asked += from..to
            failure?.let { throw it }
            photos.filterKeys { it in from..to }.filterValues { it.isNotEmpty() }
        }
    }

    private fun TestScope.viewModel(library: Library, driveState: MutableStateFlow<DriveState?> = MutableStateFlow(DriveState.Completed())): Pair<PhotoGalleryViewModel, Job> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        library.publish()
        val viewModel = PhotoGalleryViewModel(
            library.source,
            driveState = driveState,
            libraryDays = library.counts.filterNotNull(),
            reading = StandardTestDispatcher(testScheduler),
        )
        // The screen collects the days while it's shown.
        val screen = launch { viewModel.library.collect { } }
        advanceUntilIdle()
        return viewModel to screen
    }

    @Test
    fun theDaysOnScreenAndAMarginAroundThemAreRead() = runTest {
        val library = Library(1..100, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        assertEquals(listOf(92..100), library.asked, "the top is read as soon as the days are counted, where the gallery opens")
        library.asked.clear()

        // Positions 40..42 are days 60..58, newest first; 80 photos either side is 8 more days each way.
        viewModel.onAction(PhotoGalleryUiAction.Showing(40..42))
        advanceUntilIdle()

        assertEquals(listOf(50..68), library.asked)
        assertEquals((50..68).toSet() + (92..100), viewModel.uiState.value.loaded.keys)
        assertEquals(library.photos.getValue(60), viewModel.uiState.value.loaded.getValue(60))
        screen.cancel()
    }

    @Test
    fun scrollingOnReadsOnlyTheDaysNotHeldYet() = runTest {
        val library = Library(1..100, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.Showing(40..42))
        advanceUntilIdle()

        viewModel.onAction(PhotoGalleryUiAction.Showing(43..45))
        advanceUntilIdle()

        assertEquals(listOf(92..100, 50..68, 47..49), library.asked)
        screen.cancel()
    }

    @Test
    fun oldestFirstCountsPositionsFromTheOldestDay() = runTest {
        val library = Library(1..100, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.ToggleSort)
        advanceUntilIdle()

        assertEquals(listOf(92..100, 1..9), library.asked, "the top of the other order, without the screen saying so")
        screen.cancel()
    }

    @Test
    fun itHoldsAThousandPhotosAndLetsTheFarthestDaysGo() = runTest {
        val library = Library(1..400, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        for (start in 0..380 step 20) {
            viewModel.onAction(PhotoGalleryUiAction.Showing(start..start + 2))
            advanceUntilIdle()
        }

        val held = viewModel.uiState.value.loaded
        assertTrue(held.values.sumOf { it.size } <= 1_000)
        assertTrue(400 - 381 in held, "where the screen is now stays")
        assertFalse(400 in held, "the first days read, farthest away, went")
        screen.cancel()
    }

    @Test
    fun aDayWhoseCountChangesIsReadAgainAndKeepsItsPhotosUntilThen() = runTest {
        val library = Library(1..10, perDay = 3)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.Showing(0..2))
        advanceUntilIdle()
        library.asked.clear()

        val arrived = item(9 * DAY_MS + 50)
        library.photos[9] = listOf(arrived) + library.photos.getValue(9)
        library.failure = IllegalStateException("database busy")
        library.publish()
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.loaded.getValue(9).size, "an open photo on that day mustn't vanish while it's read")

        library.failure = null
        library.publish()
        advanceUntilIdle()
        assertEquals(listOf(9..9, 9..9), library.asked, "only the day that changed, again after the failure")
        assertEquals(arrived, viewModel.uiState.value.loaded.getValue(9).first())
        screen.cancel()
    }

    @Test
    fun aDayThatEmptiedGoes() = runTest {
        val library = Library(1..10, perDay = 3)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.Showing(0..2))
        advanceUntilIdle()

        library.photos.remove(10)
        library.publish()
        advanceUntilIdle()

        assertFalse(10 in viewModel.uiState.value.loaded)
        screen.cancel()
    }

    @Test
    fun aFailedReadLeavesThePlaceholdersAndTheNextScrollAsksAgain() = runTest {
        val library = Library(1..10, perDay = 3)
        library.failure = IllegalStateException("database closed")
        val (viewModel, screen) = viewModel(library)
        assertEquals(emptyMap(), viewModel.uiState.value.loaded)

        library.failure = null
        viewModel.onAction(PhotoGalleryUiAction.Showing(0..1))
        advanceUntilIdle()
        assertTrue(10 in viewModel.uiState.value.loaded)
        screen.cancel()
    }

    @Test
    fun itSaysItsSyncingWithTheCountAndOnlyAfterASyncedRoundIsAnEmptyGridEmpty() = runTest {
        val drive = MutableStateFlow<DriveState?>(null)
        val (viewModel, screen) = viewModel(Library(IntRange.EMPTY, perDay = 0), drive)
        assertFalse(viewModel.uiState.value.librarySynced, "Not mounted yet: an empty grid must not read as nothing backed up yet")

        drive.value = DriveState.Synchronizing(1_200)
        advanceUntilIdle()
        assertEquals(1_200, viewModel.uiState.value.syncedSoFar)

        drive.value = DriveState.Completed(1_200)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSyncing)
        assertTrue(viewModel.uiState.value.librarySynced)
        screen.cancel()
    }

    @Test
    fun theLibrarysDaysAreCountedFromOpeningAndStopFiveSecondsAfterTheScreenLeaves() = runTest {
        var collections = 0
        val days = flow { collections++; emit(LibraryDays(intArrayOf(20), intArrayOf(3))); awaitCancellation() }
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, libraryDays = days, reading = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(1, collections, "counting starts when the gallery opens, not a frame later")
        assertEquals(3, viewModel.library.value?.photos)

        val watching = launch { viewModel.library.collect { } }
        runCurrent()
        watching.cancel()
        advanceTimeBy(4_000)
        val shown = launch { viewModel.library.collect { } }
        runCurrent()
        assertEquals(1, collections, "back within 5 s: still counted")

        shown.cancel()
        advanceTimeBy(5_001)
        launch { viewModel.library.collect { } }.also { runCurrent() }.cancel()
        assertEquals(2, collections, "a gallery left under another screen stopped; coming back counts again")
    }

    @Test
    fun onlyRefreshAsksTheDriveToSync() = runTest {
        var syncs = 0
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, refresh = { syncs++ }, reading = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(0, syncs)

        viewModel.onAction(PhotoGalleryUiAction.Refresh)
        assertEquals(1, syncs)
    }

    @Test
    fun openingAPhotoUsesTheMediaViewerKeyedByFileId() = runTest {
        val photo = item(1)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, reading = StandardTestDispatcher(testScheduler))

        viewModel.onAction(PhotoGalleryUiAction.Open(photo))
        assertEquals(photo.fileId, viewModel.uiState.value.openedFileId)

        val viewer = assertIs<FullScreenOverlay.ViewMessageData>(photo.toViewer())
        assertEquals(photo.fileId, viewer.messageId)
        assertEquals(photo.fileId, viewer.fileId)
        assertEquals(photo.payload.key, viewer.selectedPayloadKey)
        assertEquals("IMG.jpg", viewer.title, "the name comes from the payload's descriptor")

        viewModel.onAction(PhotoGalleryUiAction.CloseViewer)
        assertNull(viewModel.uiState.value.openedFileId)
    }

    @Test
    fun anUntouchedOriginalVideoPlaysItsCopy() {
        val video = item(1, contentType = "video/quicktime")
        val withCopy = video.copy(playableCopy = video.payload.copy(key = "vcp_0000", contentType = "video/mp4"))

        assertEquals("vcp_0000", assertIs<FullScreenOverlay.VideoPlayerData>(withCopy.toViewer()).payloadKey)
    }

    @Test
    fun aVideoOpensInTheVideoPlayer() {
        val video = item(1, contentType = "video/mp4")

        assertEquals(video.payload.key, assertIs<FullScreenOverlay.VideoPlayerData>(video.toViewer()).payloadKey)
    }

    @Test
    fun aVideoDecryptsWithItsPayloadIvNotTheFiles() {
        val payloadIv = KeyHeader.newRandom16().iv
        val video = item(1, contentType = "video/mp4").let { it.copy(payload = it.payload.copy(iv = Base64Bytes(payloadIv))) }

        val keyHeader = assertIs<FullScreenOverlay.VideoPlayerData>(video.toViewer()).keyHeader

        assertContentEquals(payloadIv, keyHeader.iv)
        assertSame(video.keyHeader.aesKey, keyHeader.aesKey)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
