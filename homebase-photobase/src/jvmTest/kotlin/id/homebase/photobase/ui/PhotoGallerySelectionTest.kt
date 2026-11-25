package id.homebase.photobase.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.photobase.gallery.GalleryLayout
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.photobase.gallery.PhotoMover
import id.homebase.photobase.gallery.PhotoMoveServer
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PerFileResult
import id.homebase.photobase.gallery.galleryItem
import id.homebase.photobase.gallery.galleryTestFile
import id.homebase.photobase.gallery.newVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class PhotoGallerySelectionTest {
    private val day = 20_000
    private val photos = List(3) { galleryItem() }
    private val loaded = mapOf(day to photos)

    private class Recorder : Haptics {
        val events = mutableListOf<HapticEvent>()
        override fun perform(event: HapticEvent) {
            events += event
        }
    }

    /** The grid alone, picking as the view model would. */
    private fun ComposeUiTest.showGrid(actions: MutableList<PhotoGalleryUiAction>, haptics: Haptics) {
        val selected = mutableStateOf(emptySet<Uuid>())
        val layout = GalleryLayout(LibraryDays(intArrayOf(day), intArrayOf(photos.size)), newestFirst = true)
        setContent {
            SharedTransitionLayout {
                AnimatedVisibility(visible = true) {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        PhotoGrid(
                            layout, mutableStateOf(loaded), { action ->
                                actions += action
                                when (action) {
                                    is PhotoGalleryUiAction.ToggleSelected ->
                                        selected.value = if (action.fileId in selected.value) selected.value - action.fileId else selected.value + action.fileId
                                    is PhotoGalleryUiAction.ToggleDay -> selected.value = photos.mapTo(HashSet()) { it.fileId }
                                    else -> Unit
                                }
                            },
                            GridMemory(LazyListState()), this@SharedTransitionLayout, this@AnimatedVisibility, haptics,
                            selected = selected,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aLongPressPicksAPhotoAndThenATapPicksRatherThanOpens() = runComposeUiTest {
        val actions = mutableListOf<PhotoGalleryUiAction>()
        val haptics = Recorder()
        showGrid(actions, haptics)
        val tiles = onAllNodesWithContentDescription("Photo")

        tiles[0].performClick()
        assertEquals(PhotoGalleryUiAction.Open(photos[0]), actions.removeLast(), "nothing picked yet: a tap opens")

        tiles[0].performTouchInput { longClick() }
        assertEquals(PhotoGalleryUiAction.ToggleSelected(photos[0].fileId), actions.removeLast())
        assertEquals(listOf(HapticEvent.LongPress), haptics.events)

        tiles[1].performClick()
        assertEquals(PhotoGalleryUiAction.ToggleSelected(photos[1].fileId), actions.removeLast())
        onAllNodesWithContentDescription("Photo").filter(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(2)
        onAllNodesWithContentDescription("Photo").filter(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).assertCountEquals(1)
    }

    @Test
    fun whilePickingTheDaysHeaderPicksTheWholeDay() = runComposeUiTest {
        val actions = mutableListOf<PhotoGalleryUiAction>()
        showGrid(actions, Recorder())
        onAllNodesWithContentDescription("Photo")[0].performTouchInput { longClick() }

        onNode(SemanticsMatcher("picks the day") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Select this day" }).performClick()

        assertEquals(PhotoGalleryUiAction.ToggleDay(day), actions.last())
        onAllNodesWithContentDescription("Photo").filter(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(3)
    }

    @Test
    fun theToolbarComesWithPickingAndGoesWithClearing() = runComposeUiTest {
        // The view model's scope runs on Main, which this test doesn't pump.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val viewModel = PhotoGalleryViewModel(
            source = { _, _ -> loaded },
            mover = PhotoMover(NoServer, filesOf = { emptyList() }, binned = { emptyList() }, sync = {}),
            libraryDays = flowOf(LibraryDays(intArrayOf(day), intArrayOf(photos.size))),
        )
        setContent { PhotoGalleryScreen(viewModel, onOpenBackupSettings = null, backupIndicator = {}, haptics = Recorder()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Photo").fetchSemanticsNodes().size == photos.size }

        onAllNodesWithContentDescription("Photo")[0].performTouchInput { longClick() }

        onNodeWithText("1 selected").assertExists()
        onNodeWithText("Move to bin").assertExists()
        onNodeWithText("Archive").assertExists()
        onNodeWithContentDescription("Clear selection").performClick()
        onNodeWithText("Move to bin").assertDoesNotExist()
        assertEquals(PhotoGallery.Library, viewModel.gallery)
    }

    @Test
    fun moveToBinAsksFirstAndCancelKeepsTheSelection() = runComposeUiTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val moved = mutableListOf<Uuid>()
        val server = object : PhotoMoveServer by NoServer {
            override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>): List<PerFileResult> {
                moved += files.map { it.fileId }
                return files.map { PerFileResult(it.fileId) }
            }
        }
        val viewModel = PhotoGalleryViewModel(
            source = { _, _ -> loaded },
            mover = PhotoMover(server, filesOf = { ids -> ids.map { galleryTestFile(0, fileId = it).newVersion() } }, binned = { emptyList() }, sync = {}),
            libraryDays = flowOf(LibraryDays(intArrayOf(day), intArrayOf(photos.size))),
        )
        setContent { PhotoGalleryScreen(viewModel, onOpenBackupSettings = null, backupIndicator = {}, haptics = Recorder()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Photo").fetchSemanticsNodes().size == photos.size }
        onAllNodesWithContentDescription("Photo")[0].performTouchInput { longClick() }

        onNodeWithText("Move to bin").performClick()
        onNodeWithText("Move 1 photo to the bin?").assertExists()
        onNodeWithText("Cancel").performClick()
        onNodeWithText("1 selected").assertExists()
        assertEquals(emptyList(), moved, "nothing moves until the user says yes")

        onNodeWithText("Move to bin").performClick()
        onNode(hasText("Move to bin") and hasAnyAncestor(isDialog())).performClick()
        waitUntil(timeoutMillis = 5_000) { moved.isNotEmpty() }
        assertEquals(listOf(photos[0].fileId), moved)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()
}

private object NoServer : PhotoMoveServer {
    override suspend fun setArchivalStatus(status: ArchivalStatus, files: List<FileVersionTag>) = emptyList<PerFileResult>()
    override suspend fun softDelete(files: List<FileVersionTag>) = emptyList<PerFileResult>()
    override suspend fun setLocalTag(tag: Uuid, on: Boolean, fileIds: List<Uuid>) = emptyList<PerFileResult>()
    override suspend fun header(fileId: Uuid): HomebaseFile? = null
}
