package id.homebase.core.ui.screens.filesync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FilesyncSortingTest {

    private fun folder(name: String, vararg tags: String) =
        FilesyncNode(Uuid.random(), Uuid.random(), isFolder = true, name = name, modifiedMs = null, sizeBytes = null, tags = tags.map { FilesyncTag(it, null) })

    private fun file(name: String, modified: Long?, size: Long, vararg tags: String) =
        FilesyncNode(Uuid.random(), Uuid.random(), isFolder = false, name = name, modifiedMs = modified, sizeBytes = size, tags = tags.map { FilesyncTag(it, 6) })

    private val nodes = listOf(
        file("b.pdf", 300, 10, "Red"),
        folder("Zeta"),
        file("A.txt", 100, 30),
        folder("alpha", "Red"),
        file("c.mp4", null, 20),
    )

    private fun List<FilesyncNode>.names() = map { it.name }

    @Test
    fun byNameFoldersFirstIgnoringCaseInBothDirections() {
        assertEquals(listOf("alpha", "Zeta", "A.txt", "b.pdf", "c.mp4"), nodes.arranged(FilesyncSort()).names())
        assertEquals(listOf("Zeta", "alpha", "c.mp4", "b.pdf", "A.txt"), nodes.arranged(FilesyncSort(ascending = false)).names())
    }

    @Test
    fun byModifiedAMissingDateIsLastEitherWay() {
        assertEquals(listOf("alpha", "Zeta", "b.pdf", "A.txt", "c.mp4"), nodes.arranged(FilesyncSort(FilesyncSortKey.Modified, false)).names())
        assertEquals(listOf("alpha", "Zeta", "A.txt", "b.pdf", "c.mp4"), nodes.arranged(FilesyncSort(FilesyncSortKey.Modified, true)).names())
    }

    @Test
    fun bySizeAndByKind() {
        assertEquals(listOf("alpha", "Zeta", "A.txt", "c.mp4", "b.pdf"), nodes.arranged(FilesyncSort(FilesyncSortKey.Size, false)).names())
        assertEquals(listOf("alpha", "Zeta", "b.pdf", "A.txt", "c.mp4"), nodes.arranged(FilesyncSort(FilesyncSortKey.Kind, true)).names())
    }

    @Test
    fun theSameKeyFlipsANewKeyStartsWhereItsUsefulEnd() {
        val byName = FilesyncSort()
        assertEquals(FilesyncSort(FilesyncSortKey.Name, false), byName.by(FilesyncSortKey.Name))
        assertEquals(FilesyncSort(FilesyncSortKey.Modified, false), byName.by(FilesyncSortKey.Modified))
        assertEquals(FilesyncSort(FilesyncSortKey.Kind, true), byName.by(FilesyncSortKey.Size).by(FilesyncSortKey.Kind))
    }

    @Test
    fun searchAndTagNarrowTheList() {
        assertEquals(listOf("alpha"), nodes.arranged(FilesyncSort(), query = " AL").names())
        assertEquals(listOf("A.txt"), nodes.arranged(FilesyncSort(), query = ".txt").names())
        assertEquals(listOf("alpha", "b.pdf"), nodes.arranged(FilesyncSort(), tag = "Red").names())
    }

    @Test
    fun aTagFilterWhoseTagLeftTheFolderIsDropped() {
        assertEquals("Red", FilesyncUiState(tagFilter = "Red").withNodes(nodes).arrange().tagFilter)
        val gone = FilesyncUiState(tagFilter = "Blue").withNodes(nodes).arrange()
        assertEquals(null, gone.tagFilter)
        assertEquals(nodes.size, gone.visible.size)
    }

    @Test
    fun totalsAndTags() {
        assertEquals(FilesyncTotals(folders = 2, files = 3, bytes = 60), nodes.totals())
        assertEquals(listOf(FilesyncTag("Red", 6)), nodes.distinctTags())
    }
}
