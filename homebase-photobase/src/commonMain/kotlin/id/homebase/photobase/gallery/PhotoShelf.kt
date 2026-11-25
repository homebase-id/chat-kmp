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
    Favorites(listOf(ArchivalStatus.None, ArchivalStatus.Archived), PhotobaseProtocol.FavoriteTag);

    /** Where a photo moved here goes; Favorites isn't moved to or from. */
    val status: ArchivalStatus get() = statuses.single()

    val filter: PhotoFilter
        get() = tag?.let { PhotoFilter.Tagged(it, statuses.map { status -> status.value.toLong() }) } ?: PhotoFilter.Shelf(status.value.toLong())

    /** Whether [file], as it arrived, belongs here now. */
    fun holds(file: HomebaseFile): Boolean = statuses.any { file.isAt(it) } && (tag == null || tag in file.localTags())

    companion object {
        /** The galleries the library opens on their own, in the menu's order. */
        val away: List<PhotoShelf> = listOf(Favorites, Archive, Bin)
    }
}

internal fun HomebaseFile.isAt(status: ArchivalStatus): Boolean =
    fileState == FileState.Active && (fileMetadata.appData.archivalStatus ?: ArchivalStatus.None) == status

internal fun HomebaseFile.localTags(): List<Uuid> = fileMetadata.localAppData?.tags.orEmpty()
