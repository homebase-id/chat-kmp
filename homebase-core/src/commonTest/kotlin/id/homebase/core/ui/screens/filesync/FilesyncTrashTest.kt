package id.homebase.core.ui.screens.filesync

import id.homebase.core.filesync.FilesyncProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** filesync's node schema, "Recently deleted": who went with whom is the server's time. */
class FilesyncTrashTest {

    private fun removed(name: String, parent: Uuid, folder: Boolean, at: Long, id: Uuid = Uuid.random()) =
        FilesyncNode(id, parent, folder, name, null, null, removedMs = at, updatedMs = at)

    private fun live(name: String, parent: Uuid, folder: Boolean, written: Long) =
        FilesyncNode(Uuid.random(), parent, folder, name, null, null, updatedMs = written)

    // Photos/ removed at 200, then a.jpg at 210; Photos/old.jpg removed on its own at 100; notes.txt at the top at 300.
    private val photosId = Uuid.random()
    private val photos = removed("Photos", FilesyncProtocol.RootNodeId, folder = true, at = 200, id = photosId)
    private val a = removed("a.jpg", photosId, folder = false, at = 210)
    private val old = removed("old.jpg", photosId, folder = false, at = 100)
    private val notes = removed("notes.txt", FilesyncProtocol.RootNodeId, folder = false, at = 300)
    private val nodes = listOf(photos, a, old, notes).associateBy { it.nodeId }

    private fun Map<Uuid, FilesyncNode>.plus(vararg more: FilesyncNode) = this + more.associateBy { it.nodeId }

    @Test
    fun theItemsComeNewestFirstCountingWhatWasDeletedWithThem() {
        assertEquals(
            listOf(FilesyncTrashItem(notes, 0), FilesyncTrashItem(photos, 1), FilesyncTrashItem(old, 0)),
            FilesyncTrash.items(nodes),
        )
    }

    @Test
    fun aFolderBringsBackOnlyWhatWentWithIt() {
        assertEquals(listOf(photos, a), FilesyncTrash.group(photos, nodes))
    }

    @Test
    fun anItemInARemovedFolderBringsTheFolderBackFirst() {
        assertEquals(listOf(photos, old), FilesyncTrash.restorePlan(old, nodes))
    }

    @Test
    fun aLiveNodeWrittenBeforeItsFolderWasRemovedWentWithItThroughLiveFolders() {
        val year = live("2024", photosId, folder = true, written = 150)
        val b = live("b.jpg", year.nodeId, folder = false, written = 200)
        val all = nodes.plus(year, b)
        assertEquals(photos, FilesyncTrash.itemOf(b, all))
        assertEquals(listOf(FilesyncTrashItem(notes, 0), FilesyncTrashItem(photos, 3), FilesyncTrashItem(old, 0)), FilesyncTrash.items(all))
        assertEquals(listOf(photos, a, year, b), FilesyncTrash.group(photos, all))
        assertEquals(listOf(photos, a), FilesyncTrash.restorePlan(photos, all))
    }

    @Test
    fun aLiveNodeWrittenAfterTheRemovalIsntDeleted() {
        val edited = live("c.jpg", photosId, folder = false, written = 201)
        val all = nodes.plus(edited)
        assertNull(FilesyncTrash.itemOf(edited, all))
        assertEquals(setOf(photos, a, old, notes), FilesyncTrash.deleted(all).values.toSet())
    }

    @Test
    fun theWholeChainOfRemovedFoldersComesBackOutermostFirst() {
        val inner = removed("2024", photosId, folder = true, at = 250)
        val deep = removed("b.jpg", inner.nodeId, folder = false, at = 50)
        assertEquals(listOf(photos, inner, deep), FilesyncTrash.restorePlan(deep, nodes.plus(inner, deep)))
    }

    @Test
    fun aParentCycleEnds() {
        val x = Uuid.random()
        val y = Uuid.random()
        val loop = listOf(removed("x", y, folder = true, at = 1, id = x), removed("y", x, folder = true, at = 1, id = y)).associateBy { it.nodeId }
        assertEquals(1, FilesyncTrash.removedParents(loop.getValue(x), loop).size)
        assertEquals(loop.getValue(y), FilesyncTrash.itemOf(loop.getValue(x), loop))
    }
}
