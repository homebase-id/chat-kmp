package id.homebase.core.ui.screens.filesync

import id.homebase.core.filesync.FilesyncProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** filesync's node schema, "Recently deleted": who went with whom is the server's time. */
class FilesyncTrashTest {

    private fun removed(name: String, parent: Uuid, folder: Boolean, at: Long, id: Uuid = Uuid.random()) =
        FilesyncNode(id, parent, folder, name, null, null, removed = true, updatedMs = at)

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

    private fun trash(nodes: Map<Uuid, FilesyncNode>) = FilesyncTrash(FilesyncNodes(nodes, emptyMap()))

    @Test
    fun theItemsComeNewestFirstCountingWhatWasDeletedWithThem() {
        assertEquals(
            listOf(FilesyncTrashItem(notes, 0), FilesyncTrashItem(photos, 1), FilesyncTrashItem(old, 0)),
            trash(nodes).items(),
        )
    }

    @Test
    fun aFolderBringsBackOnlyWhatWentWithIt() {
        assertEquals(listOf(photos, a), trash(nodes).group(photos))
    }

    @Test
    fun anItemInARemovedFolderBringsTheFolderBackFirst() {
        assertEquals(listOf(photos, old), trash(nodes).restorePlan(old))
    }

    @Test
    fun aLiveNodeWrittenBeforeItsFolderWasRemovedWentWithItThroughLiveFolders() {
        val year = live("2024", photosId, folder = true, written = 150)
        val b = live("b.jpg", year.nodeId, folder = false, written = 200)
        val trash = trash(nodes.plus(year, b))
        assertEquals(photosId, trash.itemOf[b.nodeId])
        assertEquals(listOf(FilesyncTrashItem(notes, 0), FilesyncTrashItem(photos, 3), FilesyncTrashItem(old, 0)), trash.items())
        assertEquals(listOf(photos, a, year, b), trash.group(photos))
        assertEquals(listOf(photos, a), trash.restorePlan(photos))
    }

    @Test
    fun aLiveNodeWrittenAfterTheRemovalIsntDeleted() {
        val edited = live("c.jpg", photosId, folder = false, written = 201)
        val trash = trash(nodes.plus(edited))
        assertNull(trash.itemOf[edited.nodeId])
        assertEquals(setOf(photos.nodeId, a.nodeId, old.nodeId, notes.nodeId), trash.itemOf.keys)
        assertFalse(FilesyncTrash.wentWith(edited, photos.updatedMs))
    }

    @Test
    fun thePurgeTakesAnExpiredItemWithEverythingDeletedWithItAndNothingNewer() {
        val year = live("2024", photosId, folder = true, written = 150)
        val lateA = removed("late.jpg", photosId, folder = false, at = 500)
        val trash = trash(nodes.plus(year, lateA))
        assertEquals(setOf(old.nodeId), trash.removedBefore(200))
        assertEquals(setOf(old.nodeId, photosId, a.nodeId, year.nodeId, lateA.nodeId), trash.deletedWith(trash.removedBefore(201)))
        assertEquals(trash.itemOf.keys, trash.deletedWith(trash.removedBefore(301)))
    }

    @Test
    fun aNestedFolderRestoredByItselfBringsBackWhatWentWithIt() {
        val year = removed("2024", photosId, folder = true, at = 205)
        val b = removed("b.jpg", year.nodeId, folder = false, at = 206)
        val trash = trash(nodes.plus(year, b))
        assertEquals(photosId, trash.itemOf[b.nodeId])
        assertEquals(listOf(photos, year, b), trash.restorePlan(year))
    }

    @Test
    fun aLiveFolderWrittenAfterTheDeleteKeepsWhatIsInIt() {
        val year = live("2024", photosId, folder = true, written = 300)
        val m = live("m.jpg", year.nodeId, folder = false, written = 100)
        val r = removed("r.jpg", year.nodeId, folder = false, at = 400)
        val trash = trash(nodes.plus(year, m, r))
        assertNull(trash.itemOf[m.nodeId])
        assertEquals(r.nodeId, trash.itemOf[r.nodeId])
        assertEquals(listOf(photos, a), trash.group(photos))
        assertEquals(listOf(r), trash.restorePlan(r))
    }

    @Test
    fun aRestoreStopsAtALiveFolderButNotAtOneThatWentWithTheDelete() {
        val year = live("2024", photosId, folder = true, written = 150)
        val x = removed("x.jpg", year.nodeId, folder = false, at = 100)
        val trash = trash(nodes.plus(year, x))
        assertEquals(x.nodeId, trash.itemOf[x.nodeId])
        assertEquals(listOf(photos, x), trash.restorePlan(x))
        assertEquals(listOf(year, photos), trash.chainOf(year))
    }

    @Test
    fun theWholeChainOfRemovedFoldersComesBackOutermostFirst() {
        val inner = removed("2024", photosId, folder = true, at = 250)
        val deep = removed("b.jpg", inner.nodeId, folder = false, at = 50)
        assertEquals(listOf(photos, inner, deep), trash(nodes.plus(inner, deep)).restorePlan(deep))
    }

    @Test
    fun aParentCycleEnds() {
        val x = Uuid.random()
        val y = Uuid.random()
        val loop = listOf(removed("x", y, folder = true, at = 1, id = x), removed("y", x, folder = true, at = 1, id = y)).associateBy { it.nodeId }
        val trash = trash(loop)
        assertEquals(y, trash.itemOf[x])
        assertEquals(setOf(x, y), trash.restorePlan(loop.getValue(x)).mapTo(mutableSetOf()) { it.nodeId })
    }
}
