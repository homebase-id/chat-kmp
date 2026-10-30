package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDamagedException
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.common.SecureByteArray
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilesyncArchiveTest {

    private val key = KeyHeader(ByteArray(16), SecureByteArray(ByteArray(16)))
    private val contents = mutableMapOf<Uuid, ByteArray>()
    private val folders = mutableMapOf<Uuid, MutableList<Pair<FilesyncNode, FilesyncHandle>>>()

    private fun folder(name: String, parent: Uuid = Uuid.random(), removed: Boolean = false) =
        FilesyncNode(Uuid.random(), parent, isFolder = true, name = name, modifiedMs = null, sizeBytes = null, removed = removed, updatedMs = 1_709_634_031_000)

    private fun file(name: String, bytes: ByteArray, parent: Uuid = Uuid.random(), removed: Boolean = false, executable: Boolean = false): Pair<FilesyncNode, FilesyncHandle> {
        val node = FilesyncNode(Uuid.random(), parent, isFolder = false, name = name, modifiedMs = 1_709_634_031_000, sizeBytes = bytes.size.toLong(), removed = removed, executable = executable)
        val payload = FilesyncPayload(Uuid.random(), key, PayloadHash("blake3"))
        contents[payload.fileId] = bytes
        return node to FilesyncHandle(payload.fileId, Uuid.random(), FilesyncProtocol.FileFileType, key, JsonObject(emptyMap()), payload)
    }

    private fun FilesyncNode.holds(vararg children: Pair<FilesyncNode, FilesyncHandle>) {
        folders.getOrPut(nodeId) { mutableListOf() } += children
    }

    private fun FilesyncNode.withHandle(): Pair<FilesyncNode, FilesyncHandle> =
        this to FilesyncHandle(Uuid.random(), Uuid.random(), FilesyncProtocol.FolderFileType, key, JsonObject(emptyMap()), null)

    private val archive = FilesyncArchive(
        list = { ids ->
            val children = ids.flatMap { folders[it].orEmpty() }
            FilesyncNodes(children.associate { it.first.nodeId to it.first }, children.associate { it.first.nodeId to it.second })
        },
        stream = { payload, consume ->
            val bytes = contents.getValue(payload.fileId)
            if (bytes.contentEquals(DAMAGED)) throw PayloadDamagedException("test")
            consume(flowOf(bytes.copyOfRange(0, bytes.size / 2), bytes.copyOfRange(bytes.size / 2, bytes.size)))
        },
    )

    private suspend fun zipOf(selection: List<Pair<FilesyncNode, FilesyncHandle>>): File {
        val entries = archive.plan(selection.map { it.first }, selection.associate { it.first.nodeId to it.second })
        val file = File.createTempFile("filesync-archive", ".zip").apply { deleteOnExit() }
        file.outputStream().buffered().use { out -> archive.write(entries, { out.write(it) }, {}) }
        return file
    }

    @Test
    fun aSelectionZipsWithWhatsInItsFoldersALevelAtATimeAndLeavesDeletedNodesOut() = runTest {
        val photos = folder("Photos")
        val trip = folder("Trip", photos.nodeId)
        photos.holds(file("b.jpg", ByteArray(10) { 2 }, photos.nodeId), trip.withHandle(), file("gone.jpg", ByteArray(3), photos.nodeId, removed = true))
        trip.holds(file("a.jpg", ByteArray(20_000) { 1 }, trip.nodeId))
        val notes = file("notes.txt", "hello".encodeToByteArray())
        val run = file("run.sh", "#!".encodeToByteArray(), executable = true)

        ZipFile(zipOf(listOf(photos.withHandle(), notes, run))).use { zip ->
            assertEquals(
                listOf("Photos/", "notes.txt", "run.sh", "Photos/b.jpg", "Photos/Trip/", "Photos/Trip/a.jpg"),
                zip.entries().toList().map { it.name },
            )
            assertContentEquals(ByteArray(20_000) { 1 }, zip.getInputStream(zip.getEntry("Photos/Trip/a.jpg")).readBytes())
            assertContentEquals("hello".encodeToByteArray(), zip.getInputStream(zip.getEntry("notes.txt")).readBytes())
        }
    }

    @Test
    fun aFolderInsideItselfIsWalkedOnce() = runTest {
        val loop = folder("Loop")
        loop.holds(loop.withHandle())
        val entries = archive.plan(listOf(loop), emptyMap())
        assertEquals(listOf(listOf("Loop")), entries.map { it.path })
    }

    @Test
    fun aDamagedFileFailsTheArchiveAndSaysWhichOne() = runTest {
        val bad = file("broken.pdf", DAMAGED)
        val failure = assertFailsWith<FilesyncArchiveDamaged> { zipOf(listOf(file("fine.txt", ByteArray(4)), bad)) }
        assertEquals("broken.pdf", failure.name)
    }

    @Test
    fun theArchiveIsNamedForALoneFolderElseTheFolderItsIn() {
        val photos = folder("Photos")
        val (doc, _) = file("a.pdf", ByteArray(1))
        assertEquals("Photos.zip", FilesyncArchive.name(listOf(photos), "Home", "Filesync"))
        assertEquals("Home.zip", FilesyncArchive.name(listOf(photos, doc), "Home", "Filesync"))
        assertEquals("Filesync.zip", FilesyncArchive.name(listOf(doc), null, "Filesync"))
        assertEquals("a_b.zip", FilesyncArchive.name(listOf(folder("a/b")), null, "Filesync"))
    }

    @Test
    fun theSelectionOffersAZipOfItsLiveNodesAndRestoreForItsDeletedOnes() {
        val live = folder("Live")
        val gone = folder("Gone", removed = true)
        fun offered(vararg nodes: FilesyncNode) = FilesyncUiState(visible = nodes.toList())
            .copy(selection = FilesyncSelection(nodes.map { it.nodeId }.toSet()))
            .bulkActions

        assertEquals(listOf(FilesyncBulkAction.Download, FilesyncBulkAction.Chat, FilesyncBulkAction.WebDrop), offered(live))
        assertEquals(listOf(FilesyncBulkAction.Restore), offered(gone))
        assertEquals(listOf(FilesyncBulkAction.Download, FilesyncBulkAction.Chat, FilesyncBulkAction.WebDrop, FilesyncBulkAction.Restore), offered(live, gone))
        assertTrue(FilesyncUiState().bulkActions.isEmpty())
    }

    private companion object {
        val DAMAGED = "damaged".encodeToByteArray()
    }
}

