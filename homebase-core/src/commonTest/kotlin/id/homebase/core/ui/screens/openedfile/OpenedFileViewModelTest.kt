package id.homebase.core.ui.screens.openedfile

import id.homebase.api.HomebaseProtocol
import id.homebase.chat.widget.preview.FilePreviewKind
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.ui.screens.filesync.FilesyncCrumb
import id.homebase.core.ui.screens.filesync.FilesyncError
import id.homebase.core.ui.screens.filesync.FilesyncNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class OpenedFileViewModelTest {

    private val root = FilesyncCrumb(FilesyncProtocol.RootNodeId, null)
    private val docs = folder("Docs")
    private val taxes = folder("Taxes", parent = docs.nodeId)

    private fun folder(name: String, parent: Uuid = FilesyncProtocol.RootNodeId) =
        FilesyncNode(Uuid.random(), parent, isFolder = true, name = name, modifiedMs = null, sizeBytes = null)

    private class FakeGateway(val tree: Map<Uuid, List<FilesyncNode>>) : OpenedFileGateway {
        override val signedIn = MutableStateFlow(true)
        override val errors = MutableSharedFlow<FilesyncError>(extraBufferCapacity = 4)
        var ready = true
        var remembered: List<FilesyncCrumb>? = null
        var handOff: Job = Job().apply { complete() }
        val enqueued = mutableListOf<Pair<Uuid, String>>()
        val discarded = mutableListOf<String>()

        override suspend fun filesyncReady() = ready
        override suspend fun rememberedFolder() = remembered
        override suspend fun remember(path: List<FilesyncCrumb>) { remembered = path }
        override suspend fun listFolders(parent: Uuid): List<FilesyncNode> {
            return tree[parent] ?: error("no such folder")
        }
        override fun enqueue(folderId: Uuid, path: String): Job {
            enqueued += folderId to path
            return handOff
        }
        override fun discard(path: String) { discarded += path }
    }

    private fun gateway() = FakeGateway(
        mapOf(FilesyncProtocol.RootNodeId to listOf(docs), docs.nodeId to listOf(taxes), taxes.nodeId to emptyList()),
    )

    private fun request(size: Long = 1_000, name: String = "invoice.pdf", mime: String? = "application/pdf") =
        OpenedFileRequest("/stage/x/$name", name, mime, size)

    private fun vm(gateway: FakeGateway, request: OpenedFileRequest = request()) = OpenedFileViewModel(request, gateway)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun savingHandsTheFileToTheQueueInTheFolderLastSavedTo() = runTest {
        val g = gateway().apply { remembered = listOf(root, FilesyncCrumb(docs.nodeId, "Docs")) }
        val vm = vm(g)
        vm.onAction(OpenedFileAction.SaveClicked)
        val picker = assertNotNull(vm.uiState.value.picker)
        assertEquals(docs.nodeId, picker.path.last().nodeId, "opens on the remembered folder")
        assertEquals(listOf(taxes), picker.folders)
        vm.onAction(OpenedFileAction.PickerConfirm)
        assertEquals(listOf(docs.nodeId to "/stage/x/invoice.pdf"), g.enqueued)
        assertEquals(listOf(root, FilesyncCrumb(docs.nodeId, "Docs")), g.remembered)
        assertEquals(OpenedFileEvent.Saved("Docs", g.remembered!!), vm.events.first())
        assertNull(vm.uiState.value.picker)
        assertFalse(vm.uiState.value.saving)
    }

    @Test
    fun theFirstSaveOpensOnTheTopOfTheDrive() = runTest {
        val g = gateway()
        val vm = vm(g)
        vm.onAction(OpenedFileAction.SaveClicked)
        assertEquals(listOf(root), vm.uiState.value.picker?.path)
        vm.onAction(OpenedFileAction.PickerConfirm)
        assertEquals(listOf(FilesyncProtocol.RootNodeId to "/stage/x/invoice.pdf"), g.enqueued)
        assertEquals(OpenedFileEvent.Saved(null, listOf(root)), vm.events.first())
    }

    @Test
    fun theFolderPickerWalksIntoFoldersAndBackOut() = runTest {
        val vm = vm(gateway())
        vm.onAction(OpenedFileAction.SaveClicked)
        vm.onAction(OpenedFileAction.PickerOpen(docs))
        vm.onAction(OpenedFileAction.PickerOpen(taxes))
        assertEquals(listOf(null, "Docs", "Taxes"), vm.uiState.value.picker?.path?.map { it.name })
        vm.onAction(OpenedFileAction.PickerToCrumb(1))
        assertEquals(listOf(null, "Docs"), vm.uiState.value.picker?.path?.map { it.name })
        vm.onAction(OpenedFileAction.PickerDismiss)
        assertNull(vm.uiState.value.picker)
    }

    @Test
    fun aRememberedFolderThatCannotBeListedFallsBackToTheTop() = runTest {
        val g = gateway().apply { remembered = listOf(root, FilesyncCrumb(Uuid.random(), "Gone")) }
        val vm = vm(g)
        vm.onAction(OpenedFileAction.SaveClicked)
        assertEquals(listOf(root), vm.uiState.value.picker?.path)
    }

    @Test
    fun filesyncNotSetUpGoesToSetup() = runTest {
        val g = gateway().apply { ready = false }
        val vm = vm(g)
        vm.onAction(OpenedFileAction.SaveClicked)
        assertEquals(OpenedFileEvent.OpenFilesyncSetup, vm.events.first())
        assertNull(vm.uiState.value.picker)
        assertTrue(g.enqueued.isEmpty())
    }

    @Test
    fun notSignedInTheButtonSignsInAndKeepsTheCopy() = runTest {
        val g = gateway().apply { signedIn.value = false }
        val vm = vm(g)
        assertFalse(vm.uiState.value.signedIn)
        vm.onAction(OpenedFileAction.SaveClicked)
        assertEquals(OpenedFileEvent.OpenSignIn, vm.events.first())
        assertTrue(g.enqueued.isEmpty())
        vm.release()
        assertTrue(g.discarded.isEmpty(), "the screen comes back after sign-in")
    }

    @Test
    fun aFileOverTheLimitCannotBeSaved() = runTest {
        val g = gateway()
        val vm = vm(g, request(size = HomebaseProtocol.MaxPlaintextPayloadBytes + 1))
        assertTrue(vm.uiState.value.tooLarge)
        vm.onAction(OpenedFileAction.SaveClicked)
        assertNull(vm.uiState.value.picker)
        assertTrue(g.enqueued.isEmpty())
    }

    @Test
    fun aFileAtTheLimitCanBeSaved() = runTest {
        val vm = vm(gateway(), request(size = HomebaseProtocol.MaxPlaintextPayloadBytes))
        assertFalse(vm.uiState.value.tooLarge)
    }

    @Test
    fun theStagedCopyIsDeletedWhenTheScreenLeaves() = runTest {
        val g = gateway()
        val vm = vm(g)
        vm.release()
        assertEquals(listOf("/stage/x/invoice.pdf"), g.discarded)
    }

    @Test
    fun theStagedCopyIsDeletedAfterSaveHasHandedItToTheQueue() = runTest {
        val g = gateway()
        val vm = vm(g)
        vm.onAction(OpenedFileAction.SaveClicked)
        vm.onAction(OpenedFileAction.PickerConfirm)
        vm.release()
        assertEquals(listOf("/stage/x/invoice.pdf"), g.discarded)
        assertEquals(1, g.enqueued.size)
    }

    @Test
    fun theCopyIsNotDeletedWhileTheQueueIsStillCopyingIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val g = gateway().apply { handOff = launch { gate.await() } }
        val vm = vm(g)
        vm.onAction(OpenedFileAction.SaveClicked)
        vm.onAction(OpenedFileAction.PickerConfirm)
        assertTrue(vm.uiState.value.saving)
        vm.release()
        assertTrue(g.discarded.isEmpty())
        gate.complete(Unit)
    }

    @Test
    fun theSavedEventWaitsForTheHandOff() = runTest {
        val gate = CompletableDeferred<Unit>()
        val g = gateway().apply { handOff = launch { gate.await() } }
        val vm = vm(g)
        vm.onAction(OpenedFileAction.SaveClicked)
        vm.onAction(OpenedFileAction.PickerConfirm)
        assertTrue(vm.uiState.value.saving)
        gate.complete(Unit)
        assertTrue(vm.events.first() is OpenedFileEvent.Saved)
        assertFalse(vm.uiState.value.saving)
    }

    @Test
    fun queueErrorsReachTheScreen() = runTest {
        val g = gateway()
        val vm = vm(g)
        g.errors.emit(FilesyncError.UploadFailed)
        assertEquals(OpenedFileEvent.Error(FilesyncError.UploadFailed), vm.events.first())
    }

    @Test
    fun theKindFollowsTheTypeTheOtherAppSent() {
        assertEquals(FilePreviewKind.Pdf, vm(gateway()).uiState.value.kind)
        assertNull(vm(gateway(), request(name = "a.xlsx", mime = "application/vnd.ms-excel")).uiState.value.kind)
    }
}
