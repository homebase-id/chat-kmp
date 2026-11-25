package id.homebase.photobase.gallery

import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.sync.database.PhotoFilter
import id.homebase.core.photobase.PhotobaseProtocol
import kotlin.uuid.Uuid

/**
 * Which of the library's photos a gallery shows: those whose archival status on the server is one of [statuses], and
 * with a [tag], only those carrying that local tag.
 */
enum class PhotoShelf(val statuses: List<ArchivalStatus>, val tag: Uuid? = null) {
    Library(listOf(ArchivalStatus.None)),
    Archive(listOf(ArchivalStatus.Archived)),
    Bin(listOf(ArchivalStatus.Removed)),
    // Not a place photos move to or from: a view of the library's and the archive's.
    Favorites(listOf(ArchivalStatus.None, ArchivalStatus.Archived), PhotobaseProtocol.FavoriteTag);

    /** Where a photo moved here goes: only the shelves that are one status. */
    val status: ArchivalStatus get() = statuses.single()

    /** Photos can be moved to and from it. */
    val isPlace: Boolean get() = tag == null

    val filter: PhotoFilter get() = PhotoFilter(statuses.map { it.value.toLong() }, tag)

    /** Whether [file], as it arrived, belongs here now. */
    fun holds(file: HomebaseFile): Boolean =
        file.fileState == FileState.Active && file.archivalStatus() in statuses && (tag == null || tag in file.localTags())

    companion object {
        /** The galleries the library opens on their own, in the menu's order. */
        val away: List<PhotoShelf> = listOf(Favorites, Archive, Bin)
    }
}

internal fun HomebaseFile.isAt(status: ArchivalStatus): Boolean = fileState == FileState.Active && archivalStatus() == status

private fun HomebaseFile.archivalStatus(): ArchivalStatus = fileMetadata.appData.archivalStatus ?: ArchivalStatus.None

internal fun HomebaseFile.localTags(): List<Uuid> = fileMetadata.localAppData?.tags.orEmpty()
