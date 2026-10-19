package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import id.homebase.core.util.FileKind

enum class FilesyncSortKey { Name, Modified, Size, Kind }

@Immutable
data class FilesyncSort(val key: FilesyncSortKey = FilesyncSortKey.Name, val ascending: Boolean = true) {
    /** The same key flips the direction; a new one starts A–Z, or newest and largest first for dates and sizes. */
    fun by(key: FilesyncSortKey): FilesyncSort =
        if (key == this.key) copy(ascending = !ascending)
        else FilesyncSort(key, ascending = key == FilesyncSortKey.Name || key == FilesyncSortKey.Kind)
}

@Immutable
data class FilesyncTotals(val folders: Int = 0, val files: Int = 0, val bytes: Long = 0)

/**
 * What the list shows: [query] and [tag] narrow it, then folders come first in either direction, role folders first of all.
 * A missing date or size sorts last, and ties fall back to the name.
 */
internal fun List<FilesyncNode>.arranged(sort: FilesyncSort, query: String = "", tag: String? = null): List<FilesyncNode> {
    val needle = FilesyncNames.key(query.trim())
    val byName = compareBy<FilesyncNode> { it.nameKey }
    val byKey = when (sort.key) {
        FilesyncSortKey.Name -> if (sort.ascending) byName else byName.reversed()
        FilesyncSortKey.Modified -> valuesLast(sort.ascending) { it.modifiedMs }.then(byName)
        FilesyncSortKey.Size -> valuesLast(sort.ascending) { it.sizeBytes }.then(byName)
        FilesyncSortKey.Kind -> valuesLast(sort.ascending) { node -> node.kind?.let { KIND_RANK[it.ordinal] } }.then(byName)
    }
    return filter { node ->
        (needle.isEmpty() || needle in node.nameKey) && (tag == null || node.tags.any { it.name == tag })
    }.sortedWith(compareBy<FilesyncNode> { !it.isFolder }.thenBy(nullsLast()) { it.role }.then(byKey))
}

// By family, not by label: documents, then media, then the rest; the labels are translated, the order isn't.
private val KIND_RANK = listOf(
    FileKind.Pdf, FileKind.Word, FileKind.Text, FileKind.Spreadsheet, FileKind.Presentation,
    FileKind.Image, FileKind.Video, FileKind.Audio, FileKind.Archive, FileKind.Code, FileKind.Apk, FileKind.Generic,
).let { order -> IntArray(FileKind.entries.size) { order.indexOf(FileKind.entries[it]) } }

internal fun List<FilesyncNode>.totals(): FilesyncTotals {
    val folders = count { it.isFolder }
    return FilesyncTotals(folders, size - folders, sumOf { it.sizeBytes ?: 0 })
}

internal fun List<FilesyncNode>.distinctTags(): List<FilesyncTag> = flatMap { it.tags }.distinctSorted()

/** One per name, the first kept, ordered by name as filesync orders them. */
internal fun List<FilesyncTag>.distinctSorted(): List<FilesyncTag> =
    distinctBy { it.name }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

private fun <T : Comparable<T>> valuesLast(ascending: Boolean, selector: (FilesyncNode) -> T?): Comparator<FilesyncNode> =
    compareBy(nullsLast(if (ascending) naturalOrder() else reverseOrder()), selector)
