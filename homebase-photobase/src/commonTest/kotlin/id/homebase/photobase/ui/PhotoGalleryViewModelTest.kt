package id.homebase.photobase.ui

import id.homebase.api.client.KeyHeader
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.PhotoGalleryPage
import id.homebase.photobase.gallery.PhotoGalleryPageSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class Pages(val pages: Map<String?, PhotoGalleryPage>) : PhotoGalleryPageSource {
        val asked = mutableListOf<String?>()
        var failure: Exception? = null

        val orders = mutableListOf<Boolean>()

        override suspend fun page(cursor: String?, newestFirst: Boolean): PhotoGalleryPage {
            asked += cursor
            orders += newestFirst
            failure?.let { throw it }
            return pages.getValue(cursor)
        }
    }

    private fun TestScope.viewModel(source: PhotoGalleryPageSource): PhotoGalleryViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return PhotoGalleryViewModel(source, dispatcher)
    }

    @Test
    fun theSortStartsNewestFirstAndAToggleReloadsTheOtherWay() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1_000), item(2_000)), null)))
        val viewModel = viewModel(source)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2_000L, 1_000L), viewModel.uiState.value.days.flatMap { it.items }.map { it.capturedAtMs })

        viewModel.onAction(PhotoGalleryUiAction.ToggleSort)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(true, false), source.orders)
        assertEquals(listOf(1_000L, 2_000L), viewModel.uiState.value.days.flatMap { it.items }.map { it.capturedAtMs })
    }

    @Test
    fun itShowsWhatsHeldThenCatchesUpAndWarmsTheCache() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1_000)), null)))
        var caughtUp = 0
        var warmed = 0
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel(
            source,
            StandardTestDispatcher(testScheduler),
            catchUp = {
                assertEquals(1, source.asked.size, "The grid must not wait on the catch-up")
                caughtUp++
            },
            warm = { warmed++ },
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, caughtUp)
        assertEquals(1, warmed)
        assertEquals(2, source.asked.size, "It reloads once caught up")
        assertEquals(1, viewModel.uiState.value.days.flatMap { it.items }.size)
    }

    @Test
    fun itSaysItsSyncingUntilTheCatchUpReturns() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(emptyList(), null)))
        val server = CompletableDeferred<Unit>()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel(source, StandardTestDispatcher(testScheduler), catchUp = { server.await() })
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSyncing, "An empty grid must not read as nothing backed up yet")

        server.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun backingUpMoreCatchesUpAgainWithoutTheProgressBar() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1_000)), null)))
        val backedUp = MutableStateFlow(0L)
        val syncingDuringCatchUp = mutableListOf<Boolean>()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        lateinit var viewModel: PhotoGalleryViewModel
        viewModel = PhotoGalleryViewModel(
            source,
            StandardTestDispatcher(testScheduler),
            catchUp = { syncingDuringCatchUp += viewModel.uiState.value.isSyncing },
            backedUpCount = backedUp,
        )
        testScheduler.advanceUntilIdle()

        backedUp.value = 1
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(true, false), syncingDuringCatchUp)
        assertEquals(3, source.asked.size, "The first page, then a reload after each catch-up")
    }

    @Test
    fun aCatchUpWhileAPhotoIsOpenWaitsForTheViewerToClose() = runTest {
        val photo = item(1_000)
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(photo), null)))
        val backedUp = MutableStateFlow(0L)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel(source, StandardTestDispatcher(testScheduler), catchUp = {}, backedUpCount = backedUp)
        testScheduler.advanceUntilIdle()
        val beforeOpen = source.asked.size

        viewModel.onAction(PhotoGalleryUiAction.Open(photo))
        backedUp.value = 1
        testScheduler.advanceUntilIdle()
        assertEquals(beforeOpen, source.asked.size, "Replacing the first page could drop the open photo")

        viewModel.onAction(PhotoGalleryUiAction.CloseViewer)
        testScheduler.advanceUntilIdle()
        assertEquals(beforeOpen + 1, source.asked.size)
    }

    @Test
    fun scrollingPausesTheWarmUntilItStops() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1_000)), null)))
        val paused = mutableListOf<Boolean>()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel(source, StandardTestDispatcher(testScheduler), pauseWarm = { paused += it })

        viewModel.onAction(PhotoGalleryUiAction.Scrolling(true))
        viewModel.onAction(PhotoGalleryUiAction.Scrolling(false))

        assertEquals(listOf(true, false), paused)
    }

    @Test
    fun aSortToggleDoesntCatchUp() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1_000)), null)))
        var caughtUp = 0
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel(source, StandardTestDispatcher(testScheduler), catchUp = { caughtUp++ })
        testScheduler.advanceUntilIdle()

        viewModel.onAction(PhotoGalleryUiAction.ToggleSort)
        viewModel.onAction(PhotoGalleryUiAction.ToggleSort)
        testScheduler.advanceUntilIdle()

        assertEquals(1, caughtUp)
    }

    @Test
    fun aFailedCatchUpLeavesTheGridAlone() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1_000)), null)))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhotoGalleryViewModel(
            source,
            StandardTestDispatcher(testScheduler),
            catchUp = { error("offline") },
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.days.flatMap { it.items }.size)
        assertEquals(false, viewModel.uiState.value.loadFailed)
    }

    @Test
    fun itQueriesOnOpenAndPagesForward() = runTest {
        val source = Pages(
            mapOf(
                null to PhotoGalleryPage(listOf(item(2_000)), "next"),
                "next" to PhotoGalleryPage(listOf(item(1_000)), null),
            ),
        )
        val viewModel = viewModel(source)
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.days.sumOf { it.items.size })
        assertFalse(viewModel.uiState.value.endReached)

        viewModel.onAction(PhotoGalleryUiAction.LoadMore)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.days.sumOf { it.items.size })
        assertTrue(viewModel.uiState.value.endReached)

        viewModel.onAction(PhotoGalleryUiAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf(null, "next"), source.asked)
    }

    @Test
    fun refreshStartsOverFromTheServer() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1)), null)))
        val viewModel = viewModel(source)
        advanceUntilIdle()

        viewModel.onAction(PhotoGalleryUiAction.Refresh)
        advanceUntilIdle()

        assertEquals(listOf<String?>(null, null), source.asked)
        assertEquals(1, viewModel.uiState.value.days.sumOf { it.items.size })
    }

    @Test
    fun aFailedQueryKeepsWhatIsShownAndSaysSo() = runTest {
        val source = Pages(mapOf(null to PhotoGalleryPage(listOf(item(1)), null)))
        val viewModel = viewModel(source)
        advanceUntilIdle()

        source.failure = IllegalStateException("offline")
        viewModel.onAction(PhotoGalleryUiAction.Refresh)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadFailed)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.days.sumOf { it.items.size })
    }

    @Test
    fun openingAPhotoUsesTheMediaViewerKeyedByFileId() = runTest {
        val photo = item(1)
        val viewModel = viewModel(Pages(mapOf(null to PhotoGalleryPage(listOf(photo), null))))
        advanceUntilIdle()

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
    fun swipingInTheViewerMovesWhatsOpen() = runTest {
        val first = item(2)
        val second = item(1)
        val viewModel = viewModel(Pages(mapOf(null to PhotoGalleryPage(listOf(first, second), null))))
        advanceUntilIdle()

        viewModel.onAction(PhotoGalleryUiAction.Open(first))
        viewModel.onAction(PhotoGalleryUiAction.Open(second))

        assertEquals(second.fileId, viewModel.uiState.value.openedFileId)
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
}
