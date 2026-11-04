package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FilesyncFolderSharingTest {

    private fun node(parent: Uuid, name: String, folder: Boolean = false) =
        FilesyncNode(Uuid.random(), parent, folder, name, modifiedMs = null, sizeBytes = if (folder) null else 1)

    private fun handle(node: FilesyncNode) =
        FilesyncHandle(Uuid.random(), null, if (node.isFolder) FilesyncProtocol.FolderFileType else FilesyncProtocol.FileFileType, KeyHeader.newRandom16(), JsonObject(emptyMap()), null)

    private val root = FilesyncProtocol.RootNodeId
    private val photos = node(root, "Photos", folder = true)
    private val trip = node(photos.nodeId, "Trip", folder = true)
    private val beach = node(trip.nodeId, "beach.jpg")
    private val cover = node(photos.nodeId, "cover.jpg")
    private val notes = node(root, "notes.txt")
    private val handles = listOf(photos, trip, beach, cover, notes).associate { it.nodeId to handle(it) }

    @Test
    fun aFolderSharesItselfAndEverythingBelowIt() = runTest {
        val asked = mutableListOf<List<Uuid>>()
        val children = listOf(trip, beach, cover).groupBy { it.parentId }
        val shared = withDescendants(listOf(photos, notes), mapOf(photos.nodeId to handles[photos.nodeId]!!, notes.nodeId to handles[notes.nodeId]!!)) { folders ->
            asked += folders
            val below = folders.flatMap { children[it].orEmpty() }
            FilesyncNodes(below.associateBy { it.nodeId }, below.associate { it.nodeId to handles[it.nodeId]!! })
        }

        assertEquals(listOf(photos, notes, trip, cover, beach).map { handles[it.nodeId] }.toSet(), shared.toSet())
        assertEquals(5, shared.size)
        assertEquals(listOf(listOf(photos.nodeId), listOf(trip.nodeId)), asked)
    }

    @Test
    fun theTopOfWhatsSharedIsWhateverCameWithoutItsFolder() {
        val shared = listOf(photos, trip, beach, notes)

        assertEquals(setOf(photos, notes), sharedLevel(shared, null).toSet())
        assertEquals(listOf(trip), sharedLevel(shared, photos.nodeId))
        assertEquals(listOf(beach), sharedLevel(shared, trip.nodeId))
        assertEquals(listOf(beach), sharedLevel(listOf(beach), null), "a file shared alone sits at the top")
    }
}
