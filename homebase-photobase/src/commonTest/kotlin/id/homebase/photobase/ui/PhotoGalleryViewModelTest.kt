package id.homebase.photobase.ui

import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.sync.DriveState
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.PhotoGallerySource
import id.homebase.photobase.gallery.PhotoMover
import id.homebase.photobase.gallery.PhotoMoveResult
import id.homebase.photobase.gallery.PhotoMoveServer
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.photobase.gallery.PhotoAlbumSource
import id.homebase.photobase.gallery.PhotoAlbum
import id.homebase.photobase.gallery.PhotoShelf
import id.homebase.photobase.gallery.galleryTestFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PerFileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        var failOn: IntRange? = null
        var gate: CompletableDeferred<Unit>? = null
        val counts = MutableStateFlow<LibraryDays?>(null)
        val touched = MutableSharedFlow<Map<Int, List<HomebaseFile>>?>(extraBufferCapacity = 8)

        fun publish() {
            val days = photos.keys.sortedDescending()
            counts.value = LibraryDays(days.toIntArray(), days.map { photos.getValue(it).size }.toIntArray())
        }

        val source = PhotoGallerySource { from, to ->
            asked += from..to
            failure?.let { throw it }
            if (failOn == from..to) throw IllegalStateException("database busy")
            val snapshot = photos.toMap()
            gate?.await()
            snapshot.filterKeys { it in from..to }.filterValues { it.isNotEmpty() }
        }
    }

    private fun TestScope.viewModel(
        library: Library,
        driveState: MutableStateFlow<DriveState?> = MutableStateFlow(DriveState.Completed()),
        mover: PhotoMover = noMover(),
        gallery: PhotoGallery = PhotoGallery.Library,
    ): Pair<PhotoGalleryViewModel, Job> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        library.publish()
        val viewModel = PhotoGalleryViewModel(
            library.source,
            mover,
            gallery = gallery,
            driveState = driveState,
            libraryDays = library.counts.filterNotNull(),
            touchedDays = library.touched,
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

        // 80 photos either side is 8 more days each way.
        viewModel.onAction(PhotoGalleryUiAction.Showing(60, 58))
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
        viewModel.onAction(PhotoGalleryUiAction.Showing(60, 58))
        advanceUntilIdle()

        viewModel.onAction(PhotoGalleryUiAction.Showing(57, 55))
        advanceUntilIdle()

        assertEquals(listOf(92..100, 50..68, 47..49), library.asked)
        screen.cancel()
    }

    @Test
    fun aWindowWithHeldDaysInTheMiddleReadsTheRunsEitherSideOnly() = runTest {
        val library = Library(1..100, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.Showing(50, 50))
        advanceUntilIdle()

        viewModel.onAction(PhotoGalleryUiAction.Showing(70, 30))
        advanceUntilIdle()

        assertEquals(listOf(59..78, 22..41), library.asked.takeLast(2), "days 42..58 are held and aren't read again")
        screen.cancel()
    }

    @Test
    fun aPhotoReplacedOnAHeldDayIsReadAgainThoughItsCountStayed() = runTest {
        val library = Library(1..10, perDay = 3)
        val (viewModel, screen) = viewModel(library)
        library.asked.clear()

        val replacement = item(9 * DAY_MS + 99)
        library.photos[9] = listOf(replacement) + library.photos.getValue(9).drop(1)
        library.touched.emit(mapOf(9 to emptyList(), 42 to emptyList()))
        advanceUntilIdle()

        assertEquals(listOf(9..9), library.asked, "only the held day touched")
        assertEquals(replacement, viewModel.uiState.value.loaded.getValue(9).first())
        screen.cancel()
    }

    @Test
    fun aCountThatChangesWhileItsDayIsBeingReadReadsItAgain() = runTest {
        val library = Library(1..10, perDay = 3)
        val gate = CompletableDeferred<Unit>()
        library.gate = gate
        val (viewModel, screen) = viewModel(library)

        // The top read is waiting on the database when a photo lands on day 10.
        library.photos[10] = listOf(item(10 * DAY_MS + 99)) + library.photos.getValue(10)
        library.publish()
        library.touched.emit(mapOf(10 to emptyList()))
        advanceUntilIdle()
        library.gate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.loaded.getValue(10).size, "the read that began before the change isn't the last word")
        screen.cancel()
    }

    @Test
    fun aDayWithMoreThanAThousandPhotosIsKeptWhileItsShown() = runTest {
        val library = Library(1..1, perDay = 1_200)
        val (viewModel, screen) = viewModel(library)

        assertEquals(1_200, viewModel.uiState.value.loaded.getValue(1).size)
        screen.cancel()
    }

    @Test
    fun aFailedRunDoesntStopTheNextOne() = runTest {
        val library = Library(1..100, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.Showing(50, 50))
        advanceUntilIdle()
        library.failOn = 59..78

        viewModel.onAction(PhotoGalleryUiAction.Showing(70, 30))
        advanceUntilIdle()

        assertTrue(22 in viewModel.uiState.value.loaded, "the older run is read though the newer one failed")
        assertFalse(70 in viewModel.uiState.value.loaded)
        screen.cancel()
    }

    @Test
    fun aPhotoThatMovedDayIsHeldOnlyOnTheDayItWasReadOn() = runTest {
        val library = Library(1..10, perDay = 2)
        val (viewModel, screen) = viewModel(library)
        val moved = library.photos.getValue(5).first()

        // Its day changed (a new time zone, an edited date); day 5's count is topped up by another.
        library.photos[5] = listOf(item(5 * DAY_MS + 7)) + library.photos.getValue(5).drop(1)
        library.photos[6] = library.photos.getValue(6) + moved
        library.touched.emit(mapOf(6 to emptyList()))
        advanceUntilIdle()

        val holding = viewModel.uiState.value.loaded.filterValues { items -> items.any { it.fileId == moved.fileId } }.keys
        assertEquals(setOf(6), holding, "held twice, it would be two pages with one key in the viewer")
        screen.cancel()
    }

    @Test
    fun aFailedCountSaysSoAndRetryCountsAgain() = runTest {
        var recounts = 0
        val failed = MutableStateFlow(true)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, noMover(), countFailed = failed, recount = { recounts++ }, reading = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.countFailed)

        viewModel.onAction(PhotoGalleryUiAction.Retry)
        advanceUntilIdle()
        assertEquals(1, recounts)
    }

    @Test
    fun aBigDayTakesInArrivingPhotosWithoutBeingReadAgain() = runTest {
        val library = Library(1..1, perDay = 200)
        val (viewModel, screen) = viewModel(library)
        library.asked.clear()

        val arrived = id.homebase.photobase.gallery.galleryTestFile(DAY_MS + 500)
        library.touched.emit(mapOf(1 to listOf(arrived)))
        advanceUntilIdle()

        assertEquals(emptyList(), library.asked, "a backup adds to today every few seconds")
        val day = viewModel.uiState.value.loaded.getValue(1)
        assertEquals(201, day.size)
        assertEquals(arrived.fileId, day.first().fileId, "in its place, newest first")
        screen.cancel()
    }

    @Test
    fun aFileThatArrivesTwiceIsHeldOnceAsItsLastVersion() = runTest {
        val library = Library(1..1, perDay = 200)
        val (viewModel, screen) = viewModel(library)
        val first = id.homebase.photobase.gallery.galleryTestFile(DAY_MS + 500)
        val second = id.homebase.photobase.gallery.galleryTestFile(DAY_MS + 600, fileId = first.fileId)

        library.touched.emit(mapOf(1 to listOf(first, second)))
        advanceUntilIdle()

        val held = viewModel.uiState.value.loaded.getValue(1).filter { it.fileId == first.fileId }
        assertEquals(listOf(DAY_MS + 600), held.map { it.capturedAtMs }, "two pages with one key would crash the viewer")
        screen.cancel()
    }

    @Test
    fun aFullCountReadsSmallHeldDaysAgainButNotABigOneThatStillHoldsItsCount() = runTest {
        val library = Library(1..2, perDay = 160)
        library.photos[1] = library.photos.getValue(1).take(3)
        val (viewModel, screen) = viewModel(library)
        library.asked.clear()

        library.touched.emit(null)
        advanceUntilIdle()

        assertEquals(listOf(1..1), library.asked, "every reconnect would parse the big day in full again")
        screen.cancel()
    }

    @Test
    fun aFailedReadIsTriedAgainOnRefreshNotOnATimer() = runTest {
        val library = Library(1..10, perDay = 3)
        library.failure = IllegalStateException("database busy")
        val (viewModel, screen) = viewModel(library)
        val asked = library.asked.size

        library.failure = null
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(asked, library.asked.size, "nothing polls")

        viewModel.onAction(PhotoGalleryUiAction.Refresh)
        advanceUntilIdle()
        assertTrue(10 in viewModel.uiState.value.loaded)
        screen.cancel()
    }

    @Test
    fun theOpenPhotosDayIsNeverLetGo() = runTest {
        val library = Library(1..400, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        val open = library.photos.getValue(400).first()
        viewModel.onAction(PhotoGalleryUiAction.Open(open))
        for (start in 20..380 step 20) {
            viewModel.onAction(PhotoGalleryUiAction.Showing(400 - start, 400 - start - 2))
            advanceUntilIdle()
        }

        assertTrue(400 in viewModel.uiState.value.loaded, "letting it go would close the viewer on the photo it shows")
        screen.cancel()
    }

    @Test
    fun oldestFirstReadsTheOldestDaysTheScreenShows() = runTest {
        val library = Library(1..100, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.ToggleSort)
        // Oldest first, the screen's first and last days come the other way round.
        viewModel.onAction(PhotoGalleryUiAction.Showing(1, 1))
        advanceUntilIdle()

        assertEquals(listOf(92..100, 1..9), library.asked)
        screen.cancel()
    }

    @Test
    fun itHoldsAThousandPhotosAndLetsTheFarthestDaysGo() = runTest {
        val library = Library(1..400, perDay = 10)
        val (viewModel, screen) = viewModel(library)
        for (start in 0..380 step 20) {
            viewModel.onAction(PhotoGalleryUiAction.Showing(400 - start, 400 - start - 2))
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
        viewModel.onAction(PhotoGalleryUiAction.Showing(10, 8))
        advanceUntilIdle()
        library.asked.clear()

        val arrived = item(9 * DAY_MS + 50)
        library.photos[9] = listOf(arrived) + library.photos.getValue(9)
        library.failure = IllegalStateException("database busy")
        // As the index does: a recount publishes the counts and touches the day.
        library.publish()
        library.touched.emit(mapOf(9 to emptyList()))
        runCurrent()
        assertEquals(3, viewModel.uiState.value.loaded.getValue(9).size, "an open photo on that day mustn't vanish while it's read")

        // The next change reads it again; nothing polls.
        library.failure = null
        library.touched.emit(mapOf(9 to emptyList()))
        advanceUntilIdle()
        assertEquals(listOf(9..9, 9..9), library.asked, "only the day that changed, again after the failure")
        assertEquals(arrived, viewModel.uiState.value.loaded.getValue(9).first())
        screen.cancel()
    }

    @Test
    fun aDayThatEmptiedGoes() = runTest {
        val library = Library(1..10, perDay = 3)
        val (viewModel, screen) = viewModel(library)
        viewModel.onAction(PhotoGalleryUiAction.Showing(10, 8))
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
        viewModel.onAction(PhotoGalleryUiAction.Showing(10, 9))
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
        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, noMover(), libraryDays = days, reading = StandardTestDispatcher(testScheduler))
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
        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, noMover(), refresh = { syncs++ }, reading = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(0, syncs)

        viewModel.onAction(PhotoGalleryUiAction.Refresh)
        assertEquals(1, syncs)
    }

    @Test
    fun openingAPhotoUsesTheMediaViewerKeyedByFileId() = runTest {
        val photo = item(1)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, noMover(), reading = StandardTestDispatcher(testScheduler))

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

    /** Answers every write with [failure] thrown, or as done; [status] is where each photo it moved is now. */
    private class Server : PhotoMoveServer {
        val moved = mutableListOf<Pair<ArchivalStatus, List<Uuid>>>()
        val status = mutableMapOf<Uuid, ArchivalStatus>()
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>): List<PerFileResult> {
            failure?.let { throw it }
            gate?.await()
            moved += status to files.map { it.fileId }
            files.forEach { this.status[it.fileId] = status }
            return files.map { PerFileResult(it.fileId) }
        }

        override suspend fun softDelete(files: List<FileVersionTag>) = files.map { PerFileResult(it.fileId) }

        val hearts = mutableMapOf<Uuid, Boolean>()
        val tagged = mutableMapOf<Pair<Uuid, Uuid>, Boolean>()

        override suspend fun setLocalTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>): List<PerFileResult> {
            failure?.let { throw it }
            fileIds.forEach { if (tag == PhotobaseProtocol.FavoriteTag) hearts[it] = on else tagged[tag to it] = on }
            return fileIds.map { PerFileResult(it) }
        }

        override suspend fun header(fileId: Uuid): HomebaseFile? = null
    }

    private class Albums : PhotoAlbumSource {
        override val albums = MutableStateFlow(emptyList<PhotoAlbum>())
        val made = mutableListOf<Uuid>()
        val renamed = mutableListOf<Pair<Uuid, String>>()
        val deleted = mutableListOf<Uuid>()
        var failure: Exception? = null

        override suspend fun create(name: String): Uuid {
            failure?.let { throw it }
            return Uuid.random().also { made += it; albums.value += PhotoAlbum(it, name) }
        }

        override suspend fun rename(id: Uuid, name: String) {
            renamed += id to name
        }

        override suspend fun delete(id: Uuid) {
            deleted += id
        }
    }

    private fun noMover(server: Server = Server()) = PhotoMover(server, filesOf = { emptyList() }, binned = { emptyList() }, sync = {})

    private fun TestScope.moverViewModel(
        library: Library,
        server: Server,
        shelf: PhotoShelf = PhotoShelf.Library,
        gallery: PhotoGallery = PhotoGallery.OnShelf(shelf),
        albums: PhotoAlbumSource? = null,
    ): Pair<PhotoGalleryViewModel, Job> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        library.publish()
        val mover = PhotoMover(
            server = server,
            // The device holds every photo it's asked for, synced at once.
            filesOf = { ids -> ids.map { id -> galleryTestFile(0, fileId = id).onShelf(server.status[id] ?: shelf.status) } },
            binned = { emptyList() },
            sync = {},
        )
        val viewModel = PhotoGalleryViewModel(
            library.source,
            mover,
            gallery = gallery,
            libraryDays = library.counts.filterNotNull(),
            touchedDays = library.touched,
            reading = StandardTestDispatcher(testScheduler),
            albumSource = albums,
        )
        val screen = launch { viewModel.library.collect { } }
        advanceUntilIdle()
        return viewModel to screen
    }

    @Test
    fun aPhotoIsPickedAndLetGoAndADayAllAtOnce() = runTest {
        val library = Library(1..3, perDay = 4)
        val (viewModel, screen) = viewModel(library)
        val photo = library.photos.getValue(3).first()

        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo.fileId))
        assertEquals(setOf(photo.fileId), viewModel.uiState.value.selected)
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo.fileId))
        assertFalse(viewModel.uiState.value.selecting)

        val day = library.photos.getValue(3).mapTo(HashSet()) { it.fileId }
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo.fileId))
        viewModel.onAction(PhotoGalleryUiAction.ToggleDay(3))
        assertEquals(day, viewModel.uiState.value.selected, "a day partly picked is picked whole")
        viewModel.onAction(PhotoGalleryUiAction.ToggleDay(3))
        assertEquals(emptySet(), viewModel.uiState.value.selected, "and a day picked whole is let go")
        screen.cancel()
    }

    @Test
    fun movingToTheBinLetsTheSelectionGoAndOffersUndo() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val picked = library.photos.getValue(3).take(2).mapTo(HashSet()) { it.fileId }
        picked.forEach { viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(it)) }

        viewModel.onAction(PhotoGalleryUiAction.MoveSelected(PhotoShelf.Bin))
        assertFalse(viewModel.uiState.value.selecting, "at once, not when the server answers")
        advanceUntilIdle()

        assertEquals(listOf(ArchivalStatus.Removed to picked), server.moved.map { it.first to it.second.toSet() })
        val done = assertIs<PhotoGalleryEvent.Moved>(events.single())
        assertEquals(PhotoMoveResult(done = 2, written = picked), done.result)
        assertEquals(PhotoGalleryUiAction.Undo(picked, from = PhotoShelf.Bin, to = PhotoShelf.Library), done.undo)
        assertFalse(viewModel.uiState.value.busy)

        viewModel.onAction(done.undo!!)
        advanceUntilIdle()
        assertEquals(ArchivalStatus.None, server.moved.last().first)
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun aMoveThatDoesntGetThroughGivesTheSelectionBack() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server().apply { failure = IllegalStateException("offline") }
        val (viewModel, screen) = moverViewModel(library, server)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val photo = library.photos.getValue(3).first().fileId
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo))

        viewModel.onAction(PhotoGalleryUiAction.MoveSelected(PhotoShelf.Bin))
        advanceUntilIdle()

        assertEquals(setOf(photo), viewModel.uiState.value.selected)
        assertEquals(PhotoGalleryEvent.MoveFailed(MoveAction.MoveToBin), events.single())
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun theOpenPhotoMovesToTheBin() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val open = library.photos.getValue(3).first()

        viewModel.onAction(PhotoGalleryUiAction.MovePhotoToBin(open.fileId))
        advanceUntilIdle()

        assertEquals(listOf(ArchivalStatus.Removed to listOf(open.fileId)), server.moved)
        screen.cancel()
    }

    @Test
    fun aBigDayTakesInOnlyThePhotosOfItsOwnShelf() = runTest {
        val library = Library(1..1, perDay = 200)
        val (viewModel, screen) = moverViewModel(library, Server(), shelf = PhotoShelf.Bin)
        val binned = galleryTestFile(DAY_MS + 500).onShelf(ArchivalStatus.Removed)
        val restored = galleryTestFile(DAY_MS + 600)

        library.touched.emit(mapOf(1 to listOf(binned, restored)))
        advanceUntilIdle()

        val held = viewModel.uiState.value.loaded.getValue(1).map { it.fileId }
        assertTrue(binned.fileId in held)
        assertFalse(restored.fileId in held, "a photo back on the library leaves the bin")
        screen.cancel()
    }

    @Test
    fun anUndoTappedWhileAnotherActionRunsWaitsForItInsteadOfBeingDropped() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val first = library.photos.getValue(3)[0].fileId
        val second = library.photos.getValue(3)[1].fileId
        viewModel.onAction(PhotoGalleryUiAction.MovePhotoToBin(first))
        advanceUntilIdle()

        server.gate = CompletableDeferred()
        viewModel.onAction(PhotoGalleryUiAction.MovePhotoToBin(second))
        runCurrent()
        assertTrue(viewModel.uiState.value.busy)
        viewModel.onAction(PhotoGalleryUiAction.Undo(setOf(first), from = PhotoShelf.Bin, to = PhotoShelf.Library))
        server.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(ArchivalStatus.Removed, ArchivalStatus.Removed, ArchivalStatus.None), server.moved.map { it.first })
        assertEquals(listOf(first), server.moved.last().second)
        assertFalse(viewModel.uiState.value.busy)
        screen.cancel()
    }

    @Test
    fun archivingOffersUndoAndAnUndoOffersNone() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val photo = library.photos.getValue(3).first().fileId
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo))

        viewModel.onAction(PhotoGalleryUiAction.MoveSelected(PhotoShelf.Archive))
        advanceUntilIdle()
        val archived = assertIs<PhotoGalleryEvent.Moved>(events.removeLast())
        assertEquals(MoveAction.Archive, archived.action)
        assertEquals(ArchivalStatus.Archived, server.status[photo])

        viewModel.onAction(archived.undo!!)
        advanceUntilIdle()
        val undone = assertIs<PhotoGalleryEvent.Moved>(events.removeLast())
        assertEquals(MoveAction.Unarchive, undone.action)
        assertNull(undone.undo)
        assertEquals(ArchivalStatus.None, server.status[photo])
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun restoringFromTheBinOffersUndoBackToTheBin() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server, shelf = PhotoShelf.Bin)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val photo = library.photos.getValue(3).first().fileId
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo))

        viewModel.onAction(PhotoGalleryUiAction.MoveSelected(PhotoShelf.Library))
        advanceUntilIdle()
        val restored = assertIs<PhotoGalleryEvent.Moved>(events.single())
        assertEquals(MoveAction.Restore, restored.action)
        assertEquals(PhotoGalleryUiAction.Undo(setOf(photo), from = PhotoShelf.Library, to = PhotoShelf.Bin), restored.undo)
        assertEquals(ArchivalStatus.None, server.status[photo])
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun undoingAMoveToTheBinFromTheArchivePutsThePhotoBackInTheArchive() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server, shelf = PhotoShelf.Archive)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val photo = library.photos.getValue(3).first().fileId

        viewModel.onAction(PhotoGalleryUiAction.MovePhotoToBin(photo))
        advanceUntilIdle()
        assertEquals(ArchivalStatus.Removed, server.status[photo])
        viewModel.onAction(assertIs<PhotoGalleryEvent.Moved>(events.single()).undo!!)
        advanceUntilIdle()

        assertEquals(ArchivalStatus.Archived, server.status[photo])
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun heartingTheSelectionShowsTheHeartsAtOnceAndUndoTakesThemAway() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val picked = library.photos.getValue(3).take(2).mapTo(HashSet()) { it.fileId }
        picked.forEach { viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(it)) }
        assertFalse(viewModel.uiState.value.selectedAreFavorites())

        viewModel.onAction(PhotoGalleryUiAction.SetFavorite(true))
        advanceUntilIdle()

        assertEquals(picked.associateWith { true }, server.hearts)
        val hearted = viewModel.uiState.value.loaded.getValue(3).filter { it.favorite }.mapTo(HashSet()) { it.fileId }
        assertEquals(picked, hearted, "before the sync brings them back")
        val done = assertIs<PhotoGalleryEvent.Moved>(events.single())
        assertEquals(MoveAction.Favorite, done.action)
        assertEquals(PhotoGalleryUiAction.UndoTag(PhotobaseProtocol.FavoriteTag, picked, on = false), done.undo)

        viewModel.onAction(done.undo!!)
        advanceUntilIdle()
        assertEquals(picked.associateWith { false }, server.hearts)
        assertTrue(viewModel.uiState.value.loaded.getValue(3).none { it.favorite })
        assertNull(assertIs<PhotoGalleryEvent.Moved>(events.last()).undo)
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun undoingAHeartOnAMixedSelectionLeavesThePhotosThatWereFavoritesBefore() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val (before, added) = library.photos.getValue(3).take(2).map { it.fileId }
        viewModel.onAction(PhotoGalleryUiAction.SetFavorite(true, fileId = before))
        advanceUntilIdle()
        events.clear()
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(before))
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(added))

        viewModel.onAction(PhotoGalleryUiAction.SetFavorite(true))
        advanceUntilIdle()

        assertEquals(PhotoGalleryUiAction.UndoTag(PhotobaseProtocol.FavoriteTag, setOf(added), on = false), assertIs<PhotoGalleryEvent.Moved>(events.single()).undo)
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun theViewersHeartTakesOnlyItsPhotoAndLeavesTheSelection() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val (open, picked) = library.photos.getValue(3).take(2).map { it.fileId }
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(picked))

        viewModel.onAction(PhotoGalleryUiAction.SetFavorite(true, fileId = open))
        advanceUntilIdle()

        assertEquals(mapOf(open to true), server.hearts)
        assertEquals(setOf(picked), viewModel.uiState.value.selected)
        screen.cancel()
    }

    @Test
    fun selectionIsAllFavoritesOnlyWhenEveryPickedPhotoHeldIsOne() = runTest {
        val library = Library(1..3, perDay = 4)
        val (viewModel, screen) = moverViewModel(library, Server())
        val (a, b) = library.photos.getValue(3).take(2).map { it.fileId }
        viewModel.onAction(PhotoGalleryUiAction.SetFavorite(true, fileId = a))
        advanceUntilIdle()

        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(a))
        assertTrue(viewModel.uiState.value.selectedAreFavorites())
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(b))
        assertFalse(viewModel.uiState.value.selectedAreFavorites())
        screen.cancel()
    }

    @Test
    fun addingToAnAlbumShowsItsTagAtOnceAndUndoTakesOffOnlyWhatItAdded() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = moverViewModel(library, server)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val album = Uuid.random()
        val (before, added) = library.photos.getValue(3).take(2).map { it.fileId }
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(before))
        viewModel.onAction(PhotoGalleryUiAction.AddSelectedToAlbum(album))
        advanceUntilIdle()
        events.clear()
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(before))
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(added))

        viewModel.onAction(PhotoGalleryUiAction.AddSelectedToAlbum(album))
        advanceUntilIdle()

        assertEquals(mapOf((album to before) to true, (album to added) to true), server.tagged)
        assertEquals(setOf(before, added), viewModel.uiState.value.loaded.getValue(3).filter { album in it.tags }.mapTo(HashSet()) { it.fileId })
        assertEquals(emptySet(), viewModel.uiState.value.selected)
        val done = assertIs<PhotoGalleryEvent.Moved>(events.single())
        assertEquals(MoveAction.AddToAlbum, done.action)
        assertEquals(PhotoGalleryUiAction.UndoTag(album, setOf(added), on = false), done.undo)
        viewModel.onAction(done.undo!!)
        advanceUntilIdle()
        assertEquals(false, server.tagged[album to added])
        assertEquals(true, server.tagged[album to before])
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun aNewAlbumIsMadeThenTheSelectionGoesInUnderItsId() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val albums = Albums()
        val (viewModel, screen) = moverViewModel(library, server, albums = albums)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val picked = library.photos.getValue(3).take(2).mapTo(HashSet()) { it.fileId }
        picked.forEach { viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(it)) }

        viewModel.onAction(PhotoGalleryUiAction.AddSelectedToNewAlbum("Summer"))
        advanceUntilIdle()

        val made = albums.made.single()
        assertEquals("Summer", albums.albums.value.single().name)
        assertEquals(picked.associate { (made to it) to true }, server.tagged)
        assertEquals(PhotoGalleryUiAction.UndoTag(made, picked, on = false), assertIs<PhotoGalleryEvent.Moved>(events.single()).undo)
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun anAlbumThatCantBeMadeGivesTheSelectionBack() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val albums = Albums().apply { failure = IllegalStateException("offline") }
        val (viewModel, screen) = moverViewModel(library, server, albums = albums)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val photo = library.photos.getValue(3).first().fileId
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo))

        viewModel.onAction(PhotoGalleryUiAction.AddSelectedToNewAlbum("Summer"))
        advanceUntilIdle()

        assertEquals(PhotoGalleryEvent.MoveFailed(MoveAction.AddToAlbum), events.single())
        assertEquals(setOf(photo), viewModel.uiState.value.selected)
        assertEquals(emptyMap(), server.tagged)
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun anAlbumTakesOutItsSelectionAndRenamesAndDeletesItself() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val albums = Albums()
        val album = PhotoGallery.Album(Uuid.random())
        val (viewModel, screen) = moverViewModel(library, server, gallery = album, albums = albums)
        val events = mutableListOf<PhotoGalleryEvent>()
        val listening = launch { viewModel.events.collect { events += it } }
        val photo = library.photos.getValue(3).first().fileId
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo))

        viewModel.onAction(PhotoGalleryUiAction.RemoveSelectedFromAlbum)
        viewModel.onAction(PhotoGalleryUiAction.RenameAlbum("Winter"))
        viewModel.onAction(PhotoGalleryUiAction.DeleteAlbum)
        advanceUntilIdle()

        assertEquals(mapOf((album.id to photo) to false), server.tagged)
        assertEquals(listOf(album.id to "Winter"), albums.renamed)
        assertEquals(listOf(album.id), albums.deleted)
        assertEquals(
            listOf(MoveAction.RemoveFromAlbum, MoveAction.RenameAlbum, MoveAction.DeleteAlbum),
            events.map { assertIs<PhotoGalleryEvent.Moved>(it).action },
        )
        listening.cancel()
        screen.cancel()
    }

    @Test
    fun aTaggedGalleryMovesNothingItsPhotosAreOnEitherShelf() = runTest {
        val library = Library(1..3, perDay = 4)
        val server = Server()
        val (viewModel, screen) = viewModel(library, mover = noMover(server), gallery = PhotoGallery.Favorites)
        val photo = library.photos.getValue(3).first().fileId
        viewModel.onAction(PhotoGalleryUiAction.ToggleSelected(photo))

        viewModel.onAction(PhotoGalleryUiAction.MoveSelected(PhotoShelf.Bin))
        viewModel.onAction(PhotoGalleryUiAction.MovePhotoToBin(photo))
        advanceUntilIdle()

        assertEquals(emptyList(), server.moved)
        assertEquals(setOf(photo), viewModel.uiState.value.selected, "nothing taken from the selection either")
        screen.cancel()
    }

    @Test
    fun daysAlreadyCountedWhenTheViewModelIsMadeDontFindItHalfBuilt() = runTest {
        // Delivered at once, as the day index does when it holds the days already, on another thread.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val days = flowOf(LibraryDays(intArrayOf(3), intArrayOf(1)))

        val viewModel = PhotoGalleryViewModel({ _, _ -> emptyMap() }, noMover(), libraryDays = days, reading = Dispatchers.Unconfined)

        assertEquals(listOf(3), viewModel.library.value?.days?.toList())
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

private fun HomebaseFile.onShelf(status: ArchivalStatus) = copy(
    fileMetadata = fileMetadata.copy(appData = fileMetadata.appData.copy(archivalStatus = status), versionTag = Uuid.random()),
)
