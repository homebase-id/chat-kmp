package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.SecureByteArray
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilesyncMoveTest {

    private val key = KeyHeader(ByteArray(16), SecureByteArray(ByteArray(16)))
    private val handles = mutableMapOf<Uuid, FilesyncHandle>()
    private val here = Uuid.random()
    private val target = Uuid.random()

    private fun node(name: String, folder: Boolean = false, parent: Uuid = here, version: Int = 1, removed: Boolean = false): FilesyncNode {
        val node = FilesyncNode(Uuid.random(), parent, isFolder = folder, name = name, modifiedMs = null, sizeBytes = null, removed = removed)
        handles[node.nodeId] = FilesyncHandle(Uuid.random(), Uuid.random(), FilesyncProtocol.FileFileType, key, JsonObject(emptyMap()), null, version = version)
        return node
    }

    private fun plan(vararg nodes: FilesyncNode, path: List<Uuid> = listOf(FilesyncProtocol.RootNodeId, target), taken: Set<String> = emptySet()) =
        planMove(nodes.toList(), handles, target, path, taken.toMutableSet())

    @Test
    fun aNameTheFolderHasIsNumberedAndSoIsOneAnotherMoveTakes() {
        val plan = plan(node("a.txt"), node("A.TXT"), node("b.txt"), taken = setOf("a.txt"))
        assertEquals(listOf("a (1).txt", "A (2).TXT", "b.txt"), plan.moves.map { it.second })
        assertEquals(0, plan.skipped)
    }

    @Test
    fun aFolderNeverGoesIntoItselfOrBelowItself() {
        val outer = node("Outer", folder = true)
        val plan = plan(outer, path = listOf(FilesyncProtocol.RootNodeId, outer.nodeId, target))
        assertEquals(emptyList(), plan.moves)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun whatIsThereAlreadyOrDeletedIsLeftAndANewerVersionIsSkipped() {
        val plan = plan(node("there.txt", parent = target), node("gone.txt", removed = true), node("newer.txt", version = 2))
        assertEquals(emptyList(), plan.moves)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun theStateFolderNameIsNumberedAtASyncTop() {
        val plan = planMove(listOf(node(".hbsync", folder = true)), handles, FilesyncProtocol.RootNodeId, listOf(FilesyncProtocol.RootNodeId),
            FilesyncNames.takenKeys(FilesyncProtocol.RootNodeId, emptyList()))
        assertEquals(listOf(".hbsync (1)"), plan.moves.map { it.second })
    }

    @Test
    fun aMoveRewritesOnlyTheName() {
        val content = JsonObject(mapOf("v" to JsonPrimitive(1), "name" to JsonPrimitive("a.txt"), "later" to JsonPrimitive(7)))
        assertEquals(JsonObject(mapOf("v" to JsonPrimitive(1), "name" to JsonPrimitive("a (1).txt"), "later" to JsonPrimitive(7))), movedContent(content, "a (1).txt"))
    }

    @Test
    fun thePickerMovesOnlyWhereSomethingWouldMoveAndNeverIntoAMovingFolder() {
        val folder = node("Docs", folder = true)
        val root = FilesyncCrumb(here, null)
        val picker = FilesyncMovePicker(listOf(folder, node("a.txt")), listOf(root), loading = false)
        assertFalse(picker.canMoveHere, "both are here already")
        assertTrue(picker.copy(path = listOf(root, FilesyncCrumb(target, "Other"))).canMoveHere)
        assertFalse(picker.copy(path = listOf(root, FilesyncCrumb(folder.nodeId, "Docs"))).canMoveHere, "into a folder being moved")
        assertFalse(picker.copy(path = listOf(root, FilesyncCrumb(target, "Other")), loading = true).canMoveHere)
        assertTrue(picker.isMoving(folder))
    }
}
