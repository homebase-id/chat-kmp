package id.homebase.core.ui.screens.filesync

import id.homebase.core.filesync.FilesyncProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The desktop's rules from filesync deleted.rs and tray pages.rs. */
class FilesyncTrashTest {

    private fun node(name: String, parent: Uuid, folder: Boolean, at: Long, root: Uuid? = null, id: Uuid = Uuid.random()) =
        FilesyncNode(id, parent, folder, name, null, null, removal = FilesyncRemoval(at, root ?: id))

    // Photos/ deleted at 200 with a.jpg in it; Photos/old.jpg deleted on its own before; notes.txt at the top deleted at 300.
    private val photosId = Uuid.random()
    private val photos = node("Photos", FilesyncProtocol.RootNodeId, folder = true, at = 200, id = photosId)
    private val a = node("a.jpg", photosId, folder = false, at = 200, root = photosId)
    private val old = node("old.jpg", photosId, folder = false, at = 100)
    private val notes = node("notes.txt", FilesyncProtocol.RootNodeId, folder = false, at = 300)
    private val removed = listOf(photos, a, old, notes).associateBy { it.nodeId }

    @Test
    fun theTopmostItemsComeNewestFirstCountingWhatTheirDeleteTook() {
        assertEquals(
            listOf(FilesyncTrashItem(notes, 0), FilesyncTrashItem(photos, 1)),
            FilesyncTrash.topmost(removed),
        )
    }

    @Test
    fun aFolderBringsBackOnlyWhatWentWithIt() {
        assertEquals(listOf(photos, a), FilesyncTrash.group(photos, removed))
    }

    @Test
    fun anItemInARemovedFolderBringsTheFolderBackFirst() {
        assertEquals(listOf(photos, old), FilesyncTrash.restorePlan(old, removed))
    }

    @Test
    fun theWholeChainOfRemovedFoldersComesBackOutermostFirst() {
        val inner = node("2024", photosId, folder = true, at = 200, root = photosId)
        val deep = node("b.jpg", inner.nodeId, folder = false, at = 50)
        val all = removed + listOf(inner, deep).associateBy { it.nodeId }
        assertEquals(listOf(photos, inner, deep), FilesyncTrash.restorePlan(deep, all))
    }

    @Test
    fun filesAreDeletedBeforeFolders() {
        assertEquals(listOf(a, photos), FilesyncTrash.emptyOrder(listOf(photos, a)))
    }

    @Test
    fun aParentCycleEnds() {
        val x = Uuid.random()
        val y = Uuid.random()
        val loop = listOf(node("x", y, folder = true, at = 1, id = x), node("y", x, folder = true, at = 1, id = y)).associateBy { it.nodeId }
        assertEquals(1, FilesyncTrash.removedParents(loop.getValue(x), loop).size)
    }
}
