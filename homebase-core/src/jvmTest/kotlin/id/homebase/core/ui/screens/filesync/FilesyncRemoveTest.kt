package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PerFileBatchResult
import id.homebase.api.client.drives.files.PerFileResult
import id.homebase.api.common.SecureByteArray
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class FilesyncRemoveTest {

    private val key = KeyHeader(ByteArray(16), SecureByteArray(ByteArray(16)))
    private val handles = mutableMapOf<Uuid, FilesyncHandle>()
    private val names = mutableMapOf<Uuid, String>()
    private val children = mutableMapOf<Uuid, List<FilesyncNode>>()
    private val sent = mutableListOf<List<String>>()
    private val listed = mutableListOf<List<String>>()
    private var refuse: (String) -> OdinClientErrorCode? = { null }

    private fun node(name: String, folder: Boolean = false, removed: Boolean = false, parent: Uuid = Uuid.random()): FilesyncNode {
        val node = FilesyncNode(Uuid.random(), parent, isFolder = folder, name = name, modifiedMs = null, sizeBytes = null, removed = removed)
        val handle = FilesyncHandle(Uuid.random(), Uuid.random(), FilesyncProtocol.FileFileType, key, JsonObject(emptyMap()), null)
        handles[node.nodeId] = handle
        names[handle.fileId] = name
        return node
    }

    private suspend fun remove(vararg nodes: FilesyncNode) = removeInOrder(
        nodes.toList(),
        handles,
        list = { ids ->
            listed += ids.map { names.getValue(handles.getValue(it).fileId) }
            val inside = ids.flatMap { children[it].orEmpty() }
            FilesyncNodes(inside.associateBy { it.nodeId }, inside.associate { it.nodeId to handles.getValue(it.nodeId) })
        },
        mark = { files: List<FileVersionTag> ->
            sent += files.map { names.getValue(it.fileId) }
            PerFileBatchResult(files.map { PerFileResult(it.fileId, refuse(names.getValue(it.fileId))) })
        },
    )

    @Test
    fun aFolderGoesBeforeWhatIsInItALevelAtATime() = runTest {
        val docs = node("Docs", folder = true)
        val sub = node("Sub", folder = true, parent = docs.nodeId)
        children[docs.nodeId] = listOf(node("a.txt", parent = docs.nodeId), sub)
        children[sub.nodeId] = listOf(node("b.txt", parent = sub.nodeId))
        val result = remove(docs, node("x.txt"), node("gone.txt", removed = true))

        assertEquals(listOf(listOf("Docs", "x.txt"), listOf("a.txt", "Sub"), listOf("b.txt")), sent)
        assertEquals(listOf(listOf("Docs"), listOf("Sub")), listed)
        assertEquals(5, result.done)
        assertEquals(0, result.skipped)
    }

    @Test
    fun aFolderChangedElsewhereStaysWithWhatIsInIt() = runTest {
        val docs = node("Docs", folder = true)
        children[docs.nodeId] = listOf(node("a.txt", parent = docs.nodeId))
        refuse = { if (it == "Docs") OdinClientErrorCode.VersionTagMismatch else null }
        val result = remove(docs, node("x.txt"))

        assertEquals(listOf(listOf("Docs", "x.txt")), sent)
        assertEquals(emptyList(), listed)
        assertEquals(1, result.done)
        assertEquals(1, result.skipped)
    }

    @Test
    fun aBigSelectionGoesInBatches() = runTest {
        val many = List(DriveFileHttpProvider.FILE_BATCH + 1) { node("f$it") }
        remove(*many.toTypedArray())
        assertEquals(listOf(DriveFileHttpProvider.FILE_BATCH, 1), sent.map { it.size })
    }

    @Test
    fun anyOtherRefusalIsAnError() = runTest {
        refuse = { OdinClientErrorCode.UnknownId }
        assertFailsWith<IllegalStateException> { remove(node("x.txt")) }
    }
}
